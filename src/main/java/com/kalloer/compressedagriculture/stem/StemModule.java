package com.kalloer.compressedagriculture.stem;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import javax.annotation.Nullable;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.block.CompressedFarmlandBlock;
import com.kalloer.compressedagriculture.crop.GlobalPosKey;
import com.kalloer.compressedagriculture.crop.WarnThrottle;
import com.kalloer.compressedagriculture.mixin.StemBlockAccessor;
import com.kalloer.compressedagriculture.registry.ModTags;
import com.kalloer.compressedagriculture.settle.DropEntry;
import com.kalloer.compressedagriculture.settle.Settle;
import com.kalloer.compressedagriculture.settle.SettleResult;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 瓜类模块：把「茎长熟 → 果实」这条原版路径改写成「果实直接进货仓，茎退回 0 龄」。
 *
 * <p>两条入口共用同一个结算核心 {@link #harvest}：
 * <ul>
 *   <li><b>自动</b>：{@code StemBlockMixin} 注入 {@code StemBlock.randomTick} 的 {@code HEAD}。原版
 *       {@code performBonemeal} 把 {@code AGE} 推到 7 之后调用的也是 {@code BlockState.randomTick}，
 *       所以骨粉路径不需要第二个注入点。</li>
 *   <li><b>手动</b>：{@link #onRightClickBlock}，玩家空手右键世界里已经存在的果实方块（ADR-0008）。</li>
 * </ul>
 *
 * <p>全部方法都是 {@code static}：注入点在 Mixin 的静态上下文里，与事件监听必须共用同一份 WARN 限流表，
 * 做成实例反而要额外传递。
 *
 * <p>本类不认识任何具体的茎 / 果实方块 ID：果实一律由 {@code StemBlock.fruit} 字段或「被右键的那个方块」给出。
 */
public final class StemModule {
    /** 手动兜底的四邻域遍历顺序：北 → 东 → 南 → 西，取第一个匹配的茎就停（ADR-0008）。 */
    private static final List<Direction> MANUAL_SEARCH_ORDER =
            List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    /** INVALID_INPUT 的 WARN 限流表（ADR-0007），自动与手动路径共用。 */
    private static final WarnThrottle invalidInputWarn = new WarnThrottle();

    private StemModule() {}

    /**
     * 一次结算的三种结局，外加「这一格根本不走压缩路径」。
     *
     * <p>自动路径用前三种决定是否取消原版，用 {@link #NOT_APPLICABLE} 决定是否放行；
     * 手动路径用 {@link #NOT_APPLICABLE} 决定 {@code ActionResult.PASS}。
     */
    public enum Outcome {
        /** 已写入货仓。 */
        SUCCESS,
        /** 货仓放不下：一个物品都没写，果实不出现，茎保持成熟态等重试（ADR-0005）。 */
        INSUFFICIENT_SPACE,
        /** 果实战利品表为空：不写入、不重置，记 WARN（ADR-0007）。 */
        INVALID_INPUT,
        /** 茎下方不是压缩耕地（或没有货仓）：交回原版。 */
        NOT_APPLICABLE
    }

    /**
     * 茎的果实方块；不是 {@link StemBlock}、或果实方块没注册时返回 {@code null}。
     *
     * <p>{@code fruit} 是 {@code StemBlock} 的私有字段，只能通过 {@link StemBlockAccessor} 读。
     */
    @Nullable
    public static Block fruitOf(BlockState stemState) {
        if (!(stemState.getBlock() instanceof StemBlock stem)) {
            return null;
        }
        ResourceKey<Block> fruitKey = ((StemBlockAccessor) stem).compressedagriculture$fruit();
        return BuiltInRegistries.BLOCK.get(fruitKey);
    }

    /**
     * 以「果实方块」为准结算一次：产物取自果实方块自己的战利品表，{@code L} 取自茎下方压缩耕地的等级。
     *
     * <p>{@code fruitBlock} 由调用方给出——自动路径是 {@link #fruitOf} 读出的 {@code fruit} 字段，手动路径就是
     * 玩家右键的那个方块。两条路径因此共用同一段「取掉落 → 乘 9^L → 写货仓」。
     *
     * <p>不改动世界状态：{@code AGE} 归零由自动路径在 {@link Outcome#SUCCESS} 之后自己做，果实方块的移除由
     * 手动路径自己做。
     */
    public static Outcome harvest(ServerLevel level, BlockPos stemPos, Block fruitBlock) {
        BlockPos chestPos = stemPos.below();
        BlockState below = level.getBlockState(chestPos);
        if (!(below.getBlock() instanceof CompressedFarmlandBlock)) {
            return Outcome.NOT_APPLICABLE;
        }
        OptionalInt stemLevel = CompressedBlock.levelOf(below);
        if (stemLevel.isEmpty()) {
            return Outcome.NOT_APPLICABLE;
        }
        if (!(level.getBlockEntity(chestPos) instanceof CompressedBlockEntity chest)) {
            // 压缩方块都是 EntityBlock，正常不会缺 BE；缺了说明世界数据坏了，不写、不重置
            CompressedAgriculture.LOGGER.debug(
                    "compressed farmland at {} in {} has no block entity, skipping stem settlement",
                    chestPos, level.dimension().location());
            return Outcome.NOT_APPLICABLE;
        }

        SettleResult result = Settle.settle(collectDrops(level, stemPos, fruitBlock), stemLevel.getAsInt(), chest);
        return switch (result) {
            case SUCCESS -> Outcome.SUCCESS;
            case INSUFFICIENT_SPACE -> Outcome.INSUFFICIENT_SPACE;
            case INVALID_INPUT -> {
                warnInvalidInput(level, stemPos, fruitBlock);
                yield Outcome.INVALID_INPUT;
            }
        };
    }

    /** 结算成功后把茎设回 {@code AGE = 0}，让它重新长下一轮（GLOSSARY「重置幼苗」）。 */
    public static void resetStem(ServerLevel level, BlockPos stemPos, BlockState stemState) {
        level.setBlock(stemPos, stemState.setValue(StemBlock.AGE, 0), Block.UPDATE_CLIENTS);
    }

    /**
     * 手动兜底：玩家空手右键世界里已有的果实方块时，按北 → 东 → 南 → 西找第一个指向它的茎并结算（ADR-0008）。
     *
     * <p>「取第一个匹配的茎」是硬规则：第一个匹配的茎下方不是压缩耕地时直接 {@code PASS}，**不跳过、不继续找**，
     * 否则遍历序会退化成「找到能用的为止」。
     *
     * <p>走压缩路径时无论 {@code SUCCESS} 还是 {@code INSUFFICIENT_SPACE} 都取消事件并回
     * {@link InteractionResult#SUCCESS}：前者已把果实收走，后者要把果实留在世界里等重试，两种情况都不能让原版
     * 再处理一次这次右键。只有 {@link Outcome#NOT_APPLICABLE} 才 {@code PASS}，完全交回原版。
     *
     * <p>只接管空手右键（GLOSSARY「手动兜底」）：手里拿着方块时右键果实是「往果实旁边放方块」，抢掉会变成
     * 建造障碍。
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!event.getItemStack().isEmpty()) {
            return;
        }
        BlockPos fruitPos = event.getPos();
        Block fruitBlock = level.getBlockState(fruitPos).getBlock();

        for (Direction direction : MANUAL_SEARCH_ORDER) {
            BlockPos stemPos = fruitPos.relative(direction);
            BlockState stemState = level.getBlockState(stemPos);
            if (!pointsAt(stemState, fruitBlock)) {
                continue;
            }
            Outcome outcome = harvest(level, stemPos, fruitBlock);
            if (outcome == Outcome.NOT_APPLICABLE) {
                return;
            }
            if (outcome == Outcome.SUCCESS) {
                level.removeBlock(fruitPos, false);
                // 茎也退回 0 龄：本模组不产生 attached stem，若把茎留在 AGE=7，下一随机刻会立刻再结算一次，
                // 等于手动收一次白送一次。归零后与自动路径的节奏一致：一次收获对应一轮生长。
                resetStem(level, stemPos, stemState);
            }
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }
    }

    /**
     * 这个位置上的茎是否指向被右键的果实方块。
     *
     * <p>{@code StemBlock} 用 {@code fruit} 字段比对，因此方块 ID 不出现；非 {@code StemBlock} 的模组茎读不到
     * {@code fruit}，只能按 {@code #melon_like_stems} / {@code #pumpkin_like_stems} 标签认（默认空，只有整合包
     * 显式填了才生效）。
     */
    private static boolean pointsAt(BlockState stemState, Block fruitBlock) {
        if (stemState.getBlock() instanceof StemBlock) {
            return fruitOf(stemState) == fruitBlock;
        }
        return stemState.is(ModTags.Blocks.MELON_LIKE_STEMS)
                || stemState.is(ModTags.Blocks.PUMPKIN_LIKE_STEMS);
    }

    /** 按果实方块的原版战利品表取掉落，转成 {@code settle} 认识的「物品 ID + 数量」（ADR-0017）。 */
    private static List<DropEntry> collectDrops(ServerLevel level, BlockPos pos, Block fruitBlock) {
        List<ItemStack> stacks = fruitBlock.defaultBlockState().getDrops(new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY));

        List<DropEntry> drops = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || stack.getCount() <= 0) {
                continue;
            }
            drops.add(new DropEntry(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount()));
        }
        return drops;
    }

    /** 掉落清单为空：永久卡住的症状，必须可诊断，所以是 WARN，并按茎的位置限流（ADR-0007）。 */
    private static void warnInvalidInput(ServerLevel level, BlockPos stemPos, Block fruitBlock) {
        GlobalPosKey key = GlobalPosKey.of(level.dimension(), stemPos);
        if (!invalidInputWarn.shouldLog(key, level.getGameTime())) {
            return;
        }
        CompressedAgriculture.LOGGER.warn(
                "stem settlement got an empty drop list: fruit={} dimension={} pos={}",
                BuiltInRegistries.BLOCK.getKey(fruitBlock), level.dimension().location(), stemPos);
    }
}
