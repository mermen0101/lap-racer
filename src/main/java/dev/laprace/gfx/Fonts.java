package dev.laprace.gfx;

import dev.laprace.LapConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;


/**
 * The HUD's fonts, in the spirit of Gran Turismo's: Inter (labels, names; has Cyrillic) and Orbitron (wide,
 * squared digits for times, speed and gear). Both SIL Open Font License, licences in assets/laprace/licenses.
 * In game they are Minecraft TTF fonts (assets/laprace/font/*.json); for the baked dials the same files are
 * loaded into AWT.
 */
public final class Fonts {
	public static final FontDescription UI = new FontDescription.Resource(Identifier.fromNamespaceAndPath("laprace", "ui"));
	public static final FontDescription BOLD = new FontDescription.Resource(Identifier.fromNamespaceAndPath("laprace", "ui_bold"));
	public static final FontDescription DIGITS = new FontDescription.Resource(Identifier.fromNamespaceAndPath("laprace", "digits"));

	private Fonts() {
	}

	/** Text in one of our fonts (or the game's own if GT fonts are off in the settings). */
	public static Component text(String s, FontDescription f) {
		MutableComponent c = Component.literal(s);
		return LapConfig.INSTANCE.gtFonts ? c.withStyle(st -> st.withFont(f)) : c;
	}
}
