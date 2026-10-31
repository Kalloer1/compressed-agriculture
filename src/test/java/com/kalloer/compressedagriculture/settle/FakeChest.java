package com.kalloer.compressedagriculture.settle;

import java.util.Arrays;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/**
 * {@link ChestTarget} 的内存实现，供 JUnit 直接使用：27 格，每格一种物品 + {@code long} 计数。
 *
 * <p>预留只在 {@link #commit()} 时落到已存内容，{@link #rollback()} 丢弃预留。对外只暴露已存内容
 * （{@link #amountOf} / {@link #usedSlots()}）；预留状态用 {@link #hasPendingReservation()} 观察。
 * 分配策略：同一物品优先填已装着它的槽，不够再用空槽。
 */
public final class FakeChest implements ChestTarget {
    public static final int SLOT_COUNT = 27;

    private final long capacityPerSlot;
    private final ResourceLocation[] slotItems = new ResourceLocation[SLOT_COUNT];
    private final long[] slotCounts = new long[SLOT_COUNT];
    private final ResourceLocation[] pendingItems = new ResourceLocation[SLOT_COUNT];
    private final long[] pendingCounts = new long[SLOT_COUNT];

    public FakeChest(long capacityPerSlot) {
        if (capacityPerSlot <= 0L) {
            throw new IllegalArgumentException("capacityPerSlot must be positive, but was " + capacityPerSlot);
        }
        this.capacityPerSlot = capacityPerSlot;
    }

    @Override
    public long capacityPerSlot() {
        return capacityPerSlot;
    }

    @Override
    public boolean tryReserve(ResourceLocation itemId, long amount) {
        Objects.requireNonNull(itemId, "itemId");
        if (amount <= 0L) {
            return false;
        }

        long[] reserved = new long[SLOT_COUNT];
        long remaining = amount;

        // 同物品优先同槽：先填已装着它、或本轮已为它预留过的槽
        for (int slot = 0; slot < SLOT_COUNT && remaining > 0L; slot++) {
            if (itemId.equals(slotItems[slot]) || itemId.equals(pendingItems[slot])) {
                long free = capacityPerSlot - slotCounts[slot] - pendingCounts[slot];
                long take = Math.min(free, remaining);
                reserved[slot] = take;
                remaining -= take;
            }
        }

        // 再用空槽
        for (int slot = 0; slot < SLOT_COUNT && remaining > 0L; slot++) {
            if (slotItems[slot] == null && pendingItems[slot] == null) {
                long take = Math.min(capacityPerSlot, remaining);
                reserved[slot] = take;
                remaining -= take;
            }
        }

        if (remaining > 0L) {
            // 放不下：一条预留都不留
            return false;
        }

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (reserved[slot] > 0L) {
                pendingItems[slot] = itemId;
                pendingCounts[slot] += reserved[slot];
            }
        }
        return true;
    }

    @Override
    public void commit() {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (pendingCounts[slot] > 0L) {
                slotItems[slot] = pendingItems[slot];
                slotCounts[slot] += pendingCounts[slot];
            }
        }
        clearPending();
    }

    @Override
    public void rollback() {
        clearPending();
    }

    /** 已存内容中该物品的总数（不含未提交的预留）。 */
    public long amountOf(ResourceLocation itemId) {
        long total = 0L;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (itemId.equals(slotItems[slot])) {
                total += slotCounts[slot];
            }
        }
        return total;
    }

    /** 已存内容占用的槽数（不含未提交的预留）。 */
    public int usedSlots() {
        int used = 0;
        for (ResourceLocation item : slotItems) {
            if (item != null) {
                used++;
            }
        }
        return used;
    }

    /** 是否还有未提交的预留——正常情况下 {@link Settle#settle} 返回后必为 {@code false}。 */
    public boolean hasPendingReservation() {
        for (long pending : pendingCounts) {
            if (pending > 0L) {
                return true;
            }
        }
        return false;
    }

    private void clearPending() {
        Arrays.fill(pendingItems, null);
        Arrays.fill(pendingCounts, 0L);
    }
}
