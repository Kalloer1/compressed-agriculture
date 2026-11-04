package com.kalloer.compressedagriculture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kalloer.compressedagriculture.block.CompressedFarmlandBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.TriState;

/**
 * 让原版 {@code CropBlock} 把压缩耕地当耕地（ADR-0018）。
 *
 * <p>原版用身份比较认耕地（{@code state.getBlock() instanceof FarmBlock} / {@code state.is(Blocks.FARMLAND)}），
 * 压缩耕地是另一个 Block 实例，继承 {@code FarmBlock} 也不会命中，所以只能改字节码。判据一律
 * {@code instanceof CompressedFarmlandBlock}，全部压缩耕地自动命中，不列方块 ID。
 *
 * <p><b>NeoForge 21.1.235 的实际代码与 ADR-0018 表格里记的不一样</b>（实测 {@code javap} 反编译 dev 运行期用的
 * {@code CropBlock.class}）：{@code mayPlaceOn} 已由 NeoForge 改成 {@code instanceof FarmBlock}；
 * {@code getGrowthSpeed} 的「脚下是不是耕地」判断也已从 {@code is(Blocks.FARMLAND)} 改成
 * {@code canSustainPlant(...) → TriState} + {@code isFertile(...)} 两步，方法首参也从 {@code Block} 变成
 * {@code BlockState}。所以两个注入点按实际字节码落：
 * <ul>
 *   <li>{@code mayPlaceOn}：{@code RETURN} 处改返回值（NeoForge 版是 {@code instanceof FarmBlock}，注入方式不变）；</li>
 *   <li>{@code getGrowthSpeed}：{@code canSustainPlant} 与 {@code isFertile} 各 Redirect 一次，
 *       压缩耕地在前者返回 {@code TriState.TRUE}（等价于「是耕地」），在后者按自己的 {@code MOISTURE} 判断水合。</li>
 * </ul>
 * 不再按「Redirect 掉全部 {@code BlockState.is(Block)}」写：NeoForge 版 {@code getGrowthSpeed} 里剩下的
 * {@code is(Block)} 全是「四周是不是同种作物」的判断，扩展它们会让生长速度算错。
 */
@Mixin(CropBlock.class)
public abstract class CropBlockMixin {
    /** 压缩耕地能种小麦 / 胡萝卜 / 马铃薯 / 甜菜；不改写则原版 {@code mayPlaceOn} 返回 false，根本种不下去。 */
    @Inject(
            method = "mayPlaceOn(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("RETURN"),
            cancellable = true)
    private void compressedagriculture$allowCompressedFarmland(BlockState state, BlockGetter level, BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && state.getBlock() instanceof CompressedFarmlandBlock) {
            cir.setReturnValue(true);
        }
    }

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
