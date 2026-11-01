package com.kalloer.compressedagriculture.storage;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;

/**
 * 「移动中的方块」身上的货仓数据口袋（ticket 09b）。
 *
 * <p>原版 {@code PistonMovingBlockEntity} 只存 {@code movedState}，<b>没有</b> BlockEntity 字段，所以方块被推着走的
 * 2–3 tick 里货仓没有任何落脚点。这个接口由 {@code PistonMovingBlockEntityMixin} 实现在原版类上，字段用
 * {@code @Unique} 注入，数据同时进 {@code saveAdditional} / {@code loadAdditional}——移动只有几 tick，但区块卸载仍可能
 * 发生，不写盘就会丢。
 *
 * <p>编码本身不在这里：进出的都是 {@link CompressedBlockEntity#saveData} 产出的 {@link CompoundTag}，与存档、物品形态
 * （03b）、形态互转（09a）同一套（ADR-0002、ADR-0019）。
 */
public interface PistonChestCarrier {
    /** 正在移动的方块携带的货仓数据；没有（非压缩方块）时为 {@code null}。 */
    @Nullable
    CompoundTag compressedagriculture$chestData();

    /** 写入货仓数据；{@code null} 表示清空（已经落到目标方块后就不再保留一份，避免二次应用）。 */
    void compressedagriculture$setChestData(@Nullable CompoundTag data);
}
