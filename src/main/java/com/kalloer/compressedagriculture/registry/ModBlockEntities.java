package com.kalloer.compressedagriculture.registry;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.storage.CompressedBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 压缩方块的 BlockEntity 类型：只有一个，validBlocks 覆盖全部 18 个方块。
 *
 * <p>等级与形态不进 BlockEntity，所以不需要 18 个类型。
 */
public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CompressedAgriculture.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CompressedBlockEntity>> COMPRESSED =
            BLOCK_ENTITY_TYPES.register("compressed", () -> BlockEntityType.Builder
                    .of(CompressedBlockEntity::new, ModBlocks.allBlocks())
                    .build(null));

    private ModBlockEntities() {}
}
