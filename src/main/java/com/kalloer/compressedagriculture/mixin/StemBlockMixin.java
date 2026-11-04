package com.kalloer.compressedagriculture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kalloer.compressedagriculture.block.CompressedFarmlandBlock;
import com.kalloer.compressedagriculture.stem.StemModule;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 让瓜类茎长熟时直接结算进货仓（ADR-0016 / ADR-0010）。
 *
 * <p><b>「能不能种」已经不在这里</b>（ADR-0020）：{@code CompressedFarmlandBlock.canSustainPlant} 返回
 * {@code TriState.TRUE}，{@code BushBlock.canSurvive}（{@code StemBlock} 的父类）第一行就采纳它，
 * {@code mayPlaceOn} 再也走不到，所以原先那个 {@code mayPlaceOn} 注入已删除。
 *
 * <p>保留的 {@code randomTick} 注入：原版在这里「把果实放到茎旁边 + 自己转成 attached stem」，
 * 改成「直接结算进货仓」。判据只有两条：下方是 {@link CompressedFarmlandBlock}、{@code AGE == MAX_AGE}
 * （等级 {@code L} 与货仓在 {@link StemModule} 里读）。
 *
 * <p><b>为什么不单独注入 {@code performBonemeal}</b>：NeoForge 版 {@code performBonemeal} 把 {@code AGE} 推到 7
 * 之后调的是 {@code BlockState.randomTick(ServerLevel, BlockPos, RandomSource)}，虚拟分派回本类的
 * {@code randomTick}，所以 {@code HEAD} 注入已经覆盖骨粉路径。
 *
 * <p><b>{@code HEAD} 注入的已知差异</b>：原版在 {@code randomTick} 里先查光照与 {@code canCropGrow}（会触发
 * {@code CropGrowEvent.Pre}）再放果实；{@code HEAD} 注入在两者之前，所以「成熟茎在暗处也照常结算」。
 * 这是按 ticket 07 的指定注入点实现的，未额外复制光照判断。
 */
@Mixin(StemBlock.class)
public abstract class StemBlockMixin {
    /**
     * 茎长熟（{@code AGE == MAX_AGE}）且长在压缩耕地上时，把这一次「结果」结算进货仓，不再往世界里放果实。
     *
     * <p>三种结局（见 {@link StemModule.Outcome}）：
     * <ul>
     *   <li>{@code SUCCESS} → 茎设回 {@code AGE = 0} 继续长，果实不落地，也不转 attached stem；</li>
     *   <li>{@code INSUFFICIENT_SPACE} / {@code INVALID_INPUT} → 状态一动不动（保留 {@code AGE = MAX_AGE}）等重试；</li>
     *   <li>{@code NOT_APPLICABLE}（下方不是压缩耕地）→ 不取消，完全走原版。</li>
     * </ul>
     * 前两种都 cancel：不 cancel 的话原版会照样把果实放进世界。
     *
     * <p>{@code SUCCESS} 必须把 {@code AGE} 归零——否则 {@code AGE} 停在最大值，下一随机刻立刻又结算一次，
     * 变成每刻无限收获。
     */
    @Inject(
            method = "randomTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void compressedagriculture$harvestIntoCompressedChest(BlockState state, ServerLevel level, BlockPos pos,
            RandomSource random, CallbackInfo ci) {
        if (state.getValue(StemBlock.AGE) != StemBlock.MAX_AGE) {
            return;
        }
        Block fruit = StemModule.fruitOf(state);
        if (fruit == null) {
            return;
        }
        StemModule.Outcome outcome = StemModule.harvest(level, pos, fruit);
        if (outcome == StemModule.Outcome.NOT_APPLICABLE) {
            return;
        }
        if (outcome == StemModule.Outcome.SUCCESS) {
            StemModule.resetStem(level, pos, state);
        }
        ci.cancel();
    }
}
