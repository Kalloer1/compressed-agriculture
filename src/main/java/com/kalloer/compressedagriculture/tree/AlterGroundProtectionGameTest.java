package com.kalloer.compressedagriculture.tree;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 「换土」不认压缩方块的 GameTest（回归 {@code mixin/FeatureMixin}）。
 *
 * <p>为什么必须测：巨型云杉等挂着 {@code AlterGroundDecorator} 的树，会从树根附近往下找第一格
 * {@code Feature.isGrassOrDirt} 为真的方块，把它换成灰化土。压缩方块全在 {@code #minecraft:dirt} 里
 * （ADR-0010 故意的），所以原版判断对它们为真——一旦 {@code FeatureMixin} 失效，整格货仓会跟着方块一起被换成灰化土，
 * 玩家财产静默丢失。这里直接调原版静态方法，断言的就是 Mixin 改过之后的真实返回值。
 *
 * <p>第二组用例是防止「修过头」：原版的泥土 / 草方块 / 灰化土必须照旧返回 true，否则真的树就长不出该有的土。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class AlterGroundProtectionGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);

    /** 压缩泥土在原版判断眼里不是「草或泥土」→ 返回 false；同一方法对原版泥土仍返回 true（证明测试本身没测反）。 */
    @GameTest(template = TEMPLATE)
    public void compressedDirtIsNotGrassOrDirt(GameTestHelper helper) {
        BlockPos compressedPos = new BlockPos(1, 1, 0);
        BlockPos vanillaPos = new BlockPos(1, 1, 2);
        helper.setBlock(compressedPos, ModBlocks.COMPRESSED_DIRT.get(0).get());
        helper.setBlock(vanillaPos, Blocks.DIRT);

        ServerLevel level = helper.getLevel();
        BlockPos absoluteCompressed = helper.absolutePos(compressedPos);
        BlockPos absoluteVanilla = helper.absolutePos(vanillaPos);

        helper.assertTrue(Feature.isGrassOrDirt(level, absoluteVanilla),
                "对照组失败：原版泥土必须仍算「草或泥土」，否则说明测试本身测反了");
        helper.assertFalse(Feature.isGrassOrDirt(level, absoluteCompressed),
                "压缩泥土被当成「草或泥土」了：换土会把整格货仓换成灰化土（FeatureMixin 失效？）");
        helper.succeed();
    }

    /** 原版的泥土 / 草方块 / 灰化土逐个过一遍：全部仍返回 true，Mixin 没有误伤。 */
    @GameTest(template = TEMPLATE)
    public void vanillaDirtVariantsStayGrassOrDirt(GameTestHelper helper) {
        BlockPos dirtPos = new BlockPos(0, 1, 0);
        BlockPos grassPos = new BlockPos(2, 1, 0);
        BlockPos podzolPos = new BlockPos(4, 1, 0);
        helper.setBlock(dirtPos, Blocks.DIRT);
        helper.setBlock(grassPos, Blocks.GRASS_BLOCK);
        helper.setBlock(podzolPos, Blocks.PODZOL);

        ServerLevel level = helper.getLevel();
        assertStillTrue(helper, level, helper.absolutePos(dirtPos), "泥土");
        assertStillTrue(helper, level, helper.absolutePos(grassPos), "草方块");
        assertStillTrue(helper, level, helper.absolutePos(podzolPos), "灰化土");
        helper.succeed();
    }

    /** 全 9 个等级的压缩泥土都返回 false（Mixin 判的是方块类型，不该有等级漏网）。 */
    @GameTest(template = TEMPLATE)
    public void everyCompressedDirtLevelIsNotGrassOrDirt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int index = 0; index < ModBlocks.COMPRESSED_DIRT.size(); index++) {
            BlockPos pos = new BlockPos(index % 5, 1, index / 5);
            helper.setBlock(pos, ModBlocks.COMPRESSED_DIRT.get(index).get());
            BlockPos absolute = helper.absolutePos(pos);
            helper.assertFalse(Feature.isGrassOrDirt(level, absolute),
                    "第 " + (index + 1) + " 级压缩泥土在 " + absolute + " 被当成「草或泥土」了");
        }
        helper.succeed();
    }

    /**
     * 装了货仓的压缩泥土：判断为 false 之后，货仓内容仍在原格。
     *
     * <p>只验「判断不改货仓」这一半；「换土真的不会动它」要跑真实世界生成，headless 的 GameTest 做不到
     * （template 里没有树，也没法从 {@code empty} 模板生出一棵带 {@code AlterGroundDecorator} 的树）。
     */
    @GameTest(template = TEMPLATE)
    public void nonEmptyChestSurvivesTheCheck(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, ModBlocks.COMPRESSED_DIRT.get(1).get());

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absolute);
        helper.assertTrue(chest != null, "expected a chest in the compressed block at " + absolute);
        chest.storage().setChestCount(1);
        long stored = 200L;
        helper.assertTrue(chest.storage().tryReserve(WHEAT, stored), "expected the reserve to fit");
        chest.storage().commit();

        helper.assertFalse(Feature.isGrassOrDirt(level, absolute),
                "装了货仓的压缩泥土仍被当成「草或泥土」：换土会把这一整格货仓换掉");

        CompressedBlockEntity after = (CompressedBlockEntity) level.getBlockEntity(absolute);
        helper.assertTrue(after != null && after.storage().amountOf(WHEAT) == stored,
                "货仓内容在判断过程中被改动了");
        helper.succeed();
    }

    private static void assertStillTrue(GameTestHelper helper, ServerLevel level, BlockPos absolute, String what) {
        helper.assertTrue(Feature.isGrassOrDirt(level, absolute),
                what + "在 " + absolute + " 不再算「草或泥土」：FeatureMixin 误伤了原版方块");
    }
}
