package com.kalloer.compressedagriculture.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kalloer.compressedagriculture.settle.ChestCapacity;
import com.kalloer.compressedagriculture.settle.DropEntry;
import com.kalloer.compressedagriculture.settle.Settle;
import com.kalloer.compressedagriculture.settle.SettleResult;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * 货仓的分配、预留、存档三件事。
 *
 * <p>纯 Java 可测：物品身份是 {@link ResourceLocation}，存档是 {@link CompoundTag}，两者都不需要 bootstrap（ADR-0017）。
 */
class ChestStorageTest {
    private static final ResourceLocation WHEAT = item("wheat");
    private static final ResourceLocation CARROT = item("carrot");

    private static ResourceLocation item(String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }

    private static ChestStorage storageWithFullSlots(int filledSlots) {
        ChestStorage storage = new ChestStorage();
        for (int slot = 0; slot < filledSlots; slot++) {
            assertTrue(storage.tryReserve(item("filler_" + slot), storage.capacityPerSlot()));
            storage.commit();
        }
        return storage;
    }

    @Test
    void sameItemPrefersSameSlotAndOverflowsIntoEmptySlots() {
        ChestStorage storage = new ChestStorage();

        assertTrue(storage.tryReserve(WHEAT, 100L));
        storage.commit();
        assertEquals(100L, storage.amountOf(WHEAT));
        assertEquals(2, storage.usedSlots(), "100 > 每格上限 64，必须溢出到第二个槽");
        assertEquals(64L, storage.countInSlot(0));
        assertEquals(36L, storage.countInSlot(1));

        assertTrue(storage.tryReserve(WHEAT, 10L));
        storage.commit();
        assertEquals(110L, storage.amountOf(WHEAT));
        assertEquals(46L, storage.countInSlot(1), "同物品优先同槽，不该再开新槽");
        assertEquals(2, storage.usedSlots());
        assertNull(storage.itemInSlot(2));
    }

    @Test
    void reserveFailsWithoutPartialReservationWhenSlotsAreFull() {
        ChestStorage storage = new ChestStorage();
        assertTrue(storage.tryReserve(WHEAT, 32L));
        storage.commit();
        for (int slot = 1; slot < ChestStorage.SLOT_COUNT; slot++) {
            assertTrue(storage.tryReserve(item("filler_" + slot), storage.capacityPerSlot()));
            storage.commit();
        }
        assertEquals(ChestStorage.SLOT_COUNT, storage.usedSlots());

        // 小麦那格还剩 32 的空位，要 33：第一轮能预留 32，之后没有空槽 → 整条失败，且不得留下那 32
        assertFalse(storage.tryReserve(WHEAT, 33L));
        assertFalse(storage.hasPendingReservation(), "失败后不得留下部分预留");
        assertEquals(32L, storage.amountOf(WHEAT), "已存内容不得被失败预留改动");
        assertEquals(ChestStorage.SLOT_COUNT, storage.usedSlots());
    }

    @Test
    void twentyEighthDistinctItemFails() {
        ChestStorage storage = storageWithFullSlots(ChestStorage.SLOT_COUNT);
        assertEquals(ChestStorage.SLOT_COUNT, storage.usedSlots());

        assertFalse(storage.tryReserve(WHEAT, 1L), "27 格各装一种，第 28 种放不下");
        assertEquals(0L, storage.amountOf(WHEAT));
        assertFalse(storage.hasPendingReservation());
    }

    @Test
    void rollbackLeavesContentUnchangedAndCommitPersists() {
        ChestStorage storage = new ChestStorage();

        assertTrue(storage.tryReserve(WHEAT, 10L));
        assertTrue(storage.hasPendingReservation());
        assertEquals(0L, storage.amountOf(WHEAT), "预留未提交前不得计入已存内容");
        assertEquals(0, storage.usedSlots());

        storage.rollback();
        assertFalse(storage.hasPendingReservation());
        assertEquals(0L, storage.amountOf(WHEAT));
        assertEquals(0, storage.usedSlots());

        assertTrue(storage.tryReserve(WHEAT, 10L), "回滚后必须还能重新预留");
        storage.commit();
        assertEquals(10L, storage.amountOf(WHEAT));
        assertEquals(1, storage.usedSlots());
        assertFalse(storage.hasPendingReservation());
    }

    @Test
    void capacityPerSlotFollowsChestCount() {
        ChestStorage storage = new ChestStorage();
        assertEquals(0L, storage.chestCount());
        assertEquals(64L, storage.capacityPerSlot());

        storage.setChestCount(ChestCapacity.MAX_CHEST_COUNT);
        assertEquals(64_000_000_064L, storage.capacityPerSlot());
        assertEquals(64_000_000_064L, ChestCapacity.stackLimit(ChestCapacity.MAX_CHEST_COUNT));
    }

    @Test
    void setChestCountClampsOutOfRangeValues() {
        ChestStorage storage = new ChestStorage();

        storage.setChestCount(ChestCapacity.MAX_CHEST_COUNT + 1L);
        assertEquals(ChestCapacity.MAX_CHEST_COUNT, storage.chestCount());
        assertEquals(64_000_000_064L, storage.capacityPerSlot());

        storage.setChestCount(-5L);
        assertEquals(0L, storage.chestCount());
        assertEquals(64L, storage.capacityPerSlot());
    }

    @Test
    void saveAndLoadRoundTrip() {
        ChestStorage storage = new ChestStorage();
        storage.setChestCount(5L);
        assertTrue(storage.tryReserve(WHEAT, 100L));
        assertTrue(storage.tryReserve(CARROT, 7L));
        storage.commit();

        CompoundTag tag = new CompoundTag();
        storage.save(tag);
        assertEquals(ChestStorage.SCHEMA_VERSION, tag.getInt("schemaVersion"));
        assertEquals(5L, tag.getLong("chestCount"));
        assertEquals(ChestStorage.SLOT_COUNT, tag.getList("slotItems", Tag.TAG_STRING).size());
        assertEquals(ChestStorage.SLOT_COUNT, tag.getLongArray("counts").length);

        ChestStorage loaded = new ChestStorage();
        loaded.load(tag);
        assertEquals(5L, loaded.chestCount());
        assertEquals(storage.capacityPerSlot(), loaded.capacityPerSlot());
        assertEquals(100L, loaded.amountOf(WHEAT));
        assertEquals(7L, loaded.amountOf(CARROT));
        assertEquals(storage.usedSlots(), loaded.usedSlots());
        for (int slot = 0; slot < ChestStorage.SLOT_COUNT; slot++) {
            assertEquals(storage.itemInSlot(slot), loaded.itemInSlot(slot), "槽 " + slot + " 的物品应一致");
            assertEquals(storage.countInSlot(slot), loaded.countInSlot(slot), "槽 " + slot + " 的数量应一致");
        }
        assertFalse(loaded.hasPendingReservation());
    }

    @Test
    void loadWritesNullItemForZeroCount() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("chestCount", 0L);
        tag.putLongArray("counts", new long[ChestStorage.SLOT_COUNT]);

        ChestStorage loaded = new ChestStorage();
        loaded.load(tag);
        for (int slot = 0; slot < ChestStorage.SLOT_COUNT; slot++) {
            assertNull(loaded.itemInSlot(slot));
            assertEquals(0L, loaded.countInSlot(slot));
        }
        assertEquals(0, loaded.usedSlots());
    }

    @Test
    void loadClampsChestCount() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("chestCount", ChestCapacity.MAX_CHEST_COUNT + 100L);

        ChestStorage loaded = new ChestStorage();
        loaded.load(tag);
        assertEquals(ChestCapacity.MAX_CHEST_COUNT, loaded.chestCount());
        assertEquals(64_000_000_064L, loaded.capacityPerSlot());
    }

    @Test
    void settleCommitsMultipliedAmountsIntoStorage() {
        ChestStorage storage = new ChestStorage();

        assertEquals(SettleResult.SUCCESS, Settle.settle(List.of(new DropEntry(WHEAT, 2L)), 2, storage));
        assertEquals(2L * 81L, storage.amountOf(WHEAT), "L=2 的倍率是 9^2 = 81");
        assertFalse(storage.hasPendingReservation());
    }

    @Test
    void settleLeavesStorageUntouchedWhenInsufficientSpace() {
        ChestStorage storage = storageWithFullSlots(ChestStorage.SLOT_COUNT);

        assertEquals(SettleResult.INSUFFICIENT_SPACE,
                Settle.settle(List.of(new DropEntry(WHEAT, 1L)), 1, storage));
        assertEquals(0L, storage.amountOf(WHEAT));
        assertEquals(ChestStorage.SLOT_COUNT, storage.usedSlots());
        assertFalse(storage.hasPendingReservation());
    }

    @Test
    void slotAccessorsRejectOutOfRangeSlots() {
        ChestStorage storage = new ChestStorage();
        assertThrows(IndexOutOfBoundsException.class, () -> storage.itemInSlot(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> storage.countInSlot(ChestStorage.SLOT_COUNT));
    }
}
