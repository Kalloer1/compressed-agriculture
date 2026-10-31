package com.kalloer.compressedagriculture.storage;

import java.util.List;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 物品形态的 GameTest（ticket 03b）：破坏 → 掉落物带全套货仓 → 放到别处 → 内容原样回来。
 *
 * <p>放置侧<b>故意</b>只调 {@link BlockItem#updateCustomBlockEntityTag}——那正是 {@code BlockItem.place} 里真正把
 * {@code BLOCK_ENTITY_DATA} 读进 BlockEntity 的那一步（{@code applyComponentsFromItemStack} 会把
 * {@code BLOCK_ENTITY_DATA} 从组件补丁里剔除，它不负责还原 NBT）。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class CompressedItemFormGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);
    private static final ResourceLocation CARROT = BuiltInRegistries.ITEM.getKey(Items.CARROT);

    /** 掉落的方块物品带完整货仓；把它放到新位置后内容与 {@code chestCount} 完全一致。 */
    @GameTest(template = TEMPLATE)
    public void dropCarriesChestAndPlacementRestoresIt(GameTestHelper helper) {
        Block block = ModBlocks.COMPRESSED_DIRT.get(2).get();
        BlockPos sourcePos = new BlockPos(1, 1, 1);
        helper.setBlock(sourcePos, block);

        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(sourcePos);
        CompressedBlockEntity sourceChest = (CompressedBlockEntity) level.getBlockEntity(source);

        // chestCount = 1 → 每格 64 × (1 + 1) = 128
        sourceChest.storage().setChestCount(1);
        sourceChest.storage().tryReserve(WHEAT, 200L);
        sourceChest.storage().commit();
        sourceChest.storage().tryReserve(CARROT, 7L);
        sourceChest.storage().commit();

        CompoundTag expected = CompressedBlockEntity.saveData(sourceChest, level.registryAccess());

        BlockState state = level.getBlockState(source);
        List<ItemStack> drops = state.getDrops(new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(source))
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, sourceChest));

        helper.assertTrue(drops.size() == 1, "expected exactly 1 drop, got " + drops.size());
        ItemStack dropped = drops.getFirst();
        helper.assertTrue(dropped.is(block.asItem()), "expected the block itself to drop, got " + dropped);
        helper.assertTrue(dropped.has(DataComponents.BLOCK_ENTITY_DATA),
                "expected BLOCK_ENTITY_DATA on the drop, got " + dropped.getComponents());

        // 放到新位置：原版 place 链路里负责还原 NBT 的那一步
        BlockPos destPos = new BlockPos(3, 1, 3);
        helper.setBlock(destPos, block);
        BlockPos dest = helper.absolutePos(destPos);
        helper.assertTrue(BlockItem.updateCustomBlockEntityTag(level, null, dest, dropped),
                "expected updateCustomBlockEntityTag to apply the data");

        CompressedBlockEntity restored = (CompressedBlockEntity) level.getBlockEntity(dest);
        helper.assertTrue(restored.storage().chestCount() == 1L,
                "expected chestCount 1, got " + restored.storage().chestCount());
        helper.assertTrue(restored.storage().amountOf(WHEAT) == 200L,
                "expected 200 wheat, got " + restored.storage().amountOf(WHEAT));
        helper.assertTrue(restored.storage().amountOf(CARROT) == 7L,
                "expected 7 carrots, got " + restored.storage().amountOf(CARROT));
        helper.assertTrue(restored.storage().usedSlots() == 3,
                "expected 3 used slots, got " + restored.storage().usedSlots());
        helper.assertTrue(expected.equals(CompressedBlockEntity.saveData(restored, level.registryAccess())),
                "expected the restored tag to equal " + expected + ", got "
                        + CompressedBlockEntity.saveData(restored, level.registryAccess()));
        helper.succeed();
    }

    /** 对照组：干净物品（无数据组件）放下 → 空货仓、{@code chestCount = 0}。 */
    @GameTest(template = TEMPLATE)
    public void cleanItemPlacesEmptyChest(GameTestHelper helper) {
        Block block = ModBlocks.COMPRESSED_DIRT.get(2).get();
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block);

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        ItemStack clean = new ItemStack(block.asItem());
        helper.assertTrue(!clean.has(DataComponents.BLOCK_ENTITY_DATA),
                "expected a freshly crafted item to carry no BLOCK_ENTITY_DATA");

        helper.assertTrue(!BlockItem.updateCustomBlockEntityTag(level, null, absolute, clean),
                "expected updateCustomBlockEntityTag to do nothing for a clean item");

        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absolute);
        helper.assertTrue(chest.storage().chestCount() == 0L,
                "expected chestCount 0, got " + chest.storage().chestCount());
        helper.assertTrue(chest.storage().usedSlots() == 0,
                "expected an empty chest, got " + chest.storage().usedSlots() + " used slots");
        helper.succeed();
    }

    /** 创造模式中栏（pick block）拿到的是干净物品，不复制货仓——原版行为，这里钉住它。 */
    @GameTest(template = TEMPLATE)
    public void pickBlockItemCarriesNoData(GameTestHelper helper) {
        Block block = ModBlocks.COMPRESSED_DIRT.get(2).get();
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block);

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absolute);
        chest.storage().tryReserve(WHEAT, 5L);
        chest.storage().commit();

        // 中栏走 NeoForge 的 getCloneItemStack(HitResult, LevelReader, BlockPos, Player)；
        // 默认实现不看目标与玩家，所以这两个参数传 null
        ItemStack picked = level.getBlockState(absolute).getCloneItemStack(null, level, absolute, null);
        helper.assertTrue(picked.is(block.asItem()), "expected the pick-block item to be the block item");
        helper.assertTrue(!picked.has(DataComponents.BLOCK_ENTITY_DATA),
                "expected the pick-block item to carry no BLOCK_ENTITY_DATA, got " + picked.getComponents());
        helper.succeed();
    }

    /** 兜底路径：{@code BLOCK_ENTITY} 参数缺席时靠 {@code ORIGIN} 反查坐标，仍然能搬。 */
    @GameTest(template = TEMPLATE)
    public void dropWithoutBlockEntityParamFallsBackToOrigin(GameTestHelper helper) {
        Block block = ModBlocks.COMPRESSED_DIRT.get(2).get();
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block);

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absolute);
        chest.storage().tryReserve(WHEAT, 42L);
        chest.storage().commit();

        List<ItemStack> drops = level.getBlockState(absolute).getDrops(new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(absolute))
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY));

        helper.assertTrue(drops.size() == 1, "expected exactly 1 drop, got " + drops.size());
        helper.assertTrue(drops.getFirst().has(DataComponents.BLOCK_ENTITY_DATA),
                "expected BLOCK_ENTITY_DATA via the ORIGIN fallback");
        helper.succeed();
    }

    /** 被爆炸挖掉的方块同样带数据（爆炸走 {@code BlockStateBase.onBlockExploded}，也带 BLOCK_ENTITY 参数）。 */
    @GameTest(template = TEMPLATE)
    public void explosionDropCarriesChest(GameTestHelper helper) {
        Block block = ModBlocks.COMPRESSED_DIRT.get(2).get();
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block);

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absolute);
        chest.storage().tryReserve(WHEAT, 9L);
        chest.storage().commit();

        List<ItemStack> drops = Block.getDrops(level.getBlockState(absolute), level, absolute, chest);
        helper.assertTrue(drops.size() == 1, "expected exactly 1 drop, got " + drops.size());
        helper.assertTrue(drops.getFirst().has(DataComponents.BLOCK_ENTITY_DATA),
                "expected BLOCK_ENTITY_DATA on the static getDrops path");
        helper.succeed();
    }

    /**
     * 掉落的物品存盘后数据还在：{@code BLOCK_ENTITY_DATA} 的持久化 codec 是 {@code CustomData.CODEC_WITH_ID}，
     * tag 里缺 {@code id} 时组件会被静默丢掉——这条测试钉住 {@code BlockItem.setBlockEntityData} 补上的那个 {@code id}。
     */
    @GameTest(template = TEMPLATE)
    public void droppedItemSurvivesSerialization(GameTestHelper helper) {
        Block block = ModBlocks.COMPRESSED_DIRT.get(2).get();
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block);

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absolute);
        chest.storage().tryReserve(WHEAT, 33L);
        chest.storage().commit();

        List<ItemStack> drops = level.getBlockState(absolute).getDrops(new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(absolute))
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, chest));
        helper.assertTrue(drops.size() == 1, "expected exactly 1 drop, got " + drops.size());

        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var encoded = ItemStack.CODEC.encodeStart(ops, drops.getFirst())
                .getOrThrow(error -> new IllegalStateException("failed to encode the drop: " + error));
        ItemStack decoded = ItemStack.CODEC.parse(ops, encoded)
                .getOrThrow(error -> new IllegalStateException("failed to decode the drop: " + error));
        helper.assertTrue(decoded.has(DataComponents.BLOCK_ENTITY_DATA),
                "expected BLOCK_ENTITY_DATA to survive item serialization, got " + decoded.getComponents());
        helper.succeed();
    }

    /** 空货仓也照样带 tag（schemaVersion + 27 个空格），放置后是空货仓——不是「没有组件」。 */
    @GameTest(template = TEMPLATE)
    public void emptyChestStillRoundTrips(GameTestHelper helper) {
        Block block = ModBlocks.COMPRESSED_DIRT.get(1).get();
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block);

        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absolute);

        List<ItemStack> drops = level.getBlockState(absolute).getDrops(new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(absolute))
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, chest));

        helper.assertTrue(drops.size() == 1, "expected exactly 1 drop, got " + drops.size());
        helper.assertTrue(drops.getFirst().has(DataComponents.BLOCK_ENTITY_DATA),
                "expected BLOCK_ENTITY_DATA even for an empty chest");
        helper.assertTrue(chest.storage().usedSlots() == 0, "expected the source chest to stay empty");
        helper.succeed();
    }
}
