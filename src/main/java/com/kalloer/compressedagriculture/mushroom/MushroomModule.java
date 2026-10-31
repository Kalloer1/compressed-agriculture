package com.kalloer.compressedagriculture.mushroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.crop.GlobalPosKey;
import com.kalloer.compressedagriculture.crop.WarnThrottle;
import com.kalloer.compressedagriculture.registry.ModTags;
import com.kalloer.compressedagriculture.settle.DropEntry;
import com.kalloer.compressedagriculture.settle.Settle;
import com.kalloer.compressedagriculture.settle.SettleResult;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;
import com.kalloer.compressedagriculture.tree.TreeEnumeration;
import com.kalloer.compressedagriculture.tree.TreeHarvest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 蘑菇模块：骨粉把蘑菇催成蘑菇树后，整棵拆掉、产物乘 {@code 9^L} 进货仓、原位回填一个同种蘑菇（ADR-0013 / ADR-0015）。
 *
 * <p>两条路径，与树木模块同构：
 * <ul>
 *   <li><b>路径一 · 骨粉</b>：{@link BlockGrowFeatureEvent} 在 {@code MushroomBlock.growMushroom} <b>里</b>触发
 *       （已验证：走 {@code EventHooks.fireBlockGrowFeature}），此时蘑菇还在、蘑菇树还没放下去，所以只记录位置与蘑菇状态，
 *       不取消、不改 feature；真正的读方块、取掉落、拆树留到 {@link ServerTickEvent.Post}。
 *       拆除与结算整体交给 {@link TreeHarvest}——蘑菇树和普通树是同一个问题的两个实例，不复制一份引擎（ADR-0013）。</li>
 *   <li><b>手动兜底</b>：玩家挖蘑菇时把真实掉落写进货仓，成功才取消 {@link BlockEvent.BreakEvent}，产物不落世界。</li>
 * </ul>
 *
 * <p>路径二（随机扩散）没有代码：不加速、不把压缩方块加进 {@code MUSHROOM_GROW_BLOCK}，
 * 压缩草地上的蘑菇因此仍受原版光照 < 13 与 9×9×3 内同种 < 5 朵的约束（ADR-0013）。
 */
public final class MushroomModule {
    /** 本 tick 内被骨粉碰过、需要在这一 tick 末尾回看一次的蘑菇。每 tick 无条件清空。 */
    private final Map<GlobalPosKey, PendingMushroom> pendingMushrooms = new LinkedHashMap<>();

    /** 结算卡住（蘑菇树留下但永远不结算）时的 WARN 限流表，与待观察集合分开维护（ADR-0007）。 */
    private final WarnThrottle stuckWarn = new WarnThrottle();

    /**
     * 路径一的第一半：只记录位置与蘑菇状态，不取消、不改 feature。
     *
     * <p>蘑菇状态必须在这里读：{@code growMushroom} 会先 {@code removeBlock(pos)}，成功时不再恢复（ADR-0015），
     * 所以 tick 末尾读不到「原来种的是红菇还是褐菇」，回填也就无从谈起。
     *
     * <p>脚下不是压缩方块（没有货仓）的位置连记都不记，原版骨粉行为完全不受影响。
     */
    public void onBlockGrowFeature(BlockGrowFeatureEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState mushroomState = level.getBlockState(pos);
        if (!mushroomState.is(ModTags.Blocks.MUSHROOMS)) {
            return;
        }
        if (!hasChest(level, pos.below())) {
            return;
        }
        this.pendingMushrooms.put(GlobalPosKey.of(level.dimension(), pos), new PendingMushroom(pos, mushroomState));
    }

    /**
     * 路径一的第二半：每 tick 末尾回看待观察位置。
     *
     * <p>集合先整体取走再清空——清空是无条件的，且发生在读方块之前，所以即使结算过程抛异常也不会留下残留。
     * 用 {@link LinkedHashMap} 而不是 {@code Map.copyOf}，保持记录顺序；键是位置，所以同一 tick 同一位置只处理一次。
     */
    public void onServerTick(ServerTickEvent.Post event) {
        if (this.pendingMushrooms.isEmpty()) {
            return;
        }
        Map<GlobalPosKey, PendingMushroom> pending = new LinkedHashMap<>(this.pendingMushrooms);
        this.pendingMushrooms.clear();

        MinecraftServer server = event.getServer();
        for (Map.Entry<GlobalPosKey, PendingMushroom> entry : pending.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey().dimension());
            if (level == null) {
                continue;
            }
            harvestIfGrown(level, entry.getValue());
        }
    }

    /**
     * 蘑菇没长成（原版约 40% 成功率，失败时 {@code growMushroom} 把蘑菇放回原位）就什么都不做。
     *
     * <p>长成的判据复用 {@link TreeEnumeration}：非空说明原蘑菇位置已经变成（或上方已经接上）树干。
     * 再加一道「树里至少有一根 {@code mushroom_stem}」——否则蘑菇位置上方恰好立着一棵别的树时，
     * 会把那棵树当成蘑菇树拆掉。
     */
    private void harvestIfGrown(ServerLevel level, PendingMushroom mushroom) {
        TreeEnumeration.Result tree = TreeEnumeration.enumerate(level, mushroom.pos());
        if (tree.isEmpty() || !hasMushroomStem(level, tree)) {
            return;
        }
        BlockPos chestPos = mushroom.pos().below();
        if (!hasChest(level, chestPos)) {
            return;
        }

        SettleResult result = TreeHarvest.harvest(level, mushroom.pos(), chestPos, List.of(mushroom.pos()),
                mushroom.mushroomState());
        if (result == SettleResult.INVALID_INPUT) {
            // 掉落清单为空 / 容器读不出等级：永久卡住，必须可诊断（ADR-0007）
            warnStuck(level, mushroom.pos(), "mushroom tree settlement rejected the drop list");
        }
        // INSUFFICIENT_SPACE：整棵蘑菇树保留、不回填，等外部事件驱动重试（ADR-0005）
    }

    /**
     * 手动兜底：玩家挖掉压缩方块上的蘑菇时，把真实掉落写进货仓，成功才取消事件。
     *
     * <p>取消事件是为了让产物不落世界；{@code settle} 失败（货仓满）时不取消，让原版照常掉落——
     * 玩家挖了却什么都没拿到是最糟的体验。
     */
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        if (!state.is(ModTags.Blocks.MUSHROOMS)) {
            return;
        }
        BlockPos chestPos = pos.below();
        OptionalInt levelAt = CompressedBlock.levelOf(level.getBlockState(chestPos));
        if (levelAt.isEmpty()) {
            return;
        }
        if (!(level.getBlockEntity(chestPos) instanceof CompressedBlockEntity chest)) {
            return;
        }

        SettleResult result = Settle.settle(collectDrops(level, pos, state), levelAt.getAsInt(), chest);
        switch (result) {
            case SUCCESS -> event.setCanceled(true);
            case INSUFFICIENT_SPACE -> {}
            case INVALID_INPUT -> warnStuck(level, pos, "mushroom settlement rejected the drop list");
        }
    }

    /** 按原版战利品表取这一格蘑菇的真实掉落，转成 {@code settle} 认识的「物品 ID + 数量」（ADR-0017）。 */
    private static List<DropEntry> collectDrops(ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        List<ItemStack> stacks = Block.getDrops(state, level, pos, blockEntity, null, ItemStack.EMPTY);
        List<DropEntry> drops = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || stack.getCount() <= 0) {
                continue;
            }
            drops.add(new DropEntry(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount()));
        }
        return drops;
    }

    /** 压缩方块都是 {@code EntityBlock}，正常一定有 BE；没有 BE 就没有货仓，等价于「脚下不是压缩方块」。 */
    private static boolean hasChest(ServerLevel level, BlockPos pos) {
        return CompressedBlock.levelOf(level.getBlockState(pos)).isPresent()
                && level.getBlockEntity(pos) instanceof CompressedBlockEntity;
    }

    private static boolean hasMushroomStem(ServerLevel level, TreeEnumeration.Result tree) {
        for (BlockPos pos : tree.logs()) {
            if (level.getBlockState(pos).is(Blocks.MUSHROOM_STEM)) {
                return true;
            }
        }
        return false;
    }

    /** 蘑菇树留下但永远不结算是最难排查的症状，所以是 WARN，并按位置限流（ADR-0007）。 */
    private void warnStuck(ServerLevel level, BlockPos pos, String reason) {
        GlobalPosKey key = GlobalPosKey.of(level.dimension(), pos);
        if (!this.stuckWarn.shouldLog(key, level.getGameTime())) {
            return;
        }
        CompressedAgriculture.LOGGER.warn("mushroom harvest skipped: reason={} dimension={} pos={} state={}",
                reason, level.dimension().location(), pos, level.getBlockState(pos));
    }

    /** 待观察集合里当前有多少个位置，给调试与测试用。 */
    public int pendingMushroomCount() {
        return this.pendingMushrooms.size();
    }

    /** 一条待观察记录：哪一格的蘑菇被催过、催之前它是什么方块状态（回填用，ADR-0015）。 */
    private record PendingMushroom(BlockPos pos, BlockState mushroomState) {}
}
