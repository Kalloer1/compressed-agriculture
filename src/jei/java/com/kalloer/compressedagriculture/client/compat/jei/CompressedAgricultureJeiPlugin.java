package com.kalloer.compressedagriculture.client.compat.jei;

import java.util.ArrayList;
import java.util.List;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.recipe.CompressedExpansionRecipe;
import com.kalloer.compressedagriculture.recipe.CompressedUpgradeRecipe;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * JEI 插件入口（ticket 10）：把 9 合 1 升级配方与扩容配方显示成两个独立分类。
 *
 * <p>只有装了 JEI 才会加载本类：JEI 扫到 {@link JeiPlugin} 注解才实例化它。所以 JEI 缺席时模组照常启动——
 * 本包没有任何类被 {@code @Mod} 主类引用，不构成硬依赖（见 build.gradle 里 {@code libs/} 有 jar 才编译 {@code src/jei/java}）。
 *
 * <p>9 泥土 → 1 级压缩泥土是普通 {@code minecraft:crafting_shaped} JSON，JEI 的原版工作台分类已经会显示，这里不重复登记。
 *
 * <p>配方列表取自客户端当前 {@link Level} 的 {@code RecipeManager}：JEI 在配方同步完成之后才调 {@link #registerRecipes}，
 * 此时看到的与玩家手里能用的是同一批（含数据包改过的配方）。分类的 {@code T} 统一用 {@link CraftingRecipe}，
 * 不用 {@code RecipeHolder}，省掉泛型与 id 的麻烦。
 */
@JeiPlugin
public class CompressedAgricultureJeiPlugin implements IModPlugin {
    private static final ResourceLocation PLUGIN_UID =
            ResourceLocation.fromNamespaceAndPath(CompressedAgriculture.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(
                new CompressedUpgradeJeiCategory(guiHelper),
                new CompressedExpansionJeiCategory(guiHelper));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        List<CraftingRecipe> upgrades = new ArrayList<>();
        List<CraftingRecipe> expansions = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (recipe instanceof CompressedUpgradeRecipe) {
                upgrades.add(recipe);
            } else if (recipe instanceof CompressedExpansionRecipe) {
                expansions.add(recipe);
            }
        }

        registration.addRecipes(CompressedUpgradeJeiCategory.RECIPE_TYPE, upgrades);
        registration.addRecipes(CompressedExpansionJeiCategory.RECIPE_TYPE, expansions);
    }
}
