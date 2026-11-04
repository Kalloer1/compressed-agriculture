package com.kalloer.compressedagriculture.menu;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;
import com.kalloer.compressedagriculture.storage.CompressedItemHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@link CompressedChestMenu} 的点击路径 GameTest：手动放入（ticket 08b）与 SWAP 复制漏洞的回归。
 *
 * <p>为什么是 GameTest 而不是 JUnit：这里必须走原版 {@code AbstractContainerMenu#clicked} 的真实分支，
 * 而它会构造 {@link ItemStack}、查 {@link BuiltInRegistries#ITEM}，纯 JUnit 里没有注册表（理由同
 * {@code CompressedItemHandlerGameTest}）。{@link com.kalloer.compressedagriculture.storage.ChestStorage}
 * 那一层仍然是纯 JUnit。
 *
 * <p>每条用例都是「手动 new 一个菜单 + {@code makeMockPlayer()} 造的玩家 + 真方块上的
 * {@link CompressedBlockEntity}」：菜单里的玩家背包槽和 {@code player.getInventory()} 是同一个
 * {@code Inventory}，所以点击改到的就是玩家的真背包。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class CompressedChestMenuGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);

    /** 玩家背包槽 0（快捷栏第一格）在菜单里的下标：27 个货仓槽 + 27 个背包槽之后。 */
    private static final int HOTBAR_SLOT_0 = CompressedChestMenu.STORAGE_SLOTS + 27;

    /** 光标持 10 个同物品点已有 1 个的格：放入 10，货仓变 11，光标清空。 */
    @GameTest(template = TEMPLATE)
    public void pickupPlacesItemsIntoStorageSlot(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(1, 1, 1));
        chest.storage().insert(0, WHEAT, 1L);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        CompressedChestMenu menu = new CompressedChestMenu(1, player.getInventory(), chest);

        menu.setCarried(new ItemStack(Items.WHEAT, 10));
        menu.clicked(0, 0, ClickType.PICKUP, player);

        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == 11L,
                "expected 1 + 10 = 11 wheat in slot 0, got " + chest.storage().normalizedCountInSlot(0));
        helper.assertTrue(menu.getCarried().isEmpty(),
                "the carried stack must be fully consumed, got " + menu.getCarried());
        helper.succeed();
    }

    /**
     * 格已到 {@code capacityPerSlot}（{@code chestCount == 0} 时是 64）：再点一下什么都不发生。
     *
     * <p>钉住 {@code mayPlace} 的语义：格满时它仍然返回 true（同物品），于是原版 {@code doClick} 走
     * {@code safeInsert}，算出的可放量是 {@code 64 - 展示数 = 0}，一个都不搬。若把「未满」写进 {@code mayPlace}，
     * 会连带把 {@code Slot#allowModification} 打成 false，双击归并（见下一条）就失效了。
     */
    @GameTest(template = TEMPLATE)
    public void fullSlotTakesNothingMore(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(2, 1, 1));
        long capacity = chest.storage().capacityPerSlot();
        chest.storage().insert(0, WHEAT, capacity);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        CompressedChestMenu menu = new CompressedChestMenu(1, player.getInventory(), chest);

        menu.setCarried(new ItemStack(Items.WHEAT, 10));
        menu.clicked(0, 0, ClickType.PICKUP, player);

        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == capacity,
                "a full slot must stay at " + capacity + ", got " + chest.storage().normalizedCountInSlot(0));
        helper.assertTrue(menu.getCarried().getCount() == 10 && menu.getCarried().is(Items.WHEAT),
                "the cursor must keep its 10 wheat, got " + menu.getCarried());
        helper.succeed();
    }

    /**
     * 双击归并（PICKUP_ALL）在货仓槽上仍然有效：光标 10 个小麦、点一个空槽 → 从别格拨 54 个进光标。
     *
     * <p>这条走的是 {@code Slot#allowModification}（{@code mayPickup && mayPlace(getItem())}）与
     * {@code tryRemove} 的守卫；{@code mayPlace} 若在格满时返回 false，这里一个都拨不出来
     * （round5-a 报告里「双击半残」那条）。
     */
    @GameTest(template = TEMPLATE)
    public void pickAllMergesFromStorageSlot(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(6, 1, 1));
        long capacity = chest.storage().capacityPerSlot();
        chest.storage().insert(0, WHEAT, capacity);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        CompressedChestMenu menu = new CompressedChestMenu(1, player.getInventory(), chest);

        menu.setCarried(new ItemStack(Items.WHEAT, 10));
        menu.clicked(5, 0, ClickType.PICKUP_ALL, player);

        helper.assertTrue(menu.getCarried().getCount() == 64 && menu.getCarried().is(Items.WHEAT),
                "the cursor must be topped up to a full stack of wheat, got " + menu.getCarried());
        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == capacity - 54L,
                "the chest must lose exactly the 54 that went to the cursor, got "
                        + chest.storage().normalizedCountInSlot(0));
        helper.succeed();
    }

    /** 异物品：格里有 5000 个小麦时光标持 10 个石头，点击什么都不改（更不许把 5000 换成 10）。 */
    @GameTest(template = TEMPLATE)
    public void differentItemIsRejected(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(3, 1, 1));
        chest.storage().setChestCount(100L);
        chest.storage().insert(0, WHEAT, 5_000L);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        CompressedChestMenu menu = new CompressedChestMenu(1, player.getInventory(), chest);

        menu.setCarried(new ItemStack(Items.STONE, 10));
        menu.clicked(0, 0, ClickType.PICKUP, player);

        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == 5_000L,
                "5000 wheat must survive a click with 10 stone, got " + chest.storage().normalizedCountInSlot(0));
        helper.assertTrue(WHEAT.equals(chest.storage().itemInSlot(0)),
                "the slot must still hold wheat, got " + chest.storage().itemInSlot(0));
        helper.assertTrue(menu.getCarried().getCount() == 10 && menu.getCarried().is(Items.STONE),
                "the cursor must keep its 10 stone, got " + menu.getCarried());
        helper.succeed();
    }

    /**
     * SWAP 不再复制：格里有 5000 个小麦、快捷栏 0 号为空，按 {@code SWAP button = 0} 只搬走一份展示栈（64），
     * 货仓剩 4936。旧实现里 {@code setByPlayer(EMPTY)} 被整个丢掉，玩家白拿 64 个而货仓仍是 5000，可无限复制。
     */
    @GameTest(template = TEMPLATE)
    public void swapTakesOneDisplayStackAndDecrementsStorage(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(4, 1, 1));
        chest.storage().setChestCount(100L);
        chest.storage().insert(0, WHEAT, 5_000L);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        CompressedChestMenu menu = new CompressedChestMenu(1, player.getInventory(), chest);

        menu.clicked(0, 0, ClickType.SWAP, player);

        ItemStack swapped = player.getInventory().getItem(0);
        helper.assertTrue(swapped.getCount() == 64 && swapped.is(Items.WHEAT),
                "the hotbar slot must receive one display stack of 64 wheat, got " + swapped);
        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == 4_936L,
                "the chest must lose exactly those 64, got " + chest.storage().normalizedCountInSlot(0));
        helper.succeed();
    }

    /** 反向 Shift 点击（玩家背包 → 货仓）：40 个小麦进第 0 格，玩家槽清空。 */
    @GameTest(template = TEMPLATE)
    public void quickMoveFromInventoryInsertsIntoStorage(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(5, 1, 1));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        CompressedChestMenu menu = new CompressedChestMenu(1, player.getInventory(), chest);
        player.getInventory().setItem(0, new ItemStack(Items.WHEAT, 40));

        menu.clicked(HOTBAR_SLOT_0, 0, ClickType.QUICK_MOVE, player);

        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == 40L,
                "expected 40 wheat in storage slot 0, got " + chest.storage().normalizedCountInSlot(0));
        helper.assertTrue(player.getInventory().getItem(0).isEmpty(),
                "the player slot must be emptied, got " + player.getInventory().getItem(0));
        helper.succeed();
    }

    /**
     * 带数据组件的物品一律不进货仓：货仓只记「物品 ID + long 计数」，进去就等于把组件当场销毁。
     *
     * <p>三条入口都要堵：点槽（mayPlace）、Shift 放入（quickMoveIntoStorage）、漏斗（isItemValid）。
     * 反例清单：附魔书→普通书、损坏的镐→免费修复、潜影盒→内容物销毁。
     */
    @GameTest(template = TEMPLATE)
    public void componentItemsAreRejectedEverywhere(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(6, 1, 1));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        CompressedChestMenu menu = new CompressedChestMenu(1, player.getInventory(), chest);

        // 带 DAMAGE 的钻石镐：放进去再取出就是满耐久
        ItemStack damagedPickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        damagedPickaxe.set(DataComponents.DAMAGE, 100);

        // 1. 点槽
        menu.setCarried(damagedPickaxe.copy());
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == 0L,
                "a damaged pickaxe must not enter the chest, got " + chest.storage().normalizedCountInSlot(0));
        menu.setCarried(ItemStack.EMPTY);

        // 2. Shift 放入
        player.getInventory().setItem(0, damagedPickaxe.copy());
        menu.clicked(HOTBAR_SLOT_0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == 0L,
                "shift-click must not put a damaged pickaxe in, got " + chest.storage().normalizedCountInSlot(0));
        helper.assertFalse(player.getInventory().getItem(0).isEmpty(),
                "the pickaxe must stay in the player inventory");

        // 3. 漏斗 / 管道
        CompressedItemHandler handler = new CompressedItemHandler(chest);
        helper.assertFalse(handler.isItemValid(0, damagedPickaxe.copy()),
                "the item handler must reject component items too");
        helper.assertTrue(handler.insertItem(0, damagedPickaxe.copy(), false).getCount() == 1,
                "the item handler must hand the pickaxe back");

        // 裸物品照旧能进
        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 10), false).isEmpty(),
                "plain wheat must still fit");
        helper.succeed();
    }

    /**
     * 展示数不能超过物品自身堆叠上限：末影珍珠上限 16，格里存了 64 个时展示数必须是 16。
     *
     * <p>否则 {@code Slot#safeInsert} 里 {@code getMaxStackSize(stack) - getItem().getCount()} 会算出负数，
     * 让光标「长」出物品、造出超上限堆叠。这里直接断言展示栈本身。
     */
    @GameTest(template = TEMPLATE)
    public void displayCountNeverExceedsItemStackLimit(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(7, 1, 1));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        CompressedChestMenu menu = new CompressedChestMenu(1, player.getInventory(), chest);
        chest.storage().setChestCount(1L);
        chest.storage().insert(0, BuiltInRegistries.ITEM.getKey(Items.ENDER_PEARL), 64L);

        ItemStack display = menu.getSlot(0).getItem();
        helper.assertTrue(display.is(Items.ENDER_PEARL),
                "expected ender pearls in the display stack, got " + display);
        helper.assertTrue(display.getCount() == 16,
                "display count must be capped at the item's own stack size (16), got " + display.getCount());
        helper.succeed();
    }

    private static CompressedBlockEntity placeChest(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, ModBlocks.COMPRESSED_DIRT.get(0).get());
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absolute);
        if (chest == null) {
            throw new IllegalStateException("expected a compressed chest at " + absolute);
        }
        return chest;
    }
}
