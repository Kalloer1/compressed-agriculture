package com.kalloer.compressedagriculture.storage;

import java.util.Arrays;
import java.util.Objects;

import javax.annotation.Nullable;

import com.kalloer.compressedagriculture.settle.ChestCapacity;
import com.kalloer.compressedagriculture.settle.ChestTarget;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * 压缩方块的货仓：27 格，每格一种物品 + {@code long} 计数。
 *
 * <p>纯数据，不碰世界、不碰 BlockEntity、不碰注册表——物品身份是 {@link ResourceLocation}（ADR-0017），
 * 所以整套分配与预留逻辑可以脱离游戏被 JUnit 直接测。{@link CompressedBlockEntity} 只是「持有它 + 存档 + setChanged」。
 *
 * <p>三阶段契约见 {@link ChestTarget}：{@link #tryReserve} 只留预留，{@link #commit()} 落库，{@link #rollback()}
 * 丢弃。预留只在单次 {@code settle} 调用内有效，{@code settle} 用 {@code try/finally} 保证两者恰好发生一个。
 *
 * <p>分配策略与 ticket 02 的 {@code FakeChest} 一致：同物品优先同槽，不够再用空槽，仍不够就整条失败且不留部分预留。
 *
 * <p><b>不变量：{@code slotItems[i] == null} ⇔ 该格为空 ⇔ 有效数量为 0。</b>「有效数量」一律指
 * {@link #normalizedCountInSlot}：{@code slotItems[i] == null} 时它就是 0，与 {@code slotCounts[i]} 里残留什么无关。
 * 反过来「{@code slotItems[i] != null} 而 {@code slotCounts[i] == 0}」只是未归一化的中间态（例如直接改过的存档），
 * 有效数量同样是 0。所有读计数的外部代码都应走 {@link #normalizedCountInSlot}，别直接读 {@code slotCounts}——
 * 后者对空格没有保证。
 *
 * <p>NBT 读写也放在本类：{@link CompoundTag} 不需要 bootstrap，所以存档格式能脱离游戏验证。
 */
public final class ChestStorage implements ChestTarget {
    /** 货仓格数。 */
    public static final int SLOT_COUNT = 27;
    /** 存档格式版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 扩容档位的 tag 键。存档与物品形态（{@code BLOCK_ENTITY_DATA}）共用同一个键，读取方（如 9 合 1 配方）引用本常量。 */
    public static final String TAG_CHEST_COUNT = "chestCount";

    private static final String TAG_SCHEMA_VERSION = "schemaVersion";
    private static final String TAG_SLOT_ITEMS = "slotItems";
    private static final String TAG_COUNTS = "counts";

    /** 货仓扩容档位，恒在 {@code [0, MAX_CHEST_COUNT]} 内（见 {@link #setChestCount}）。 */
    private long chestCount;
    private final ResourceLocation[] slotItems = new ResourceLocation[SLOT_COUNT];
    private final long[] slotCounts = new long[SLOT_COUNT];
    private final ResourceLocation[] pendingItems = new ResourceLocation[SLOT_COUNT];
    private final long[] pendingCounts = new long[SLOT_COUNT];

    /** 货仓扩容档位，与等级正交（ADR-0003）。 */
    public long chestCount() {
        return this.chestCount;
    }

    /** 设置扩容档位。越界值 clamp 到 {@code [0, MAX_CHEST_COUNT]}——存档被改坏时也不让货仓进入非法状态。 */
    public void setChestCount(long chestCount) {
        this.chestCount = Math.clamp(chestCount, 0L, ChestCapacity.MAX_CHEST_COUNT);
    }

    /** 每格上限 {@code 64 × (1 + chestCount)}，公式只在 {@link ChestCapacity} 里写一次。 */
    @Override
    public long capacityPerSlot() {
        return ChestCapacity.stackLimit(this.chestCount);
    }

    /** 该格已存内容的物品，空格返回 {@code null}。 */
    @Nullable
    public ResourceLocation itemInSlot(int slot) {
        checkSlot(slot);
        return this.slotItems[slot];
    }

    /**
     * 该格已存内容的<b>原始</b>计数（{@code slotCounts[slot]}）。
     *
     * <p>只给存档、调试和本类的内部分配逻辑用：{@code slotItems[slot] == null} 时这里可能还残留非 0 值，
     * 所以「这格有多少」一律读 {@link #normalizedCountInSlot}。
     */
    public long countInSlot(int slot) {
        checkSlot(slot);
        return this.slotCounts[slot];
    }

    /**
     * 该格的归一化数量：{@code slotItems[slot] == null} 时恒为 0，否则等于 {@code slotCounts[slot]}。
     *
     * <p>这是对外唯一的「这格有多少」入口。直接用 {@link #countInSlot} 判断空/满会在「物品为 null 但计数残留」时
     * 报出错误的数字（漏斗据此以为插得进去，于是每 tick 空转）。越界槽位返回 0，与其他容器 API 的宽容语义一致。
     */
    public long normalizedCountInSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return 0L;
        }
        if (this.slotItems[slot] == null) {
            return 0L;
        }
        return Math.max(0L, this.slotCounts[slot]);
    }

    /** 已存内容中该物品的总数（不含未提交的预留）。 */
    public long amountOf(ResourceLocation itemId) {
        Objects.requireNonNull(itemId, "itemId");
        long total = 0L;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (itemId.equals(this.slotItems[slot])) {
                total += this.slotCounts[slot];
            }
        }
        return total;
    }

    /** 已存内容占用的槽数（不含未提交的预留）。 */
    public int usedSlots() {
        int used = 0;
        for (ResourceLocation item : this.slotItems) {
            if (item != null) {
                used++;
            }
        }
        return used;
    }

    /** 是否还有未提交的预留——{@code settle} 返回后正常情况下必为 {@code false}。 */
    public boolean hasPendingReservation() {
        for (long pending : this.pendingCounts) {
            if (pending > 0L) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean tryReserve(ResourceLocation itemId, long amount) {
        Objects.requireNonNull(itemId, "itemId");
        if (amount <= 0L) {
            return false;
        }

        long capacity = capacityPerSlot();
        long[] reserved = new long[SLOT_COUNT];
        long remaining = amount;

        // 同物品优先同槽：先填已装着它、或本轮已为它预留过的槽
        for (int slot = 0; slot < SLOT_COUNT && remaining > 0L; slot++) {
            if (itemId.equals(this.slotItems[slot]) || itemId.equals(this.pendingItems[slot])) {
                long free = Math.max(0L, capacity - this.slotCounts[slot] - this.pendingCounts[slot]);
                long take = Math.min(free, remaining);
                reserved[slot] = take;
                remaining -= take;
            }
        }

        // 再用空槽
        for (int slot = 0; slot < SLOT_COUNT && remaining > 0L; slot++) {
            if (this.slotItems[slot] == null && this.pendingItems[slot] == null) {
                long take = Math.min(capacity, remaining);
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
                this.pendingItems[slot] = itemId;
                this.pendingCounts[slot] += reserved[slot];
            }
        }
        return true;
    }

    /**
     * 从 {@code slot} 取出最多 {@code amount} 个物品，返回实际取出量（ticket 08 的界面取出）。
     *
     * <p>只扣已提交的内容，不碰预留：{@code amount} 超过该格现有量时只扣现有量；扣到 0 时该格同时满足
     * 「{@code slotItems[slot] == null} 且 {@code counts[slot] == 0}」——03 定下的序列化约束（见 {@link #save}）。
     *
     * <p>越界槽位返回 0 而不是抛异常：槽位号来自客户端的点击包，玩家发来的坏包不能让服务端崩。
     * 这与 {@link #itemInSlot} / {@link #countInSlot} 的抛异常语义不同，是有意的。
     */
    public long extract(int slot, long amount) {
        if (slot < 0 || slot >= SLOT_COUNT || amount <= 0L) {
            return 0L;
        }

        long stored = this.slotCounts[slot];
        if (stored <= 0L) {
            // 空格（或存档里数量为 0 的坏格）：顺手归一成 null / 0，不改别的状态
            this.slotItems[slot] = null;
            this.slotCounts[slot] = 0L;
            return 0L;
        }

        long taken = Math.min(stored, amount);
        long left = stored - taken;
        if (left == 0L) {
            this.slotItems[slot] = null;
            this.slotCounts[slot] = 0L;
        } else {
            this.slotCounts[slot] = left;
        }
        return taken;
    }

    /**
     * 往 {@code slot} 放进最多 {@code amount} 个 {@code itemId}，返回实际放进量（ticket 08 的界面放入）。
     *
     * <p>与 {@link #extract} 对称，也是<b>按槽位写</b>：界面上的点击永远指向某一格，所以不能让货仓自己挑槽
     * （{@link #tryReserve} 会从第 0 格开始扫，玩家点第 5 格、物品却进了第 1 格）。
     *
     * <p>规则：
     * <ul>
     *   <li>有效数量为 0 的格（含 {@code slotItems[i] != null} 但计数残留 0 的归一化情形）：接受任何物品，写入物品身份；</li>
     *   <li>已有同物品的格：只能加到 {@link #capacityPerSlot()}；</li>
     *   <li>已有别的物品：返回 0——一格一种物品；</li>
     *   <li>越界槽位、非正数量：返回 0 而不抛异常，理由同 {@link #extract}（槽位号来自客户端的点击包）。</li>
     * </ul>
     *
     * <p>不碰预留：本方法只用于「货仓已经稳定」的场景（界面点击、单测），{@code settle} 的写入仍走
     * {@link #tryReserve} / {@link #commit()}。
     */
    public long insert(int slot, ResourceLocation itemId, long amount) {
        Objects.requireNonNull(itemId, "itemId");
        if (slot < 0 || slot >= SLOT_COUNT || amount <= 0L) {
            return 0L;
        }

        long stored = normalizedCountInSlot(slot);
        if (stored > 0L && !itemId.equals(this.slotItems[slot])) {
            return 0L;
        }

        long free = capacityPerSlot() - stored;
        if (free <= 0L) {
            return 0L;
        }

        long inserted = Math.min(amount, free);
        this.slotItems[slot] = itemId;
        this.slotCounts[slot] = stored + inserted;
        return inserted;
    }

    @Override
    public void commit() {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (this.pendingCounts[slot] > 0L) {
                this.slotItems[slot] = this.pendingItems[slot];
                this.slotCounts[slot] += this.pendingCounts[slot];
            }
        }
        clearPending();
    }

    @Override
    public void rollback() {
        clearPending();
    }

    /**
     * 写进 {@code tag}：{@code schemaVersion}（int）、{@code chestCount}（long）、{@code slotItems}（27 个字符串，
     * 空格写空串）、{@code counts}（27 个 long 的数组）。{@code counts[i] == 0} 时 {@code slotItems[i]} 写空串，
     * 读回来就是 {@code null}。没有 {@code cropId} 字段——作物身份只存在于世界里的植株上。
     */
    public void save(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        tag.putInt(TAG_SCHEMA_VERSION, SCHEMA_VERSION);
        tag.putLong(TAG_CHEST_COUNT, this.chestCount);

        ListTag items = new ListTag();
        long[] counts = new long[SLOT_COUNT];
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ResourceLocation item = this.slotItems[slot];
            boolean empty = item == null || this.slotCounts[slot] <= 0L;
            items.add(StringTag.valueOf(empty ? "" : item.toString()));
            counts[slot] = empty ? 0L : this.slotCounts[slot];
        }
        tag.put(TAG_SLOT_ITEMS, items);
        tag.putLongArray(TAG_COUNTS, counts);
    }

    /**
     * 从 {@code tag} 读回内容，整体覆盖现有状态（包括丢弃未提交的预留）。
     *
     * <p>{@code chestCount} 越界会 clamp 到 {@code [0, MAX_CHEST_COUNT]}。缺字段按空货仓处理；单个格子若数量为 0、
     * 物品名为空或解析失败，该格丢弃为空格——存档被改坏时宁可少一格，也不让货仓进入非法状态。
     */
    public void load(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        setChestCount(tag.getLong(TAG_CHEST_COUNT));

        Arrays.fill(this.slotItems, null);
        Arrays.fill(this.slotCounts, 0L);

        ListTag items = tag.getList(TAG_SLOT_ITEMS, Tag.TAG_STRING);
        long[] counts = tag.getLongArray(TAG_COUNTS);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            long count = slot < counts.length ? counts[slot] : 0L;
            String rawItem = slot < items.size() ? items.getString(slot) : "";
            if (count <= 0L || rawItem.isEmpty()) {
                continue;
            }
            ResourceLocation item = ResourceLocation.tryParse(rawItem);
            if (item == null) {
                continue;
            }
            this.slotItems[slot] = item;
            this.slotCounts[slot] = count;
        }

        clearPending();
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IndexOutOfBoundsException("slot must be within [0, " + SLOT_COUNT + "), but was " + slot);
        }
    }

    private void clearPending() {
        Arrays.fill(this.pendingItems, null);
        Arrays.fill(this.pendingCounts, 0L);
    }
}
