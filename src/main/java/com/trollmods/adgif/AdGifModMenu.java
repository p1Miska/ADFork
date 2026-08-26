package com.trollmods.adgif;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu integration. Loaded only when Mod Menu is installed.
 */
public class AdGifModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AdGifConfigScreen::new;
    }
}