package com.kalloer.compressedagriculture.recipe;

import java.util.OptionalLong;

import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.block.CompressedForm;
import com.kalloer.compressedagriculture.registry.ModBlockEntities;
import com.kalloer.compressedagriculture.registry.ModRecipes;
import com.kalloer.compressedagriculture.settle.ChestCapacity;
import com.kalloer.compressedagriculture.storage.ChestStorage;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 9 合 1 升级配方：9 个同形态、等级 {@code n-1} 的压缩方块 → 1 个等级 {@code n} 的同形态方块。
 *
 * <p>产物由配方自身决定（每种产物一个 JSON 的 {@code result} 字段），等级与形态从产物方块反推，所以代码里不出现任何具体方块 ID。
 *
 * <p>两条数据规则：
 * <ul>
 *   <li>{@code chestCount} 取 9 个输入之和（{@code Math.addExact}），与等级正交（ADR-0003）——升等级不自动扩容，但材料自带的
 *       扩容档位要跟着搬过去，否则玩家拿 9 个装了货仓的方块只能换回一个空货仓。</li>
 *   <li>内容物一律清空：格子里的东西不搬进产物，避免「合成即无损搬家」绕过货仓容量。</li>
 * </ul>
 *
 * <p>累加结果超出 {@link ChestCapacity#MAX_CHEST_COUNT} 时 {@link #matches} 返回 {@code false}——不消耗材料、不产出，
 * 与「放不下就整条失败」的 {@code settle} 语义一致。
 *
 * <p>继承 {@link CustomRecipe} 而不是 {@code ShapedRecipe}：输入是「9 个同等级同形态的方块」，不是固定物品序列，无法用
 * {@code Ingredient} 表达。代价是 {@code isSpecial()} 为 {@code true}，配方不进配方书（原版所有 special 配方同理）。
 */
public class CompressedUpgradeRecipe extends CustomRecipe {
    /** 合成格数：3×3 全满。 */
    public static final int INPUT_COUNT = 9;

    private final Item result;
    private final CompressedBlock target;

    public CompressedUpgradeRecipe(CraftingBookCategory category, Item result) {
        super(category);
        if (!(result instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof CompressedBlock block)) {
            throw new IllegalArgumentException("result must be a compressed block item, but was "
                    + BuiltInRegistries.ITEM.getKey(result));
        }
        if (block.level() <= CompressedBlock.MIN_LEVEL) {
            throw new IllegalArgumentException("result must be level >= " + (CompressedBlock.MIN_LEVEL + 1)
                    + ", but " + BuiltInRegistries.ITEM.getKey(result) + " is level " + block.level());
        }
        this.result = result;
        this.target = block;
    }

    /** 产物物品（干净形态，不带货仓数据）。 */
    public Item result() {
        return this.result;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return chestCountSum(input).isPresent();
    }

    /**
     * 产物的等级 = 输入等级 + 1、形态同输入、{@code chestCount} = 9 个输入之和、内容物全空。
     *
     * <p>累加值写进 {@code BLOCK_ENTITY_DATA} 时走 {@link ChestStorage#save}——与存档、掉落物（ticket 03b）同一套编码。
     * 外面必须套 {@link BlockItem#setBlockEntityData}：{@code BLOCK_ENTITY_DATA} 的持久化 codec 是
     * {@code CustomData.CODEC_WITH_ID}，tag 里没有 {@code id} 的物品存盘时组件会被静默丢掉。
     */
    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        OptionalLong sum = chestCountSum(input);
        if (sum.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ChestStorage storage = new ChestStorage();
        storage.setChestCount(sum.getAsLong());
        CompoundTag tag = new CompoundTag();
        storage.save(tag);

        ItemStack assembled = new ItemStack(this.result);
        BlockItem.setBlockEntityData(assembled, ModBlockEntities.COMPRESSED.get(), tag);
        return assembled;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= INPUT_COUNT;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.COMPRESSED_UPGRADE.get();
    }

    /**
     * 匹配成功时返回 9 个输入的 {@code chestCount} 之和，否则空。
     *
     * <p>格子顺序无关：只比较每个格子的等级与形态，不比较位置。{@code CraftingInput} 已把合成格裁剪到非空方块的外接矩形，
     * 所以「9 个格子全非空」等价于 {@code size() == 9}。
     */
    private OptionalLong chestCountSum(CraftingInput input) {
        if (input.size() != INPUT_COUNT) {
            return OptionalLong.empty();
        }

        CompressedForm form = this.target.form();
        int requiredLevel = this.target.level() - 1;
        long sum = 0L;
        for (int slot = 0; slot < INPUT_COUNT; slot++) {
            ItemStack stack = input.getItem(slot);
            if (!(stack.getItem() instanceof BlockItem blockItem)
                    || !(blockItem.getBlock() instanceof CompressedBlock block)) {
                return OptionalLong.empty();
            }
            if (block.form() != form || block.level() != requiredLevel) {
                return OptionalLong.empty();
            }
            try {
                sum = Math.addExact(sum, chestCountOf(stack));
            } catch (ArithmeticException overflow) {
                return OptionalLong.empty();
            }
        }

        // 负数只可能来自被改坏的存档；与超出上限一样按「不匹配」处理，不产出非法 chestCount
        if (sum < 0L || sum > ChestCapacity.MAX_CHEST_COUNT) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(sum);
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
     * JSON 侧只认 {@code category}（可选，默认 {@code misc}）与 {@code result}（产物物品 ID）。
     *
     * <p>不用 {@code SimpleCraftingRecipeSerializer}：它只编码 {@code category}，装不下产物。
     */
    public static final class Serializer implements RecipeSerializer<CompressedUpgradeRecipe> {
        public static final MapCodec<CompressedUpgradeRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC)
                        .forGetter(CompressedUpgradeRecipe::category),
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("result").forGetter(CompressedUpgradeRecipe::result))
                .apply(instance, CompressedUpgradeRecipe::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, CompressedUpgradeRecipe> STREAM_CODEC =
                StreamCodec.composite(
                        CraftingBookCategory.STREAM_CODEC, CompressedUpgradeRecipe::category,
                        ByteBufCodecs.registry(Registries.ITEM), CompressedUpgradeRecipe::result,
                        CompressedUpgradeRecipe::new);

        @Override
        public MapCodec<CompressedUpgradeRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CompressedUpgradeRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
