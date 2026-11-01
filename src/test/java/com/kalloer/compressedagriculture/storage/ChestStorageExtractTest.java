package com.kalloer.compressedagriculture.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * 界面取出（ticket 08）：{@link ChestStorage#extract} 的扣减语义。
 *
 * <p>纯 Java 可测，理由同 {@link ChestStorageTest}：物品身份是 {@link ResourceLocation}，存档是 {@link CompoundTag}。
 */
class ChestStorageExtractTest {
    private static final ResourceLocation WHEAT = ResourceLocation.fromNamespaceAndPath("minecraft", "wheat");

    private static ChestStorage storageWithWheat(long amount) {
        ChestStorage storage = new ChestStorage();
        assertTrue(storage.tryReserve(WHEAT, amount));
        storage.commit();
        return storage;
    }

    @Test
    void extractDecrementsSlot() {
        ChestStorage storage = storageWithWheat(100L);
        assertEquals(64L, storage.countInSlot(0), "100 超过每格上限 64，第二格装 36");
        assertEquals(36L, storage.countInSlot(1));

        assertEquals(10L, storage.extract(0, 10L));
        assertEquals(54L, storage.countInSlot(0));
        assertEquals(WHEAT, storage.itemInSlot(0), "没扣到 0 时物品身份保持不变");
        assertEquals(90L, storage.amountOf(WHEAT), "总额 = 54 + 36");
    }

    @Test
    void extractToZeroClearsItemAndCount() {
        ChestStorage storage = storageWithWheat(5L);

        assertEquals(5L, storage.extract(0, 5L));
        assertNull(storage.itemInSlot(0), "扣到 0 时 slotItems[i] 必须变 null（03 的序列化约束）");
        assertEquals(0L, storage.countInSlot(0), "扣到 0 时 counts[i] 必须为 0");
        assertEquals(0, storage.usedSlots());
        assertEquals(0L, storage.amountOf(WHEAT));
    }

    @Test
    void extractMoreThanStoredOnlyTakesStored() {
        ChestStorage storage = storageWithWheat(7L);

        assertEquals(7L, storage.extract(0, 1_000L));
        assertNull(storage.itemInSlot(0));
        assertEquals(0L, storage.countInSlot(0));
        assertEquals(0L, storage.extract(0, 1_000L), "空格再取还是 0");
    }

    @Test
    void extractRejectsNonPositiveAmountsOutOfRangeSlotsAndEmptySlots() {
        ChestStorage storage = storageWithWheat(10L);

        assertEquals(0L, storage.extract(0, 0L), "amount == 0 不扣");
        assertEquals(0L, storage.extract(0, -3L), "amount < 0 不扣");
        assertEquals(10L, storage.countInSlot(0), "上面两次都不得改动状态");

        assertEquals(0L, storage.extract(-1, 1L), "越界返回 0，不抛异常");
        assertEquals(0L, storage.extract(ChestStorage.SLOT_COUNT, 1L), "越界返回 0，不抛异常");
        assertEquals(10L, storage.countInSlot(0), "越界调用不得改动状态");

        assertEquals(0L, storage.extract(26, 5L), "空格返回 0");
        assertNull(storage.itemInSlot(26));
        assertEquals(10L, storage.countInSlot(0));
        assertFalse(storage.hasPendingReservation());
    }

    @Test
    void extractedSlotSurvivesSaveLoadRoundTrip() {
        ChestStorage storage = storageWithWheat(100L);
        assertEquals(64L, storage.extract(0, 64L), "把第一格取空");

        CompoundTag tag = new CompoundTag();
        storage.save(tag);

        ChestStorage loaded = new ChestStorage();
        loaded.load(tag);

        assertNull(loaded.itemInSlot(0), "取空的格在存档里必须是空格");
        assertEquals(0L, loaded.countInSlot(0));
        assertEquals(36L, loaded.countInSlot(1), "没取的第二格原样保留");
        assertEquals(36L, loaded.amountOf(WHEAT));
        assertEquals(1, loaded.usedSlots());
        assertEquals(storage.chestCount(), loaded.chestCount());
    }
}
