package com.kalloer.compressedagriculture.client.compat.jei;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.recipe.CompressedExpansionRecipe;
import com.kalloer.compressedagriculture.recipe.CompressedUpgradeRecipe;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI 插件入口：把 9 合 1 升级配方与扩容配方显示在<b>原版合成台分类</b>下。
 *
 * <p>只有装了 JEI 才会加载本类：JEI 扫到 {@link JeiPlugin} 注解才实例化它。所以 JEI 缺席时模组照常启动——
 * 本包没有任何类被 {@code @Mod} 主类引用，不构成硬依赖（见 build.gradle 里 {@code libs/} 有 jar 才编译 {@code src/jei/java}）。
 *
 * <p>这里<b>不注册自建分类</b>，也不往 {@code registerRecipes} 里加东西：两个自定义配方是 {@code RecipeType.CRAFTING}
 * 下的 {@link net.minecraft.world.item.crafting.CustomRecipe}，JEI 的 {@code VanillaRecipes} 会自己从
 * {@code RecipeManager} 把它们捞进 {@code CraftingRecipeCategory}。我们只补「怎么画」——它们没有配料表，
 * 默认扩展拿 {@code getIngredients()} 什么也渲染不出来，所以用 {@link #registerVanillaCategoryExtensions} 挂上自己的扩展。
 *
 * <p>每个配方类只能注册一次：{@code CraftingExtensionHelper.addRecipeExtension} 对同一个类重复注册会抛
 * {@link IllegalArgumentException}。这里一个类挂一个扩展，不重复。
 *
 * <p>9 泥土 → 1 级压缩泥土是普通 {@code minecraft:crafting_shaped} JSON，JEI 的原版工作台分类已经会显示，这里不插手。
 */
@JeiPlugin
public class CompressedAgricultureJeiPlugin implements IModPlugin {
    private static final ResourceLocation PLUGIN_UID =
            ResourceLocation.fromNamespaceAndPath(CompressedAgriculture.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    /**
     * 两个自定义配方都是 {@code CustomRecipe}：{@code getIngredients()} 为空、{@code isSpecial()} 恒为 {@code true}。
     *
     * <p>JEI 侧的两道检查都不会因此跳过它们：{@code CraftingRecipeCategory.isHandled} 只问「有没有扩展」，
     * {@code CategoryRecipeValidator.hasValidInputsAndOutputs} 遇到 {@code isSpecial()} 直接返回 {@code true}。
     * 所以只要挂上扩展，槽位就由扩展自己画，不需要给配方补 {@code getIngredients()}。
     */
    @Override
    public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        registration.getCraftingCategory().addExtension(
                CompressedUpgradeRecipe.class, new CompressedUpgradeCraftingExtension());
        registration.getCraftingCategory().addExtension(
                CompressedExpansionRecipe.class, new CompressedExpansionCraftingExtension());
    }
}
