package com.kalloer.compressedagriculture.menu;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.registry.ModMenus;
import com.kalloer.compressedagriculture.storage.ChestStorage;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

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

/**
 * 压缩方块货仓的 27 格界面（ticket 08）。
 *
 * <p>继承 {@link AbstractContainerMenu} 而不是自己搓一个 Screen：08b 的扩容重试路径 1 依赖
 * {@code PlayerContainerEvent.Close}，只有标准 menu 关闭时才发。
 *
 * <p>只取出、不放入：27 个货仓槽的 {@code mayPlace} 恒为 false，玩家背包的物品塞不进货仓。
 * 取出在服务端执行（{@link ChestStorage#extract}），客户端只读同步结果。
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
    private static final int STORAGE_Y = 28;
    private static final int PLAYER_INVENTORY_X = 8;
    private static final int PLAYER_INVENTORY_Y = 94;
    private static final int HOTBAR_Y = 152;

    /** 展示栈的数量上限：原版 {@link ItemStack} 计数是 {@code int}，且一格显示不下更大的数。 */
    private static final int MAX_DISPLAY_COUNT = 64;

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

    /** 货仓第 {@code storageSlot} 格的精确计数：槽里的展示栈封顶 64，界面的数字要读这里。 */
    public long countInSlot(int storageSlot) {
        if (storageSlot < 0 || storageSlot >= STORAGE_SLOTS) {
            return 0L;
        }
        ChestStorage storage = storage();
        return storage == null ? 0L : storage.countInSlot(storageSlot);
    }

    @Override
    public boolean stillValid(Player player) {
        return this.access.evaluate((level, pos) -> level.getBlockState(pos).getBlock() instanceof CompressedBlock
                && player.canInteractWithBlock(pos, MAX_INTERACT_DISTANCE), false);
    }

    /**
     * Shift 点击：只允许「货仓 → 玩家背包」。玩家背包里的东西不进货仓，所以反方向直接返回空。
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
            return ItemStack.EMPTY;
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
     * 从货仓第 {@code storageSlot} 格取走最多 {@code amount} 个，返回实际取走量。
     *
     * <p>客户端也会调用（点击预测），所以扣减本身不区分端；推送给客户端的那一步在
     * {@link CompressedBlockEntity#changedAndSync()} 里判端。
     */
    private long extract(int storageSlot, int amount) {
        if (this.chest == null) {
            return 0L;
        }
        long taken = this.chest.storage().extract(storageSlot, amount);
        if (taken > 0L) {
            this.chest.changedAndSync();
        }
        return taken;
    }

    @Nullable
    private ChestStorage storage() {
        return this.chest == null ? null : this.chest.storage();
    }

    /**
     * 货仓槽：读的时候把「物品 ID + long 计数」翻成展示用 {@link ItemStack}，写的时候一律拒绝。
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
            return new ItemStack(item, (int) Math.min(count, MAX_DISPLAY_COUNT));
        }

        @Override
        public boolean hasItem() {
            return !getItem().isEmpty();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
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

        @Override
        public void set(ItemStack stack) {
            // 只取出：任何写入都丢掉
        }

        @Override
        public void setByPlayer(ItemStack stack) {
            // 只取出：任何写入都丢掉
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
            // 只取出：任何写入都丢掉
        }

        @Override
        public void setChanged() {
            // 货仓的持久化由 CompressedBlockEntity#changedAndSync 负责，这里不需要转发给容器
        }
    }
}
