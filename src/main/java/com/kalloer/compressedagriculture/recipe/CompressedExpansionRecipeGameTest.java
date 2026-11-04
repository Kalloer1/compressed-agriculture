package com.kalloer.compressedagriculture.recipe;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlockEntities;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.settle.ChestCapacity;
import com.kalloer.compressedagriculture.storage.ChestStorage;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 扩容配方的 GameTest（ticket 08b）。
 *
 * <p>断言都走 {@code RecipeManager} 的真实查找链路，测的是数据包 + 匹配规则，不是直接 new 出来的配方对象。
 *
 * <p>扩容的语义是「1 个任意压缩方块 + 若干箱子 → 同一个方块，档位 += 箱子总数」：箱子按 {@code getCount()} 跨格子累加，
 * 陷阱箱与普通箱子等价，混进任何别的物品都不匹配。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class CompressedExpansionRecipeGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);

    /** 1 个箱子 → 档位 +1。 */
    @GameTest(template = TEMPLATE)
    public void singleChestAddsOne(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_DIRT.get(0).get();
        ItemStack[] grid = emptyGrid();
        grid[0] = new ItemStack(source);
        grid[4] = new ItemStack(Items.CHEST);

        ItemStack result = craft(helper, grid);
        helper.assertTrue(result.is(source.asItem()), "expected compressed dirt back, got " + result);
        helper.assertTrue(resultChestCount(result) == 1L, "expected chestCount 1, got " + resultChestCount(result));
        helper.succeed();
    }

    /** 箱子跨格子累加：44 + 64 + 1 = 109，档位 +109（不是 +3、也不是 +1）。 */
    @GameTest(template = TEMPLATE)
    public void chestsAccumulateAcrossSlots(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_FARMLAND.get(2).get();
        ItemStack[] grid = emptyGrid();
        grid[0] = compressed(source, 4L, WHEAT, 500L);
        grid[1] = count(Items.CHEST, 44);
        grid[6] = count(Items.CHEST, 64);
        grid[8] = count(Items.CHEST, 1);

        ItemStack result = craft(helper, grid);
        helper.assertTrue(result.is(source.asItem()), "expected the same block item back, got " + result);

        CustomData data = result.get(DataComponents.BLOCK_ENTITY_DATA);
        helper.assertTrue(data != null, "expected BLOCK_ENTITY_DATA on the result, got " + result.getComponents());
        helper.assertTrue(data.copyTag().contains("id"),
                "expected the block entity type id in the tag, otherwise the component is dropped on save");

        ChestStorage storage = new ChestStorage();
        storage.load(data.copyTag());
        helper.assertTrue(storage.chestCount() == 113L, "expected chestCount 4 + 109 = 113, got " + storage.chestCount());
        // 源货仓的 chestCount = 4，每格上限 = 64 × (1 + 4) = 320，所以 500 个小麦本来就占 2 格。
        // 这里断言的是「总量一字不差」，不是槽位个数——槽位个数由 ChestStorage 的分配策略决定，不属于本票的契约。
        helper.assertTrue(storage.amountOf(WHEAT) == 500L,
                "expected 500 wheat to survive, got " + storage.amountOf(WHEAT));
        helper.assertTrue(WHEAT.equals(storage.itemInSlot(0)),
                "expected slot 0 to keep wheat, got " + storage.itemInSlot(0));
        helper.succeed();
    }

    /** 陷阱箱与普通箱子等价，混用也照算：1 + 1 = 2。 */
    @GameTest(template = TEMPLATE)
    public void trappedChestIsAccepted(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_DIRT.get(0).get();
        ItemStack[] grid = emptyGrid();
        grid[2] = new ItemStack(source);
        grid[3] = new ItemStack(Items.TRAPPED_CHEST);
        grid[5] = new ItemStack(Items.CHEST);

        ItemStack result = craft(helper, grid);
        helper.assertTrue(result.is(source.asItem()), "expected compressed dirt back, got " + result);
        helper.assertTrue(resultChestCount(result) == 2L, "expected chestCount 2, got " + resultChestCount(result));
        helper.succeed();
    }

    /** 单次自然上限：8 格 × 64 = 512 档，不需要任何特判。 */
    @GameTest(template = TEMPLATE)
    public void fullGridOfChestsAdds512(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_DIRT.get(8).get();
        ItemStack[] grid = emptyGrid();
        for (int slot = 0; slot < grid.length; slot++) {
            grid[slot] = count(Items.CHEST, 64);
        }
        grid[0] = new ItemStack(source);

        ItemStack result = craft(helper, grid);
        helper.assertTrue(resultChestCount(result) == 512L, "expected chestCount 512, got " + resultChestCount(result));
        helper.succeed();
    }

    /** 没有 {@code BLOCK_ENTITY_DATA} 的方块按空货仓处理：产出带 {@code chestCount = 箱子数} 的空货仓。 */
    @GameTest(template = TEMPLATE)
    public void expansionOnCleanBlockStartsAtChestCount(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_DIRT.get(0).get();
        ItemStack[] grid = emptyGrid();
        grid[0] = new ItemStack(source);
        grid[8] = count(Items.CHEST, 3);

        ItemStack result = craft(helper, grid);
        helper.assertTrue(result.is(source.asItem()), "expected compressed dirt back, got " + result);

        CustomData data = result.get(DataComponents.BLOCK_ENTITY_DATA);
        helper.assertTrue(data != null, "expected BLOCK_ENTITY_DATA on the result");
        ChestStorage storage = new ChestStorage();
        storage.load(data.copyTag());
        helper.assertTrue(storage.chestCount() == 3L, "expected chestCount 3, got " + storage.chestCount());
        helper.assertTrue(storage.usedSlots() == 0, "expected an empty chest, got " + storage.usedSlots() + " slots");
        helper.succeed();
    }

    /** 已经到 {@link ChestCapacity#MAX_CHEST_COUNT} 时再加 1 个箱子就不匹配——不消耗材料、不产出。 */
    @GameTest(template = TEMPLATE)
    public void expansionStopsAtMaxChestCount(GameTestHelper helper) {
        ItemStack atMax = compressed(ModBlocks.COMPRESSED_DIRT.get(0).get(), ChestCapacity.MAX_CHEST_COUNT, null, 0L);
        ItemStack[] grid = emptyGrid();
        grid[0] = atMax;
        grid[4] = new ItemStack(Items.CHEST);
        helper.assertTrue(noRecipe(helper, grid),
                "expected chestCount " + ChestCapacity.MAX_CHEST_COUNT + " to match nothing");
        helper.succeed();
    }

    /** 只剩 1 档空间时还能匹配，用满就不再匹配。 */
    @GameTest(template = TEMPLATE)
    public void expansionCanFillTheLastChestSlot(GameTestHelper helper) {
        ItemStack oneLeft = compressed(ModBlocks.COMPRESSED_DIRT.get(0).get(),
                ChestCapacity.MAX_CHEST_COUNT - 1L, null, 0L);
        ItemStack[] grid = emptyGrid();
        grid[0] = oneLeft;
        grid[4] = new ItemStack(Items.CHEST);
        ItemStack result = craft(helper, grid);
        helper.assertTrue(resultChestCount(result) == ChestCapacity.MAX_CHEST_COUNT,
                "expected chestCount " + ChestCapacity.MAX_CHEST_COUNT + ", got " + resultChestCount(result));

        grid[4] = count(Items.CHEST, 2);
        helper.assertTrue(noRecipe(helper, grid), "expected 2 chests to overflow the last chest slot");
        helper.succeed();
    }

    /** 混进任何非箱子物品都不匹配；少一个箱子（只有方块）也不匹配。 */
    @GameTest(template = TEMPLATE)
    public void wrongMaterialDoesNotMatch(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_DIRT.get(0).get();

        ItemStack[] onlyBlock = emptyGrid();
        onlyBlock[4] = new ItemStack(source);
        helper.assertTrue(noRecipe(helper, onlyBlock), "expected the block alone to match nothing");

        ItemStack[] withGold = emptyGrid();
        withGold[0] = new ItemStack(source);
        withGold[1] = new ItemStack(Items.CHEST);
        withGold[8] = new ItemStack(Items.GOLD_INGOT);
        helper.assertTrue(noRecipe(helper, withGold), "expected a gold ingot to match nothing");

        ItemStack[] withWheat = emptyGrid();
        withWheat[0] = new ItemStack(source);
        withWheat[1] = count(Items.CHEST, 2);
        withWheat[8] = new ItemStack(Items.WHEAT);
        helper.assertTrue(noRecipe(helper, withWheat), "expected wheat to match nothing");

        ItemStack[] twoBlocks = emptyGrid();
        twoBlocks[0] = new ItemStack(source);
        twoBlocks[1] = new ItemStack(ModBlocks.COMPRESSED_DIRT.get(1).get());
        twoBlocks[8] = new ItemStack(Items.CHEST);
        helper.assertTrue(noRecipe(helper, twoBlocks), "expected two compressed blocks to match nothing");
        helper.succeed();
    }

    /**
     * 本配方不往合成格里还任何东西：{@code remaining} 是「扣完之后还要再还给玩家什么」，不是「结算后的数量」。
     * 真正的净消耗由 {@link #craftingTableConsumesTheWholeChestStack} 走 {@code ResultSlot#onTake} 验。
     */
    @GameTest(template = TEMPLATE)
    public void getRemainingItemsHandsNothingBack(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_DIRT.get(0).get();
        ItemStack[] grid = emptyGrid();
        grid[0] = count(source.asItem(), 2);
        grid[4] = count(Items.CHEST, 64);
        grid[8] = new ItemStack(Items.TRAPPED_CHEST);

        CompressedExpansionRecipe recipe = recipeFor(helper, grid);
        NonNullList<ItemStack> remaining = recipe.getRemainingItems(inputOf(grid));
        helper.assertTrue(remaining.size() == grid.length, "expected one entry per slot, got " + remaining.size());
        for (int slot = 0; slot < remaining.size(); slot++) {
            helper.assertTrue(remaining.get(slot).isEmpty(),
                    "expected slot " + slot + " to hand nothing back, got " + remaining.get(slot));
        }
        helper.succeed();
    }

    /**
     * 真走 {@code ResultSlot#onTake} 的扣料：1 个方块 + 64 箱子 + 1 陷阱箱，合成一次后格子里的箱子一个不剩，档位 +65。
     *
     * <p>这里手工复刻 {@code AbstractContainerMenu#doClick} 的两步：{@code tryRemove} → {@code onTake}。
     * 不能只调 {@code onTake}：事件是在 {@code checkTakeAchievements} 里由 {@code removeCount > 0} 触发的，
     * 而 {@code removeCount} 只由 {@code remove} / {@code onQuickCraft} 累加。
     *
     * <p>{@code CraftingMenu} 用的是 {@code ContainerLevelAccess.NULL}，{@code slotsChanged} 不会重算结果槽，
     * 所以产物要手动摆上去。
     */
    @GameTest(template = TEMPLATE)
    public void craftingTableConsumesTheWholeChestStack(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        CraftingMenu menu = new CraftingMenu(0, player.getInventory());
        Block source = ModBlocks.COMPRESSED_DIRT.get(0).get();
        ItemStack[] grid = emptyGrid();
        grid[0] = count(source.asItem(), 2);
        grid[4] = count(Items.CHEST, 64);
        grid[8] = new ItemStack(Items.TRAPPED_CHEST);
        for (int slot = 0; slot < grid.length; slot++) {
            // CraftingMenu 的 0 号槽是结果槽，合成格从 1 开始
            menu.getSlot(slot + 1).set(grid[slot]);
        }

        ItemStack result = craft(helper, grid);
        helper.assertTrue(resultChestCount(result) == 65L, "expected chestCount 65, got " + resultChestCount(result));
        menu.getSlot(CraftingMenu.RESULT_SLOT).set(result);

        Slot resultSlot = menu.getSlot(CraftingMenu.RESULT_SLOT);
        Optional<ItemStack> taken = resultSlot.tryRemove(result.getCount(), Integer.MAX_VALUE, player);
        helper.assertTrue(taken.isPresent(), "expected the result slot to hand out the crafted stack");
        resultSlot.onTake(player, taken.get());

        helper.assertTrue(menu.getSlot(1).getItem().getCount() == 1,
                "expected 1 compressed block left in the grid (only one is consumed), got " + menu.getSlot(1).getItem());
        helper.assertTrue(menu.getSlot(5).getItem().isEmpty(),
                "expected all 64 chests to be consumed, got " + menu.getSlot(5).getItem());
        helper.assertTrue(menu.getSlot(9).getItem().isEmpty(),
                "expected the trapped chest to be consumed, got " + menu.getSlot(9).getItem());
        helper.succeed();
    }

    /**
     * 2×2 背包合成格同样会被补扣——「不用工作台也能复制」的那条通道。
     *
     * <p>这里没直接用 {@code InventoryMenu}：它的 {@code slotsChanged} 无条件调
     * {@code CraftingMenu.slotChangedCraftingGrid}，那里把 owner 强转 {@code ServerPlayer} 并调
     * {@code connection.send(...)}，GameTest 的 mock 玩家两样都没有（转不过去 / connection 为 null），
     * 格子一改就炸，根本走不到本模组的代码。所以直接搭 {@code InventoryMenu} 构造器里那套同样的零件：
     * {@code new TransientCraftingContainer(menu, 2, 2)} + {@code new ResultContainer()} + {@code new ResultSlot(...)}。
     */
    @GameTest(template = TEMPLATE)
    public void inventoryGridConsumesTheWholeChestStack(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        TransientCraftingContainer craftSlots = new TransientCraftingContainer(new NoopMenu(), 2, 2);
        ResultSlot resultSlot = new ResultSlot(player, craftSlots, new ResultContainer(), 0, 0, 0);

        Block source = ModBlocks.COMPRESSED_DIRT.get(0).get();
        craftSlots.setItem(0, count(source.asItem(), 2));
        craftSlots.setItem(3, count(Items.CHEST, 64));

        ItemStack result = craft(helper, craftSlots.asCraftInput());
        helper.assertTrue(resultChestCount(result) == 64L, "expected chestCount 64, got " + resultChestCount(result));
        resultSlot.set(result);

        Optional<ItemStack> taken = resultSlot.tryRemove(result.getCount(), Integer.MAX_VALUE, player);
        helper.assertTrue(taken.isPresent(), "expected the result slot to hand out the crafted stack");
        resultSlot.onTake(player, taken.get());

        helper.assertTrue(craftSlots.getItem(0).getCount() == 1,
                "expected 1 compressed block left in the 2x2 grid, got " + craftSlots.getItem(0));
        helper.assertTrue(craftSlots.getItem(3).isEmpty(),
                "expected all 64 chests to be consumed, got " + craftSlots.getItem(3));
        helper.succeed();
    }

    /**
     * 不匹配的矩阵一格都不动：handler 只在 {@code matchesExpansion} 为真时才改写格子。
     *
     * <p>事件在这里手工 new 出来直接交给 handler（GameTest 环境是 bootstrap 过的，构造得出来），
     * 不必真走 {@code ResultSlot}——handler 的输入就是「玩家 + 产物 + 合成矩阵」三样。
     *
     * <p>第二个矩阵是差点看错的边界：9 格箱子、没有压缩方块。它有「整格箱子」但没有扩容配方，handler 必须不管。
     */
    @GameTest(template = TEMPLATE)
    public void handlerLeavesOtherGridsUntouched(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);

        TransientCraftingContainer dirtGrid = new TransientCraftingContainer(new NoopMenu(), 3, 3);
        for (int slot = 0; slot < CompressedExpansionRecipe.MAX_GRID_SIZE; slot++) {
            dirtGrid.setItem(slot, count(Items.DIRT, 9));
        }
        ExpansionConsumptionHandler.onItemCrafted(
                new PlayerEvent.ItemCraftedEvent(player, new ItemStack(Items.DIRT), dirtGrid));
        for (int slot = 0; slot < CompressedExpansionRecipe.MAX_GRID_SIZE; slot++) {
            helper.assertTrue(dirtGrid.getItem(slot).getCount() == 9,
                    "expected slot " + slot + " to stay at 9 dirt, got " + dirtGrid.getItem(slot));
        }

        TransientCraftingContainer chestOnlyGrid = new TransientCraftingContainer(new NoopMenu(), 3, 3);
        for (int slot = 0; slot < CompressedExpansionRecipe.MAX_GRID_SIZE; slot++) {
            chestOnlyGrid.setItem(slot, count(Items.CHEST, 64));
        }
        ExpansionConsumptionHandler.onItemCrafted(
                new PlayerEvent.ItemCraftedEvent(player, new ItemStack(Items.CHEST), chestOnlyGrid));
        for (int slot = 0; slot < CompressedExpansionRecipe.MAX_GRID_SIZE; slot++) {
            helper.assertTrue(chestOnlyGrid.getItem(slot).getCount() == 64,
                    "expected slot " + slot + " to stay at 64 chests, got " + chestOnlyGrid.getItem(slot));
        }
        helper.succeed();
    }

    private static ItemStack craft(GameTestHelper helper, ItemStack[] grid) {
        return craft(helper, inputOf(grid));
    }

    private static ItemStack craft(GameTestHelper helper, CraftingInput input) {
        CompressedExpansionRecipe recipe = recipeFor(helper, input);
        return recipe.assemble(input, helper.getLevel().registryAccess());
    }

    private static CompressedExpansionRecipe recipeFor(GameTestHelper helper, ItemStack[] grid) {
        return recipeFor(helper, inputOf(grid));
    }

    private static CompressedExpansionRecipe recipeFor(GameTestHelper helper, CraftingInput input) {
        ServerLevel level = helper.getLevel();
        Optional<RecipeHolder<CraftingRecipe>> found = level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level);
        if (found.isEmpty()) {
            throw new IllegalStateException("no recipe matched " + input.items());
        }
        RecipeHolder<CraftingRecipe> holder = found.get();
        ResourceLocation expected = ResourceLocation.fromNamespaceAndPath(CompressedAgriculture.MODID,
                "expand_compressed_chest");
        if (!holder.id().equals(expected)) {
            throw new IllegalStateException("expected " + expected + ", got " + holder.id());
        }
        if (!(holder.value() instanceof CompressedExpansionRecipe recipe)) {
            throw new IllegalStateException("expected a CompressedExpansionRecipe, got " + holder.value());
        }
        return recipe;
    }

    private static boolean noRecipe(GameTestHelper helper, ItemStack[] grid) {
        return helper.getLevel().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, inputOf(grid), helper.getLevel()).isEmpty();
    }

    /**
     * 3×3 合成输入。显式用 {@code CraftingInput.of}（而不是让 {@code RecipeManager} 自己裁剪外接矩形）：
     * 真实的 {@code CraftingMenu} 也是这么建的，箱子堆叠时空格不会被压缩掉。
     *
     * <p>空格一律补 {@link ItemStack#EMPTY}：{@code List.of} 拒 null，漏一个就 {@code NullPointerException}。
     */
    private static CraftingInput inputOf(ItemStack[] grid) {
        ItemStack[] padded = emptyGrid();
        for (int slot = 0; slot < padded.length; slot++) {
            ItemStack stack = slot < grid.length ? grid[slot] : null;
            padded[slot] = stack == null ? ItemStack.EMPTY : stack;
        }
        return CraftingInput.of(3, 3, List.of(padded));
    }

    /**
     * 3×3 合成格，空格是 {@link ItemStack#EMPTY} 而不是 {@code null}。
     *
     * <p>不能写成 {@code new ItemStack[9]}：{@code null} 会在两个地方炸——{@link CraftingInput#of} 的
     * {@code List.of} 拒 null，{@code NonNullList#set}（{@code Slot#set} 的落点）也会用
     * {@code Validate.notNull} 抛「The validated object is null」。
     */
    private static ItemStack[] emptyGrid() {
        ItemStack[] grid = new ItemStack[CompressedExpansionRecipe.MAX_GRID_SIZE];
        Arrays.fill(grid, ItemStack.EMPTY);
        return grid;
    }

    private static ItemStack count(net.minecraft.world.level.ItemLike item, int count) {
        return new ItemStack(item, count);
    }

    private static long resultChestCount(ItemStack result) {
        CustomData data = result.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            return 0L;
        }
        ChestStorage storage = new ChestStorage();
        storage.load(data.copyTag());
        return storage.chestCount();
    }

    /** 带 {@code chestCount}（以及一格内容）的压缩方块物品，编码路径与掉落物（03b）一致。 */
    private static ItemStack compressed(Block block, long chestCount, ResourceLocation itemId, long count) {
        ChestStorage storage = new ChestStorage();
        storage.setChestCount(chestCount);
        if (itemId != null && count > 0L) {
            storage.tryReserve(itemId, count);
            storage.commit();
        }
        CompoundTag tag = new CompoundTag();
        storage.save(tag);

        ItemStack stack = new ItemStack(block);
        BlockItem.setBlockEntityData(stack, ModBlockEntities.COMPRESSED.get(), tag);
        return stack;
    }

    /**
     * {@code TransientCraftingContainer} 的构造器要一个 menu 才能通知「矩阵变了」。GameTest 里只需要一个什么都不做的宿主：
     * 没有 {@code ContainerSynchronizer}，也不需要重算结果槽。
     */
    private static final class NoopMenu extends AbstractContainerMenu {
        private NoopMenu() {
            super(null, 0);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void slotsChanged(Container container) {
        }
    }
}
