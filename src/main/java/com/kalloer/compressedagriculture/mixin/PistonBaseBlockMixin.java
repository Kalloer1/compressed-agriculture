package com.kalloer.compressedagriculture.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.kalloer.compressedagriculture.block.CompressedBlock;
import com.kalloer.compressedagriculture.storage.PistonChestMigration;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 让压缩方块能被活塞推动，并把货仓交给移动中的方块（ticket 09b）。
 *
 * <p>两个注入点，都按 NeoForge 21.1.235 的实际字节码落（{@code javap -p -c} 复核）：
 * <ul>
 *   <li>{@code isPushable}：{@code switch (getPistonPushReaction())} 的<b>默认分支</b>是
 *       {@code return !state.hasBlockEntity();}，带 BlockEntity 的方块一律推不动。这里只改写那一处
 *       {@code hasBlockEntity()} 的调用结果，前面的世界高度、世界边界、黑曜石 / 哭泣的黑曜石 / 重生锚 /
 *       强化深板岩、活塞自身等边界判断全部原样保留。</li>
 *   <li>{@code moveBlocks}：原版在循环里 {@code level.setBlockEntity(newMovingBlockEntity(...))} 之后就不再管源方块的
 *       BlockEntity，最后把源位置清成空气。这里在同一处注册完移动方块后立刻取源位置的货仓塞进去——源方块此时还没被清掉。</li>
 * </ul>
 *
 * <p>只放行 {@link CompressedBlock}，其它带 BlockEntity 的方块（箱子、熔炉……）行为不变。
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    /**
     * 默认分支放行压缩方块：{@code hasBlockEntity()} 返回真，但只要方块是本模组的压缩方块就当成「没有 BlockEntity」。
     *
     * <p>目标是 {@code isPushable} 里唯一一处 {@code BlockState.hasBlockEntity()} 调用（默认分支），所以不会误伤
     * 前面的高度 / 黑名单判断。
     */
    @Redirect(
            method = "isPushable(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;ZLnet/minecraft/core/Direction;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;hasBlockEntity()Z"))
    private static boolean compressedagriculture$pushableDespiteChest(BlockState state) {
        return state.hasBlockEntity() && !(state.getBlock() instanceof CompressedBlock);
    }

    /**
     * 移动方块一注册进世界就搬数据：{@code Level.setBlockEntity} 的接收者是 {@link Level}，被注册的是刚 new 出来的
     * {@code PistonMovingBlockEntity}，两者正是 {@link PistonChestMigration#capture} 需要的输入。
     *
     * <p>活塞头那一处调用（{@code isSourcePiston = true}）的 {@code movedState} 是活塞头，不是压缩方块，
     * {@code capture} 里第一道判据就返回了。
     */
    @Redirect(
            method = "moveBlocks(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private static void compressedagriculture$carryChest(Level level, BlockEntity moving) {
        PistonChestMigration.capture(level, moving.getBlockPos(), moving);
        level.setBlockEntity(moving);
    }
}
