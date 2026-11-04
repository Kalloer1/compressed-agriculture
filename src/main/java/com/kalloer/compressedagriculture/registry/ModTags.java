package com.kalloer.compressedagriculture.registry;

import com.kalloer.compressedagriculture.CompressedAgriculture;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * 本模组的标签常量。数据文件在 {@code data/compressedagriculture/tags/block/} 下。
 *
 * <p>标签只表达「我认为哪些方块算作物 / 树苗」，行为类判断（排除茎、排除树苗、成熟判定）在代码层做（ADR-0009）。
 */
public final class ModTags {
    private ModTags() {}

    /** 方块标签。 */
    public static final class Blocks {
        /**
         * 普通作物模块认的作物集合，数据层默认是 {@code #minecraft:crops} + {@code minecraft:nether_wart}。
         *
         * <p>下界疣必须显式列出：它 {@code extends BushBlock} 而**不是** {@code CropBlock} 子类，原版
         * {@code #minecraft:crops} 里也没有它，所以 {@code CropModule} 的 {@code instanceof CropBlock} 回退路径也接不住它。
         */
        public static final TagKey<Block> COMPRESSIBLE_CROPS = create("compressible_crops");

        /**
         * 非 {@code StemBlock} 的模组西瓜类茎，默认空。
         *
         * <p>只用于手动兜底：这类茎读不到 {@code fruit} 字段，无法判断它指的是哪个果实，所以按标签认邻接关系。
         */
        public static final TagKey<Block> MELON_LIKE_STEMS = create("melon_like_stems");

        /** 非 {@code StemBlock} 的模组南瓜类茎，默认空。用途同 {@link #MELON_LIKE_STEMS}。 */
        public static final TagKey<Block> PUMPKIN_LIKE_STEMS = create("pumpkin_like_stems");

        /**
         * 树干判据的补充标签，默认空。
         *
         * <p>1.21.1 的原版 {@code BlockTags} 没有 {@code SLIMY_LOGS}（那是 Create 的自有标签），所以本模组自建一个，
         * 给「算树干但不在 {@code #minecraft:logs} 里」的方块留出口。
         */
        public static final TagKey<Block> SLIMY_LOGS = create("slimy_logs");

        /** 树根判据，默认 {@code minecraft:mangrove_roots}。命中的方块会让枚举向 DOWN 扩散（ADR-0011）。 */
        public static final TagKey<Block> TREE_ROOTS = create("tree_roots");

        /**
         * 蘑菇模块认的蘑菇集合，默认 {@code minecraft:red_mushroom} + {@code minecraft:brown_mushroom}（ADR-0013）。
         *
         * <p>整合包可以用数据包覆盖它来加入自定义蘑菇；代码里不出现任何具体蘑菇 ID。
         */
        public static final TagKey<Block> MUSHROOMS = create("mushrooms");

        /**
         * 挂在树上的方块，默认空。命中的位置进 {@code attachments}，且不再从它继续扩散。
         *
         * <p>1.21.1 的 NeoForge 没有 {@code AllBlockTags.TREE_ATTACHMENTS}（那是 Create 的自有标签），所以本模组自建。
         */
        public static final TagKey<Block> TREE_ATTACHMENTS = create("tree_attachments");

        /**
         * 禁种表，默认空。命中的植物方块在压缩耕地上退回原版判断（ADR-0020）。
         *
         * <p>压缩耕地是通用地基：{@code canSustainPlant} 对一切植物返回 {@code TRUE}。某个模组植物因此在压缩耕地上
         * 行为崩坏时，把它写进这个标签即可退回原版约束，不需要改代码、不需要重编译。
         */
        public static final TagKey<Block> NOT_PLANTABLE_ON_COMPRESSED_FARMLAND =
                create("not_plantable_on_compressed_farmland");

        /**
         * 多格植物，默认 {@code minecraft:bamboo} + {@code minecraft:cactus} + {@code minecraft:sugar_cane}（ADR-0021）。
         *
         * <p>这些植物靠自身随机刻在头顶逐格累加<b>同种</b>方块；压缩耕地上只保留 1 格，长到第 2 格就被截顶。
         * 紫颂不在这里（它走 ticket 14 的特例规则），树苗 / 蘑菇也不在——它们长出的是不同方块，由树木 / 蘑菇模块接管。
         */
        public static final TagKey<Block> MULTI_BLOCK_PLANTS = create("multi_block_plants");

        private Blocks() {}

        private static TagKey<Block> create(String name) {
            return TagKey.create(Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(CompressedAgriculture.MODID, name));
        }
    }
}
