package com.kalloer.compressedagriculture.recipe;

import java.util.Optional;

import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.registry.ModBlockEntities;
import com.kalloer.compressedagriculture.registry.ModRecipes;
import com.kalloer.compressedagriculture.settle.ChestCapacity;
import com.kalloer.compressedagriculture.storage.ChestStorage;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
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
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 扩容配方：任意 1 个压缩方块 + 若干箱子 → 同一个方块，{@code chestCount + 箱子总数}。
 *
 * <p>与 9 合 1 升级（{@link CompressedUpgradeRecipe}）正交：升级改等级、本配方改容量档位（ADR-0003）。倍率 {@code 9^L}
 * 与形态都不变，产物就是输入物品本身，所以代码里同样不出现任何具体方块 ID。
 *
 * <p>输入只用 {@code Ingredient} 表达不了「任意形态、任意等级的压缩方块」，所以判据写在 {@link #matches} 里：非空物品里
 * <b>恰好 1 个</b>是压缩方块的 {@link BlockItem}，其余全部是 {@link #MATERIALS} 里的箱子。格子顺序无关，箱子跨格子累加
 * （3 个格子 44 + 64 + 1 个箱子贡献 {@code chestCount + 109}）；出现任何一个非箱子的其他物品即不匹配。
 *
 * <p>箱子在真实合成台里会堆叠，3×3 根本凑不满 9 个非空槽位，所以<b>不能</b>要求「格子全满」——那会让配方永远无法触发。
 * 判据只要求「非空格不少于 2 个」（恰好 1 个方块 + 至少 1 个箱子），单次自然上限 8 格 × 64 = 512 档。
 *
 * <p>{@code chestCount} 已达 {@link ChestCapacity#MAX_CHEST_COUNT} 时 {@link #matches} 返回 {@code false}——到顶不消耗材料，
 * 与 9 合 1 的溢出处理一致。累加用 {@code Math.addExact} 防溢出。
 */
public class CompressedExpansionRecipe extends CustomRecipe {
    /** 合成格数：3×3 全满。 */
    public static final int MAX_GRID_SIZE = 9;
    /** 可计入扩容档位的箱子。两种箱子各贡献 1 档，混用也照算。 */
    public static final Ingredient MATERIALS = Ingredient.of(Items.CHEST, Items.TRAPPED_CHEST);
    /** 压缩块之外至少要有 1 个箱子，否则 1 个方块单独摆着也算匹配。 */
    public static final int MIN_CHEST_COUNT = 1;

    public CompressedExpansionRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return expansionOf(input).isPresent();
    }

    /**
     * 产物 = 输入的那个方块物品 + {@code chestCount + 箱子总数}，27 格内容原样保留。
     *
     * <p>内容搬运走 {@link ChestStorage#load} / {@link ChestStorage#save}，与存档、掉落物（03b）、9 合 1 是同一套编码：
     * 读回输入物品的 {@code BLOCK_ENTITY_DATA} 整份 tag，只改 {@code chestCount}。输入没有组件时按空货仓处理
     * （{@code SCHEMA_VERSION} + 新的 {@code chestCount} + 空槽）。
     *
     * <p>外面必须套 {@link BlockItem#setBlockEntityData}：{@code BLOCK_ENTITY_DATA} 的持久化 codec 是
     * {@code CustomData.CODEC_WITH_ID}，tag 里没有 {@code id} 的物品存盘时组件会被静默丢掉。
     */
    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        Expansion expansion = expansionOf(input).orElse(null);
        if (expansion == null) {
            return ItemStack.EMPTY;
        }
        ItemStack source = expansion.source();

        ChestStorage storage = new ChestStorage();
        CustomData data = source.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data != null) {
            storage.load(data.copyTag());
        }
        storage.setChestCount(expansion.chestCount());

        CompoundTag tag = new CompoundTag();
        storage.save(tag);

        ItemStack assembled = new ItemStack(source.getItem());
        BlockItem.setBlockEntityData(assembled, ModBlockEntities.COMPRESSED.get(), tag);
        return assembled;
    }

    /**
     * 本配方不往合成格里还任何东西。
     *
     * <p>{@code remaining} 的语义是「这一格扣完之后<b>还要再还给玩家</b>什么」，不是「这一格结算后的数量」：
     * {@code ResultSlot#onTake} 先每格扣 1 个，再把 remaining 合并回同一格（{@code grow}）。曾经这里对匹配格返回
     * {@code copyWithCount(1)}，等于「把刚扣掉的那 1 个原样退回」，净消耗恒为 0（BUG3，箱子与方块都能无限复制）。
     *
     * <p>箱子要按「整格」计入 {@code chestCount}（单次最多 +512），而 {@code onTake} 一次只能从每格拿走 1 个，
     * 所以箱子格的多扣部分不在配方里做，由 {@link ExpansionConsumptionHandler} 在合成事件里把该格改写成 1 个
     * （其余当场销毁），再由 {@code onTake} 的循环拿走最后 1 个。方块格只扣 1 个，同槽里剩下的同类方块按原版语义
     * 留在合成格里。
     */
    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        return NonNullList.withSize(input.size(), ItemStack.EMPTY);
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.COMPRESSED_EXPANSION.get();
    }

    /**
     * 匹配结果：唯一的压缩方块所在格 + 产物应有的 {@code chestCount}；不匹配时返回空。
     *
     * <p>判据逐条：
     * <ul>
     *   <li>非空物品里恰好 1 个是压缩方块，且其余全部是 {@link #MATERIALS} 里的箱子——出现别的物品即返回空。</li>
     *   <li>箱子的 {@code getCount()} 跨格子累加，{@code Math.addExact} 防溢出。</li>
     *   <li>{@code chestCount + 箱子总数} 超出 {@link ChestCapacity#MAX_CHEST_COUNT} 返回空（到顶不消耗材料）。</li>
     * </ul>
     */
    private static Optional<Expansion> expansionOf(CraftingInput input) {
        if (input.size() < 2) {
            return Optional.empty();
        }

        Expansion expansion = null;
        long chestCount = 0L;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (isCompressedBlock(stack)) {
                if (expansion != null) {
                    // 两个压缩方块：不知道拿哪个扩容、该扣哪个，一律不匹配
                    return Optional.empty();
                }
                expansion = new Expansion(stack, slot, currentChestCount(stack));
            } else if (isChest(stack)) {
                try {
                    chestCount = Math.addExact(chestCount, (long) stack.getCount());
                } catch (ArithmeticException overflow) {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        }

        if (expansion == null || chestCount < MIN_CHEST_COUNT) {
            return Optional.empty();
        }

        long total;
        try {
            total = Math.addExact(expansion.chestCount(), chestCount);
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
        // 负数只可能来自被改坏的存档；与超出上限一样按「不匹配」处理，不产出非法 chestCount
        if (total < 0L || total > ChestCapacity.MAX_CHEST_COUNT) {
            return Optional.empty();
        }
        return Optional.of(new Expansion(expansion.source(), expansion.slot(), total));
    }

    private static boolean isCompressedBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof CompressedBlock;
    }

    /** 包内可见：{@link ExpansionConsumptionHandler} 要用同一套判据挑出「整格扣掉」的箱子格。 */
    static boolean isChest(ItemStack stack) {
        return MATERIALS.test(stack);
    }

    /**
     * 这个矩阵是不是一条扩容配方。
     *
     * <p>给两个「原版路径不会帮我们扣料」的地方做兜底判定，避免改到别的配方：
     * {@link ExpansionConsumptionHandler}（玩家合成，按整格补扣）与 {@code CrafterBlockMixin}（自动合成器，拒绝合成）。
     */
    public static boolean matchesExpansion(CraftingInput input) {
        return expansionOf(input).isPresent();
    }

    /**
     * 是否有任何一个箱子格装了不止 1 个。
     *
     * <p>自动合成器每格只 {@code shrink(1)}，而本配方按 {@code getCount()} 整格计档——这种矩阵让它按整格记档却不整格扣料，
     * 就是 64 倍复制。{@code CrafterBlockMixin} 用这个判据拒合成；单格恰好 1 个箱子时照常合成（消耗量与记档一致，不复制）。
     */
    public static boolean hasOversizedChestStack(CraftingInput input) {
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (isChest(stack) && stack.getCount() > 1) {
                return true;
            }
        }
        return false;
    }

    /** 物品上 {@code BLOCK_ENTITY_DATA} 里的 {@code chestCount}，没有组件按 0 算。 */
    private static long currentChestCount(ItemStack stack) {
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            return 0L;
        }
        return data.copyTag().getLong(ChestStorage.TAG_CHEST_COUNT);
    }

    /** 匹配结果：被扩容的方块（所在格）与产物应有的 {@code chestCount}。 */
    private record Expansion(ItemStack source, int slot, long chestCount) {}

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
