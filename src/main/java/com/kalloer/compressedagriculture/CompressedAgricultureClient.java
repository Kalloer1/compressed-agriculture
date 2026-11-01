package com.kalloer.compressedagriculture;

import com.kalloer.compressedagriculture.client.CompressedChestScreen;
import com.kalloer.compressedagriculture.registry.ModMenus;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CompressedAgriculture.MODID, dist = Dist.CLIENT)
public class CompressedAgricultureClient {
    public CompressedAgricultureClient(ModContainer container, IEventBus modEventBus) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // 货仓界面的客户端一侧（ticket 08）：menu 类型 → Screen 的绑定只在这里做一次
        modEventBus.addListener(RegisterMenuScreensEvent.class, CompressedAgricultureClient::registerMenuScreens);
    }

    private static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.COMPRESSED_CHEST.get(), CompressedChestScreen::new);
    }
}
