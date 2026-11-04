package com.kalloer.compressedagriculture.block;

import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.storage.CompressedChestTransfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 用原版工具把压缩方块换成另一种形态（ticket 09、ADR-0002、ADR-0018）。
 *
 * <p>两条规则，等级 {@code L} 不变、货仓原样带走：
 * <ul>
 *   <li><b>锄头右键压缩泥土</b> → 同等级压缩耕地。原版 {@code HoeItem.TILLABLES} 是硬编码方块表、不读标签，
 *       压缩泥土永远不在表里，所以必须在这里显式接管。</li>
 *   <li><b>铲子右键压缩耕地</b> → 同等级压缩泥土。原版 {@code ShovelItem.FLATTENABLES} 同样是硬编码表，
 *       而且原版铲子产出的是 {@code DIRT_PATH}，与「两种形态」不符，所以也不复用。</li>
 * </ul>
 *
 * <p>只处理形态与等级都取自 {@link CompressedBlock} 的方块，代码里不出现任何具体方块 ID。
 *
 * <p>只在服务端动手：客户端让原版 {@code useOn} 走一遍空逻辑（返回 {@code PASS}），不需要预测，方块变化由服务端同步。
 */
public final class FormConversionModule {
    private FormConversionModule() {}

    /**
     * {@code PlayerInteractEvent.RightClickBlock} 的处理入口。
     *
     * <p>命中时取消事件并回 {@link InteractionResult#SUCCESS}：转换已经做完，不能再让原版 {@code useOn} 处理一次
     * （否则手持锄头右键压缩泥土会同时触发原版 {@code useOn} 的逻辑，且耐久会被扣两次）。
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CompressedBlock compressed)) {
            return;
        }

        ItemStack stack = event.getItemStack();
        CompressedForm targetForm;
        SoundEvent sound;
        if (stack.getItem() instanceof HoeItem) {
            if (compressed.form() != CompressedForm.DIRT) {
                return;
            }
            targetForm = CompressedForm.FARMLAND;
            sound = SoundEvents.HOE_TILL;
        } else if (stack.getItem() instanceof ShovelItem) {
            if (compressed.form() == CompressedForm.DIRT) {
                return;
            }
            targetForm = CompressedForm.DIRT;
            sound = SoundEvents.SHOVEL_FLATTEN;
        } else {
            return;
        }

        BlockState converted = ModBlocks.block(targetForm, compressed.level()).defaultBlockState();
        if (!CompressedChestTransfer.convert(level, pos, converted)) {
            return;
        }

        Player player = event.getEntity();
        level.playSound(player, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 8, 0.3, 0.0, 0.3, 0.0);
        stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(event.getHand()));

        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
