package com.kalloer.compressedagriculture.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * 界面放入（ticket 08b）：{@link ChestStorage#insert} 的写入语义。
 *
 * <p>纯 Java 可测，理由同 {@link ChestStorageExtractTest}：物品身份是 {@link ResourceLocation}，存档是 {@link CompoundTag}。
 */
class ChestStorageInsertTest {
    private static final ResourceLocation WHEAT = ResourceLocation.fromNamespaceAndPath("minecraft", "wheat");
    private static final ResourceLocation STONE = ResourceLocation.fromNamespaceAndPath("minecraft", "stone");

    @Test
    void insertIntoEmptySlotStoresItemAndCount() {
        ChestStorage storage = new ChestStorage();

        assertEquals(10L, storage.insert(0, WHEAT, 10L));
        assertEquals(WHEAT, storage.itemInSlot(0), "空格必须写入物品身份");
        assertEquals(10L, storage.countInSlot(0));
        assertEquals(1, storage.usedSlots());
        assertFalse(storage.hasPendingReservation(), "界面放入不走预留，必须不留 pending");
    }

    @Test
    void insertStacksUpToCapacityAndOnlyTakesFree() {
        ChestStorage storage = new ChestStorage();
        long capacity = storage.capacityPerSlot();
        assertEquals(64L, capacity, "chestCount == 0 时每格 64");

        assertEquals(64L, storage.insert(0, WHEAT, 100L), "超过上限只收下 64");
        assertEquals(64L, storage.countInSlot(0));
        assertEquals(0L, storage.insert(0, WHEAT, 5L), "已到上限时一个都进不去");
        assertEquals(64L, storage.countInSlot(0));

        storage.setChestCount(1L);
        assertEquals(64L, storage.insert(0, WHEAT, 200L), "扩容到 128 后还能再收 64");
        assertEquals(128L, storage.countInSlot(0));
        assertEquals(0L, storage.insert(0, WHEAT, 1L));
    }

    @Test
    void insertRejectsDifferentItem() {
        ChestStorage storage = new ChestStorage();
        assertEquals(10L, storage.insert(0, WHEAT, 10L));

        assertEquals(0L, storage.insert(0, STONE, 5L), "一格一种物品：异物品一个都不收");
        assertEquals(WHEAT, storage.itemInSlot(0), "物品身份不许被异物品顶掉");
        assertEquals(10L, storage.countInSlot(0));
        assertEquals(0L, storage.amountOf(STONE));
    }

    @Test
    void insertIntoEmptiedSlotAcceptsAnotherItem() {
        ChestStorage storage = new ChestStorage();
        assertEquals(5L, storage.insert(0, WHEAT, 5L));
        assertEquals(5L, storage.extract(0, 5L));
        assertNull(storage.itemInSlot(0), "取空后该格必须是空格");

        assertEquals(3L, storage.insert(0, STONE, 3L), "取空的格可以放别的物品");
        assertEquals(STONE, storage.itemInSlot(0));
        assertEquals(3L, storage.countInSlot(0));
    }

    @Test
    void insertRejectsNonPositiveAmountsOutOfRangeSlotsAndNullItem() {
        ChestStorage storage = new ChestStorage();

        assertEquals(0L, storage.insert(0, WHEAT, 0L), "amount == 0 不写");
        assertEquals(0L, storage.insert(0, WHEAT, -3L), "amount < 0 不写");
        assertEquals(0, storage.usedSlots(), "上面两次都不得改动状态");

        assertEquals(0L, storage.insert(-1, WHEAT, 1L), "越界返回 0，不抛异常");
        assertEquals(0L, storage.insert(ChestStorage.SLOT_COUNT, WHEAT, 1L), "越界返回 0，不抛异常");
        assertEquals(0, storage.usedSlots(), "越界调用不得改动状态");

        assertThrows(NullPointerException.class, () -> storage.insert(0, null, 1L), "itemId 不许为 null");
    }

    @Test
    void insertedSlotSurvivesSaveLoadRoundTrip() {
        ChestStorage storage = new ChestStorage();
        storage.setChestCount(2L);
        assertEquals(100L, storage.insert(3, WHEAT, 100L));

        CompoundTag tag = new CompoundTag();
        storage.save(tag);

        ChestStorage loaded = new ChestStorage();
        loaded.load(tag);

        assertEquals(WHEAT, loaded.itemInSlot(3));
        assertEquals(100L, loaded.countInSlot(3));
        assertEquals(2L, loaded.chestCount());
        assertEquals(100L, loaded.amountOf(WHEAT));
    }
}
