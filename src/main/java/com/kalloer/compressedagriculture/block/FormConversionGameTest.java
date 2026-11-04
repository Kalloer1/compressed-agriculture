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
 * 形态互转 / 水合的 GameTest（ticket 09）。
 *
 * <p>两条用例对应票里的两条验收：货仓一字不差地跟着换形态、有水源就 {@code MOISTURE = 7}。
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
}
