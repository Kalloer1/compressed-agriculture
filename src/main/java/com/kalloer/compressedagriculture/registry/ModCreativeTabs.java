package com.kalloer.compressedagriculture.registry;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.block.CompressedBlock;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式标签页，装全部 27 个压缩方块——手工验收（放方块、挖方块、{@code /data get block} 看货仓）靠它取方块。
 *
 * <p>标题键 {@code itemGroup.compressedagriculture} 已在 {@code lang/en_us.json} 里。
 */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CompressedAgriculture.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> COMPRESSED_AGRICULTURE =
            CREATIVE_MODE_TABS.register("compressedagriculture", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.compressedagriculture"))
                    .icon(() -> new ItemStack(ModBlocks.COMPRESSED_DIRT.get(0).get()))
                    .displayItems((parameters, output) -> {
                        for (DeferredBlock<? extends CompressedBlock> block : ModBlocks.ALL_COMPRESSED) {
                            output.accept(block.get());
                        }
                    })
                    .build());

    private ModCreativeTabs() {}
}
