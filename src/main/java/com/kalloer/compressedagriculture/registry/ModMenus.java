package com.kalloer.compressedagriculture.registry;

import com.kalloer.compressedagriculture.CompressedAgriculture;
import com.kalloer.compressedagriculture.menu.CompressedChestMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 界面类型。只有一个：27 格货仓界面。
 *
 * <p>用 {@link IMenuTypeExtension#create} 而不是 {@code new MenuType(...)}：客户端构造 menu 时需要知道方块坐标，
 * 坐标是服务端打开界面时随包发过去的（{@code player.openMenu(provider, pos)}），普通 {@code MenuSupplier}
 * 拿不到那份数据。
 */
public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, CompressedAgriculture.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<CompressedChestMenu>> COMPRESSED_CHEST =
            MENU_TYPES.register("compressed_chest",
                    () -> IMenuTypeExtension.create(CompressedChestMenu::new));

    private ModMenus() {}
}
