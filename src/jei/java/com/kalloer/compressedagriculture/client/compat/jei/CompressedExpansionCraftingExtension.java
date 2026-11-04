package com.kalloer.compressedagriculture.client.compat.jei;

import java.util.ArrayList;
import java.util.List;

import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.recipe.CompressedExpansionRecipe;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.settle.ChestCapacity;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * 扩容配方在原版合成台分类里的显示（ticket 11）。
 *
 * <p>它是 {@link mezz.jei.api.recipe.category.extensions.vanilla.crafting.IExtendableCraftingRecipeCategory}
 * 的扩展点，不是自建分类：玩家看到的就是「工作台」分类，带工作台站点图标与可合成标记，与真实的合成行为一致。
 *
 * <p>为什么要自己写槽位：{@link CompressedExpansionRecipe} 是 {@code CustomRecipe}，没有配料表，
 * 原版分类默认的 {@code CraftingCategoryExtension} 拿 {@code getIngredients()} 什么也渲染不出来。这里的输入是「1 个任意压缩方块 + 任意数量的箱子」，
 * 产物「同方块（chestCount 加上箱子总数）」，都需要按 {@link ModBlocks#ALL_COMPRESSED} 现场拼。
 *
 * <p>输入槽列全部 27 个方块而不是只列一个：扩容对形态和等级都没有要求，只列一个会让玩家以为只对某个方块有效。
 * 输入槽与产物槽用 {@code createFocusLink} 绑在一起——聚焦哪个方块，另一边就是同一个方块，
 * 玩家看到的是「这个方块 + N 个箱子 = 这个方块」，而不是「任意方块 → 任意方块」。
 *
 * <p><b>箱子格子为什么是摆设</b>：真实配方只要求「至少 1 个箱子」并且箱子<b>数量不定</b>（3×3 里每格最多 64 个，
 * 单次自然上限 8 格 × 64 = 512）。JEI 的一个输入槽只能表示「这里可以放哪些物品」，不能表示「可放几个」，
 * 所以这里按 3×3 的格子容量把箱子铺满 8 个格子（各自 1 个箱子，两种箱子交替），用来表达「每个格子都能放」；
 * 具体数量和「至少 1 个」的规则写在箱子的 tooltip 里（{@link #CHEST_TOOLTIP_KEY}）。
 *
 * <p>容量变化写在产物的 tooltip 里（每格上限 {@code 64 × (1 + chestCount)}，见 {@link ChestCapacity}），
 * 不用画在空白背景上的小字：原版分类的背景是共享的，分类扩展画不下也不该画。
 */
public class CompressedExpansionCraftingExtension
        implements ICraftingCategoryExtension<CompressedExpansionRecipe> {
    /** 产物槽上方的提示键：两个 {@code %s} 分别是单次配方的下限与上限每格堆叠上限。 */
    private static final String CAPACITY_TOOLTIP_KEY = "jei.compressedagriculture.compressed_expansion.capacity";

    /** 箱子输入槽的提示键：数量不定，界面画不出来，用文字说明。 */
    private static final String CHEST_TOOLTIP_KEY = "jei.compressedagriculture.compressed_expansion.chests";

    /** 3×3 里除压缩方块之外的格子数：每个格子都画一个箱子。 */
    private static final int CHEST_SLOTS = CompressedExpansionRecipe.MAX_GRID_SIZE - 1;

    /**
     * 单次合成能加的箱子数上限：3×3 里除压缩方块之外的每格最多 64 个箱子。
     *
     * <p>只用于显示范围，不参与配方判定——箱子数量本来就是不定的。
     */
    private static final long MAX_CHEST_COUNT = (long) CHEST_SLOTS * CompressedExpansionRecipe.MIN_CHEST_COUNT * 64L;

    @Override
    public int getWidth(RecipeHolder<CompressedExpansionRecipe> holder) {
        return 0;
    }

    @Override
    public int getHeight(RecipeHolder<CompressedExpansionRecipe> holder) {
        return 0;
    }

    @Override
    public void setRecipe(RecipeHolder<CompressedExpansionRecipe> holder, IRecipeLayoutBuilder builder,
            ICraftingGridHelper craftingGridHelper, IFocusGroup focuses) {
        List<ItemStack> compressed = compressedStacks();
        List<ItemStack> chests = chestStacks();

        // 3×3 网格交给 ICraftingGridHelper：位置与尺寸由原版分类决定，我们不假设背景大小
        List<List<ItemStack>> grid = new ArrayList<>(CompressedExpansionRecipe.MAX_GRID_SIZE);
        grid.add(compressed);
        for (int slot = 0; slot < CHEST_SLOTS; slot++) {
            grid.add(chests);
        }
        List<IRecipeSlotBuilder> inputSlots =
                craftingGridHelper.createAndSetInputs(builder, grid, 3, 3);
        for (int slot = 1; slot < inputSlots.size(); slot++) {
            inputSlots.get(slot).addRichTooltipCallback((view, tooltip) -> tooltip.add(Component.translatable(
                    CHEST_TOOLTIP_KEY, CHEST_SLOTS)));
        }

        IRecipeSlotBuilder blockSlot = builder.addSlot(RecipeIngredientRole.OUTPUT, 0, 0);
        blockSlot.addItemStacks(compressed);
        // 箱子数量不定，所以每格上限是一个区间：至少加 1 个箱子，最多加满其余 8 格 × 64 个
        blockSlot.addRichTooltipCallback((view, tooltip) -> tooltip.add(Component.translatable(
                CAPACITY_TOOLTIP_KEY,
                ChestCapacity.stackLimit(1L),
                ChestCapacity.stackLimit(MAX_CHEST_COUNT))));

        // 产物就是输入的那个方块：聚焦联动让两边始终显示同一个
        builder.createFocusLink(inputSlots.get(0), blockSlot);
    }

    /**
     * {@link CompressedExpansionRecipe#MATERIALS} 里的箱子各一个：容量配方两种箱子等价，都列出来玩家才知道陷阱箱也算。
     */
    private static List<ItemStack> chestStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : CompressedExpansionRecipe.MATERIALS.getItems()) {
            stacks.add(stack.copyWithCount(CompressedExpansionRecipe.MIN_CHEST_COUNT));
        }
        return stacks;
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
