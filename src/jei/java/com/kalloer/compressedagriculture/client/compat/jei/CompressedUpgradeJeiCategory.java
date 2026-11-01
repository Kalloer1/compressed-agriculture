package com.kalloer.compressedagriculture.client.compat.jei;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.recipe.CompressedUpgradeRecipe;
import com.kalloer.compressedagriculture.registry.ModBlocks;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

/**
 * 「9 合 1 升级」的 JEI 分类：3×3 输入 → 箭头 → 产物，照搬原版工作台的摆位。
 *
 * <p>输入内容由产物反推，不读配方 JSON 以外的任何东西：产物是哪个形态、哪个等级的压缩方块，输入就是同形态、
 * 等级减一的 9 个方块（{@link CompressedUpgradeRecipe} 的匹配规则）。这样 27 个方块里只要有升级配方就自动显示，
 * 代码里不出现任何具体方块 ID。
 *
 * <p>只显示「干净」的方块物品：配方允许输入自带 {@code chestCount}（会累加进产物），但那是数据而不是配方形状，
 * 显示成 9 个空货仓方块更接近玩家在合成格里看到的样子。
 */
public class CompressedUpgradeJeiCategory implements IRecipeCategory<CraftingRecipe> {
    public static final RecipeType<CraftingRecipe> RECIPE_TYPE =
            RecipeType.create(CompressedAgriculture.MODID, "compressed_upgrade", CraftingRecipe.class);

    /** 3×3 输入格原点。 */
    private static final int GRID_X = 1;
    private static final int GRID_Y = 1;
    /** 原版工作台格间距 18px（16px 格 + 2px 缝）。 */
    private static final int SLOT_SPACING = 18;
    private static final int OUTPUT_X = 95;
    private static final int OUTPUT_Y = 19;
    private static final int ARROW_X = 59;
    private static final int ARROW_Y = 19;
    private static final int WIDTH = 116;
    private static final int HEIGHT = 54;

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawableStatic arrow;

    public CompressedUpgradeJeiCategory(IGuiHelper guiHelper) {
        this.background = guiHelper.createBlankDrawable(WIDTH, HEIGHT);
        this.icon = guiHelper.createDrawableItemStack(new ItemStack(ModBlocks.COMPRESSED_DIRT.get(0).get()));
        this.arrow = guiHelper.getRecipeArrow();
    }

    @Override
    public RecipeType<CraftingRecipe> getRecipeType() {
        return RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.compressedagriculture.compressed_upgrade");
    }

    @Override
    public IDrawable getBackground() {
        return this.background;
    }

    @Override
    public IDrawable getIcon() {
        return this.icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, CraftingRecipe recipe, IFocusGroup focuses) {
        ItemStack input = previousLevel(recipe);
        if (!input.isEmpty()) {
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    builder.addInputSlot(GRID_X + column * SLOT_SPACING, GRID_Y + row * SLOT_SPACING)
                            .setStandardSlotBackground()
                            .addItemStack(input);
                }
            }
        }

        ItemStack output = result(recipe);
        if (!output.isEmpty()) {
            builder.addOutputSlot(OUTPUT_X, OUTPUT_Y)
                    .setOutputSlotBackground()
                    .addItemStack(output);
        }
    }

    @Override
    public void draw(CraftingRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics,
            double mouseX, double mouseY) {
        this.arrow.draw(guiGraphics, ARROW_X, ARROW_Y);
    }

    /** 产物物品（配方 JSON 里的 {@code result}）。 */
    private static ItemStack result(CraftingRecipe recipe) {
        if (recipe instanceof CompressedUpgradeRecipe upgrade) {
            return new ItemStack(upgrade.result());
        }
        return ItemStack.EMPTY;
    }

    /** 产物方块的「同形态、等级减一」物品；产物不是压缩方块时返回空。 */
    private static ItemStack previousLevel(CraftingRecipe recipe) {
        if (recipe instanceof CompressedUpgradeRecipe upgrade
                && upgrade.result() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof CompressedBlock target) {
            return new ItemStack(ModBlocks.block(target.form(), target.level() - 1));
        }
        return ItemStack.EMPTY;
    }
}
