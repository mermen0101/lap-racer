package dev.laprace.gfx;

import java.awt.Font;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Our TTF files loaded into AWT, for text baked into textures (dial numbers, labels). */
final class AwtFonts {
	private static final Map<String, Font> FONTS = new HashMap<>();

	private AwtFonts() {
	}

	static synchronized Font get(String file, float px) {
		Font base = FONTS.computeIfAbsent(file, f -> {
			try (InputStream in = AwtFonts.class.getResourceAsStream("/assets/laprace/font/" + f)) {
				if (in != null) {
					return Font.createFont(Font.TRUETYPE_FONT, in);
				}
			} catch (Exception e) {
				// fall through to a system font
			}
			return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
		});
		return base.deriveFont(px);
	}
}
