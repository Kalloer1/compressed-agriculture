package com.kalloer.compressedagriculture.block;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 通用地基的 GameTest（ticket 12、ADR-0020）。
 *
 * <p>四条用例覆盖 {@link CompressedFarmlandBlock#canSustainPlant} 的四类后果：原本种不上的植物现在能种上
 * （下界疣、蘑菇、甘蔗），以及本来就不受它影响的原版约束仍然生效（仙人掌的侧面检查）。
 *
 * <p>一律直接问 {@code BlockState.canSurvive(LevelReader, BlockPos)}，不靠「放下去再看会不会弹掉」：
 * {@code Level.setBlock} 不会校验被放的方块自己，弹掉是下一次邻居更新触发 {@code updateShape} 才发生的，
 * 直接问 {@code canSurvive} 更确定、也更贴验收标准。仙人掌那条例外——它验的就是「邻居更新后弹掉」，
 * 所以真的去放一个邻居方块。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class UniversalSubstrateGameTest {
    private static final String TEMPLATE = "empty";

    /**
     * 下界疣：原版 {@code NetherWartBlock.mayPlaceOn} 只认 {@code soul_sand}，现在压缩耕地也认，而且能长到 3 龄。
     *
     * <p><b>覆盖不到「长熟 → 结算」这条闭环</b>：{@code CropModule} 只结算
     * {@code #compressedagriculture:compressible_crops} 里的方块或 {@code CropBlock} 子类，下界疣两者都不是
     * （原版 {@code #minecraft:crops} 不含 {@code nether_wart}），所以 3 龄后停在原地、不进仓。
     * 本用例只验「能种、能长」，闭环留给「下界疣算不算可压缩作物」这个单独的决定。
     */
    @GameTest(template = TEMPLATE)
    public void netherWartSurvivesAndGrows(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(1, 1, 1);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos wartPos = farmlandPos.above();
        helper.setBlock(wartPos, Blocks.NETHER_WART.defaultBlockState());

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(wartPos);
        helper.assertTrue(level.getBlockState(absolute).canSurvive(level, absolute),
                "expected nether wart to survive on compressed farmland, vanilla only accepts soul sand");

        // NetherWartBlock.randomTick 每龄 1/10 概率：0 → 3 平均约 30 次，200 次长不满的概率低于 1e-9
        for (int attempt = 0; attempt < 200
                && level.getBlockState(absolute).getValue(NetherWartBlock.AGE) < NetherWartBlock.MAX_AGE; attempt++) {
            level.getBlockState(absolute).randomTick(level, absolute, level.getRandom());
        }

        BlockState grown = level.getBlockState(absolute);
        helper.assertTrue(grown.is(Blocks.NETHER_WART), "expected the nether wart to stay, got " + grown);
        helper.assertTrue(grown.getValue(NetherWartBlock.AGE) == NetherWartBlock.MAX_AGE,
                "expected the nether wart to reach AGE 3 on compressed farmland, got " + grown);
        helper.succeed();
    }

    /**
     * 蘑菇：白天光照下不弹掉。
     *
     * <p>原版 {@code MushroomBlock.canSurvive} 要求「光照 &lt; 13 且下方 {@code isSolidRender}」。压缩耕地的形状是
     * 15/16，{@code isSolidRender} 恒为 false——两道都会被 {@code canSustainPlant} 的 {@code TRUE} 一起绕过。
     * 用例先把时间设到正午，让「光照 &lt; 13」这道真的会拦人，否则测不出东西。
     */
    @GameTest(template = TEMPLATE)
    public void mushroomSurvivesInDaylight(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(2, 1, 2);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos mushroomPos = farmlandPos.above();
        helper.setBlock(mushroomPos, Blocks.RED_MUSHROOM.defaultBlockState());

        ServerLevel level = helper.getLevel();
        level.setDayTime(6000L);
        BlockPos absolute = helper.absolutePos(mushroomPos);

        int brightness = level.getRawBrightness(absolute, 0);
        helper.assertTrue(brightness >= 13,
                "expected a bright test site so vanilla would reject the mushroom, got light " + brightness);
        helper.assertTrue(level.getBlockState(absolute).canSurvive(level, absolute),
                "expected the mushroom to survive on compressed farmland in daylight");
        helper.succeed();
    }

    /** 甘蔗：没有临水也活。原版要求下方是 {@code #dirt}/{@code #sand} 且水平相邻有水。 */
    @GameTest(template = TEMPLATE)
    public void sugarCaneSurvivesWithoutWater(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(3, 1, 3);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos canePos = farmlandPos.above();
        helper.setBlock(canePos, Blocks.SUGAR_CANE.defaultBlockState());

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(canePos);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.assertBlockPresent(Blocks.AIR, farmlandPos.relative(direction));
        }
        helper.assertTrue(level.getBlockState(absolute).canSurvive(level, absolute),
                "expected sugar cane to survive on compressed farmland without water nearby");
        helper.succeed();
    }

    /**
     * 仙人掌：压缩耕地上能活，但侧面贴着实心方块时照样弹掉。
     *
     * <p>{@code CactusBlock.canSurvive} 的四邻检查在 {@code canSustainPlant} 之前，通用地基碰不到它。
     * 这条用例不直接问 {@code canSurvive} 的结果，而是真的放一个邻居方块、等它弹掉——验的是可观察行为。
     *
     * <p>需要等两 tick：{@code CactusBlock.updateShape} 不直接换方块，只 {@code scheduleTick(pos, this, 1)}，
     * 真正拆除发生在下一次 {@code CactusBlock.tick}。
     */
    @GameTest(template = TEMPLATE)
    public void cactusStillRejectsSolidNeighbour(GameTestHelper helper) {
        BlockPos farmlandPos = new BlockPos(4, 1, 4);
        helper.setBlock(farmlandPos, ModBlocks.COMPRESSED_FARMLAND.get(0).get());
        BlockPos cactusPos = farmlandPos.above();
        helper.setBlock(cactusPos, Blocks.CACTUS.defaultBlockState());

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(cactusPos);
        helper.assertTrue(level.getBlockState(absolute).is(Blocks.CACTUS),
                "expected the cactus to stay on compressed farmland with air on all four sides");

        helper.setBlock(cactusPos.east(), ModBlocks.COMPRESSED_DIRT.get(0).get());

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(absolute).isAir(),
                    "expected the cactus to pop off once a solid block touches its side, got "
                            + level.getBlockState(absolute));
            helper.succeed();
        });
    }
}
