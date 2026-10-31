package com.kalloer.compressedagriculture.block;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;
import com.kalloer.compressedagriculture.storage.CompressedChestTransfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 形态互转 / 水合 / 草蔓延的 GameTest（ticket 09）。
 *
 * <p>三条用例对应票里的三条验收：货仓一字不差地跟着换形态、有水源就 {@code MOISTURE = 7}、草蔓延把相邻压缩泥土变成
 * 同等级压缩草地且不丢那一格的货仓。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class FormConversionGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);

    /** 装了货仓的压缩泥土 → 压缩耕地：等级不变、{@code chestCount} 与 27 格内容一字不差。 */
    @GameTest(template = TEMPLATE)
    public void conversionKeepsChestAndLevel(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ModBlocks.COMPRESSED_DIRT.get(2).get());

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absolute);
        chest.storage().setChestCount(1);
        chest.storage().tryReserve(WHEAT, 200L);
        chest.storage().commit();
        CompoundTag expected = CompressedBlockEntity.saveData(chest, level.registryAccess());

        BlockState farmland = ModBlocks.block(CompressedForm.FARMLAND, 3).defaultBlockState();
        helper.assertTrue(CompressedChestTransfer.convert(level, absolute, farmland),
                "expected the conversion to be accepted by the level");
        helper.assertTrue(level.getBlockState(absolute).is(ModBlocks.COMPRESSED_FARMLAND.get(2).get()),
                "expected compressed farmland of level 3, got " + level.getBlockState(absolute));

        CompressedBlockEntity converted = (CompressedBlockEntity) level.getBlockEntity(absolute);
        helper.assertTrue(converted != null, "expected a chest on the converted block");
        CompoundTag actual = CompressedBlockEntity.saveData(converted, level.registryAccess());
        helper.assertTrue(expected.equals(actual),
                "expected the chest data to survive the conversion, expected=" + expected + " actual=" + actual);
        helper.succeed();
    }

    /** 压缩耕地旁边有水 → 一次随机刻就把 {@code MOISTURE} 顶到 7。 */
    @GameTest(template = TEMPLATE)
    public void waterHydratesFarmland(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(2, 2, 2);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        helper.setBlock(farmlandPos.offset(3, 0, 0), Blocks.WATER);

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(farmlandPos);
        BlockState state = level.getBlockState(absolute);
        helper.assertTrue(state.getValue(CompressedFarmlandBlock.MOISTURE) == 0,
                "expected dry farmland before the random tick, got " + state);

        state.randomTick(level, absolute, level.getRandom());

        int moisture = level.getBlockState(absolute).getValue(CompressedFarmlandBlock.MOISTURE);
        helper.assertTrue(moisture == CompressedFarmlandBlock.MAX_MOISTURE,
                "expected MOISTURE 7 after a random tick next to water, got " + moisture);
        helper.succeed();
    }

    /**
     * 压缩草地 + 相邻压缩泥土（带内容）→ 多次随机刻后相邻格变同等级压缩草地，货仓内容还在；
     * 旁边那个<b>不同等级</b>的压缩泥土保持原样（蔓延不跨等级）。
     */
    @GameTest(template = TEMPLATE)
    public void grassSpreadsOntoNeighbourKeepingChest(GameTestHelper helper) {
        BlockPos grassPos = new BlockPos(4, 1, 4);
        BlockPos dirtPos = grassPos.offset(-1, 0, 0);
        BlockPos otherLevelPos = grassPos.offset(0, 0, -1);
        helper.setBlock(grassPos, ModBlocks.COMPRESSED_GRASS.get(0).get());
        helper.setBlock(dirtPos, ModBlocks.COMPRESSED_DIRT.get(0).get());
        helper.setBlock(otherLevelPos, ModBlocks.COMPRESSED_DIRT.get(1).get());
        // 亮度保险：GameTest 世界若被判定为不够亮，蔓延直接不发生
        helper.setBlock(grassPos.above(3), Blocks.GLOWSTONE);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteGrass = helper.absolutePos(grassPos);
        BlockPos absoluteDirt = helper.absolutePos(dirtPos);
        BlockPos absoluteOtherLevel = helper.absolutePos(otherLevelPos);

        CompressedBlockEntity dirtChest = (CompressedBlockEntity) level.getBlockEntity(absoluteDirt);
        dirtChest.storage().setChestCount(2);
        dirtChest.storage().tryReserve(WHEAT, 300L);
        dirtChest.storage().commit();
        CompoundTag expected = CompressedBlockEntity.saveData(dirtChest, level.registryAccess());

        BlockState grassState = level.getBlockState(absoluteGrass);
        int ticks = 0;
        while (level.getBlockState(absoluteDirt).is(ModBlocks.COMPRESSED_DIRT.get(0).get()) && ticks < 2000) {
            grassState.randomTick(level, absoluteGrass, level.getRandom());
            ticks++;
        }

        helper.assertTrue(level.getBlockState(absoluteDirt).is(ModBlocks.COMPRESSED_GRASS.get(0).get()),
                "expected compressed grass of level 1 next to the source after " + ticks + " random ticks, got "
                        + level.getBlockState(absoluteDirt));

        CompressedBlockEntity spreadChest = (CompressedBlockEntity) level.getBlockEntity(absoluteDirt);
        helper.assertTrue(spreadChest != null, "expected the covered block to keep its chest");
        CompoundTag actual = CompressedBlockEntity.saveData(spreadChest, level.registryAccess());
        helper.assertTrue(expected.equals(actual),
                "expected the covered block to keep its chest data, expected=" + expected + " actual=" + actual);

        helper.assertTrue(level.getBlockState(absoluteOtherLevel).is(ModBlocks.COMPRESSED_DIRT.get(1).get()),
                "expected the level-2 compressed dirt to stay dirt, got " + level.getBlockState(absoluteOtherLevel));
        helper.succeed();
    }
}
