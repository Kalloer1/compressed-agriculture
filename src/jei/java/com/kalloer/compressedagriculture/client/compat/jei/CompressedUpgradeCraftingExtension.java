package com.kalloer.compressedagriculture.client.compat.jei;

import java.util.List;

import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.recipe.CompressedUpgradeRecipe;
import com.kalloer.compressedagriculture.registry.ModBlocks;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * 9 合 1 升级配方在原版合成台分类里的显示。
 *
 * <p>与 {@link CompressedExpansionCraftingExtension} 一样是分类扩展，不是自建分类：玩家看到的就是「工作台」分类，
 * 带工作台站点图标与可合成标记。
 *
 * <p>为什么要自己写槽位：{@link CompressedUpgradeRecipe} 是 {@code CustomRecipe}，没有配料表，
 * 原版分类默认的 {@code CraftingCategoryExtension} 拿 {@code getIngredients()} 什么也渲染不出来。
 *
 * <p>输入内容由产物反推：产物是哪个形态、哪个等级的压缩方块，输入就是同形态、等级减一的 9 个方块
 * （{@link CompressedUpgradeRecipe} 的匹配规则）。代码里不出现任何具体方块 ID。
 *
 * <p>只显示「干净」的方块物品：配方允许输入自带 {@code chestCount}（会累加进产物），但那是数据而不是配方形状，
 * 显示成 9 个空货仓方块更接近玩家在合成格里看到的样子。
 */
public class CompressedUpgradeCraftingExtension
        implements ICraftingCategoryExtension<CompressedUpgradeRecipe> {

    @Override
    public void setRecipe(RecipeHolder<CompressedUpgradeRecipe> holder, IRecipeLayoutBuilder builder,
            ICraftingGridHelper craftingGridHelper, IFocusGroup focuses) {
        CompressedUpgradeRecipe recipe = holder.value();

        // 产物槽与输入槽都交给 ICraftingGridHelper：位置与尺寸由原版分类决定，我们不假设背景大小
        craftingGridHelper.createAndSetOutputs(builder, List.of(new ItemStack(recipe.result())));

        ItemStack input = previousLevel(recipe);
        if (!input.isEmpty()) {
            craftingGridHelper.createAndSetInputs(builder, List.<List<ItemStack>>of(
                    List.of(input), List.of(input), List.of(input),
                    List.of(input), List.of(input), List.of(input),
                    List.of(input), List.of(input), List.of(input)), 3, 3);
        }
    }

    /** 产物方块的「同形态、等级减一」物品；产物不是压缩方块时返回空。 */
    private static ItemStack previousLevel(CompressedUpgradeRecipe recipe) {
        if (recipe.result() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof CompressedBlock target) {
            return new ItemStack(ModBlocks.block(target.form(), target.level() - 1));
        }
        return ItemStack.EMPTY;
    }
}
