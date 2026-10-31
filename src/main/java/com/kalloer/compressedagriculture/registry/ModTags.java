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
        /** 普通作物模块认的作物集合，数据层默认引用 {@code #minecraft:crops}。 */
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

        private Blocks() {}

        private static TagKey<Block> create(String name) {
            return TagKey.create(Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(CompressedAgriculture.MODID, name));
        }
    }
}
