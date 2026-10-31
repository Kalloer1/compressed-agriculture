package com.kalloer.compressedagriculture.recipe;

import java.util.List;
import java.util.Optional;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.registry.ModBlockEntities;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.storage.ChestStorage;

import net.minecraft.core.component.DataComponents;
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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 合成配方的 GameTest（ticket 03c）。
 *
 * <p>全部断言都走 {@code RecipeManager}：既验证 25 个 JSON 能被加载成配方，也验证真实的「找配方」链路
 * （{@code getRecipeFor}）对这套输入给出 / 不给配方。不直接 new 配方对象，避免测的是构造器而不是数据。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class CompressedUpgradeRecipeGameTest {
    private static final String TEMPLATE = "empty";

    /** 9 个 L1 压缩泥土（其中两个带 chestCount 5 / 7）→ L2，chestCount = 12，内容物全空。 */
    @GameTest(template = TEMPLATE)
    public void nineInputsMergeChestCountAndClearContents(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_DIRT.get(0).get();
        ItemStack[] grid = filledGrid(source);
        grid[0] = compressed(source, 5L);
        grid[4] = compressed(source, 7L);

        CraftingInput input = inputOf(grid);
        ServerLevel level = helper.getLevel();
        CompressedUpgradeRecipe recipe = upgradeRecipe(level, input, "compressed_dirt_2");

        ItemStack result = recipe.assemble(input, level.registryAccess());
        Block expectedBlock = ModBlocks.COMPRESSED_DIRT.get(1).get();
        helper.assertTrue(result.is(expectedBlock.asItem()),
                "expected " + expectedBlock.asItem() + ", got " + result);
        helper.assertTrue(((CompressedBlock) expectedBlock).level() == 2, "expected the level-2 dirt block");

        CustomData data = result.get(DataComponents.BLOCK_ENTITY_DATA);
        helper.assertTrue(data != null, "expected BLOCK_ENTITY_DATA on the result, got " + result.getComponents());
        CompoundTag tag = data.copyTag();
        helper.assertTrue(tag.contains("id"),
                "expected the block entity type id in the tag, otherwise the component is dropped on save: " + tag);

        ChestStorage storage = new ChestStorage();
        storage.load(tag);
        helper.assertTrue(storage.chestCount() == 12L, "expected chestCount 12, got " + storage.chestCount());
        helper.assertTrue(storage.usedSlots() == 0, "expected empty contents, got " + storage.usedSlots() + " slots");
        helper.succeed();
    }

    /** 基础配方：9 泥土 → 干净 L1 压缩泥土（不带任何货仓数据组件）。 */
    @GameTest(template = TEMPLATE)
    public void nineDirtCraftCleanLevelOne(GameTestHelper helper) {
        ItemStack[] grid = new ItemStack[CompressedUpgradeRecipe.INPUT_COUNT];
        for (int slot = 0; slot < grid.length; slot++) {
            grid[slot] = new ItemStack(Items.DIRT);
        }
        CraftingInput input = inputOf(grid);

        ServerLevel level = helper.getLevel();
        Optional<RecipeHolder<CraftingRecipe>> found = level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(found.isPresent(), "expected a recipe for 9 dirt");
        helper.assertTrue(found.get().id().equals(id("compressed_dirt_1")),
                "expected the base recipe, got " + found.get().id());

        ItemStack result = found.get().value().assemble(input, level.registryAccess());
        helper.assertTrue(result.is(ModBlocks.COMPRESSED_DIRT.get(0).get().asItem()),
                "expected level 1 compressed dirt, got " + result);
        helper.assertTrue(!result.has(DataComponents.BLOCK_ENTITY_DATA),
                "expected no BLOCK_ENTITY_DATA on the base result, got " + result.getComponents());
        helper.succeed();
    }

    /** 少放一个（9 格中有一个空）不匹配。 */
    @GameTest(template = TEMPLATE)
    public void eightInputsDoNotMatch(GameTestHelper helper) {
        ItemStack[] grid = filledGrid(ModBlocks.COMPRESSED_DIRT.get(0).get());
        grid[4] = ItemStack.EMPTY;

        helper.assertTrue(noRecipe(helper, inputOf(grid)), "expected 8 inputs to match nothing");
        helper.succeed();
    }

    /** 混入一个其他等级 / 其他形态不匹配。 */
    @GameTest(template = TEMPLATE)
    public void mixedLevelOrFormDoesNotMatch(GameTestHelper helper) {
        Block level1 = ModBlocks.COMPRESSED_DIRT.get(0).get();

        ItemStack[] otherLevel = filledGrid(level1);
        otherLevel[7] = new ItemStack(ModBlocks.COMPRESSED_DIRT.get(1).get());
        helper.assertTrue(noRecipe(helper, inputOf(otherLevel)), "expected a level-2 input to match nothing");

        ItemStack[] otherForm = filledGrid(level1);
        otherForm[2] = new ItemStack(ModBlocks.COMPRESSED_GRASS.get(0).get());
        helper.assertTrue(noRecipe(helper, inputOf(otherForm)), "expected a grass input to match nothing");

        // 9 个 L1 泥土不能合成压缩草地（三种形态各自独立成链）
        ItemStack[] nineDirt = filledGrid(level1);
        CraftingInput input = inputOf(nineDirt);
        Optional<RecipeHolder<CraftingRecipe>> found = helper.getLevel().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel());
        helper.assertTrue(found.isPresent(), "expected a recipe for 9 level-1 dirt blocks");
        ItemStack result = found.get().value().assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(result.is(ModBlocks.COMPRESSED_DIRT.get(1).get().asItem()),
                "expected the dirt chain only, got " + result);
        helper.succeed();
    }

    /** 累加超过 MAX_CHEST_COUNT 不匹配（不消耗、不产出）。 */
    @GameTest(template = TEMPLATE)
    public void overflowingChestCountDoesNotMatch(GameTestHelper helper) {
        Block source = ModBlocks.COMPRESSED_DIRT.get(0).get();
        ItemStack[] grid = filledGrid(source);
        grid[0] = compressed(source, 600_000_000L);
        grid[1] = compressed(source, 600_000_000L);

        helper.assertTrue(noRecipe(helper, inputOf(grid)), "expected 1.2e9 chestCount to match nothing");
        helper.succeed();
    }

    /** 24 个升级配方全部从数据包加载成功，且都是本模组的序列化器。 */
    @GameTest(template = TEMPLATE)
    public void allUpgradeRecipesLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (String form : List.of("dirt", "grass", "farmland")) {
            for (int blockLevel = 2; blockLevel <= CompressedBlock.MAX_LEVEL; blockLevel++) {
                ResourceLocation recipeId = id("compressed_" + form + "_" + blockLevel);
                Optional<RecipeHolder<?>> holder = level.getRecipeManager().byKey(recipeId);
                helper.assertTrue(holder.isPresent(), "expected recipe " + recipeId + " to be loaded");
                helper.assertTrue(holder.get().value() instanceof CompressedUpgradeRecipe,
                        "expected " + recipeId + " to be a CompressedUpgradeRecipe, got " + holder.get().value());
            }
        }
        helper.succeed();
    }

    /** 找出唯一的升级配方，并断言它就是 {@code expectedRecipePath}。 */
    private static CompressedUpgradeRecipe upgradeRecipe(ServerLevel level, CraftingInput input, String expectedRecipePath) {
        Optional<RecipeHolder<CraftingRecipe>> found = level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level);
        if (found.isEmpty()) {
            throw new IllegalStateException("no recipe matched " + input.items());
        }
        RecipeHolder<CraftingRecipe> holder = found.get();
        if (!holder.id().equals(id(expectedRecipePath))) {
            throw new IllegalStateException("expected " + expectedRecipePath + ", got " + holder.id());
        }
        if (!(holder.value() instanceof CompressedUpgradeRecipe recipe)) {
            throw new IllegalStateException("expected a CompressedUpgradeRecipe, got " + holder.value());
        }
        return recipe;
    }

    private static boolean noRecipe(GameTestHelper helper, CraftingInput input) {
        return helper.getLevel().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel()).isEmpty();
    }

    private static CraftingInput inputOf(ItemStack[] grid) {
        return CraftingInput.of(3, 3, List.of(grid));
    }

    private static ItemStack[] filledGrid(Block block) {
        ItemStack[] grid = new ItemStack[CompressedUpgradeRecipe.INPUT_COUNT];
        for (int slot = 0; slot < grid.length; slot++) {
            grid[slot] = new ItemStack(block);
        }
        return grid;
    }

    /** 带 {@code chestCount} 的压缩方块物品，编码路径与掉落物（ticket 03b）一致。 */
    private static ItemStack compressed(Block block, long chestCount) {
        ChestStorage storage = new ChestStorage();
        storage.setChestCount(chestCount);
        CompoundTag tag = new CompoundTag();
        storage.save(tag);

        ItemStack stack = new ItemStack(block);
        BlockItem.setBlockEntityData(stack, ModBlockEntities.COMPRESSED.get(), tag);
        return stack;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CompressedAgriculture.MODID, path);
    }
}
