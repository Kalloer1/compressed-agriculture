package com.kalloer.compressedagriculture.crop;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 按位置限流的「这次该不该记日志」判断（ADR-0007）。
 *
 * <p>纯 Java：只认 {@link GlobalPosKey} 与游戏刻数字，不碰世界、不碰注册表，所以可以直接被 JUnit 覆盖。
 *
 * <p>限流表与待观察集合（{@code CropModule} 里的骨粉待观察位置）分开维护——两者生命周期不同：
 * 待观察集合每 tick 清空，限流表要跨 tick 记住上次记日志的时刻。
 */
public final class WarnThrottle {
    /** 同一位置两次日志之间的最小间隔：600 tick = 30 秒。 */
    public static final long DEFAULT_INTERVAL_TICKS = 600L;

    /** 限流表条目上限，超过时先清掉已经过期的条目，避免误配整合包把内存吃光。 */
    private static final int MAX_ENTRIES = 1024;

    private final long intervalTicks;
    private final Map<GlobalPosKey, Long> lastLoggedAt = new HashMap<>();

    public WarnThrottle() {
        this(DEFAULT_INTERVAL_TICKS);
    }

    public WarnThrottle(long intervalTicks) {
        if (intervalTicks <= 0L) {
            throw new IllegalArgumentException("intervalTicks must be positive, but was " + intervalTicks);
        }
        this.intervalTicks = intervalTicks;
    }

    /**
     * 该位置此刻是否允许记一条日志。返回 {@code true} 时同时把「上次记录时刻」推进到 {@code gameTime}，
     * 因此同一个 tick 里连续调用只会放行第一次。
     */
    public boolean shouldLog(GlobalPosKey key, long gameTime) {
        Long previous = this.lastLoggedAt.get(key);
        if (previous != null && gameTime - previous < this.intervalTicks) {
            return false;
        }
        if (previous == null && this.lastLoggedAt.size() >= MAX_ENTRIES) {
            this.evictExpired(gameTime);
        }
        this.lastLoggedAt.put(key, gameTime);
        return true;
    }

    /** 当前被记住的位置数量，给测试与调试用。 */
    public int trackedPositions() {
        return this.lastLoggedAt.size();
    }

    private void evictExpired(long gameTime) {
        Iterator<Map.Entry<GlobalPosKey, Long>> iterator = this.lastLoggedAt.entrySet().iterator();
        while (iterator.hasNext()) {
            if (gameTime - iterator.next().getValue() >= this.intervalTicks) {
                iterator.remove();
            }
        }
    }
}
