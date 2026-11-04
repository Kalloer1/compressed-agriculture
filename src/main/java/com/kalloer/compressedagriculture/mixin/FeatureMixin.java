package com.kalloer.compressedagriculture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kalloer.compressedagriculture.block.CompressedBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * 防止树木的「换土」步骤把压缩方块换成灰化土（连带丢掉货仓）。
 *
 * <p>巨型云杉等挂着 {@code AlterGroundDecorator}（{@code treedecators} 包里的树装饰器）的树会从树根往下找第一格 {@code Feature.isGrassOrDirt} 为真的方块，
 * 把它换成本身持有的方块（{@code podzol} 等）。压缩方块全在 {@code #minecraft:dirt} 里（ADR-0010 故意的，
 * 瓜类种植需要），所以这个判断对它们为真，压缩方块会被替换而货仓的 27 格内容随之消失。
 *
 * <p><b>为什么注入 {@code isGrassOrDirt} 而不是 {@code isDirt}</b>：{@code isGrassOrDirt} 是 {@code isDirt} 的唯一应用点，
 * 但只要有人直接调 {@code isDirt}（例如别的模组的替换逻辑），在 {@code isGrassOrDirt} 上拦不住它；
 * 方法体只有一行
 * {@code level.isStateAtPosition(pos, Feature::isDirt)}（{@code javap} 已核对），
 * 因此「真实返回值 + 该位置的方块状态」足以判断，不需要动 {@code isDirt} 本身。
 *
 * <p><b>1.21.1 dev 运行期字节码（{@code javap -p -c net.minecraft.world.level.levelgen.feature.Feature}）</b>：
 * <pre>
 * public static boolean isGrassOrDirt(LevelSimulatedReader, BlockPos);
 *   0: aload_0
 *   1: aload_1
 *   2: invokedynamic  test:()Ljava/util/function/Predicate;   // Feature::isDirt
 *   7: invokeinterface LevelSimulatedReader.isStateAtPosition(BlockPos, Predicate)Z
 *  12: ireturn
 * </pre>
 * 方法体是单条返回，签名与方法体都比 ADR-0018 里记的那几种情况简单，所以用 {@code @Inject(at = RETURN, cancellable)}
 * 改返回值。保留 {@code #minecraft:dirt} 标签不动（ADR-0010），只让「换土」这一步不认压缩方块。
 */
@Mixin(Feature.class)
public abstract class FeatureMixin {
    /**
     * 压缩方块不是「草或泥土」：原返回 true 且该位置是压缩方块时改回 false。
     *
     * <p>其他方块（含真正的草方块、泥土、灰化土）原样返回，行为不变。
     * 非 {@link BlockGetter} 的 {@code LevelSimulatedReader}（测试用的模拟读器）也原样返回。
     */
    @Inject(
            method = "isGrassOrDirt(Lnet/minecraft/world/level/LevelSimulatedReader;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("RETURN"),
            cancellable = true)
    private static void compressedagriculture$notGrassOrDirtForCompressedBlocks(LevelSimulatedReader level, BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !(level instanceof BlockGetter blockGetter)) {
            return;
        }
        // 只查方块身份，不读 BlockEntity：压缩方块是 CompressedBlock 实例就够了，换土与货仓无关
        if (blockGetter.getBlockState(pos).getBlock() instanceof CompressedBlock) {
            cir.setReturnValue(false);
        }
    }
}
