package com.kalloer.compressedagriculture.settle;

/**
 * 货仓扩容档位（chestCount）与每格上限（stackLimit）的换算。
 *
 * <p>{@code stackLimit = 64 × (1 + chestCount)}，全程 {@code long}：取到 {@link #MAX_CHEST_COUNT} 时是
 * {@code 64_000_000_064}，已超出 {@code int} 范围（ADR-0003）。
 *
 * <p>货仓实现（CompressedBlockEntity）直接引用本类，不再自己声明 {@code MAX_CHEST_COUNT}。
 */
public final class ChestCapacity {
    /** 货仓扩容上限。 */
    public static final long MAX_CHEST_COUNT = 1_000_000_000L;

    /** 未扩容（chestCount == 0）时的每格上限。 */
    private static final long BASE_STACK_LIMIT = 64L;

    private ChestCapacity() {}

    /**
     * 每格上限。{@code chestCount} 必须在 {@code [0, MAX_CHEST_COUNT]} 内，越界说明存档被改坏或扩容逻辑算错。
     */
    public static long stackLimit(long chestCount) {
        if (chestCount < 0L || chestCount > MAX_CHEST_COUNT) {
            throw new IllegalArgumentException(
                    "chestCount must be within [0, " + MAX_CHEST_COUNT + "], but was " + chestCount);
        }
        return BASE_STACK_LIMIT * (1L + chestCount);
    }
}
