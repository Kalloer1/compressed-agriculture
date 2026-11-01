package com.kalloer.compressedagriculture.registry;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.recipe.CompressedExpansionRecipe;
import com.kalloer.compressedagriculture.recipe.CompressedUpgradeRecipe;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 自定义配方序列化器：9 合 1 升级（{@code compressedagriculture:compressed_upgrade}）与扩容
 * （{@code compressedagriculture:compressed_expansion}）。
 *
 * <p>普通 JSON 配方（9 泥土 → 1 级压缩泥土）用原版 {@code minecraft:crafting_shaped}，不在这里注册。
 */
public final class ModRecipes {
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, CompressedAgriculture.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<CompressedUpgradeRecipe>> COMPRESSED_UPGRADE =
            RECIPE_SERIALIZERS.register("compressed_upgrade", CompressedUpgradeRecipe.Serializer::new);

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<CompressedExpansionRecipe>> COMPRESSED_EXPANSION =
            RECIPE_SERIALIZERS.register("compressed_expansion", CompressedExpansionRecipe.Serializer::new);

    private ModRecipes() {}
}
