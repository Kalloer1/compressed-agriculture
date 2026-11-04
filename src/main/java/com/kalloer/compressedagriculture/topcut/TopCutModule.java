package com.kalloer.compressedagriculture.topcut;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.block.CompressedFarmlandBlock;
import com.kalloer.compressedagriculture.crop.GlobalPosKey;
import com.kalloer.compressedagriculture.registry.ModTags;
import com.kalloer.compressedagriculture.settle.DropEntry;
import com.kalloer.compressedagriculture.settle.Settle;
import com.kalloer.compressedagriculture.settle.SettleResult;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 多格植物截顶模块（ADR-0021）：压缩耕地上长出来的多格植物只保留 1 格高度。
 *
 * <p><b>触发</b>：{@code BlockBehaviour#onPlace}（经 {@code BlockBehaviourOnPlaceMixin}）。它由
 * {@code LevelChunk.setBlockState} 对每一次方块变更调用，一处代码覆盖原版 + 模组的多格植物，不需要认任何方块 ID。
 *
 * <p><b>为什么只记录、不在 {@code onPlace} 里当场拆</b>：{@code onPlace} 处在 {@code setBlockState} 内部，
 * 邻居更新还没走完，在里面再 {@code setBlock} 是重入。所以注入体只把坐标塞进本 tick 的集合，真正的读状态、
 * 结算、拆除留到 {@link ServerTickEvent.Post}——与骨粉路径 B、树木模块（ADR-0014）、蘑菇模块（ADR-0013）同一个先例。
 *
 * <p><b>为什么必须「第 2 格与第 1 格是同一方块」</b>：树苗长成树时第 2 格是原木，与第 1 格不同方块，不判这一条
 * 就会把树干瞬间砍掉，与树木模块打架。同理，蘑菇长成蘑菇块也不是同一方块。
 *
 * <p><b>停摆</b>：货仓放不下截顶产物时，第 2 格原样保留、机器暂停，等玩家腾出货仓空间。WARN 每个坐标只打一次，
 * 拆除成功或状态变化后清除记录，所以同一处停摆不会每 tick 刷屏。
 *
 * <p>集合是每 tick 清空的 {@code Set}，只由 {@code onPlace} 填充——本模块没有任何「每 tick 全图扫描」的逻辑。
 * 单例：注入点（Mixin 的静态上下文）与事件监听必须共用同一份集合。
 */
public final class TopCutModule {
    /** 注入点与事件监听共用的唯一实例。 */
    public static final TopCutModule INSTANCE = new TopCutModule();

    /** 本 tick 内被放下的、可能是「第 2 格」的位置。每 tick 无条件清空。 */
    private final Set<GlobalPosKey> pending = new HashSet<>();

    /** 已经报过 WARN 的位置（停摆 / 掉落清单为空）。拆除成功或状态变化时移除，所以同一坐标只报一次。 */
    private final Set<GlobalPosKey> warned = new HashSet<>();

    private TopCutModule() {}

    /**
     * 方块刚被放进世界时的入口，由 {@code BlockBehaviourOnPlaceMixin} 调用。
     *
     * <p>判定顺序按「最便宜的先判」：{@code onPlace} 在世界生成时会被调用几百万次，越早返回越好。
     * 世界生成走 {@code WorldGenRegion} 而不是 {@link ServerLevel}，所以第一道就把它整段挡掉。
     *
     * <p>只判定 + 记录，不碰世界（见类注释）。货仓是否存在留到 {@code onServerTick} 里再查——那时方块已经稳定。
     */
    public static void onBlockPlaced(Level level, BlockPos pos, BlockState placed) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(level.getBlockState(pos.below(2)).getBlock() instanceof CompressedFarmlandBlock)) {
            return;
        }
        if (level.getBlockState(pos.below()).getBlock() != placed.getBlock()) {
            return;
        }
        if (!placed.is(ModTags.Blocks.MULTI_BLOCK_PLANTS)) {
            return;
        }
        INSTANCE.pending.add(GlobalPosKey.of(serverLevel.dimension(), pos));
    }

    /**
     * 本 tick 末尾处理待截顶位置。集合先整体取走再清空——清空无条件且发生在读方块之前，所以结算抛异常也不会留下残留。
     */
    public void onServerTick(ServerTickEvent.Post event) {
        if (this.pending.isEmpty()) {
            return;
        }
        Set<GlobalPosKey> batch = Set.copyOf(this.pending);
        this.pending.clear();

        MinecraftServer server = event.getServer();
        for (GlobalPosKey key : batch) {
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null) {
                continue;
            }
            tryCut(level, key.pos());
        }
    }

    /**
     * 一次截顶尝试。全部判定都重新读世界——记录时成立不代表现在成立（玩家可能已经拆了、植物可能已经弹掉）。
     *
     * <p>三种结局：{@code SUCCESS} → 第 2 格设空气、第 1 格不动；{@code INSUFFICIENT_SPACE} → 第 2 格原样保留
     * （停摆）；{@code INVALID_INPUT} → 不写入、不拆除，记 WARN（ADR-0007）。
     */
    private void tryCut(ServerLevel level, BlockPos pos) {
        GlobalPosKey key = GlobalPosKey.of(level.dimension(), pos);
        BlockState second = level.getBlockState(pos);
        BlockState soil = level.getBlockState(pos.below(2));
        if (!isSecondBlockOfMultiBlockPlant(second, level.getBlockState(pos.below()))
                || !(soil.getBlock() instanceof CompressedFarmlandBlock)) {
            this.warned.remove(key);
            return;
        }
        OptionalInt soilLevel = CompressedBlock.levelOf(soil);
        if (soilLevel.isEmpty()) {
            this.warned.remove(key);
            return;
        }
        if (!(level.getBlockEntity(pos.below(2)) instanceof CompressedBlockEntity chest)) {
            // 压缩方块都是 EntityBlock，正常不会缺 BE；缺了说明世界数据坏了，不写、不拆
            this.warned.remove(key);
            CompressedAgriculture.LOGGER.debug(
                    "compressed farmland at {} in {} has no block entity, skipping top-cut",
                    pos.below(2), level.dimension().location());
            return;
        }

        switch (Settle.settle(collectDrops(level, pos, second), soilLevel.getAsInt(), chest)) {
            case SUCCESS -> {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                clearBambooLeaves(level, pos.below());
                this.warned.remove(key);
            }
            case INSUFFICIENT_SPACE -> warnStalled(level, pos, second, key);
            case INVALID_INPUT -> warnEmptyDrops(level, pos, second, key);
        }
    }

    /** 「第 2 格是第 1 格的同种多格植物」。同方块这一条同时挡掉「树苗 → 树干」与「蘑菇 → 蘑菇块」。 */
    private static boolean isSecondBlockOfMultiBlockPlant(BlockState second, BlockState first) {
        return !second.isAir()
                && first.getBlock() == second.getBlock()
                && second.is(ModTags.Blocks.MULTI_BLOCK_PLANTS);
    }

    /** 按第 2 格自己的原版战利品表取掉落，转成 {@code settle} 认识的「物品 ID + 数量」（ADR-0017）。 */
    private static List<DropEntry> collectDrops(ServerLevel level, BlockPos pos, BlockState state) {
        List<ItemStack> stacks = state.getDrops(new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, level.getBlockEntity(pos)));

        List<DropEntry> drops = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || stack.getCount() <= 0) {
                continue;
            }
            drops.add(new DropEntry(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount()));
        }
        return drops;
    }

    /** 停摆：货仓满，第 2 格留着等玩家腾空间。同一坐标只报一次，否则会每 tick 刷屏。 */
    private void warnStalled(ServerLevel level, BlockPos pos, BlockState second, GlobalPosKey key) {
        if (!this.warned.add(key)) {
            return;
        }
        CompressedAgriculture.LOGGER.warn(
                "top-cut stalled, the chest below is full: block={} dimension={} pos={}",
                BuiltInRegistries.BLOCK.getKey(second.getBlock()), level.dimension().location(), pos);
    }

    /** 掉落清单为空：永久卡住的症状，必须可诊断，所以是 WARN（ADR-0007），同样只报一次。 */
    private void warnEmptyDrops(ServerLevel level, BlockPos pos, BlockState second, GlobalPosKey key) {
        if (!this.warned.add(key)) {
            return;
        }
        CompressedAgriculture.LOGGER.warn(
                "top-cut got an empty drop list: block={} dimension={} pos={} state={}",
                BuiltInRegistries.BLOCK.getKey(second.getBlock()), level.dimension().location(), pos, second);
    }

    /**
     * 竹子生长时会顺手把下方方块的 {@code LEAVES} 改成 {@code SMALL}，第 2 格拆掉后第 1 格会残留这个视觉标记。
     * 顺手置回 {@code NONE}；不影响任何判定，所以只按属性存在与否处理，不认方块。
     */
    private static void clearBambooLeaves(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.BAMBOO_LEAVES)
                && state.getValue(BlockStateProperties.BAMBOO_LEAVES) != BambooLeaves.NONE) {
            level.setBlock(pos, state.setValue(BlockStateProperties.BAMBOO_LEAVES, BambooLeaves.NONE),
                    Block.UPDATE_ALL);
        }
    }
}
