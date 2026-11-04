package com.kalloer.compressedagriculture;

import org.slf4j.Logger;

import com.kalloer.compressedagriculture.block.FormConversionModule;
import com.kalloer.compressedagriculture.crop.CropModule;
import com.kalloer.compressedagriculture.mushroom.MushroomModule;
import com.kalloer.compressedagriculture.registry.ModBlockEntities;
import com.kalloer.compressedagriculture.registry.ModBlocks;
import com.kalloer.compressedagriculture.registry.ModCreativeTabs;
import com.kalloer.compressedagriculture.registry.ModMenus;
import com.kalloer.compressedagriculture.registry.ModRecipes;
import com.kalloer.compressedagriculture.retry.SettlementRetry;
import com.kalloer.compressedagriculture.stem.StemModule;
import com.kalloer.compressedagriculture.tree.TreeModule;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CompressedAgriculture.MODID)
public class CompressedAgriculture {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "compressedagriculture";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    public CompressedAgriculture(IEventBus modEventBus, ModContainer modContainer) {
        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 方块先于物品注册：方块物品的 supplier 在物品注册时要拿到已注册的方块实例
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.BLOCK_ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModMenus.MENU_TYPES.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModRecipes.RECIPE_SERIALIZERS.register(modEventBus);

        // 普通作物模块：随机刻路径 + 骨粉路径（ADR-0001）
        CropModule cropModule = new CropModule();
        NeoForge.EVENT_BUS.addListener(CropGrowEvent.Post.class, cropModule::onCropGrow);
        NeoForge.EVENT_BUS.addListener(BonemealEvent.class, cropModule::onBonemeal);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, cropModule::onServerTick);

        // 08b：三处重试时机（关闭界面 / 右键 / 破坏前），都是玩家主动动作，不做轮询（ADR-0005）
        NeoForge.EVENT_BUS.addListener(PlayerContainerEvent.Close.class, SettlementRetry::onContainerClose);
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, SettlementRetry::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, SettlementRetry::onBlockBreak);

        // 瓜类模块的手动兜底（ADR-0008）；自动收获在 StemBlockMixin 里，不走事件
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, StemModule::onRightClickBlock);

        // 形态互转（ticket 09）：锄头把压缩泥土变压缩耕地、铲子把压缩耕地变压缩泥土
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class,
                FormConversionModule::onRightClickBlock);

        // 树木模块：生长事件只记录（不取消），本 tick 末尾再结算、拆除、回填（ADR-0014）
        TreeModule treeModule = new TreeModule();
        NeoForge.EVENT_BUS.addListener(BlockGrowFeatureEvent.class, treeModule::onBlockGrowFeature);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, treeModule::onServerTick);

        // 蘑菇模块：同一个生长事件只记录（不取消），本 tick 末尾交给 TreeHarvest 拆蘑菇树；手动挖掘走兜底（ADR-0013）
        MushroomModule mushroomModule = new MushroomModule();
        NeoForge.EVENT_BUS.addListener(BlockGrowFeatureEvent.class, mushroomModule::onBlockGrowFeature);
        NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, mushroomModule::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, mushroomModule::onServerTick);
    }
}
