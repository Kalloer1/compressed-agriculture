package com.kalloer.compressedagriculture.crop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 普通作物模块：作物在压缩耕地上长熟时，把产物按 {@code 9^L} 写进脚下压缩方块的货仓，并把幼苗重置回 0 龄。
 *
 * <p>两条触发路径（ADR-0001）：
 * <ul>
 *   <li><b>路径 A · 随机刻</b>：{@link CropGrowEvent.Post} 的 {@code getState()} 已经是长完后的状态，成熟就直接结算。</li>
 *   <li><b>路径 B · 骨粉</b>：{@link BonemealEvent} 在 {@code performBonemeal} <b>之前</b>触发，读不到催熟后的状态，
 *       所以只把位置记进本 tick 的待观察集合，不取消、不改 {@code setSuccessful}；等 {@link ServerTickEvent.Post}
 *       再读当前状态结算。</li>
 * </ul>
 *
 * <p>两条路径汇入同一个 {@link #trySettle}，写入一律走 {@link Settle#settle}。
 */
public final class CropModule {
    /** 本 tick 内被骨粉碰过、需要在这一 tick 末尾回看一次的位置。每 tick 无条件清空。 */
    private final Set<GlobalPosKey> pendingBonemeal = new HashSet<>();

    /** INVALID_INPUT 的 WARN 限流表，与待观察集合分开维护（ADR-0007）。 */
    private final WarnThrottle invalidInputWarn = new WarnThrottle();

    /** 路径 A：随机刻生长成功后立刻结算。 */
    public void onCropGrow(CropGrowEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        trySettle(level, event.getPos(), event.getState());
    }

    /**
     * 路径 B 的第一半：只记录位置。原版随后照常执行 {@code performBonemeal}，本监听器不干预它的结果。
     *
     * <p>事件在客户端也会触发，所以先挡掉非 {@link ServerLevel}；脚下不是压缩方块的位置不记，免得给每 tick 的
     * 遍历塞无关位置。
     */
    public void onBonemeal(BonemealEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (!(level.getBlockState(pos.below()).getBlock() instanceof CompressedBlock)) {
            return;
        }
        this.pendingBonemeal.add(GlobalPosKey.of(level.dimension(), pos));
    }

    /**
     * 路径 B 的第二半：每 tick 末尾回看待观察位置。
     *
     * <p>集合先整体取走再清空——清空是无条件的，且发生在读方块之前，所以即使结算过程抛异常也不会留下上一 tick 的残留。
     * 集合本身去重，同一 tick 内同一位置被骨粉多次只结算一次。
     */
    public void onServerTick(ServerTickEvent.Post event) {
        if (this.pendingBonemeal.isEmpty()) {
            return;
        }
        Set<GlobalPosKey> pending = Set.copyOf(this.pendingBonemeal);
        this.pendingBonemeal.clear();

        MinecraftServer server = event.getServer();
        for (GlobalPosKey key : pending) {
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null) {
                continue;
            }
            BlockPos pos = key.pos();
            trySettle(level, pos, level.getBlockState(pos));
        }
    }

    /**
     * 一次结算尝试。作物不成熟、或脚下不是压缩方块（没有货仓）时直接放行原版行为。
     */
    private void trySettle(ServerLevel level, BlockPos pos, BlockState state) {
        if (!CompressibleCrops.isCompressibleCrop(state) || !CompressibleCrops.isMature(state)) {
            return;
        }

        BlockPos chestPos = pos.below();
        OptionalInt cropLevel = CompressedBlock.levelOf(level.getBlockState(chestPos));
        if (cropLevel.isEmpty()) {
            return;
        }
        if (!(level.getBlockEntity(chestPos) instanceof CompressedBlockEntity chest)) {
            // 压缩方块都是 EntityBlock，正常不会缺 BE；缺了说明世界数据坏了，不写、不重置
            CompressedAgriculture.LOGGER.debug("compressed block at {} in {} has no block entity, skipping crop settlement",
                    chestPos, level.dimension().location());
            return;
        }

        SettleResult result = Settle.settle(collectDrops(level, pos, state), cropLevel.getAsInt(), chest);
        switch (result) {
            case SUCCESS -> resetToSeedling(level, pos, state);
            // 货仓满：不写入、不重置、保留成熟态，等外部事件驱动重试（ADR-0005）
            case INSUFFICIENT_SPACE -> {}
            case INVALID_INPUT -> warnInvalidInput(level, pos, state);
        }
    }

    /** 按原版战利品表取掉落，转成 {@code settle} 认识的「物品 ID + 数量」（ADR-0017）。 */
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

    private static void resetToSeedling(ServerLevel level, BlockPos pos, BlockState state) {
        if (!CompressibleCrops.hasAgeProperty(state)) {
            CompressedAgriculture.LOGGER.debug("settled {} at {} in {} but it has no age property, leaving state as is",
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()), pos, level.dimension().location());
            return;
        }
        CompressibleCrops.resetToSeedling(level, pos, state);
    }

    /** 掉落清单为空：永久卡住的症状，必须可诊断，所以是 WARN，并按位置限流（ADR-0007）。 */
    private void warnInvalidInput(ServerLevel level, BlockPos pos, BlockState state) {
        GlobalPosKey key = GlobalPosKey.of(level.dimension(), pos);
        if (!this.invalidInputWarn.shouldLog(key, level.getGameTime())) {
            return;
        }
        CompressedAgriculture.LOGGER.warn(
                "crop settlement got an empty drop list: block={} dimension={} pos={} state={}",
                BuiltInRegistries.BLOCK.getKey(state.getBlock()), level.dimension().location(), pos, state);
    }

    /** 待观察集合里当前有多少个位置，给调试与测试用。 */
    public int pendingBonemealCount() {
        return this.pendingBonemeal.size();
    }
}
