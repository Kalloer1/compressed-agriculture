package com.kalloer.compressedagriculture.retry;

import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.crop.CropModule;
import com.kalloer.compressedagriculture.menu.CompressedChestMenu;
import com.kalloer.compressedagriculture.stem.StemModule;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 结算重试（ADR-0005）：货仓满了导致 {@code INSUFFICIENT_SPACE} 的作物会保持成熟态，由玩家的主动动作再试一次。
 *
 * <p>三个时机，都是「玩家正在操作这个方块」，没有 tick 轮询（ADR-0005 / ADR-0012）：
 * <ul>
 *   <li>{@link #onContainerClose}：关掉本模组的货仓界面（主路径，玩家刚腾出空间）；</li>
 *   <li>{@link #onRightClickBlock}：右键这个压缩方块（这次右键不会开界面时）；</li>
 *   <li>{@link #onBlockBreak}：破坏这个压缩方块<b>之前</b>——先补收，再让 03b 把货仓写进掉落物。</li>
 * </ul>
 *
 * <p>结算核心不在这里：普通作物转调 {@link CropModule#trySettle}，瓜类转调 {@link StemModule#harvest}，两个模块与三处重试
 * 共用同一份逻辑。重复结算天然不会发生——{@code SUCCESS} 之后作物已被重置（幼苗 / {@code AGE = 0}），不再算成熟；
 * {@code INSUFFICIENT_SPACE} 则保持成熟态等下一次。
 */
public final class SettlementRetry {
    /** 重试范围：货仓上方 3×3（{@code x-1..x+1, z-1..z+1}），因为一株作物只长在货仓正上方，周围 8 格是给「同一块地相邻摆放」留的余量。 */
    private static final int RETRY_RADIUS = 1;

    private SettlementRetry() {}

    /**
     * 对 {@code chestPos} 上方 3×3 逐格尝试一次结算。非成熟 / 已结算的格子是空操作。
     */
    public static void retryAbove(ServerLevel level, BlockPos chestPos) {
        for (int dx = -RETRY_RADIUS; dx <= RETRY_RADIUS; dx++) {
            for (int dz = -RETRY_RADIUS; dz <= RETRY_RADIUS; dz++) {
                BlockPos pos = chestPos.offset(dx, 1, dz);
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof StemBlock) {
                    retryStem(level, pos, state);
                } else {
                    // 不成熟、不是可压缩作物、下方不是压缩方块，都在 trySettle 里直接放行
                    CropModule.trySettle(level, pos, state);
                }
            }
        }
    }

    /** 瓜类：茎 {@code AGE == MAX_AGE} 且下方是压缩耕地时结算一次，成功则退回 0 龄（与 {@code StemBlockMixin} 同一套收尾）。 */
    private static void retryStem(ServerLevel level, BlockPos stemPos, BlockState stemState) {
        if (stemState.getValue(StemBlock.AGE) != StemBlock.MAX_AGE) {
            return;
        }
        Block fruit = StemModule.fruitOf(stemState);
        if (fruit == null) {
            return;
        }
        if (StemModule.harvest(level, stemPos, fruit) == StemModule.Outcome.SUCCESS) {
            StemModule.resetStem(level, stemPos, stemState);
        }
    }

    /** 位置 1：关闭本模组的货仓界面。{@code Close} 由服务端发出，客户端挡掉即可。 */
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getContainer() instanceof CompressedChestMenu menu)) {
            return;
        }
        BlockPos chestPos = menu.blockPos();
        if (chestPos == null) {
            return;
        }
        retryAbove(level, chestPos);
    }

    /**
     * 位置 2：右键压缩方块。
     *
     * <p>已扩容且空手时这次右键会打开界面，那条路径由关闭界面时补收（位置 1），这里不重复做；其余情况（未扩容、
     * 或手里拿着东西不会开界面）就地补收一次。
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos chestPos = event.getPos();
        if (!(level.getBlockState(chestPos).getBlock() instanceof CompressedBlock)) {
            return;
        }
        if (!(level.getBlockEntity(chestPos) instanceof CompressedBlockEntity chest)) {
            return;
        }
        if (chest.storage().chestCount() > 0L && event.getItemStack().isEmpty()) {
            return;
        }
        retryAbove(level, chestPos);
    }

    /**
     * 位置 3：破坏压缩方块之前。
     *
     * <p>选 {@code BlockEvent.BreakEvent} 而不是 {@code PlayerInteractEvent.LeftClickBlock}：前者在方块真正被移除<b>之前</b>
     * 触发，且只在服务端、只在确定要挖掉时触发一次（左键会连续触发，还会在创造模式/空手试探时误触）。
     * 事件返回后原版才走 {@code getDrops}，所以刚补收的产出会被 03b 带进掉落物。
     */
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getState().getBlock() instanceof CompressedBlock)) {
            return;
        }
        retryAbove(level, event.getPos());
    }
}
