package dev.laprace.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.laprace.SettingsScreen;

/** Mod Menu entry point: the settings button opens {@link SettingsScreen} (the mod's own; no Cloth Config needed). */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}
