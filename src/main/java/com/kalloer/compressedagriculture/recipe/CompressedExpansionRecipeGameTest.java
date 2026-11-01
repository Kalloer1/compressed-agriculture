package com.kalloer.compressedagriculture.recipe;

import java.util.List;
import java.util.Optional;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlockEntities;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.settle.ChestCapacity;
import com.kalloer.compressedagriculture.storage.ChestStorage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 扩容配方的 GameTest（ticket 08b）。
 *
 * <p>断言都走 {@code RecipeManager} 的真实查找链路，测的是数据包 + 匹配规则，不是直接 new 出来的配方对象。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class CompressedExpansionRecipeGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);

    /** 带内容的压缩耕地 + 8 铁锭 → 同一个方块，{@code chestCount + 1}，等级、形态与 27 格内容不变。 */
    @GameTest(template = TEMPLATE)
    public void expansionAddsOneAndKeepsContents(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_FARMLAND.get(2).get();
        ItemStack[] grid = gridWith(compressed(source, 4L, WHEAT, 500L));
        CraftingInput input = inputOf(grid);

        ServerLevel level = helper.getLevel();
        CompressedExpansionRecipe recipe = expansionRecipe(level, input);
        ItemStack result = recipe.assemble(input, level.registryAccess());

        helper.assertTrue(result.is(source.asItem()), "expected the same block item back, got " + result);
        CustomData data = result.get(DataComponents.BLOCK_ENTITY_DATA);
        helper.assertTrue(data != null, "expected BLOCK_ENTITY_DATA on the result, got " + result.getComponents());
        helper.assertTrue(data.copyTag().contains("id"),
                "expected the block entity type id in the tag, otherwise the component is dropped on save");

        ChestStorage storage = new ChestStorage();
        storage.load(data.copyTag());
        helper.assertTrue(storage.chestCount() == 5L, "expected chestCount 5, got " + storage.chestCount());
        // 源货仓的 chestCount = 4，每格上限 = 64 × (1 + 4) = 320，所以 500 个小麦本来就占 2 格。
        // 这里断言的是「总量一字不差」，不是槽位个数——槽位个数由 ChestStorage 的分配策略决定，不属于本票的契约。
        helper.assertTrue(storage.amountOf(WHEAT) == 500L,
                "expected 500 wheat to survive, got " + storage.amountOf(WHEAT));
        helper.assertTrue(WHEAT.equals(storage.itemInSlot(0)),
                "expected slot 0 to keep wheat, got " + storage.itemInSlot(0));
        helper.succeed();
    }

    /** 没有 {@code BLOCK_ENTITY_DATA} 的方块按空货仓处理：产出带 {@code chestCount = 1} 的空货仓。 */
    @GameTest(template = TEMPLATE)
    public void expansionOnCleanBlockStartsAtOne(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_DIRT.get(0).get();
        CraftingInput input = inputOf(gridWith(new ItemStack(source)));

        ServerLevel level = helper.getLevel();
        ItemStack result = expansionRecipe(level, input).assemble(input, level.registryAccess());
        helper.assertTrue(result.is(source.asItem()), "expected compressed dirt back, got " + result);

        CustomData data = result.get(DataComponents.BLOCK_ENTITY_DATA);
        helper.assertTrue(data != null, "expected BLOCK_ENTITY_DATA on the result");
        ChestStorage storage = new ChestStorage();
        storage.load(data.copyTag());
        helper.assertTrue(storage.chestCount() == 1L, "expected chestCount 1, got " + storage.chestCount());
        helper.assertTrue(storage.usedSlots() == 0, "expected an empty chest, got " + storage.usedSlots() + " slots");
        helper.succeed();
    }

    /** 已到 {@link ChestCapacity#MAX_CHEST_COUNT} 时配方不匹配——不消耗材料、不产出。 */
    @GameTest(template = TEMPLATE)
    public void expansionStopsAtMaxChestCount(GameTestHelper helper) {
        ItemStack atMax = compressed(ModBlocks.COMPRESSED_DIRT.get(0).get(), ChestCapacity.MAX_CHEST_COUNT, null, 0L);
        helper.assertTrue(noRecipe(helper, inputOf(gridWith(atMax))),
                "expected chestCount " + ChestCapacity.MAX_CHEST_COUNT + " to match nothing");
        helper.succeed();
    }

    /** 8 个铁锭必须齐：少一个、或混进别的材料都不匹配。 */
    @GameTest(template = TEMPLATE)
    public void wrongMaterialDoesNotMatch(GameTestHelper helper) {
        ItemStack block = new ItemStack(ModBlocks.COMPRESSED_DIRT.get(0).get());

        ItemStack[] shortGrid = gridWith(block);
        shortGrid[8] = ItemStack.EMPTY;
        helper.assertTrue(noRecipe(helper, inputOf(shortGrid)), "expected 8 items to match nothing");

        ItemStack[] wrongMaterial = gridWith(block);
        wrongMaterial[8] = new ItemStack(Items.GOLD_INGOT);
        helper.assertTrue(noRecipe(helper, inputOf(wrongMaterial)), "expected a gold ingot to match nothing");

        ItemStack[] twoBlocks = gridWith(block);
        twoBlocks[0] = new ItemStack(ModBlocks.COMPRESSED_DIRT.get(1).get());
        helper.assertTrue(noRecipe(helper, inputOf(twoBlocks)), "expected two compressed blocks to match nothing");
        helper.succeed();
    }

    private static CompressedExpansionRecipe expansionRecipe(ServerLevel level, CraftingInput input) {
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

    private static boolean noRecipe(GameTestHelper helper, CraftingInput input) {
        return helper.getLevel().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel()).isEmpty();
    }

    /** 8 个铁锭 + 第 9 格放 {@code block}（格子顺序无关，放哪一格都行）。 */
    private static ItemStack[] gridWith(ItemStack block) {
        ItemStack[] grid = new ItemStack[CompressedExpansionRecipe.INPUT_COUNT];
        for (int slot = 0; slot < grid.length; slot++) {
            grid[slot] = new ItemStack(CompressedExpansionRecipe.MATERIAL);
        }
        grid[4] = block;
        return grid;
    }

    private static CraftingInput inputOf(ItemStack[] grid) {
        return CraftingInput.of(3, 3, List.of(grid));
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
}
