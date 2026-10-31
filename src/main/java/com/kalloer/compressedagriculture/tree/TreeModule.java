package com.kalloer.compressedagriculture.tree;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.crop.GlobalPosKey;
import com.kalloer.compressedagriculture.crop.WarnThrottle;
import com.kalloer.compressedagriculture.settle.SettleResult;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 树木模块的事件接线：生长只记录、不改原版行为，长成后在本 tick 末尾结算、拆除、回填（ADR-0014）。
 *
 * <p>为什么必须分两段：{@link BlockGrowFeatureEvent} 在 {@code TreeGrower.growTree} <b>里</b>触发，
 * 此时树还没放下去、四棵树苗也还在。所以事件里只能「记录」（包括必须在生长前算出的 2×2 布局），
 * 真正读方块状态、取掉落、拆树要等 {@link ServerTickEvent.Post}。
 *
 * <p>不取消事件、不改 feature：压缩树苗与原版完全一致地走 {@code STAGE 0 → 1 → growTree}（ADR-0014）。
 * 树苗下方不是压缩方块时连记都不记，原版路径完全不受影响。
 */
public final class TreeModule {
    /** 本 tick 内被生长事件碰过、需要在这一 tick 末尾回看一次的位置。每 tick 无条件清空。 */
    private final Map<GlobalPosKey, PendingTree> pendingTrees = new LinkedHashMap<>();

    /** 卡住（树留下但永远不结算）时的 WARN 限流表，与待观察集合分开维护（ADR-0007）。 */
    private final WarnThrottle stuckWarn = new WarnThrottle();

    /**
     * 生长事件的第一半：只记录位置，不取消、不改 feature。
     *
     * <p>事件在 {@code growTree} 内部触发，此时四棵树苗都还在，所以 2×2 布局必须在这里判定——
     * 生长完成后树苗就被换成了树干或空气。
     */
    public void onBlockGrowFeature(BlockGrowFeatureEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState sapling = level.getBlockState(pos);
        if (!sapling.is(BlockTags.SAPLINGS)) {
            return;
        }
        List<BlockPos> cells = layoutOf(level, pos, sapling);
        // 基底探测只看西北角下方那一格，与原版 2×2 生长判定锚定同一个位置（ADR-0004 / §6.2）
        if (CompressedBlock.levelOf(level.getBlockState(cells.get(0).below())).isEmpty()) {
            return;
        }
        this.pendingTrees.put(GlobalPosKey.of(level.dimension(), pos), new PendingTree(pos, cells, sapling));
    }

    /**
     * 生长事件的第二半：每 tick 末尾回看待观察位置。
     *
     * <p>集合先整体取走再清空——清空是无条件的，且发生在读方块之前，所以即使结算过程抛异常也不会留下残留。
     * 用 {@link LinkedHashMap} 复制而不是 {@code Map.copyOf}，保持记录顺序，多棵树同 tick 结算时行为可复现。
     */
    public void onServerTick(ServerTickEvent.Post event) {
        if (this.pendingTrees.isEmpty()) {
            return;
        }
        Map<GlobalPosKey, PendingTree> pending = new LinkedHashMap<>(this.pendingTrees);
        this.pendingTrees.clear();

        MinecraftServer server = event.getServer();
        for (Map.Entry<GlobalPosKey, PendingTree> entry : pending.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey().dimension());
            if (level == null) {
                continue;
            }
            harvestIfGrown(level, entry.getValue());
        }
    }

    /** 没长成（原版把树苗放回来了）就什么都不做；长成了就选容器、结算、拆树、回填。 */
    private void harvestIfGrown(ServerLevel level, PendingTree tree) {
        BlockPos trunk = findGrownTrunk(level, tree);
        if (trunk == null) {
            return;
        }
        BlockPos chestPos = findChest(level, tree.cells());
        if (chestPos == null) {
            warnStuck(level, tree.anchor(), "no compressed block with a chest under the sapling layout");
            return;
        }
        if (!TreeEnumeration.validateCut(level, trunk)) {
            // 枚举起点上方还有连通的原木（说明这不是最低一根），不拆，免得只拆掉半棵树
            warnStuck(level, tree.anchor(), "enumeration anchor is not the lowest log");
            return;
        }

        SettleResult result = TreeHarvest.harvest(level, trunk, chestPos, tree.cells(), tree.saplingState());
        if (result == SettleResult.INVALID_INPUT) {
            warnStuck(level, tree.anchor(), "settlement rejected the tree drop list");
        }
    }

    /**
     * 找枚举起点：先试事件坐标（单格树就是它），再试西北角（2×2 巨树的树干落在西北角，
     * 若事件坐标是另外三格之一，那里长完后是空气）。
     *
     * <p>判据直接问 {@link TreeEnumeration#enumerate}：空结果就是「这一格不是树根」，
     * 不在这里另写一份原木判定。
     */
    @Nullable
    private static BlockPos findGrownTrunk(ServerLevel level, PendingTree tree) {
        if (!TreeEnumeration.enumerate(level, tree.anchor()).isEmpty()) {
            return tree.anchor();
        }
        BlockPos northWest = tree.cells().get(0);
        if (!northWest.equals(tree.anchor()) && !TreeEnumeration.enumerate(level, northWest).isEmpty()) {
            return northWest;
        }
        return null;
    }

    /**
     * 容器选择：按 西北 → 东北 → 西南 → 东南 取第一个存在货仓的压缩方块（ADR-0004）。
     *
     * <p>{@code tree.cells()} 就是按这个顺序排的，所以遍历它即可，不另排序。
     */
    @Nullable
    private static BlockPos findChest(ServerLevel level, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            BlockPos below = cell.below();
            if (CompressedBlock.levelOf(level.getBlockState(below)).isPresent()
                    && level.getBlockEntity(below) instanceof CompressedBlockEntity) {
                return below;
            }
        }
        return null;
    }

    /**
     * 算出树苗布局，顺序固定为 西北 → 东北 → 西南 → 东南。
     *
     * <p>2×2 搜索照抄原版 {@code TreeGrower.growTree}：偏移按 {@code (0,0) → (0,-1) → (-1,0) → (-1,-1)}，
     * 四格中任意一格都可以当锚点，只认第一个命中的；比较只看 {@link Block} 相同，不看 {@code STAGE}（ADR-0014）。
     *
     * <p>没命中 2×2 就是单格树，布局只有锚点自己。
     */
    private static List<BlockPos> layoutOf(ServerLevel level, BlockPos pos, BlockState sapling) {
        Block block = sapling.getBlock();
        for (int x = 0; x >= -1; x--) {
            for (int z = 0; z >= -1; z--) {
                BlockPos northWest = pos.offset(x, 0, z);
                if (isSameBlock(level, northWest, block)
                        && isSameBlock(level, northWest.east(), block)
                        && isSameBlock(level, northWest.south(), block)
                        && isSameBlock(level, northWest.east().south(), block)) {
                    return List.of(northWest, northWest.east(), northWest.south(), northWest.east().south());
                }
            }
        }
        return List.of(pos);
    }

    private static boolean isSameBlock(ServerLevel level, BlockPos pos, Block block) {
        return level.getBlockState(pos).is(block);
    }

    /** 树留下但永远不结算是最难排查的症状，所以是 WARN，并按位置限流（ADR-0007）。 */
    private void warnStuck(ServerLevel level, BlockPos pos, String reason) {
        GlobalPosKey key = GlobalPosKey.of(level.dimension(), pos);
        if (!this.stuckWarn.shouldLog(key, level.getGameTime())) {
            return;
        }
        CompressedAgriculture.LOGGER.warn("tree harvest skipped: reason={} dimension={} pos={}",
                reason, level.dimension().location(), pos);
    }

    /** 待观察集合里当前有多少个位置，给调试与测试用。 */
    public int pendingTreeCount() {
        return this.pendingTrees.size();
    }

    /**
     * 一条待观察记录：哪个位置触发了生长、这一轮要拆哪几格、回填什么。
     *
     * <p>{@code anchor} 是事件坐标，{@code cells} 是生长前算好的布局（西北 → 东北 → 西南 → 东南）。
     */
    private record PendingTree(BlockPos anchor, List<BlockPos> cells, BlockState saplingState) {
        private PendingTree {
            cells = List.copyOf(cells);
        }
    }
}
