package com.kalloer.compressedagriculture.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kalloer.compressedagriculture.settle.ChestCapacity;

/**
 * 格子里精确计数的缩写格式。
 *
 * <p>只测字符串：几何（缩放、锚点、避让原版「64」）没法在无头环境里断言，靠人眼验收。
 * 格式本身是可测的硬约束——格子 16px、文字缩一半画，所以缩写最多 4 个字符（12px）才能塞进去；
 * 一旦冒出 5 个字符就会压到右边邻格的图标上。
 */
class CompressedChestScreenShortCountTest {

    @Test
    void exactBelowOneThousand() {
        assertEquals("0", CompressedChestScreen.shortCount(0L));
        assertEquals("1", CompressedChestScreen.shortCount(1L));
        assertEquals("64", CompressedChestScreen.shortCount(64L));
        assertEquals("999", CompressedChestScreen.shortCount(999L));
    }

    @Test
    void singleDigitWholeKeepsOneDecimal() {
        assertEquals("6.4k", CompressedChestScreen.shortCount(6_400L));
        assertEquals("9.9k", CompressedChestScreen.shortCount(9_999L));
        assertEquals("6.4M", CompressedChestScreen.shortCount(6_400_000L));
    }

    @Test
    void trailingZeroDecimalIsDropped() {
        assertEquals("1k", CompressedChestScreen.shortCount(1_000L));
        assertEquals("1M", CompressedChestScreen.shortCount(1_000_000L));
        assertEquals("1G", CompressedChestScreen.shortCount(1_000_000_000L));
        assertEquals("64k", CompressedChestScreen.shortCount(64_000L));
        assertEquals("64M", CompressedChestScreen.shortCount(64_000_000L));
    }

    @Test
    void twoDigitWholeDropsTheDecimal() {
        // 截断不是四舍五入：12_399 → 12k，而不是 12.3k / 12.4k
        assertEquals("10k", CompressedChestScreen.shortCount(10_000L));
        assertEquals("12k", CompressedChestScreen.shortCount(12_399L));
        assertEquals("999k", CompressedChestScreen.shortCount(999_999L));
        assertEquals("999M", CompressedChestScreen.shortCount(999_999_999L));
    }

    @Test
    void fullCapacityIsFourCharacters() {
        long perSlotLimit = ChestCapacity.stackLimit(ChestCapacity.MAX_CHEST_COUNT);
        assertEquals("64G", CompressedChestScreen.shortCount(perSlotLimit));
    }

    @Test
    void neverLongerThanFourCharacters() {
        long[] counts = {
            0L, 64L, 999L, 1_000L, 6_400L, 9_999L, 10_000L, 12_399L, 64_000L, 99_999L, 999_999L,
            1_000_000L, 6_400_000L, 64_000_000L, 999_999_999L, 1_000_000_000L, 64_000_000_064L
        };
        for (long count : counts) {
            String text = CompressedChestScreen.shortCount(count);
            assertTrue(text.length() <= 4, count + " -> " + text + " 超过 4 个字符，会压到邻格");
        }
    }
}
