package com.kalloer.compressedagriculture.registry;

import java.util.ArrayList;
import java.util.List;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.block.CompressedDirtBlock;
import com.kalloer.compressedagriculture.block.CompressedFarmlandBlock;
import com.kalloer.compressedagriculture.block.CompressedForm;
import com.kalloer.compressedagriculture.block.CompressedGrassBlock;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 27 个压缩方块（3 形态 × 9 等级）与它们的方块物品。
 *
 * <p>方块 ID 形如 {@code compressed_dirt_<level>} / {@code compressed_grass_<level>} /
 * {@code compressed_farmland_<level>}，等级从 1 起。三个 {@code List} 都按等级排序，下标 {@code level - 1}。
 *
 * <p>方块属性照抄对应原版方块（{@code Blocks.DIRT} / {@code GRASS_BLOCK} / {@code FARMLAND}）：挖掘速度、工具需求、
 * 音效、地图颜色一致，活塞能推。原版这三个方块都<b>没有</b> {@code requiresCorrectToolForDrops()}（徒手也能挖出掉落物），
 * 所以这里也不加。
 *
 * <p><b>已知风险</b>：{@code PushReaction} 是默认的 {@code NORMAL}，所以活塞推得动；但原版
 * {@code PistonMovingBlockEntity} 只搬方块状态、不搬 BlockEntity 的 NBT，被推的压缩方块会丢掉货仓内容。
 * 活塞迁移不在本票范围内，留给后续票决定（要么搬数据，要么改成 {@code PushReaction.BLOCK}）。
 */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CompressedAgriculture.MODID);
    public static final DeferredRegister.Items BLOCK_ITEMS =
            DeferredRegister.createItems(CompressedAgriculture.MODID);

    /** 压缩泥土，下标 {@code level - 1}。 */
    public static final List<DeferredBlock<CompressedDirtBlock>> COMPRESSED_DIRT = registerDirt();
    /** 压缩草地，下标 {@code level - 1}。 */
    public static final List<DeferredBlock<CompressedGrassBlock>> COMPRESSED_GRASS = registerGrass();
    /** 压缩耕地，下标 {@code level - 1}。 */
    public static final List<DeferredBlock<CompressedFarmlandBlock>> COMPRESSED_FARMLAND = registerFarmland();
    /** 全部 27 个，注册 BlockEntityType 的 validBlocks 与创造模式标签页用。 */
    public static final List<DeferredBlock<? extends CompressedBlock>> ALL_COMPRESSED = allCompressed();

    private ModBlocks() {}

    /**
     * 指定形态、指定等级的方块实例。形态与等级都来自 {@link CompressedBlock}，27 个方块全覆盖。
     *
     * <p>形态互转（ticket 09）靠它从「源方块的形态 + 等级」推出目标方块，代码里不出现任何具体方块 ID。
     *
     * @throws IllegalArgumentException 等级不在 {@code [MIN_LEVEL, MAX_LEVEL]} 内
     */
    public static Block block(CompressedForm form, int level) {
        int index = level - CompressedBlock.MIN_LEVEL;
        if (index < 0 || index >= CompressedBlock.MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "level must be within [" + CompressedBlock.MIN_LEVEL + ", " + CompressedBlock.MAX_LEVEL
                            + "], but was " + level);
        }
        return switch (form) {
            case DIRT -> COMPRESSED_DIRT.get(index).get();
            case GRASS -> COMPRESSED_GRASS.get(index).get();
            case FARMLAND -> COMPRESSED_FARMLAND.get(index).get();
        };
    }

    /** 全部 27 个方块实例。只在方块注册完成后调用（BlockEntityType 注册时）。 */
    public static Block[] allBlocks() {
        Block[] blocks = new Block[ALL_COMPRESSED.size()];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = ALL_COMPRESSED.get(i).get();
        }
        return blocks;
    }

    private static List<DeferredBlock<? extends CompressedBlock>> allCompressed() {
        List<DeferredBlock<? extends CompressedBlock>> blocks =
                new ArrayList<>(3 * CompressedBlock.MAX_LEVEL);
        blocks.addAll(COMPRESSED_DIRT);
        blocks.addAll(COMPRESSED_GRASS);
        blocks.addAll(COMPRESSED_FARMLAND);
        return List.copyOf(blocks);
    }

    private static List<DeferredBlock<CompressedDirtBlock>> registerDirt() {
        List<DeferredBlock<CompressedDirtBlock>> blocks = new ArrayList<>(CompressedBlock.MAX_LEVEL);
        for (int level = CompressedBlock.MIN_LEVEL; level <= CompressedBlock.MAX_LEVEL; level++) {
            blocks.add(register("compressed_dirt_" + level, level, CompressedDirtBlock::new, dirtProperties()));
        }
        return List.copyOf(blocks);
    }

    private static List<DeferredBlock<CompressedGrassBlock>> registerGrass() {
        List<DeferredBlock<CompressedGrassBlock>> blocks = new ArrayList<>(CompressedBlock.MAX_LEVEL);
        for (int level = CompressedBlock.MIN_LEVEL; level <= CompressedBlock.MAX_LEVEL; level++) {
            blocks.add(register("compressed_grass_" + level, level, CompressedGrassBlock::new, grassProperties()));
        }
        return List.copyOf(blocks);
    }

    private static List<DeferredBlock<CompressedFarmlandBlock>> registerFarmland() {
        List<DeferredBlock<CompressedFarmlandBlock>> blocks = new ArrayList<>(CompressedBlock.MAX_LEVEL);
        for (int level = CompressedBlock.MIN_LEVEL; level <= CompressedBlock.MAX_LEVEL; level++) {
            blocks.add(register("compressed_farmland_" + level, level, CompressedFarmlandBlock::new,
                    farmlandProperties()));
        }
        return List.copyOf(blocks);
    }

    /** 注册一个压缩方块，并顺手注册同名方块物品（没有物品就拿不到、放不下）。 */
    private static <B extends CompressedBlock> DeferredBlock<B> register(String name, int level,
            CompressedBlockFactory<B> factory, BlockBehaviour.Properties properties) {
        DeferredBlock<B> block = BLOCKS.registerBlock(name, props -> factory.create(level, props), properties);
        BLOCK_ITEMS.registerSimpleBlockItem(name, block);
        return block;
    }

    /** 照抄 {@code Blocks.DIRT}。 */
    private static BlockBehaviour.Properties dirtProperties() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.DIRT).strength(0.5F).sound(SoundType.GRAVEL);
    }

    /** 照抄 {@code Blocks.GRASS_BLOCK}（含 {@code randomTicks}，ticket 09 的蔓延需要它）。 */
    private static BlockBehaviour.Properties grassProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.GRASS)
                .randomTicks()
                .strength(0.6F)
                .sound(SoundType.GRASS);
    }

    /** 照抄 {@code Blocks.FARMLAND}（含 {@code randomTicks}，水合留给 ticket 09）。 */
    private static BlockBehaviour.Properties farmlandProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.DIRT)
                .randomTicks()
                .strength(0.6F)
                .sound(SoundType.GRAVEL)
                .isViewBlocking((state, level, pos) -> true)
                .isSuffocating((state, level, pos) -> true);
    }

    /** 等级要作为构造参数传进方块，{@code Function<Properties, Block>} 装不下，所以单独给一个二参工厂。 */
    @FunctionalInterface
    private interface CompressedBlockFactory<B extends CompressedBlock> {
        B create(int level, BlockBehaviour.Properties properties);
    }
}
