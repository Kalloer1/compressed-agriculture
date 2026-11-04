package com.kalloer.compressedagriculture.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * 归一化计数（{@link ChestStorage#normalizedCountInSlot}）：{@code slotItems == null} 时候恒为 0。
 *
 * <p>纯 JUnit，与 {@link ChestStorageTest} 同一套理由（ADR-0017）：物品身份是 {@link ResourceLocation}、
 * 存档是 {@link CompoundTag}，都不需要 bootstrap。适配器层（{@link CompressedItemHandler}）需要构造
 * {@code ItemStack}，所以那一层由 {@code CompressedItemHandlerGameTest} 覆盖。
 */
class ChestStorageNormalizedCountTest {
    private static final ResourceLocation WHEAT = ResourceLocation.fromNamespaceAndPath("minecraft", "wheat");

    @Test
    void emptyStorageReportsZeroEverywhere() {
        ChestStorage storage = new ChestStorage();
        for (int slot = 0; slot < ChestStorage.SLOT_COUNT; slot++) {
            assertNull(storage.itemInSlot(slot));
            assertEquals(0L, storage.normalizedCountInSlot(slot), "槽 " + slot + " 应是 0");
        }
    }

    @Test
    void normalizedCountMatchesStoredCountForFilledSlots() {
        ChestStorage storage = new ChestStorage();
        assertTrue(storage.tryReserve(WHEAT, 100L));
        storage.commit();

        assertEquals(64L, storage.normalizedCountInSlot(0));
        assertEquals(36L, storage.normalizedCountInSlot(1));
        assertEquals(0L, storage.normalizedCountInSlot(2));
    }

    @Test
    void clearedSlotReportsZeroEvenBeforeCountIsZeroed() {
        ChestStorage storage = new ChestStorage();
        assertTrue(storage.tryReserve(WHEAT, 5L));
        storage.commit();

        assertEquals(5L, storage.extract(0, 5L));
        assertNull(storage.itemInSlot(0));
        assertEquals(0L, storage.normalizedCountInSlot(0), "扣空后归一化计数必须是 0");
        assertEquals(0L, storage.countInSlot(0));
    }

    /**
     * 「物品为 null 但计数残留」是合法可达的状态（例如直接改过存档 / 旧版本留下的格）：
     * 原始计数读得到那个残留值，归一化计数必须是 0——否则漏斗会以为这格已经装着 64 个而永远插不进去。
     */
    @Test
    void nullItemWithResidualCountNormalizesToZero() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(ChestStorage.TAG_CHEST_COUNT, 0L);
        long[] counts = new long[ChestStorage.SLOT_COUNT];
        counts[3] = 64L;
        tag.putLongArray("counts", counts);
        // 不写 slotItems：读回来 27 格全是空格 → slot 3 就是「物品为 null、计数残留 64」

        ChestStorage storage = new ChestStorage();
        storage.load(tag);

        assertNull(storage.itemInSlot(3), "物品名为空时该格读回空格");
        assertEquals(0L, storage.countInSlot(3), "读入阶段已经把这种坏格归一成 0");
        assertEquals(0L, storage.normalizedCountInSlot(3));
        assertEquals(0, storage.usedSlots());
    }

    /** 越界槽位返回 0，与 {@code extract} 的宽容语义一致（不像 {@code countInSlot} 那样抛异常）。 */
    @Test
    void outOfRangeSlotsReportZero() {
        ChestStorage storage = new ChestStorage();
        assertEquals(0L, storage.normalizedCountInSlot(-1));
        assertEquals(0L, storage.normalizedCountInSlot(ChestStorage.SLOT_COUNT));
    }
}
