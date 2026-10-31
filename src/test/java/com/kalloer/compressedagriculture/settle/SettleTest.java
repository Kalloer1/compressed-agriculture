package com.kalloer.compressedagriculture.settle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

/**
 * {@link Settle} 的外部行为：返回什么、货仓最终存了什么。不关心内部循环次数与 {@code tryReserve} 调用次数。
 */
class SettleTest {
    private static final ResourceLocation WHEAT = ResourceLocation.fromNamespaceAndPath("minecraft", "wheat");
    private static final ResourceLocation CARROT = ResourceLocation.fromNamespaceAndPath("minecraft", "carrot");

    /** chestCount == 0 的货仓：27 格 × 64 = 1728 总量。 */
    private static FakeChest unexpandedChest() {
        return new FakeChest(ChestCapacity.stackLimit(0L));
    }

    @Test
    void singleDropTimesNineToTheLevel() {
        FakeChest chest = unexpandedChest();

        assertEquals(SettleResult.SUCCESS, Settle.settle(List.of(new DropEntry(WHEAT, 3L)), 1, chest));

        assertEquals(27L, chest.amountOf(WHEAT));
        assertEquals(1, chest.usedSlots());
        assertFalse(chest.hasPendingReservation());
    }

    @Test
    void levelNineMultipliesBy387420489() {
        FakeChest chest = new FakeChest(ChestCapacity.stackLimit(ChestCapacity.MAX_CHEST_COUNT));

        assertEquals(SettleResult.SUCCESS, Settle.settle(List.of(new DropEntry(WHEAT, 1L)), 9, chest));

        assertEquals(387_420_489L, chest.amountOf(WHEAT));
        assertFalse(chest.hasPendingReservation());
    }

    @Test
    void scalingOverflowFailsWithoutTouchingChest() {
        FakeChest chest = new FakeChest(ChestCapacity.stackLimit(ChestCapacity.MAX_CHEST_COUNT));

        assertEquals(SettleResult.INSUFFICIENT_SPACE,
                Settle.settle(List.of(new DropEntry(WHEAT, Long.MAX_VALUE)), 9, chest));

        assertEquals(0L, chest.amountOf(WHEAT));
        assertEquals(0, chest.usedSlots());
        assertFalse(chest.hasPendingReservation());
    }

    @Test
    void oneItemNotFittingLeavesEveryItemUnwritten() {
        FakeChest chest = unexpandedChest(); // 27 格 × 64 = 1728，装不下 200 × 9^1

        assertEquals(SettleResult.INSUFFICIENT_SPACE,
                Settle.settle(List.of(new DropEntry(WHEAT, 1L), new DropEntry(CARROT, 200L)), 1, chest));

        assertEquals(0L, chest.amountOf(WHEAT));
        assertEquals(0L, chest.amountOf(CARROT));
        assertEquals(0, chest.usedSlots());
        assertFalse(chest.hasPendingReservation());
    }

    @Test
    void emptyOrNullDropListIsInvalidInputAndDoesNotTouchTarget() {
        assertEquals(SettleResult.INVALID_INPUT, Settle.settle(List.of(), 1, untouchedTarget()));
        assertEquals(SettleResult.INVALID_INPUT, Settle.settle(null, 1, untouchedTarget()));
    }

    @Test
    void unexpandedChestHasStackLimit64() {
        assertEquals(64L, ChestCapacity.stackLimit(0L));
        assertEquals(64L, unexpandedChest().capacityPerSlot());
    }

    @Test
    void maxChestCountKeepsStackLimitBeyondIntRange() {
        assertEquals(64_000_000_064L, ChestCapacity.stackLimit(ChestCapacity.MAX_CHEST_COUNT));

        FakeChest chest = new FakeChest(ChestCapacity.stackLimit(ChestCapacity.MAX_CHEST_COUNT));
        assertEquals(64_000_000_064L, chest.capacityPerSlot());
    }

    @Test
    void sameItemReusesItsSlotAcrossSettles() {
        FakeChest chest = unexpandedChest();

        assertEquals(SettleResult.SUCCESS, Settle.settle(List.of(new DropEntry(WHEAT, 1L)), 1, chest));
        assertEquals(SettleResult.SUCCESS, Settle.settle(List.of(new DropEntry(WHEAT, 1L)), 1, chest));

        assertEquals(18L, chest.amountOf(WHEAT));
        assertEquals(1, chest.usedSlots());
    }

    @Test
    void duplicateDropEntriesMergeBeforeMultiplier() {
        FakeChest chest = unexpandedChest();

        assertEquals(SettleResult.SUCCESS,
                Settle.settle(List.of(new DropEntry(WHEAT, 2L), new DropEntry(WHEAT, 3L)), 1, chest));

        assertEquals(45L, chest.amountOf(WHEAT));
        assertEquals(1, chest.usedSlots());
    }

    @Test
    void levelOutsideOneToNineIsInvalidInput() {
        assertEquals(SettleResult.INVALID_INPUT,
                Settle.settle(List.of(new DropEntry(WHEAT, 1L)), 0, untouchedTarget()));
        assertEquals(SettleResult.INVALID_INPUT,
                Settle.settle(List.of(new DropEntry(WHEAT, 1L)), 10, untouchedTarget()));
    }

    /** 任何方法被调用即失败的货仓，用来断言「INVALID_INPUT 不碰 target」。 */
    private static ChestTarget untouchedTarget() {
        return new ChestTarget() {
            @Override
            public long capacityPerSlot() {
                throw new AssertionError("target must not be touched");
            }

            @Override
            public boolean tryReserve(ResourceLocation itemId, long amount) {
                throw new AssertionError("target must not be touched");
            }

            @Override
            public void commit() {
                throw new AssertionError("target must not be touched");
            }

            @Override
            public void rollback() {
                throw new AssertionError("target must not be touched");
            }
        };
    }
}
