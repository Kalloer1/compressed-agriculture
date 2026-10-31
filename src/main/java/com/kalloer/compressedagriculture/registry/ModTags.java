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

        private Blocks() {}

        private static TagKey<Block> create(String name) {
            return TagKey.create(Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(CompressedAgriculture.MODID, name));
        }
    }
}
