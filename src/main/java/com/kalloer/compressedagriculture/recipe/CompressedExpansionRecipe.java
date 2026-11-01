package com.kalloer.compressedagriculture.recipe;

import java.util.OptionalLong;

import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.registry.ModBlockEntities;
import com.kalloer.compressedagriculture.registry.ModRecipes;
import com.kalloer.compressedagriculture.settle.ChestCapacity;
import com.kalloer.compressedagriculture.storage.ChestStorage;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 扩容配方：1 个压缩方块 + 8 个铁锭 → 同一个方块，{@code chestCount + 1}。
 *
 * <p>与 9 合 1 升级（{@link CompressedUpgradeRecipe}）正交：升级改等级、本配方改容量档位（ADR-0003）。倍率 {@code 9^L}
 * 与形态都不变，产物就是输入物品本身，所以代码里同样不出现任何具体方块 ID。
 *
 * <p>输入只用 {@code Ingredient} 表达不了「任意形态、任意等级的压缩方块」，所以判据写在 {@link #matches} 里：
 * 9 格里恰好 1 个是压缩方块的 {@link BlockItem}，其余 8 个是 {@link #MATERIAL}。格子顺序无关。
 *
 * <p>{@code chestCount} 已达 {@link ChestCapacity#MAX_CHEST_COUNT} 时 {@link #matches} 返回 {@code false}——到顶不消耗材料，
 * 与 9 合 1 的溢出处理一致。累加用 {@code Math.addExact}。
 */
public class CompressedExpansionRecipe extends CustomRecipe {
    /** 合成格数：3×3 全满。 */
    public static final int INPUT_COUNT = 9;
    /** 除压缩方块外的 8 格材料。 */
    public static final Item MATERIAL = Items.IRON_INGOT;
    /** 材料个数。 */
    public static final int MATERIAL_COUNT = INPUT_COUNT - 1;

    public CompressedExpansionRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return compressedSlot(input) >= 0;
    }

    /**
     * 产物 = 输入的那个方块物品 + {@code chestCount + 1}，27 格内容原样保留。
     *
     * <p>内容搬运走 {@link ChestStorage#load} / {@link ChestStorage#save}，与存档、掉落物（03b）、9 合 1 是同一套编码：
     * 读回输入物品的 {@code BLOCK_ENTITY_DATA} 整份 tag，只改 {@code chestCount}。输入没有组件时按空货仓处理
     * （{@code SCHEMA_VERSION} + {@code chestCount = 1} + 空槽）。
     *
     * <p>外面必须套 {@link BlockItem#setBlockEntityData}：{@code BLOCK_ENTITY_DATA} 的持久化 codec 是
     * {@code CustomData.CODEC_WITH_ID}，tag 里没有 {@code id} 的物品存盘时组件会被静默丢掉。
     */
    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        int slot = compressedSlot(input);
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        ItemStack source = input.getItem(slot);
        OptionalLong expanded = expandedChestCount(source);
        if (expanded.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ChestStorage storage = new ChestStorage();
        CustomData data = source.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data != null) {
            storage.load(data.copyTag());
        }
        storage.setChestCount(expanded.getAsLong());

        CompoundTag tag = new CompoundTag();
        storage.save(tag);

        ItemStack assembled = new ItemStack(source.getItem());
        BlockItem.setBlockEntityData(assembled, ModBlockEntities.COMPRESSED.get(), tag);
        return assembled;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= INPUT_COUNT;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.COMPRESSED_EXPANSION.get();
    }

    /**
     * 唯一的压缩方块所在格；不满足「恰好 1 个压缩方块 + 8 个材料 + 容量没到顶」时返回 {@code -1}。
     *
     * <p>{@code CraftingInput} 已把合成格裁剪到非空方块的外接矩形，9 个物品必然占满 3×3，所以「9 格全非空」等价于
     * {@code size() == 9}。
     */
    private static int compressedSlot(CraftingInput input) {
        if (input.size() != INPUT_COUNT) {
            return -1;
        }

        int found = -1;
        for (int slot = 0; slot < INPUT_COUNT; slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof CompressedBlock) {
                if (found >= 0 || expandedChestCount(stack).isEmpty()) {
                    return -1;
                }
                found = slot;
            } else if (!stack.is(MATERIAL)) {
                return -1;
            }
        }
        return found;
    }

    /** {@code chestCount + 1}；到顶、或输入带着被改坏的负值/溢出时返回空（不匹配）。 */
    private static OptionalLong expandedChestCount(ItemStack stack) {
        long current = chestCountOf(stack);
        if (current < 0L) {
            return OptionalLong.empty();
        }
        long expanded;
        try {
            expanded = Math.addExact(current, 1L);
        } catch (ArithmeticException overflow) {
            return OptionalLong.empty();
        }
        if (expanded > ChestCapacity.MAX_CHEST_COUNT) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(expanded);
    }

    /** 物品上 {@code BLOCK_ENTITY_DATA} 里的 {@code chestCount}，没有组件按 0 算。 */
    private static long chestCountOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            return 0L;
        }
        return data.copyTag().getLong(ChestStorage.TAG_CHEST_COUNT);
    }

    /**
     * JSON 侧只认 {@code category}（可选，默认 {@code misc}）：产物完全由输入决定，没有 {@code result} 字段。
     *
     * <p>不用 {@code SimpleCraftingRecipeSerializer} 是为了与 9 合 1 保持同一种写法（也为了以后加字段时不用换序列化器）。
     */
    public static final class Serializer implements RecipeSerializer<CompressedExpansionRecipe> {
        public static final MapCodec<CompressedExpansionRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC)
                        .forGetter(CompressedExpansionRecipe::category))
                .apply(instance, CompressedExpansionRecipe::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, CompressedExpansionRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        CraftingBookCategory.STREAM_CODEC, CompressedExpansionRecipe::category,
                        CompressedExpansionRecipe::new);

        @Override
        public MapCodec<CompressedExpansionRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CompressedExpansionRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
