package com.kalloer.compressedagriculture.client.compat.jei;

import java.util.ArrayList;
import java.util.List;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.recipe.CompressedExpansionRecipe;
import com.kalloer.compressedagriculture.registry.ModBlocks;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * 「扩容」的 JEI 分类：任意压缩方块 + 8 铁锭 → 同一个方块，容量档位 +1。
 *
 * <p>产物随输入变化（同形态、同等级，{@code chestCount + 1}），所以产物槽放的就是输入槽那 27 个方块本身，
 * 两个槽用 {@code createFocusLink} 绑在一起——聚焦哪个方块，另一边显示同一个。玩家看到的是
 * 「这个方块 + 8 铁锭 = 这个方块（容量 +1）」，下方一行小字点明 +1 的是容量。
 *
 * <p>输入槽列全部 27 个而不是只列一个：扩容配方对形态和等级都没有要求，只列一个会让玩家以为只对某个方块有效。
 * 数量直接取 {@link CompressedExpansionRecipe#MATERIAL_COUNT}，与配方判据同一处定义。
 */
public class CompressedExpansionJeiCategory implements IRecipeCategory<CraftingRecipe> {
    public static final RecipeType<CraftingRecipe> RECIPE_TYPE =
            RecipeType.create(CompressedAgriculture.MODID, "compressed_expansion", CraftingRecipe.class);

    private static final int BLOCK_SLOT_X = 1;
    private static final int SLOT_Y = 19;
    private static final int MATERIAL_SLOT_X = 19;
    private static final int ARROW_X = 41;
    private static final int ARROW_Y = 19;
    private static final int OUTPUT_X = 69;
    private static final int OUTPUT_Y = 19;
    private static final int LABEL_Y = 40;
    private static final int WIDTH = 96;
    private static final int HEIGHT = 54;
    private static final int LABEL_COLOR = 0xFF404040;
    private static final String CAPACITY_LABEL = "Capacity +1";

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawableStatic arrow;

    public CompressedExpansionJeiCategory(IGuiHelper guiHelper) {
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
        return Component.translatable("jei.compressedagriculture.compressed_expansion");
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
        List<ItemStack> compressed = compressedStacks();
        IRecipeSlotBuilder blockSlot = builder.addInputSlot(BLOCK_SLOT_X, SLOT_Y)
                .setStandardSlotBackground()
                .addItemStacks(compressed);
        builder.addInputSlot(MATERIAL_SLOT_X, SLOT_Y)
                .setStandardSlotBackground()
                .addItemStack(new ItemStack(CompressedExpansionRecipe.MATERIAL, CompressedExpansionRecipe.MATERIAL_COUNT));
        IRecipeSlotBuilder outputSlot = builder.addOutputSlot(OUTPUT_X, OUTPUT_Y)
                .setOutputSlotBackground()
                .addItemStacks(compressed);

        // 产物就是输入的那个方块：聚焦联动让两边始终显示同一个，而不是「任意方块 → 任意方块」
        builder.createFocusLink(blockSlot, outputSlot);
    }

    @Override
    public void draw(CraftingRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics,
            double mouseX, double mouseY) {
        this.arrow.draw(guiGraphics, ARROW_X, ARROW_Y);

        Font font = Minecraft.getInstance().font;
        guiGraphics.drawString(font, CAPACITY_LABEL, (WIDTH - font.width(CAPACITY_LABEL)) / 2, LABEL_Y,
                LABEL_COLOR, false);
    }

    /** 27 个压缩方块物品（3 形态 × 9 等级），顺序同创造模式标签页。 */
    private static List<ItemStack> compressedStacks() {
        List<ItemStack> stacks = new ArrayList<>(ModBlocks.ALL_COMPRESSED.size());
        for (DeferredBlock<? extends CompressedBlock> block : ModBlocks.ALL_COMPRESSED) {
            stacks.add(new ItemStack(block.get()));
        }
        return stacks;
    }
}
