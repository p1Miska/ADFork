package com.trollmods.adgif;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu integration. Adds a gear/"Настройки" button next to AdGif
 * in the Mods list. Loaded only when Mod Menu is installed (the entrypoint
 * is simply never queried otherwise), so the dependency is optional.
 */
public class AdGifModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AdGifConfigScreen::new;
    }
}