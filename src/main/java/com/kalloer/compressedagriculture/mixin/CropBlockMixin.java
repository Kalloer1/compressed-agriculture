package com.kalloer.compressedagriculture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.kalloer.compressedagriculture.block.CompressedFarmlandBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.TriState;

/**
 * 让原版 {@code CropBlock} 的生长速度把压缩耕地当耕地（ADR-0018、ADR-0020）。
 *
 * <p>原版用身份比较认耕地（{@code state.getBlock() instanceof FarmBlock}），压缩耕地是另一个 Block 实例，
 * 继承 {@code FarmBlock} 也不会命中。判据一律 {@code instanceof CompressedFarmlandBlock}，
 * 全部压缩耕地自动命中，不列方块 ID。
 *
 * <p><b>「能不能种」已经不在这里</b>（ADR-0020）：{@code CompressedFarmlandBlock.canSustainPlant} 返回
 * {@code TriState.TRUE}，{@code CropBlock.canSurvive} 第一行就采纳它，{@code mayPlaceOn} 再也走不到，
 * 所以原先那个 {@code mayPlaceOn} 注入已删除。
 *
 * <p>剩下两个注入点都在 {@code getGrowthSpeed}（按 NeoForge 21.1.235 的实际字节码落，{@code javap} 复核）：
 * <ul>
 *   <li>{@code canSustainPlant} → {@code TriState}：压缩耕地返回 {@code TRUE}（等价于「是耕地」）。
 *       本类的 override 已经能做到同样的事，这个 Redirect 因此是冗余的，但保留——它不改变结果，
 *       而删掉会让「生长速度不依赖 canSustainPlant 的默认实现」这层保证消失。</li>
 *   <li>{@code isFertile}：原版内部仍是 {@code is(Blocks.FARMLAND)}，压缩耕地永远为 false，
 *       于是「水合 → 3.0」对压缩耕地失效。这里改读压缩耕地自己的 {@code MOISTURE}。</li>
 * </ul>
 * 不按「Redirect 掉全部 {@code BlockState.is(Block)}」写：NeoForge 版 {@code getGrowthSpeed} 里剩下的
 * {@code is(Block)} 全是「四周是不是同种作物」的判断，扩展它们会让生长速度算错。
 */
@Mixin(CropBlock.class)
public abstract class CropBlockMixin {
    /**
     * 生长速度的「这块地算不算耕地」判断。原版（NeoForge 版）先问 {@code canSustainPlant}，回 {@code DEFAULT}
     * 时退回 {@code instanceof FarmBlock}。压缩耕地在这里返回 {@code TRUE}，等价于「是耕地」，随后原版给 1.0（未水合）。
     */
    @Redirect(
            method = "getGrowthSpeed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;canSustainPlant(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/neoforged/neoforge/common/util/TriState;"))
    private static TriState compressedagriculture$sustainPlant(BlockState state, BlockGetter level, BlockPos pos,
            Direction facing, BlockState plantState) {
        if (state.getBlock() instanceof CompressedFarmlandBlock) {
            return TriState.TRUE;
        }
        return state.canSustainPlant(level, pos, facing, plantState);
    }

    /**
     * 水合判断。原版 {@code BlockState.isFertile} 内部仍是 {@code is(Blocks.FARMLAND)}，压缩耕地永远为 false，
     * 于是「水合 → 3.0」这一段对压缩耕地失效。这里改读压缩耕地自己的 {@code MOISTURE}
     * （与 {@code FarmBlock.MOISTURE} 同一个属性实例，ticket 03 已保证存在）。水合值的写入由 ticket 09 负责。
     */
    @Redirect(
            method = "getGrowthSpeed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;isFertile(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean compressedagriculture$isFertile(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.getBlock() instanceof CompressedFarmlandBlock) {
            return state.getValue(CompressedFarmlandBlock.MOISTURE) > 0;
        }
        return state.isFertile(level, pos);
    }
}
