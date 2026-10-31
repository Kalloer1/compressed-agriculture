package com.kalloer.compressedagriculture.settle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/**
 * 统一入口：把一份掉落清单乘上 {@code 9^L} 后原子写入货仓。
 *
 * <p>纯函数——不读世界、不查注册表、不认识任何具体方块或物品 ID。等级 {@code L} 由调用方从作物正下方的
 * 压缩方块读出后传入（ADR-0006），入口内不反查坐标。
 *
 * <p>语义：
 * <ol>
 *   <li>掉落清单为 {@code null} 或空、或 {@code level} 不在 {@code 1..9} → {@link SettleResult#INVALID_INPUT}，货仓完全不被触碰。</li>
 *   <li>按物品 ID 合并重复条目，再整体乘倍率 {@code 9^L}；合并与乘算全部在第一次 {@link ChestTarget#tryReserve} 之前完成，
 *       溢出按 {@link SettleResult#INSUFFICIENT_SPACE} 处理，不做静默截断。</li>
 *   <li>逐个预留，任一失败 → {@link SettleResult#INSUFFICIENT_SPACE}，已预留的全部回滚，一个物品都不落库。</li>
 *   <li>全部成功 → {@link ChestTarget#commit()} → {@link SettleResult#SUCCESS}。</li>
 * </ol>
 */
public final class Settle {
    /** 压缩方块等级（合成深度）的取值范围：1..9。 */
    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 9;

    private Settle() {}

    public static SettleResult settle(List<DropEntry> drops, int level, ChestTarget target) {
        Objects.requireNonNull(target, "target");
        if (drops == null || drops.isEmpty()) {
            return SettleResult.INVALID_INPUT;
        }
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            return SettleResult.INVALID_INPUT;
        }

        List<DropEntry> scaled;
        try {
            scaled = scale(drops, multiplier(level));
        } catch (ArithmeticException overflow) {
            // 溢出即放不下，且此时还没碰过 target，天然满足「无任何预留」
            return SettleResult.INSUFFICIENT_SPACE;
        }

        boolean committed = false;
        try {
            for (DropEntry drop : scaled) {
                if (!target.tryReserve(drop.itemId(), drop.count())) {
                    return SettleResult.INSUFFICIENT_SPACE;
                }
            }
            target.commit();
            committed = true;
            return SettleResult.SUCCESS;
        } finally {
            if (!committed) {
                target.rollback();
            }
        }
    }

    /** 倍率 {@code 9^L}。L 在 {@code 1..9} 时最大 387,420,489，不会溢出。 */
    private static long multiplier(int level) {
        long result = 1L;
        for (int i = 0; i < level; i++) {
            result = Math.multiplyExact(result, 9L);
        }
        return result;
    }

    /** 先按物品 ID 合并（保持首次出现顺序），再逐项乘倍率。任一步溢出都抛 {@link ArithmeticException}。 */
    private static List<DropEntry> scale(List<DropEntry> drops, long multiplier) {
        Map<ResourceLocation, Long> merged = new LinkedHashMap<>();
        for (DropEntry drop : drops) {
            Long previous = merged.get(drop.itemId());
            merged.put(drop.itemId(), previous == null ? drop.count() : Math.addExact(previous, drop.count()));
        }

        List<DropEntry> scaled = new ArrayList<>(merged.size());
        for (Map.Entry<ResourceLocation, Long> entry : merged.entrySet()) {
            scaled.add(new DropEntry(entry.getKey(), Math.multiplyExact(entry.getValue(), multiplier)));
        }
        return scaled;
    }
}
