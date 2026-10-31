package com.kalloer.compressedagriculture.crop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * {@link WarnThrottle} 的外部行为：同一位置 600 tick 内只放行一次，不同位置互不影响（ADR-0007）。
 */
class WarnThrottleTest {
    private static final ResourceKey<Level> OVERWORLD = dimension("overworld");
    private static final ResourceKey<Level> NETHER = dimension("the_nether");

    private static ResourceKey<Level> dimension(String path) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("minecraft", path));
    }

    private static GlobalPosKey at(ResourceKey<Level> dimension, int x, int y, int z) {
        return new GlobalPosKey(dimension, BlockPos.asLong(x, y, z));
    }

    @Test
    void firstTimeAlwaysLogs() {
        WarnThrottle throttle = new WarnThrottle();

        assertTrue(throttle.shouldLog(at(OVERWORLD, 0, 64, 0), 100L));
    }

    @Test
    void repeatedWithinSixHundredTicksIsSuppressed() {
        WarnThrottle throttle = new WarnThrottle();
        GlobalPosKey key = at(OVERWORLD, 0, 64, 0);

        assertTrue(throttle.shouldLog(key, 100L));
        assertFalse(throttle.shouldLog(key, 101L));
        assertFalse(throttle.shouldLog(key, 699L));
    }

    @Test
    void logsAgainOnceTheIntervalHasPassed() {
        WarnThrottle throttle = new WarnThrottle();
        GlobalPosKey key = at(OVERWORLD, 0, 64, 0);

        assertTrue(throttle.shouldLog(key, 100L));
        assertTrue(throttle.shouldLog(key, 700L));
        assertFalse(throttle.shouldLog(key, 1299L));
        assertTrue(throttle.shouldLog(key, 1300L));
    }

    @Test
    void differentPositionsDoNotSuppressEachOther() {
        WarnThrottle throttle = new WarnThrottle();

        assertTrue(throttle.shouldLog(at(OVERWORLD, 0, 64, 0), 100L));
        assertTrue(throttle.shouldLog(at(OVERWORLD, 1, 64, 0), 100L));
        assertTrue(throttle.shouldLog(at(OVERWORLD, 0, 64, 1), 100L));
        assertTrue(throttle.shouldLog(at(NETHER, 0, 64, 0), 100L));
    }

    @Test
    void samePackedPositionInDifferentDimensionsAreIndependent() {
        WarnThrottle throttle = new WarnThrottle();

        assertTrue(throttle.shouldLog(at(OVERWORLD, 5, 70, 5), 0L));
        assertTrue(throttle.shouldLog(at(NETHER, 5, 70, 5), 0L));
    }

    @Test
    void trackedPositionsCountsDistinctLocations() {
        WarnThrottle throttle = new WarnThrottle();
        GlobalPosKey key = at(OVERWORLD, 0, 64, 0);

        throttle.shouldLog(key, 0L);
        throttle.shouldLog(key, 1L);
        throttle.shouldLog(at(OVERWORLD, 0, 64, 1), 1L);

        org.junit.jupiter.api.Assertions.assertEquals(2, throttle.trackedPositions());
    }
}
