package com.kalloer.compressedagriculture.storage;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.settle.ChestCapacity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@link CompressedItemHandler} 的 GameTest（ticket 11 回归）。
 *
 * <p>为什么是 GameTest 而不是 JUnit：适配器每次调用都要靠 {@link BuiltInRegistries#ITEM} 把
 * {@link ResourceLocation} 翻成 {@link Item}、并构造 {@link ItemStack}，而 {@code ItemStack} 的构造会触发注册表初始化
 * （ADR-0017）；纯 JUnit 里 {@code Bootstrap.bootStrap()} 会因为 FML 的 {@code LoadingModList} 未初始化而抛 NPE。
 * {@link ChestStorage} 那一层仍然是纯 JUnit（见 {@code ChestStorageTest} / {@code ChestStorageNormalizedCountTest}）。
 */
@GameTestHolder(CompressedAgriculture.MODID)
@PrefixGameTestTemplate(false)
public class CompressedItemHandlerGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation WHEAT = BuiltInRegistries.ITEM.getKey(Items.WHEAT);

    /** 空货仓的每一格都是空栈，且 {@link CompressedItemHandler#getSlotLimit} 恒为 64。 */
    @GameTest(template = TEMPLATE)
    public void emptyChestReportsEmptyStacks(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(1, 1, 1));
        CompressedItemHandler handler = new CompressedItemHandler(chest);

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            helper.assertTrue(handler.getStackInSlot(slot).isEmpty(),
                    "slot " + slot + " of a fresh chest must be empty, got " + handler.getStackInSlot(slot));
            helper.assertTrue(handler.getSlotLimit(slot) == 64,
                    "slot " + slot + " limit must be 64, got " + handler.getSlotLimit(slot));
        }
        helper.succeed();
    }

    /**
     * 归零后 {@code slotItems == null} 但计数可能残留的格：必须报「空」，并且 {@code isItemValid} 必须报「能插」。
     *
     * <p>这正是 P1 的回归点：归一化之前 {@code isItemValid} 会拿残留计数当已存量，漏斗每 tick 白试一次。
     */
    @GameTest(template = TEMPLATE)
    public void slotWithStaleCountReadsAsEmpty(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(2, 1, 1));
        CompressedItemHandler handler = new CompressedItemHandler(chest);

        // 先装满一格 64，再整体取走：取空的那条路径会把 null / 0 一起归一
        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 64), false).isEmpty(),
                "expected the whole stack to fit into an empty slot");
        helper.assertTrue(handler.extractItem(0, 64, false).getCount() == 64, "expected 64 wheat out");

        helper.assertTrue(handler.getStackInSlot(0).isEmpty(), "expected an empty stack after extracting everything");
        helper.assertTrue(handler.isItemValid(0, new ItemStack(Items.WHEAT, 1)),
                "an emptied slot must accept items again");

        // 再插一批：走的是「物品为 null 但计数残留」分支也必须能进去
        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.CARROT, 10), false).isEmpty(),
                "expected the emptied slot to accept a different item");
        helper.assertTrue(chest.storage().amountOf(BuiltInRegistries.ITEM.getKey(Items.CARROT)) == 10L,
                "expected 10 carrots in the chest");
        helper.succeed();
    }

    /**
     * 同种物品一次搬入超过一个堆叠（128 > 64）：一次调用就全部进去，且 {@code isItemValid} 不因「已存 ≥64」报 false。
     *
     * <p>这正是 P1 的回归点：旧实现里 {@code isItemValid} 拿 {@link CompressedItemHandler} 的搬运单位 64 当存储上限，
     * 漏斗推到 64 个后就被 {@code HopperBlockEntity.tryMoveInItem} 的 {@code canPlaceItem} 拒掉，容量随扩容档位上涨等于摆设。
     */
    @GameTest(template = TEMPLATE)
    public void insertBeyondOneStackSucceeds(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(3, 1, 1));
        // 必须先把档位拉开：chestCount == 0 时 capacityPerSlot 就是 64，「超过一个堆叠」根本无处可放。
        // 这里要 256 = 64 × (1 + 3)。
        chest.storage().setChestCount(3L);
        CompressedItemHandler handler = new CompressedItemHandler(chest);

        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 64), false).isEmpty(),
                "expected 64 wheat to fit");
        // 漏斗视角：槽里已有 64 个，仍然是「能放同种物品」（旧实现这里恒为 false）
        helper.assertTrue(handler.isItemValid(0, new ItemStack(Items.WHEAT, 1)),
                "a slot holding 64 wheat must still accept wheat: capacity is capacityPerSlot, not 64");
        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 64), false).isEmpty(),
                "expected the second 64 wheat to fit");

        // 一次搬 128（两个普通堆叠）：循环搬，一次调用就得全部收下
        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 128), false).isEmpty(),
                "one call must be able to move more than a single stack");
        helper.assertTrue(chest.storage().amountOf(WHEAT) == 256L,
                "expected 256 wheat stored, got " + chest.storage().amountOf(WHEAT));
        helper.succeed();
    }

    /** 插入多少、取出多少：插入会循环，取出仍一次一份堆叠（{@code IItemHandler} 要求 ≤ maxStackSize）。 */
    @GameTest(template = TEMPLATE)
    public void insertAndExtractAgree(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(4, 1, 1));
        // 一次搬 128 需要 128 个空位：chestCount == 1 时 capacityPerSlot = 128。
        chest.storage().setChestCount(1L);
        CompressedItemHandler handler = new CompressedItemHandler(chest);

        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 128), false).isEmpty(),
                "expected 128 wheat to fit in one call");
        helper.assertTrue(chest.storage().amountOf(WHEAT) == 128L,
                "expected 128 wheat stored, got " + chest.storage().amountOf(WHEAT));

        long firstOut = handler.extractItem(0, 64, false).getCount();
        long secondOut = handler.extractItem(0, 64, false).getCount();
        helper.assertTrue(firstOut == 64L && secondOut == 64L,
                "expected 64 + 64 out, got " + firstOut + " + " + secondOut);
        helper.assertTrue(chest.storage().amountOf(WHEAT) == 0L,
                "expected the chest to be empty, got " + chest.storage().amountOf(WHEAT));
        helper.succeed();
    }

    /**
     * 格满（{@code capacityPerSlot}）前 {@code isItemValid} 为 true、之后为 false；{@code insertItem} 把放不下的原样退回。
     *
     * <p>用 {@code chestCount == 1}（每格 128）方便构造：126 + 1 + 1 恰好撞到上限。
     */
    @GameTest(template = TEMPLATE)
    public void fullSlotRejectsButPartialInsertReturnsRemainder(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(5, 1, 1));
        chest.storage().setChestCount(1L);
        long capacity = chest.storage().capacityPerSlot();
        CompressedItemHandler handler = new CompressedItemHandler(chest);

        helper.assertTrue(capacity == 128L, "capacityPerSlot must be 64 x (1 + chestCount), got " + capacity);
        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 126), false).isEmpty(),
                "expected 126 wheat to fit");
        helper.assertTrue(handler.isItemValid(0, new ItemStack(Items.WHEAT, 1)),
                "126 < 128: the slot must still accept wheat");
        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 1), false).isEmpty(),
                "expected 127th wheat to fit");
        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 1), false).isEmpty(),
                "expected 128th wheat to fit");
        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == capacity,
                "expected a full slot of " + capacity + ", got " + chest.storage().normalizedCountInSlot(0));

        helper.assertFalse(handler.isItemValid(0, new ItemStack(Items.WHEAT, 1)),
                "a slot at capacityPerSlot must report invalid");
        ItemStack remainder = handler.insertItem(0, new ItemStack(Items.WHEAT, 64), false);
        helper.assertTrue(remainder.getCount() == 64,
                "a full slot must return the whole stack, got " + remainder.getCount());
        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == capacity,
                "a rejected insert must not change the stored count, got "
                        + chest.storage().normalizedCountInSlot(0));

        // 部分装下：先取出 10 个腾出空间，一次塞 64，应进去 10、退回 54
        helper.assertTrue(handler.extractItem(0, 10, false).getCount() == 10, "expected 10 wheat out");
        ItemStack partial = handler.insertItem(0, new ItemStack(Items.WHEAT, 64), false);
        helper.assertTrue(partial.getCount() == 54,
                "expected 10 accepted and 54 returned, got " + partial.getCount());
        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == capacity,
                "expected the slot to be full again, got " + chest.storage().normalizedCountInSlot(0));
        helper.succeed();
    }

    /** 非同类物品：{@code isItemValid} 为 false，{@code insertItem} 原样退回、不合并不串格。 */
    @GameTest(template = TEMPLATE)
    public void differentItemIsRejected(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(6, 1, 1));
        CompressedItemHandler handler = new CompressedItemHandler(chest);

        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 1), false).isEmpty(),
                "expected 1 wheat to fit");
        helper.assertFalse(handler.isItemValid(0, new ItemStack(Items.CARROT, 1)),
                "a slot holding wheat must not accept carrots");
        ItemStack remainder = handler.insertItem(0, new ItemStack(Items.CARROT, 10), false);
        helper.assertTrue(remainder.getCount() == 10,
                "expected all 10 carrots back, got " + remainder.getCount());
        helper.assertTrue(chest.storage().amountOf(BuiltInRegistries.ITEM.getKey(Items.CARROT)) == 0L,
                "no carrot may be stored");
        helper.succeed();
    }

    /** 只读插入（simulate）：返回量反映「能否装下」，但一格都不真存、也不留预留。 */
    @GameTest(template = TEMPLATE)
    public void simulatedInsertReportsButDoesNotStore(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(7, 1, 1));
        CompressedItemHandler handler = new CompressedItemHandler(chest);

        helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 128), true).isEmpty(),
                "a simulated insert into an empty slot should accept the whole stack");
        helper.assertTrue(chest.storage().amountOf(WHEAT) == 0L,
                "a simulated insert must not store anything, got " + chest.storage().amountOf(WHEAT));
        helper.assertFalse(chest.storage().hasPendingReservation(),
                "a simulated insert must leave no pending reservation");
        helper.succeed();
    }

    /**
     * 大容量（{@code chestCount} 到顶，每格 {@code 64_000_000_064}）：超出一次搬运量的插入由调用方分多次来，
     * 适配器既不溢出成负数，也不丢数。
     */
    @GameTest(template = TEMPLATE)
    public void largeCapacityDoesNotOverflow(GameTestHelper helper) {
        CompressedBlockEntity chest = placeChest(helper, new BlockPos(8, 1, 1));
        chest.storage().setChestCount(ChestCapacity.MAX_CHEST_COUNT);
        CompressedItemHandler handler = new CompressedItemHandler(chest);

        for (int i = 0; i < 3; i++) {
            helper.assertTrue(handler.insertItem(0, new ItemStack(Items.WHEAT, 64), false).isEmpty(),
                    "insert " + i + " should be accepted in full");
        }
        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == 192L,
                "expected 192 wheat in slot 0, got " + chest.storage().normalizedCountInSlot(0));

        ItemStack out = handler.extractItem(0, Integer.MAX_VALUE, false);
        helper.assertTrue(out.getCount() == 64,
                "extraction must be capped at one normal stack, got " + out.getCount());
        helper.assertTrue(chest.storage().normalizedCountInSlot(0) == 128L,
                "expected 128 wheat left, got " + chest.storage().normalizedCountInSlot(0));
        helper.succeed();
    }

    private static CompressedBlockEntity placeChest(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, ModBlocks.COMPRESSED_DIRT.get(0).get());
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        CompressedBlockEntity chest = (CompressedBlockEntity) level.getBlockEntity(absolute);
        if (chest == null) {
            throw new IllegalStateException("expected a compressed chest at " + absolute);
        }
        return chest;
    }
}
