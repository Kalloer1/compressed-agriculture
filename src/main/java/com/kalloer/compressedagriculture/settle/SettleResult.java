package com.kalloer.compressedagriculture.settle;

/**
 * 一次结算（{@link Settle#settle}）的结果。
 *
 * <ul>
 *   <li>{@link #SUCCESS}：全部物品已写入货仓。调用方此时才做成功处理（重置幼苗 / 回填）。</li>
 *   <li>{@link #INSUFFICIENT_SPACE}：原子失败——一个物品都没写。调用方保留成熟态、不重置幼苗（ADR-0005）。</li>
 *   <li>{@link #INVALID_INPUT}：输入不合法（掉落清单为空、等级越界），货仓完全没被碰过，不做处理。</li>
 * </ul>
 */
public enum SettleResult {
    SUCCESS,
    INSUFFICIENT_SPACE,
    INVALID_INPUT
}
