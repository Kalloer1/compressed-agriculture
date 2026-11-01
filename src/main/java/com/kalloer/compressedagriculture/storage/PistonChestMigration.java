package com.kalloer.compressedagriculture.storage;

import javax.annotation.Nullable;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;

/**
 * 活塞推动压缩方块时的货仓搬运（ticket 09b）：源方块 → 移动中的方块 → 目标方块。
 *
 * <p>原版 {@code PistonMovingBlockEntity} 不保留被推方块的 BlockEntity（它只有 {@code movedState}），所以活塞一推，
 * 货仓就没了。搬运分两步，由两个 Mixin 各调一次：
 * <ol>
 *   <li>{@link #capture}：{@code PistonBaseBlock.moveBlocks} 刚把移动方块注册进世界时，从<b>源位置</b>读出
 *       {@link CompressedBlockEntity#saveData} 塞进 {@link PistonChestCarrier}。必须在源方块被清成空气之前读——
 *       {@code moveBlocks} 的循环顺序保证了这一点（先处理远的格子，源格子的 BlockEntity 那时还在）。</li>
 *   <li>{@link #restore}：{@code PistonMovingBlockEntity.tick} / {@code finalTick} 把方块放到目标位置之后，把数据
 *       {@link CompressedBlockEntity#loadData} 进目标位置的新 BlockEntity。</li>
 * </ol>
 *
 * <p>粘性活塞拉回走的是同一对 {@code moveBlocks} + {@code tick}，自动覆盖，没有第二条路径。
 *
 * <p>编码只有一套：{@link CompressedBlockEntity#saveData} / {@link CompressedBlockEntity#loadData}（ADR-0002、ADR-0019）。
 */
public final class PistonChestMigration {
    private PistonChestMigration() {}

    /**
     * 从源位置把货仓数据搬到刚创建的移动方块上。不是压缩方块、源位置没有货仓时什么都不做。
     *
     * @param destPos 移动方块所在的（目标）坐标
     */
    public static void capture(Level level, BlockPos destPos, BlockEntity moving) {
        if (level.isClientSide || !(moving instanceof PistonMovingBlockEntity piston)) {
            return;
        }
        if (!(piston.getMovedState().getBlock() instanceof CompressedBlock)) {
            return;
        }
        // moveBlocks 里 destPos = sourcePos.relative(dir)，dir 就是移动方向（extending 时等于 FACING，收缩时相反）
        BlockPos sourcePos = destPos.relative(piston.getMovementDirection().getOpposite());
        if (!(level.getBlockEntity(sourcePos) instanceof CompressedBlockEntity chest)) {
            return;
        }
        CompoundTag data = CompressedBlockEntity.saveData(chest, level.registryAccess());
        ((PistonChestCarrier) piston).compressedagriculture$setChestData(data);
    }

    /**
     * 方块已经在 {@code pos} 落定后，把移动方块携带的货仓数据写进目标位置的 BlockEntity。
     *
     * <p>落定后的位置不是压缩方块（例如被邻居形状判定改成别的方块）或本来就没有携带数据时什么都不做——数据仍留在
     * 移动方块上，不主动丢。写成功后清空携带的数据，避免 {@code tick} 与 {@code finalTick} 两条路径各应用一次。
     */
    public static void restore(Level level, BlockPos pos, PistonMovingBlockEntity moving) {
        if (level.isClientSide) {
            return;
        }
        PistonChestCarrier carrier = (PistonChestCarrier) moving;
        CompoundTag data = carrier.compressedagriculture$chestData();
        if (data == null || !(level.getBlockState(pos).getBlock() instanceof CompressedBlock)) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof CompressedBlockEntity chest) {
            CompressedBlockEntity.loadData(chest, data, level.registryAccess());
            chest.setChanged();
            carrier.compressedagriculture$setChestData(null);
        } else {
            // 压缩方块必然实现 EntityBlock，走不到这里；真走到了宁可留日志也不要静默丢数据
            CompressedAgriculture.LOGGER.warn(
                    "Compressed chest data had nowhere to go at {}; the moving block keeps it in NBT", pos);
        }
    }

    /** {@link #capture} 的读侧便捷方法，给调试与测试用；没有数据时返回 {@code null}。 */
    @Nullable
    public static CompoundTag peek(BlockEntity moving) {
        return moving instanceof PistonChestCarrier carrier ? carrier.compressedagriculture$chestData() : null;
    }
}
