package com.kalloer.compressedagriculture.settle;

import net.minecraft.resources.ResourceLocation;

/**
 * 结算要写入的货仓（chest）。所有对世界的依赖都挡在这道接口后面，所以 {@link Settle#settle} 可以脱离游戏被 JUnit 测试。
 *
 * <p>三阶段契约：
 * <ol>
 *   <li>{@link #tryReserve} 只做预留，不改变已存内容；返回 {@code false} 表示放不下，且不得留下部分预留。</li>
 *   <li>全部预留成功 → {@link #commit()}，预留落到已存内容。</li>
 *   <li>任一预留失败或中途抛异常 → {@link #rollback()}，预留全部丢弃。</li>
 * </ol>
 *
 * <p><b>预留状态只在单次 {@link Settle#settle} 调用内有效，不跨调用存活。</b>
 * {@code settle} 用 {@code try/finally} 保证每次调用结束前 commit 与 rollback 恰好发生一个，
 * 因此实现方不需要（也不应该）为「上次留下的预留」做清理。
 */
public interface ChestTarget {
    /**
     * 每格上限，等于 {@code stackLimit = ChestCapacity.stackLimit(chestCount)}（ADR-0003）。
     * 货仓扩容与等级正交：等级决定乘倍率，本方法决定装得下多少。
     */
    long capacityPerSlot();

    /**
     * 预留 {@code amount} 个 {@code itemId}。放得下返回 {@code true}，放不下返回 {@code false} 且不留任何部分预留。
     * 预留期间已存内容不变，见接口注释的三阶段契约。
     */
    boolean tryReserve(ResourceLocation itemId, long amount);

    /** 把本次结算的全部预留落到已存内容。 */
    void commit();

    /** 丢弃本次结算的全部预留，已存内容不变。 */
    void rollback();
}
