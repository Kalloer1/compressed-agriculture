package com.kalloer.compressedagriculture.menu;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.registry.ModMenus;
import com.kalloer.compressedagriculture.storage.ChestStorage;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;
import com.kalloer.compressedagriculture.storage.CompressedItemHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 压缩方块货仓的 27 格界面（ticket 08）。
 *
 * <p>继承 {@link AbstractContainerMenu} 而不是自己搓一个 Screen：08b 的扩容重试路径 1 依赖
 * {@code PlayerContainerEvent.Close}，只有标准 menu 关闭时才发。
 *
 * <p>可以取出、也可以放入，但放入受两条约束（见 {@link StorageSlot#mayPlace}）：一格只承载一种物品、
 * 展示栈封顶 64；真正的上限 {@link ChestStorage#capacityPerSlot()}（{@code 64 × (1 + chestCount)}）
 * 由 {@link ChestStorage#insert} 与 {@code Slot#safeInsert} 的“可放量”各自兜住。
 * 写入与取出都只在服务端执行（{@link ChestStorage#insert} / {@link ChestStorage#extract}）；客户端上的菜单代码
 * 同样会被点击预测调用，但这两个方法在客户端直接返回 0，本地货仓只当服务端快照的只读镜像，见 {@link #insert} 注释。
 *
 * <p>显示用两类同步，都是原版机制：
 * <ul>
 *   <li>物品图标走槽位同步（{@code ClientboundContainerSetContentPacket} / {@code ...SetSlotPacket}）——
 *       槽里放的是「物品 + 显示数量（{@code long} 截到 64）」的展示栈；
 *   <li>精确的 {@code long} 计数与等级/容量档位走 {@link CompressedBlockEntity#getUpdateTag()} 与数据槽。
 * </ul>
 * 界面本身不做任何自己的网络包（票里选的同步方式①）。
 */
public class CompressedChestMenu extends AbstractContainerMenu {
    /** 货仓槽数，等于 {@link ChestStorage#SLOT_COUNT}。 */
    public static final int STORAGE_SLOTS = ChestStorage.SLOT_COUNT;
    /** 玩家背包（27 + 9 快捷栏）槽数。 */
    public static final int PLAYER_SLOTS = 36;

    /** 数据槽 0：容量档位 {@code chestCount}。 */
    private static final int DATA_CHEST_COUNT = 0;
    /** 数据槽 1：等级 {@code L}。 */
    private static final int DATA_LEVEL = 1;
    private static final int DATA_COUNT = 2;

    private static final int COLUMNS = 9;
    private static final int SLOT_SIZE = 18;
    private static final int STORAGE_X = 8;
    private static final int STORAGE_Y = 18;
    private static final int PLAYER_INVENTORY_X = 8;
    private static final int PLAYER_INVENTORY_Y = 84;
    private static final int HOTBAR_Y = 142;

    /** 展示栈的数量上限：原版 {@link ItemStack} 计数是 {@code int}，且一格显示不下更大的数。 */
    private static final int MAX_DISPLAY_COUNT = 64;

    /**
     * 展示栈的实际上限 = {@code min(64, 物品自身堆叠上限)}。
     *
     * <p>必须按物品再夹一次（末影珍珠 16、剑 1）：格里的展示数若超过物品堆叠上限，
     * {@code Slot#safeInsert} 里 {@code getMaxStackSize(stack) - getItem().getCount()} 会算出负数，
     * 反而让光标「长」出物品、造出超上限堆叠。
     */
    private static int displayLimit(Item item) {
        return Math.min(MAX_DISPLAY_COUNT, item.getDefaultMaxStackSize());
    }

    /** 界面离方块的交互距离，与原版箱子一致。 */
    private static final double MAX_INTERACT_DISTANCE = 4.0;

    /** 未知物品 ID 只警告一次，避免每帧刷屏。 */
    private static final Set<ResourceLocation> WARNED_UNKNOWN_ITEMS = ConcurrentHashMap.newKeySet();

    private final ContainerLevelAccess access;
    @Nullable
    private final CompressedBlockEntity chest;
    private final int blockLevel;

    private final ContainerData data = new ContainerData() {
        private final int[] values = new int[DATA_COUNT];

        @Override
        public int get(int index) {
            return index >= 0 && index < this.values.length ? this.values[index] : 0;
        }

        @Override
        public void set(int index, int value) {
            if (index >= 0 && index < this.values.length) {
                this.values[index] = value;
            }
        }

        @Override
        public int getCount() {
            return this.values.length;
        }
    };

    /** 服务端构造：方块就在手上。 */
    public CompressedChestMenu(int containerId, Inventory playerInventory, CompressedBlockEntity chest) {
        this(containerId, playerInventory, new ChestLookup(
                chest,
                chest.getLevel() == null
                        ? ContainerLevelAccess.NULL
                        : ContainerLevelAccess.create(chest.getLevel(), chest.getBlockPos()),
                CompressedBlock.levelOf(chest.getBlockState()).orElse(0)));
    }

    /** 客户端构造：服务端打开界面时把方块坐标写进包（{@code player.openMenu(provider, pos)}）。 */
    public CompressedChestMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf data) {
        this(containerId, playerInventory, lookupOnClient(playerInventory, data));
    }

    private CompressedChestMenu(int containerId, Inventory playerInventory, ChestLookup lookup) {
        super(ModMenus.COMPRESSED_CHEST.get(), containerId);
        this.access = lookup.access();
        this.chest = lookup.chest();
        this.blockLevel = lookup.level();

        long chestCount = this.chest == null ? 0L : this.chest.storage().chestCount();
        this.data.set(DATA_CHEST_COUNT, (int) Math.min(chestCount, Integer.MAX_VALUE));
        this.data.set(DATA_LEVEL, this.blockLevel);
        this.addDataSlots(this.data);

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int storageSlot = column + row * COLUMNS;
                this.addSlot(new StorageSlot(storageSlot,
                        STORAGE_X + column * SLOT_SIZE, STORAGE_Y + row * SLOT_SIZE));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                this.addSlot(new Slot(playerInventory, column + row * COLUMNS + 9,
                        PLAYER_INVENTORY_X + column * SLOT_SIZE, PLAYER_INVENTORY_Y + row * SLOT_SIZE));
            }
        }
        for (int column = 0; column < COLUMNS; column++) {
            this.addSlot(new Slot(playerInventory, column,
                    PLAYER_INVENTORY_X + column * SLOT_SIZE, HOTBAR_Y));
        }
    }

    private record ChestLookup(@Nullable CompressedBlockEntity chest, ContainerLevelAccess access, int level) {}

    private static ChestLookup lookupOnClient(Inventory playerInventory, @Nullable RegistryFriendlyByteBuf data) {
        if (data == null) {
            return new ChestLookup(null, ContainerLevelAccess.NULL, 0);
        }
        BlockPos pos = data.readBlockPos();
        if (!(playerInventory.player.level().getBlockEntity(pos) instanceof CompressedBlockEntity chest)) {
            // 区块还没到 / 方块已被换掉：给个空货仓界面，别让客户端崩
            return new ChestLookup(null, ContainerLevelAccess.NULL, 0);
        }
        return new ChestLookup(chest, ContainerLevelAccess.create(chest.getLevel(), pos),
                CompressedBlock.levelOf(chest.getBlockState()).orElse(0));
    }

    /** 等级 {@code L}（数据槽 1），决定收获倍率。 */
    public int blockLevel() {
        return this.data.get(DATA_LEVEL);
    }

    /** 容量档位 {@code chestCount}（数据槽 0），决定每格上限 {@code 64 × (1 + chestCount)}。 */
    public long chestCount() {
        return this.data.get(DATA_CHEST_COUNT);
    }

    /**
     * 界面绑定的方块坐标，给 08b 的「关闭界面时重试结算」定位货仓。
     *
     * <p>客户端区块还没到时（{@link ChestLookup} 里 {@code chest} 为 {@code null}）返回 {@code null}，调用方跳过即可。
     */
    @Nullable
    public BlockPos blockPos() {
        return this.chest == null ? null : this.chest.getBlockPos();
    }

    /** 货仓第 {@code storageSlot} 格的精确计数：槽里的展示栈封顶 64，界面的数字要读这里。 */
    public long countInSlot(int storageSlot) {
        if (storageSlot < 0 || storageSlot >= STORAGE_SLOTS) {
            return 0L;
        }
        ChestStorage storage = storage();
        // 走归一化：slotItems 为 null 而 slotCounts 残留时，原始计数会报出一个不存在的数量
        return storage == null ? 0L : storage.normalizedCountInSlot(storageSlot);
    }

    @Override
    public boolean stillValid(Player player) {
        return this.access.evaluate((level, pos) -> level.getBlockState(pos).getBlock() instanceof CompressedBlock
                && player.canInteractWithBlock(pos, MAX_INTERACT_DISTANCE), false);
    }

    /**
     * Shift 点击：两个方向都支持——货仓 → 玩家背包走 {@link #moveItemStackTo}，玩家背包 → 货仓走
     * {@link #quickMoveIntoStorage}。
     *
     * <p>反方向不能用 {@link #moveItemStackTo}：它把物品就地写回 {@code getItem()} 返回的那个 {@code ItemStack}
     * 再 {@code setChanged()}，而货仓槽的 {@code getItem()} 每次都给一份新副本（真身在 {@link ChestStorage} 的
     * {@code long} 计数里），就地改动会直接丢掉。所以这个方向自己找槽、自己扣玩家槽。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= this.slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        if (index >= STORAGE_SLOTS) {
            return quickMoveIntoStorage(slot);
        }

        ItemStack display = slot.getItem();
        ItemStack remaining = display.copy();
        if (!this.moveItemStackTo(remaining, STORAGE_SLOTS, this.slots.size(), true)) {
            return ItemStack.EMPTY;
        }
        int moved = display.getCount() - remaining.getCount();
        if (moved <= 0) {
            return ItemStack.EMPTY;
        }
        // moveItemStackTo 只搬 ItemStack，货仓的 long 计数得自己扣
        long taken = extract(index, moved);
        return taken <= 0L ? ItemStack.EMPTY : display.copyWithCount((int) taken);
    }

    /**
     * 玩家背包槽 → 货仓：找一格能收下这件物品的货仓槽，放进 {@code min(玩家栈数量, 该格剩余容量)}，
     * 再从玩家槽里扣掉同样的数量。
     *
     * <p>返回搬进去的那一份：{@code AbstractContainerMenu#doClick} 靠它判断「还能不能接着搬」；
     * 找不到目标格、或服务端拒绝（客户端一律返回 0）时返回 {@link ItemStack#EMPTY}，玩家槽一个都不动。
     *
     * <p>一次只填一格，不像 {@code moveItemStackTo} 那样扫全表：货仓一格一种物品，一件物品只有一个合法落点。
     * 一次搬不完时靠调用方（原版的 {@code while} 循环）再来一次，第二次自然落到下一格。
     */
    private ItemStack quickMoveIntoStorage(Slot sourceSlot) {
        ChestStorage storage = storage();
        if (storage == null) {
            return ItemStack.EMPTY;
        }
        ItemStack source = sourceSlot.getItem();
        if (source.isEmpty() || !CompressedItemHandler.isStorable(source)) {
            // 带数据组件的物品（附魔、耐久、潜影盒内容…）不能进货仓：货仓只记「物品 ID + 计数」，
            // 放进去等于把组件当场销毁，取出来就是一个裸物品
            return ItemStack.EMPTY;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(source.getItem());
        int target = findStorageSlotFor(storage, itemId);
        if (target < 0) {
            return ItemStack.EMPTY;
        }
        long free = storage.capacityPerSlot() - storage.normalizedCountInSlot(target);
        if (free <= 0L) {
            return ItemStack.EMPTY;
        }
        long inserted = insert(target, itemId, Math.min(source.getCount(), free));
        if (inserted <= 0L) {
            return ItemStack.EMPTY;
        }
        ItemStack moved = source.copyWithCount((int) inserted);
        source.shrink((int) inserted);
        if (source.isEmpty()) {
            sourceSlot.setByPlayer(ItemStack.EMPTY);
        } else {
            sourceSlot.setChanged();
        }
        return moved;
    }

    /** 能收下 {@code itemId} 的货仓槽：同物品且未满优先，其次有效数量为 0 的格；都没有返回 -1。 */
    private static int findStorageSlotFor(ChestStorage storage, ResourceLocation itemId) {
        long capacity = storage.capacityPerSlot();
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            if (itemId.equals(storage.itemInSlot(slot)) && storage.normalizedCountInSlot(slot) < capacity) {
                return slot;
            }
        }
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            if (storage.normalizedCountInSlot(slot) <= 0L) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * 从货仓第 {@code storageSlot} 格取走最多 {@code amount} 个，返回实际取走量；<b>客户端恒返回 0</b>。
     *
     * <p>为什么客户端不扣：客户端的菜单也会被点击预测调用（{@code MultiPlayerGameMode#handleInventoryMouseClick}
     * → {@link AbstractContainerMenu#clicked}，落点是 {@link StorageSlot#remove} 与本类的 {@link #quickMoveStack}），
     * 而客户端的 {@link ChestStorage} 只是服务端用 {@link CompressedBlockEntity#getUpdateTag()} /
     * {@code getUpdatePacket()} 同步下来的镜像。本类没有 override {@code clicked}，服务端一旦拒绝这次点击
     * （原版回弹 / {@code syncState}），客户端没有任何回滚路径，就会留下幽灵物品或重复计数，
     * 直到服务端下个数据包到来才被纠正。所以客户端只读：本地看起来比服务端旧一帧，等
     * {@link CompressedBlockEntity#changedAndSync()} 推来的 BlockEntity 数据刷新即可，不会永久卡住。
     *
     * <p>客户端返回 0 是安全的：{@link Slot#tryRemove} 把 {@code remove} 的 {@code ItemStack.EMPTY}
     * 当成「取不到」直接放弃，光标与槽位都不动（与原版空槽同一条路径）。
     */
    private long extract(int storageSlot, int amount) {
        if (this.chest == null) {
            return 0L;
        }
        Level level = this.chest.getLevel();
        if (level != null && level.isClientSide()) {
            // 客户端：不碰本地货仓镜像，视图完全由服务端数据包驱动
            return 0L;
        }
        long taken = this.chest.storage().extract(storageSlot, amount);
        if (taken > 0L) {
            this.chest.changedAndSync();
        }
        return taken;
    }

    /**
     * 往货仓第 {@code storageSlot} 格放进最多 {@code amount} 个，返回实际放进量；<b>客户端恒返回 0</b>。
     *
     * <p>与 {@link #extract} 完全对称（包括客户端只读）：本地货仓只是服务端同步下来的镜像，
     * 如果客户端先乐观地加上去，服务端一旦拒绝（比如容量刚好被漏斗占满）就没有回滚路径，
     * 只能等下个数据包纠正，中间会看到幽灵物品。代价是放入的显示晚 1 tick / 1 RTT，与取出时一致。
     */
    private long insert(int storageSlot, ResourceLocation itemId, long amount) {
        if (this.chest == null) {
            return 0L;
        }
        Level level = this.chest.getLevel();
        if (level != null && level.isClientSide()) {
            return 0L;
        }
        long inserted = this.chest.storage().insert(storageSlot, itemId, amount);
        if (inserted > 0L) {
            this.chest.changedAndSync();
        }
        return inserted;
    }

    @Nullable
    private ChestStorage storage() {
        return this.chest == null ? null : this.chest.storage();
    }

    /**
     * 货仓槽：读的时候把「物品 ID + long 计数」翻成展示用 {@link ItemStack}，写的时候按「展示栈的增量」
     * 换算回货仓的 {@code long} 计数（见 {@link #applyDelta}）。
     *
     * <p>数量截到 64 是因为原版 {@code ItemStack} 计数是 {@code int}、界面也画不下更大的数字；精确的
     * {@code long} 值由界面从同步过来的 BlockEntity 里读。
     */
    private final class StorageSlot extends Slot {
        private final int storageSlot;

        private StorageSlot(int storageSlot, int x, int y) {
            super(new SimpleContainer(STORAGE_SLOTS), storageSlot, x, y);
            this.storageSlot = storageSlot;
        }

        @Override
        public ItemStack getItem() {
            ChestStorage storage = storage();
            if (storage == null) {
                return ItemStack.EMPTY;
            }
            ResourceLocation itemId = storage.itemInSlot(this.storageSlot);
            long count = storage.countInSlot(this.storageSlot);
            if (itemId == null || count <= 0L) {
                return ItemStack.EMPTY;
            }
            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (item == null || item == Items.AIR) {
                // 模组被卸载：物品 ID 还在存档里，但已经没有对应物品。跳过该格并警告一次，别让界面崩
                if (WARNED_UNKNOWN_ITEMS.add(itemId)) {
                    CompressedAgriculture.LOGGER.warn(
                            "Chest slot {} holds unknown item {}, skipping it in the GUI", this.storageSlot, itemId);
                }
                return ItemStack.EMPTY;
            }
            return new ItemStack(item, (int) Math.min(count, displayLimit(item)));
        }

        @Override
        public boolean hasItem() {
            return !getItem().isEmpty();
        }

        /**
         * 能不能把这个物品放进这格：空槽 true / 同物品 true（<b>不管满没满</b>）/ 其他物品 false。
         *
         * <p>为什么「同物品」不看容量：原版 {@code Slot#mayPlace} 回答的是「这类物品能不能进这种槽」，
         * 「现在还有没有空位」由 {@code Slot#safeInsert} 里的 {@code getMaxStackSize(stack) - getItem().getCount()}
         * 负责（格满时它算出可放量 0，一个都不会搬）。在这里判满反而会弄坏两条原版路径：
         * {@code Slot#allowModification}（{@code mayPickup && mayPlace(getItem())}）会变成 false，
         * 把满格的双击归并（PICKUP_ALL）与 {@code tryRemove} 一起挡掉。
         *
         * <p>「其他物品 false」是硬要求：{@code AbstractContainerMenu#doClick} 的 SWAP / PICKUP 分支在
         * {@code mayPlace} 为 true 时会 {@code setByPlayer(手里那 10 个)}，把整格 5000 个换成 10 个别的物品。
         * 「一格一种物品」必须在这一层挡住，{@link #applyDelta} 只做第二道保险。
         *
         * <p>本方法与 {@code CompressedItemHandler#isItemValid} 语义<b>不同</b>，别「统一」掉：那个回答的是
         * 「漏斗现在能不能塞进去」（必须看容量，否则扩容后的槽会被原版漏斗的预检当满格拒掉）。
         */
        @Override
        public boolean mayPlace(ItemStack stack) {
            if (stack.isEmpty() || !CompressedItemHandler.isStorable(stack)) {
                // 不接收带数据组件的物品：货仓一格只记「物品 ID + long 计数」（ADR-0017），
                // 附魔书会变普通书、损坏的镐会「免费修复」、潜影盒的内容会被直接销毁。
                // 注意 mayPlace(getItem()) 传进来的是重建的裸栈（组件 patch 恒空），所以双击归并不受影响。
                return false;
            }
            ChestStorage storage = storage();
            if (storage == null) {
                return false;
            }
            if (storage.normalizedCountInSlot(this.storageSlot) <= 0L) {
                return true;
            }
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(storage.itemInSlot(this.storageSlot));
        }

        @Override
        public boolean mayPickup(Player player) {
            return !getItem().isEmpty();
        }

        @Override
        public int getMaxStackSize() {
            return MAX_DISPLAY_COUNT;
        }

        @Override
        public ItemStack remove(int amount) {
            ItemStack display = getItem();
            if (display.isEmpty() || amount <= 0) {
                return ItemStack.EMPTY;
            }
            long taken = extract(this.storageSlot, Math.min(amount, display.getCount()));
            return taken <= 0L ? ItemStack.EMPTY : display.copyWithCount((int) taken);
        }

        /**
         * 三个写入口（{@link #set} / {@link #setByPlayer} 的两个重载）都收敛到这里。
         *
         * <p><b>为什么是「增量」而不是「绝对覆盖」：</b>原版 {@code Slot#safeInsert} 在格子里已有同物品时会
         * {@code itemstack.grow(i); setByPlayer(itemstack)}，传进来的 {@code itemstack} 是 {@link #getItem()}
         * 返回的<b>展示栈</b>（≤ 64），不是这格的真实数量。所以写入量取「新展示数 − 当前展示数」：
         * {@code > 0} 走插入、{@code < 0} 走取出、{@code 0} 什么都不做。
         *
         * <p>SWAP 取出（{@code setByPlayer(EMPTY)}，格里有 5000）就是靠这条规则算成「取 64」的：旧实现
         * 把写入整个丢掉，玩家拿到了 64 个而货仓仍是 5000，可以无限复制。
         *
         * <p>客户端不写：{@link #insert} / {@link #extract} 在客户端恒返回 0，本地镜像只由服务端数据包刷新。
         */
        private void applyDelta(ItemStack newStack) {
            ChestStorage storage = storage();
            if (storage == null) {
                return;
            }
            if (!newStack.isEmpty() && !CompressedItemHandler.isStorable(newStack)) {
                // 第三道保险（前两道在 mayPlace / quickMoveIntoStorage）：原版还有别的 setByPlayer 调用点
                return;
            }
            long shown = Math.min(storage.normalizedCountInSlot(this.storageSlot), MAX_DISPLAY_COUNT);
            long target = newStack.isEmpty() ? 0L : Math.min(newStack.getCount(), MAX_DISPLAY_COUNT);
            long delta = target - shown;
            if (delta > 0L) {
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(newStack.getItem());
                long stored = storage.normalizedCountInSlot(this.storageSlot);
                if (stored > 0L && !itemId.equals(storage.itemInSlot(this.storageSlot))) {
                    // 一格一种物品：写入路径的第二道保险（第一道在 mayPlace）
                    return;
                }
                insert(this.storageSlot, itemId, delta);
            } else if (delta < 0L) {
                // delta 的绝对值封顶在 MAX_DISPLAY_COUNT，转 int 不会溢出
                extract(this.storageSlot, (int) -delta);
            }
        }

        @Override
        public void set(ItemStack stack) {
            applyDelta(stack);
        }

        @Override
        public void setByPlayer(ItemStack stack) {
            applyDelta(stack);
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
            applyDelta(stack);
        }

        @Override
        public void setChanged() {
            // 货仓的持久化由 CompressedBlockEntity#changedAndSync 负责，这里不需要转发给容器
        }
    }
}
