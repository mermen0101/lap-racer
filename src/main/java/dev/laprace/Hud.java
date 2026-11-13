package dev.laprace;

import dev.laprace.cruise.Cruise;
import dev.laprace.gfx.Arcs;
import dev.laprace.gfx.Baked;
import dev.laprace.gfx.Fonts;
import dev.laprace.gfx.Gauges;
import dev.laprace.gfx.GlyphAtlas;
import dev.laprace.gfx.Labels;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The race HUD, matched to Gran Turismo 7's race screen. Positions and sizes were measured on GT 7 frames
 * (1920 x 1080 = 640 x 360 layout units, 3 px a unit) and on the user's GT screenshots; the cockpit sits 14 units
 * higher than in GT so that Minecraft's hotbar stays clear.
 * <p>
 * Text: everything whose size matters is drawn from baked textures at an exact cap height ({@link Labels} for
 * words, {@link GlyphAtlas} for numbers, tabular, so running times do not jitter); the static parts (dials,
 * panel, maps) are baked once ({@link Baked}). A frame is a few dozen quads.
 */
public final class Hud {
	public static final int WHITE = 0xFFFFFFFF;
	public static final int GREY = 0xFFB4B8BC;
	public static final int DIM = 0xFF70767E;
	public static final int GREEN = 0xFF2DB84B;
	public static final int YELLOW = 0xFFFFC21A;
	public static final int RED = 0xFFE0282E;
	public static final int BLUE = 0xFF1E6FD9;
	public static final int PURPLE = 0xFF8A45B8;
	private static final int DARK_TEXT = 0xFF141618;

	private static final float GRID_W = 640;
	/** The cockpit is this much higher than in GT (room for the hotbar). */
	private static final float LIFT = 14;
	public static final int MFD_PAGES = 4;

	/**
	 * The centre display's pages. With Ice Racer installed (the kill-a-car's autopilot) there is no fuel: its page is
	 * the chase instead.
	 */
	public static int mfdPages() {
		return MFD_PAGES;
	}

	/** What page {@code page} shows: 0 map, 1 radar, 2 session, 3 fuel, 4 the chase. */
	private static int pageKind(int page) {
		return page == 3 && IceRacerLink.present() ? 4 : page;
	}

	/** Ice Racer's chase was on last frame (the display turns to its page when one starts). */
	private static boolean wasChasing;

	private static final Baked SPEED_DIAL = new Baked("speed_dial");
	private static final Baked RPM_DIAL = new Baked("rpm_dial");
	private static final Baked PANEL = new Baked("panel");
	private static final Baked PANEL_RED = new Baked("panel_red");
	private static final Baked PANEL_PURPLE = new Baked("panel_purple");
	private static final Baked PANEL_CYAN = new Baked("panel_cyan");
	private static final Baked PILL = new Baked("pill");
	private static final Baked DRIFT_DOT = new Baked("drift_dot");
	private static final Baked G_DOT = new Baked("g_dot");
	/** Drift marker position, smoothed (-1 .. 1 of the bar's half width). */
	private static float driftShown;
	private static final Baked ARROW_L = new Baked("page_left");
	private static final Baked ARROW_R = new Baked("page_right");
	private static final Baked RADAR = new Baked("radar");
	private static final Baked MFD_SHADE = new Baked("mfd_shade");
	private static final Baked MAP = new Baked("map");
	private static final Baked ARROW = new Baked("arrow");
	private static final Baked ARROW_OTHER = new Baked("arrow_other");
	private static final Arcs ARC_WHITE = new Arcs("arc_white", Gauges.DIAL_R, Gauges.ARC_W, 0xFFF6F6F6);
	private static final Arcs ARC_RED = new Arcs("arc_red", Gauges.DIAL_R, Gauges.ARC_W, 0xFFE8302A);
	private static final Arcs SUB_ARC = new Arcs("sub_arc", Gauges.SUB_R, Gauges.SUB_W, 0xFFF6F6F6);
	private static final Arcs SUB_ARC_RED = new Arcs("sub_arc_red", Gauges.SUB_R, Gauges.SUB_W, 0xFFE8302A);
	private static final Arcs COUNT_RING = new Arcs("count_ring", 24, 1.4, 0xF0FFFFFF);

	// numbers (cap heights in units, measured on GT)
	private static final GlyphAtlas SPEED = new GlyphAtlas("speed", Labels.DIGITS, 9.0, 1.25, 0.15);
	private static final GlyphAtlas GEAR = new GlyphAtlas("gear", Labels.DIGITS, 15.0, 1.05, 0.2);
	private static final GlyphAtlas SUGGEST = new GlyphAtlas("suggest", Labels.DIGITS, 7.3, 1.1, 0);
	private static final GlyphAtlas TIMER = new GlyphAtlas("timer", Labels.DIGITS, 6.0, 1.2, 0.22);
	private static final GlyphAtlas CLOCK = new GlyphAtlas("clock", Labels.DIGITS, 5.6, 1.2, 0.25);
	private static final GlyphAtlas ODO = new GlyphAtlas("odo", Labels.UI, 3.4, 1.0, 0);
	private static final GlyphAtlas NUM = new GlyphAtlas("num", Labels.UI, 3.9, 1.0, 0);
	private static final GlyphAtlas NUM_S = new GlyphAtlas("num_s", Labels.UI, 3.3, 1.0, 0);
	private static final GlyphAtlas POS_BIG = new GlyphAtlas("pos_big", Labels.UI, 11.0, 1.0, 0.3);
	private static final GlyphAtlas POS_OF = new GlyphAtlas("pos_of", Labels.UI, 7.2, 1.0, 0.42);
	private static final GlyphAtlas COUNT = new GlyphAtlas("count", Labels.DIGITS, 16, 1.1, 0.3);

	/** Screen pixels per layout unit in the frame being drawn. */
	private static double K = 3;

	private static double[] mapFit;
	private static Track mapTrack;

	private Hud() {
	}

	private static LapConfig cfg() {
		return LapConfig.INSTANCE;
	}

	private static int alpha(int base) {
		int a = (int) Math.round((base >>> 24) * cfg().panelOpacity / 100.0);
		return (Math.clamp(a, 0, 255) << 24) | (base & 0xFFFFFF);
	}

	private static final java.util.Map<String, String> TR = new java.util.HashMap<>();
	private static String trLang = "";

	/** Translated text; plain keys are cached (they are asked for every frame), cleared when the language changes. */
	private static String tr(String key, Object... args) {
		if (args.length > 0) {
			return Component.translatable(key, args).getString();
		}
		String lang = Minecraft.getInstance().getLanguageManager().getSelected();
		if (!lang.equals(trLang)) {
			TR.clear();
			trLang = lang;
		}
		return TR.computeIfAbsent(key, kk -> Component.translatable(kk).getString());
	}

	// ------------------------------------------------------------------ drawing helpers

	/** A word / label, top of its capitals at y. */
	private static double label(GuiGraphicsExtractor g, String s, String font, double cap, float x, float y, int align, int color) {
		return Labels.draw(g, s, font, cap, x, y, align, color, K);
	}

	/** Colour between a and b, t in 0..1. */
	private static int blend(int a, int b, double t) {
		int r = 0xFF000000;
		for (int sh = 0; sh <= 16; sh += 8) {
			int ca = (a >> sh) & 0xFF, cb = (b >> sh) & 0xFF;
			r |= ((int) Math.round(ca + (cb - ca) * t) & 0xFF) << sh;
		}
		return r;
	}

	/** Same with a thin dark outline (text over the track). */
	private static double labelO(GuiGraphicsExtractor g, String s, String font, double cap, float x, float y, int align, int color) {
		return Labels.draw(g, s, font, cap, 1, 0.18, x, y, align, color, K);
	}

	/** With a thicker outline (one screen pixel more): position and lap at the top left. */
	private static double labelO2(GuiGraphicsExtractor g, String s, String font, double cap, float x, float y, int align, int color) {
		return Labels.draw(g, s, font, cap, 1, 0.18 + 1 / K, x, y, align, color, K);
	}

	/** MC-font text (only where exact size does not matter: messages, results). */
	private static void txt(GuiGraphicsExtractor g, Font f, String s, FontDescription fd, float x, float y, float sc,
			int color, int align, boolean shadow) {
		Component c = Fonts.text(s, fd);
		float w = f.width(c) * sc;
		float ox = align < 0 ? x : align == 0 ? x - w / 2 : x - w;
		g.pose().pushMatrix();
		g.pose().translate(ox, y);
		g.pose().scale(sc, sc);
		g.text(f, c, 0, 0, color, shadow);
		g.pose().popMatrix();
	}

	private static float width(Font f, String s, FontDescription fd, float sc) {
		return f.width(Fonts.text(s, fd)) * sc;
	}

	private static void fill(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color) {
		g.pose().pushMatrix();
		g.pose().scale(0.25f, 0.25f);
		g.fill(Math.round(x0 * 4), Math.round(y0 * 4), Math.round(x1 * 4), Math.round(y1 * 4), color);
		g.pose().popMatrix();
	}

	/** Horizontal band fading out to the right (GT lap list, fastest-lap band). */
	private static void fade(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color) {
		int steps = 8;
		float w = (x1 - x0) / steps;
		for (int i = 0; i < steps; i++) {
			int a = (int) ((color >>> 24) * (1 - i / (float) steps * 0.9f));
			fill(g, x0 + i * w, y0, x0 + (i + 1) * w, y1, (a << 24) | (color & 0xFFFFFF));
		}
	}

	/** Band dense at the right, fading out to the left (the fastest-lap time sits on its right end). */
	private static void fadeLeft(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color) {
		int steps = 10;
		float w = (x1 - x0) / steps;
		for (int i = 0; i < steps; i++) {
			float t = (i + 0.5f) / steps; // 0 at the left .. 1 at the right
			int a = (int) ((color >>> 24) * Math.min(1f, t * 1.6f));
			fill(g, x0 + i * w, y0, x0 + (i + 1) * w, y1, (a << 24) | (color & 0xFFFFFF));
		}
	}

	/**
	 * The racer's face from their skin (with the hat layer), where a flag would be in GT; a boat without a known
	 * player (a named boat, a ghost) gets its colour instead.
	 */
	private static void face(GuiGraphicsExtractor g, Racer r, float x, float y, float size) {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.multiplayer.PlayerInfo info = mc.getConnection() == null ? null
				: mc.getConnection().getPlayerInfo(r.id);
		if (info == null) {
			fill(g, x, y, x + size, y + size, 0xE6FFFFFF);
			fill(g, x + 0.7f, y + 0.7f, x + size - 0.7f, y + size - 0.7f, r.self ? RED : r.color);
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(size / 16f, size / 16f);
		net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, info.getSkin(), 0, 0, 16);
		g.pose().popMatrix();
	}

	/** GT's lap-list band: solid for the first half, then fading out to the right. */
	private static void band(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color) {
		float w = x1 - x0;
		float solid = w * 0.5f;
		fill(g, x0, y0, x0 + solid, y1, color);
		int steps = 8;
		float sw = (w - solid) / steps;
		for (int i = 0; i < steps; i++) {
			int a = (int) ((color >>> 24) * (1 - (i + 0.5f) / steps));
			fill(g, x0 + solid + i * sw, y0, x0 + solid + (i + 1) * sw, y1, (a << 24) | (color & 0xFFFFFF));
		}
	}

	private static void outlined(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int fillC, int line) {
		fill(g, x0, y0, x1, y1, fillC);
		fill(g, x0, y0, x1, y0 + 0.4f, line);
		fill(g, x0, y1 - 0.4f, x1, y1, line);
		fill(g, x0, y0, x0 + 0.4f, y1, line);
		fill(g, x1 - 0.4f, y0, x1, y1, line);
	}

	private static String trim(String s, String font, double cap, double maxW) {
		if (Labels.width(s, font, cap, K) <= maxW) {
			return s;
		}
		while (s.length() > 1 && Labels.width(s + ".", font, cap, K) > maxW) {
			s = s.substring(0, s.length() - 1);
		}
		return s + ".";
	}

	// ------------------------------------------------------------------ entry

	public static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (Perf.ON) {
			long t0 = System.nanoTime();
			draw(g, delta);
			Perf.hud(System.nanoTime() - t0);
		} else {
			draw(g, delta);
		}
	}

	private static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		LapConfig c = cfg();
		if (mc.player == null || !c.showHud) {
			return;
		}
		RaceSession s = RaceSession.INSTANCE;
		Entity boat = RaceSession.boatOf(mc.player);
		boolean hudOn = boat != null || s.racing() || TrackRecorder.recording;
		java.util.List<dev.laprace.net.CrewOk.Signal> oks = dev.laprace.net.CrewOk.shown();
		if (!hudOn && oks.isEmpty()) {
			return;
		}
		Font f = mc.font;
		float part = delta.getGameTimeDeltaPartialTick(false);
		float k = g.guiWidth() / GRID_W * (float) c.hudScale;
		float W = g.guiWidth() / k;
		float H = g.guiHeight() / k;
		K = Math.round(k * mc.getWindow().getGuiScale() * 100) / 100.0;

		g.pose().pushMatrix();
		g.pose().scale(k, k);
		try {
			crewOk(g, oks, H);
			if (!hudOn) {
				return;
			}
			if (s.track != null) {
				positionAndLap(g, s);
				if (c.showBoard) {
					tower(g, s, H);
				}
				if (c.showTiming) {
					lapTimer(g, s, W, part);
				}
				if (c.showMap) {
					map(g, s, W, mc);
				}
				if (c.showLapList) {
					lapList(g, s, W, H);
				}
				raceClock(g, s, H, part);
				messages(g, f, s, W);
				if (s.phase == RaceSession.Phase.COUNTDOWN) {
					countdown(g, s, W, part);
				}
				bigPopups(g, s, W);
				eventBanner(g, W);
				if (s.phase == RaceSession.Phase.FINISHED) {
					results(g, f, s, W, H);
				}
			} else {
				labelO(g, tr("laprace.hud.no_track"), Labels.UI, 3.4, 14, 14, -1, GREY);
			}
			if (TrackRecorder.recording) {
				labelO(g, "● REC " + TrackRecorder.points() + " m", Labels.UI, 4, W / 2, 60, 0,
						(s.now / 10) % 2 == 0 ? RED : WHITE);
			}
			if (boat != null && c.showSpeedo) {
				cockpit(g, s, boat, W, H, part);
			}
			cards(g, s, W, H, part);
		} finally {
			g.pose().popMatrix();
		}
	}

	/** The pit crew's: a driver of theirs said "OK" - a plain white plate on the left, fading out at the end. */
	private static void crewOk(GuiGraphicsExtractor g, java.util.List<dev.laprace.net.CrewOk.Signal> oks, float H) {
		long now = System.currentTimeMillis();
		float y = H * 0.42f;
		for (dev.laprace.net.CrewOk.Signal o : oks) {
			long age = now - o.at();
			float a = age > dev.laprace.net.CrewOk.SHOW_MS - 600 ? (dev.laprace.net.CrewOk.SHOW_MS - age) / 600f : 1;
			float in = Math.min(1, age / 150f); // slides in
			int al = Math.clamp(Math.round(a * 255), 0, 255);
			float x = 14 - (1 - in) * 30, w = 64, h = 21;
			fill(g, x, y, x + w, y + h, (Math.round(al * 0.96f) << 24) | 0xF4F4F4);
			label(g, "OK", Labels.BOLD, 8, x + 6, y + 6.5f, -1, (al << 24) | 0x111111);
			label(g, trim(o.from(), Labels.UI, 3.6, w - 30), Labels.UI, 3.6, x + 27, y + 8.7f, -1, (al << 24) | 0x333333);
			y += h + 3;
		}
	}

	// ------------------------------------------------------------------ position, lap (GT: box 24 u, "/ N" cap 7.2)

	private static void positionAndLap(GuiGraphicsExtractor g, RaceSession s) {
		Racer me = s.self;
		int total = Math.max(1, s.order.size());
		int pos = me != null ? s.positionOf(me) : 0;
		outlined(g, 14, 13, 38, 37, alpha(0x8C000000), 0x80A0A4A8);
		POS_BIG.draw(g, pos > 0 ? String.valueOf(pos) : "-", 26, 19.5f, 0, WHITE, K);
		labelO2(g, tr("laprace.hud.position"), Labels.UI, 4.2, 44, 14, -1, WHITE);
		labelO2(g, "/", Labels.UI, 7.2, 44, 25, -1, WHITE);
		POS_OF.draw(g, String.valueOf(total), 50, 25, -1, WHITE, K);

		boolean racing = s.racing();
		String lap;
		String of = "";
		if (racing) {
			int l = me == null || s.phase == RaceSession.Phase.COUNTDOWN ? 1
					: me.finished ? s.raceLaps : Math.min(Math.max(1, me.lapsDone + 1), s.raceLaps);
			lap = String.valueOf(l);
			of = String.valueOf(s.raceLaps);
			if (s.raceEndWall > 0) {
				// a race by time: the time left instead of the laps (then the last lap)
				long left = s.raceEndWall - System.currentTimeMillis();
				lap = String.valueOf(me == null ? 1 : me.lapsDone + 1);
				of = left > 0 ? ServerRace.clockOf(left / 1000) : "+1";
			}
		} else {
			lap = String.valueOf(me == null ? 1 : me.lapsDone + 1);
		}
		// GT: on the last lap the "LAP" caption becomes a red FINAL LAP tag, and it stays the whole lap
		boolean finalLap = racing && s.phase == RaceSession.Phase.RUNNING && me != null && !me.finished
				&& me.lapsDone == s.raceLaps - 1;
		if (finalLap) {
			String l = tr("laprace.hud.final_lap");
			double lw = Labels.width(l, Labels.UI, 4.2, K) + 6;
			fill(g, 97, 12, 97 + (float) lw, 20.6f, alpha(0xD8D8404C));
			label(g, l, Labels.UI, 4.2, 100, 14.2f, -1, WHITE);
		} else {
			labelO2(g, tr("laprace.hud.lap"), Labels.UI, 4.2, 99, 14, -1, WHITE);
		}
		double w = POS_OF.draw(g, lap, 99, 25, -1, WHITE, K);
		if (!of.isEmpty()) {
			labelO2(g, "/", Labels.UI, 7.2, 99 + (float) w + 2, 25, -1, WHITE);
			POS_OF.draw(g, of, 99 + (float) w + 7, 25, -1, WHITE, K);
		}
	}

	// ------------------------------------------------------------------ the tower (GT: rows 13 u, number box 11.5 u)

	private static void tower(GuiGraphicsExtractor g, RaceSession s, float H) {
		List<Racer> order = s.order;
		if (order.isEmpty()) {
			return;
		}
		float x = 14, y = 50, rowH = 12.6f, box = 11.5f, nameW = 56, flagW = 11;
		float right = x + box + 0.8f + nameW + flagW;
		double cap = 4.2;
		int selfIdx = s.self == null ? -1 : order.indexOf(s.self);
		boolean racing = s.racing() && s.phase != RaceSession.Phase.COUNTDOWN;
		List<Integer> rows = new ArrayList<>();
		int max = cfg().boardRows;
		if (order.size() <= max || selfIdx < 0) {
			for (int i = 0; i < Math.min(order.size(), max); i++) {
				rows.add(i);
			}
		} else {
			for (int i = 0; i < 3; i++) {
				rows.add(i);
			}
			int from = Math.max(3, selfIdx - 2);
			int to = Math.min(order.size() - 1, selfIdx + 2);
			if (from > 3) {
				rows.add(-1);
			}
			for (int i = from; i <= to; i++) {
				rows.add(i);
			}
		}
		for (int i : rows) {
			if (y + rowH > H - 90) {
				break;
			}
			if (i < 0) {
				fill(g, x, y + 0.6f, right, y + 1.0f, 0x80FFFFFF);
				y += 2;
				continue;
			}
			Racer r = order.get(i);
			boolean me = r == s.self;
			float ty = y + (rowH - 1 - (float) cap) / 2; // caps centred in the row
			outlined(g, x, y, x + box, y + rowH - 1, me ? 0xFFEDEDED : alpha(0x8C000000), me ? 0xFFFFFFFF : 0x70A0A4A8);
			NUM.draw(g, String.valueOf(i + 1), x + box / 2, ty, 0, me ? DARK_TEXT : WHITE, K);
			fill(g, x + box + 0.8f, y, right, y + rowH - 1, me ? 0xF0D6D8DA : alpha(0x6E000000));
			label(g, trim(r.name, Labels.UI, cap, nameW - 5), Labels.UI, cap, x + box + 3.5f, ty, -1,
					me ? DARK_TEXT : r.inView ? WHITE : DIM);
			float fx = right - flagW + 1;
			face(g, r, fx + 0.6f, y + 1.85f, rowH - 4.7f);
			if (me && i > 0) {
				String gap = racing ? s.leaderText(r) : s.gapText(r, i);
				if (!gap.isEmpty() && (gap.startsWith("+") || gap.startsWith("-"))) {
					double gw = NUM.width(gap, K) + 5;
					fill(g, right + 0.8f, y, right + 0.8f + (float) gw, y + rowH - 1, alpha(0xE6C8202A));
					NUM.draw(g, gap, right + 0.8f + (float) gw / 2, ty, 0, WHITE, K);
				}
			}
			y += rowH;
			// GT: the gap from the car ahead to us under its row, from us to the car behind under ours
			if (racing && selfIdx >= 0 && (i == selfIdx - 1 || i == selfIdx) && i + 1 < order.size()) {
				String gap = s.intervalText(order.get(i + 1), i + 1).replace("+", "");
				if (!gap.isEmpty() && Character.isDigit(gap.charAt(0))) {
					String shown = (i == selfIdx ? "- " : "+ ") + gap;
					fill(g, x + 30, y, right, y + 8.3f, alpha(0x5A000000));
					NUM_S.draw(g, shown, right - 2, y + 2.5f, 1, WHITE, K);
					y += 8.3f;
				}
			}
		}
		// fastest lap of the session ("ЛК")
		Racer fl = null;
		if (s.sessionBestLap > 0) {
			for (Racer r : order) {
				if (r.bestLap > 0 && Math.abs(r.bestLap - s.sessionBestLap) < 1e-6) {
					fl = r;
				}
			}
		}
		if (fl != null) {
			y += 8;
			if (y + 22 < H - 80) {
				float ty = y + (rowH - 1 - (float) cap) / 2;
				outlined(g, x, y, x + box, y + rowH - 1, alpha(0x8C000000), 0x70A0A4A8);
				label(g, tr("laprace.hud.fl"), Labels.UI, 3.3, x + box / 2, y + (rowH - 1 - 3.3f) / 2, 0, WHITE);
				fill(g, x + box + 0.8f, y, right, y + rowH - 1, alpha(0x6E000000));
				label(g, trim(fl.name, Labels.UI, cap, nameW - 5), Labels.UI, cap, x + box + 3.5f, ty, -1, WHITE);
				face(g, fl, right - flagW + 1.6f, y + 1.85f, rowH - 4.7f);
				y += rowH;
				// GT: dense under the time at the right, fading out to the left
				fadeLeft(g, x + box + 0.8f, y, right, y + 9, 0xE6602A9A);
				NUM.draw(g, Fmt.gt(s.sessionBestLap), right - 2, y + 2.5f, 1, WHITE, K);
			}
		}
	}

	// ------------------------------------------------------------------ lap timer (GT: 74 x 12 u, digits cap 6)

	private static void lapTimer(GuiGraphicsExtractor g, RaceSession s, float W, float part) {
		Racer me = s.self;
		float cx = W / 2;
		float y = 74;
		boolean racing = s.racing();
		double t;
		boolean practiceFlash = !racing && s.now < s.lapFlashUntil;
		if (s.phase == RaceSession.Phase.COUNTDOWN) {
			t = 0;
		} else if (practiceFlash) {
			t = s.lapFlashTime;
		} else if (me != null && me.lapStart >= 0 && !me.finished) {
			t = (s.now + part - me.lapStart) / 20.0;
		} else if (me != null && me.finished) {
			t = me.lastLap;
		} else {
			t = 0;
		}
		float w = 74, h = 12.3f;
		PILL.ensure(K + "|" + w, () -> Gauges.pill(K, w, h))
				.draw(g, cx - w / 2 - (float) (1 / K), y - (float) (1 / K), PILL.w / (float) K, PILL.h / (float) K);
		TIMER.draw(g, Fmt.gt(t), cx, y + (h - 6.0f) / 2, 0, WHITE, K);
		// over the timer: FINAL LAP once as the last lap begins (2 s, then it fades); in or out of the pit, GT's
		// "pit lane" / "out lap"
		long fa = s.finalLapTick >= 0 ? s.now - s.finalLapTick : -1;
		if (racing && fa >= 0 && fa < 52) {
			float fade = fa < 40 ? 1f : 1f - (fa - 40 + part) / 12f;
			int a = Math.clamp((int) (fade * 255), 0, 255);
			String l = tr("laprace.hud.final_lap");
			double lw = Labels.width(l, Labels.BOLD, 3.2, K) + 6;
			fill(g, cx - (float) lw / 2, y - 7.5f, cx + (float) lw / 2, y - 1, ((a * 0xF0 / 255) << 24) | 0xC8202A);
			label(g, l, Labels.BOLD, 3.2, cx, y - 5.85f, 0, (a << 24) | 0xFFFFFF);
		} else if (me != null && me.inPit) {
			labelO(g, tr("laprace.hud.pit_lane"), Labels.UI, 3.4, cx, y - 5.8f, 0, WHITE);
		} else if (me != null && me.pitThisLap && !me.finished) {
			labelO(g, tr("laprace.hud.out_lap"), Labels.UI, 3.4, cx, y - 5.8f, 0, WHITE);
		}
		if (!practiceFlash) {
			ghostGap(g, cx - w / 2 - 1, y, h);
		}
		if (practiceFlash) {
			String b = tr(s.lapFlashPb ? "laprace.hud.best" : "laprace.hud.last");
			double bw = Labels.width(b, Labels.BOLD, 3.4, K) + 8;
			fill(g, cx - w / 2 - (float) bw - 1, y, cx - w / 2 - 1, y + h, s.lapFlashPb ? 0xF02DB84B : 0xF0606468);
			label(g, b, Labels.BOLD, 3.4, cx - w / 2 - 1 - (float) bw / 2, y + (h - 3.4f) / 2, 0, WHITE);
		}
		// the delta: GT has no running delta, only the split at each sector, shown for a few seconds
		RaceSession.Popup split = null;
		for (RaceSession.Popup p : s.popups) {
			if (!p.big()) {
				split = p;
			}
		}
		if (split != null) {
			String d = split.value();
			int dc = d.startsWith("-") ? BLUE : 0xFFC8202A;
			if (split.color() == PURPLE) {
				dc = 0xFF7A3BAA;
			}
			double dw = Math.max(26, NUM.width(d, K) + 8);
			fill(g, cx + w / 2 + 1, y, cx + w / 2 + 1 + (float) dw, y + h, (dc & 0xFFFFFF) | 0xE8000000);
			NUM.draw(g, d, cx + w / 2 + 1 + (float) dw / 2, y + (h - 3.9f) / 2, 0, WHITE, K);
		}
	}

	// ------------------------------------------------------------------ map, lap list (GT: rows 11.2 u, box 14 u)

	private static void map(GuiGraphicsExtractor g, RaceSession s, float W, Minecraft mc) {
		Track t = s.track;
		if (t != mapTrack) {
			mapTrack = t;
			mapFit = Gauges.mapFit(t);
		}
		double px = K;
		float mx = W - 112, my = 6;
		int sectors = s.sectorCount();
		PitLane lane = PitLane.of(t);
		java.util.List<double[]> pit = lane == null ? null : lane.pts;
		MAP.ensure(t.name + "|" + px + "|" + sectors + "|" + (pit == null ? 0 : pit.size()), () -> Gauges.map(px, t, sectors, mapFit, pit))
				.draw(g, mx, my, Gauges.MAP_W, Gauges.MAP_H);
		double sc = mapFit[0], ox = mapFit[1], oy = mapFit[2];
		ARROW_OTHER.ensure(px, () -> Gauges.arrow(px, 0xFF4AA8FF));
		for (Racer r : s.racers.values()) {
			if (r.self || !r.inView) {
				continue;
			}
			float x = (float) (mx + ox + (r.px - t.minX) * sc), y = (float) (my + oy + (r.pz - t.minZ) * sc);
			double dir = Math.toDegrees(Math.atan2(r.pz - r.prevZ, r.px - r.prevX));
			ARROW_OTHER.drawRotated(g, x, y, (float) dir, (float) (4 * px), (float) (3 * px), (float) (px * 1.25));
		}
		Entity boat = mc.player == null ? null : RaceSession.boatOf(mc.player);
		if (boat != null) {
			float x = (float) (mx + ox + (boat.getX() - t.minX) * sc), y = (float) (my + oy + (boat.getZ() - t.minZ) * sc);
			ARROW.ensure(px, () -> Gauges.arrow(px, 0xFFE8302A))
					.drawRotated(g, x, y, (float) heading(boat), (float) (4 * px), (float) (3 * px), (float) px);
		}
	}

	private static double heading(Entity boat) {
		Vec3 v = boat.getDeltaMovement();
		return Math.hypot(v.x, v.z) > 0.05 ? Math.toDegrees(Math.atan2(v.z, v.x))
				: Math.toDegrees(Math.atan2(Math.cos(Math.toRadians(boat.getYRot())), -Math.sin(Math.toRadians(boat.getYRot()))));
	}

	private static void lapList(GuiGraphicsExtractor g, RaceSession s, float W, float H) {
		Racer me = s.self;
		if (me == null) {
			return;
		}
		float x = W - 72, y = 100, rowH = 11.2f, box = 13.5f, bandW = 48;
		int lap = me.lapsDone + 1;
		if (s.racing()) {
			lap = Math.min(lap, Math.max(1, s.raceLaps));
		}
		List<Double> times = me.lapTimes;
		int rows = Math.max(1, Math.min(Math.min(times.size() + 1, (int) ((H - 190 - y) / rowH)), 10));
		for (int k = 0; k < rows; k++) {
			if (me.finished && k == 0) {
				continue;
			}
			int n = lap - k;
			double t = k == 0 ? -1 : times.get(times.size() - k);
			boolean best = t > 0 && Math.abs(t - me.bestLap) < 1e-9;
			float ty = y + (rowH - 1 - 3.9f) / 2;
			fill(g, x, y, x + box, y + rowH - 1, 0xE6D8DADC);
			NUM.draw(g, String.valueOf(n), x + box / 2, ty, 0, DARK_TEXT, K);
			// GT: every lap has a band behind its time fading out to the right - blue for the best, translucent
			// black for the others (the running lap has none)
			band(g, x + box + 0.6f, y, x + box + bandW, y + rowH - 1, best ? 0xE61E6FD9 : alpha(0xA6000000));
			NUM.draw(g, Fmt.gt(t), x + box + 4, ty, -1, WHITE, K);
			if (k > 0 && times.size() - k < me.lapPit.size() && me.lapPit.get(times.size() - k)) {
				// right after the time, inside its band
				labelO(g, tr("laprace.hud.pit_mark"), Labels.BOLD, 3.6, x + box + 4 + (float) NUM.width(Fmt.gt(t), K) + 3, ty + 0.15f, -1, WHITE);
			}
			y += rowH;
		}
	}

	private static void raceClock(GuiGraphicsExtractor g, RaceSession s, float H, float part) {
		if (!s.racing() || s.phase == RaceSession.Phase.COUNTDOWN) {
			return;
		}
		Racer me = s.self;
		double end = me != null && me.finished ? me.finishTick : s.now + part;
		CLOCK.draw(g, Fmt.gt((end - s.goTick) / 20.0), 14, H - 14, -1, WHITE, K);
	}

	// ------------------------------------------------------------------ the cockpit

	private static void cockpit(GuiGraphicsExtractor g, RaceSession s, Entity boat, float W, float H, float part) {
		LapConfig c = cfg();
		double px = K;
		float cx = W / 2;
		float base = H - 360 - LIFT;
		double rate = BoatData.unitRate();
		String unit = unitLabel();
		double speed = BoatData.speed() * rate;
		int maxShown = niceMax(c.speedoMaxKmh / 3.6 * rate);
		int step = maxShown > 200 ? 40 : maxShown > 100 ? 20 : 10;
		double rpm = Engine.rpm(part);

		// ---- the panel (measured: bar top at 264.6 u in GT, panel 136 u wide)
		float pw = Gauges.PANEL_W, ph = Gauges.PANEL_H;
		float barTop = base + 264.6f;
		float px0 = cx - pw / 2, py = barTop - Gauges.PANEL_TOP_PAD;
		PANEL.ensure(px, () -> Gauges.panel(px, 0)).draw(g, px0, py, pw, ph);
		boolean limiter = rpm >= Engine.MAX_RPM - 150 && Engine.gear == 8;
		boolean shift = rpm >= Engine.SHIFT_UP_RPM - 900 && Engine.gear < 8;
		if (limiter) {
			PANEL_CYAN.ensure(px, () -> Gauges.panel(px, 0xFF6EE6F0)).draw(g, px0, py, pw, ph);
		} else if (shift) {
			PANEL_PURPLE.ensure(px, () -> Gauges.panel(px, 0xFFD69CEB)).draw(g, px0, py, pw, ph);
		} else {
			PANEL_RED.ensure(px, () -> Gauges.panel(px, 0xFFE0282E))
					.drawPart(g, px0, py, pw, ph, Gauges.revCut(rpm / Engine.MAX_RPM));
		}
		// the red dot over the bar: GT shows the steering there; we show the drift angle - the bow's angle off the
		// way the boat goes, signed (right of it: right), 90 degrees at the bar's end; smoothed so it does not twitch
		float want = (float) Math.clamp(BoatData.driftSigned / 90.0, -1, 1);
		driftShown += (want - driftShown) * 0.25f;
		float dotHalf = pw / 2 - Gauges.REV_X0 - 2;
		float dxL = pw / 2 + driftShown * dotHalf; // along the panel, from its left edge
		float dotY = py + (float) Gauges.panelTop(dxL) - 4.3f;
		DRIFT_DOT.ensure(px, () -> Gauges.dot(px, 0xFFE8302A)).draw(g, px0 + dxL - 1.5f, dotY - 1.5f, 3, 3);
		float div = px0 + pw * Gauges.PANEL_SPLIT;
		// speed: right edge 6.4 u left of the divider, caps 11.6 u under the bar top; unit under it
		SPEED.draw(g, String.valueOf((int) Math.round(speed)), div - 6.4f, barTop + 11.6f, 1, WHITE, K);
		labelO(g, unit, Labels.UI, 3.0, div - 6.4f, barTop + 24.4f, 1, 0xFFDADADA);
		// gear: centre 15.2 u right of the divider, caps 9.9 u under the bar top
		GEAR.draw(g, String.valueOf(Engine.gear), div + 15.2f, barTop + 9.9f, 0, WHITE, K);
		// suggested gear: red box 14.6 x 11.1 u, its digit dark; AT / AP centred under it
		float bx = div + 29.6f;
		if (Engine.suggestGear > 0) {
			fill(g, bx + 0.6f, barTop + 10.3f, bx + 14.0f, barTop + 21.4f, 0xF5E8402F);
			fill(g, bx, barTop + 10.9f, bx + 14.6f, barTop + 20.8f, 0xF5E8402F);
			SUGGEST.draw(g, String.valueOf(Engine.suggestGear), bx + 7.3f, barTop + 12.2f, 0, 0xFF111111, K);
		}
		labelO(g, tr("laprace.hud.at"), Labels.UI, 3.0, bx + 7.3f, barTop + 25.3f, 0, 0xFFDADADA);
		// GT writes AUTOPILOT while it drives, whatever it is doing (Ice Racer's autopilot too; its chase: CHASE)
		boolean irChase = IceRacerLink.chasing();
		if (Cruise.enabled || irChase || IceRacerLink.autopilot()) {
			String ap = tr(irChase && !Cruise.enabled ? "laprace.hud.chase" : "laprace.hud.autopilot");
			// GT: bold white, pulsing - opacity 100% -> 0% over half a second, back to 100% over the next half
			// (linear, like two keyframes), every frame so it is smooth
			double cyc = ((s.now + part) % 20) / 10.0; // 0 .. 2
			float a = (float) Math.abs(1 - cyc);
			int alpha = Math.clamp(Math.round(a * 255), 0, 255);
			if (alpha > 3) {
				Labels.draw(g, ap, Labels.BOLD, 4.8, 1, 0.22, cx, barTop - 16, 0, (alpha << 24) | 0xFFFFFF, K);
			}
		}

		// ---- centre display: title 40 u under the bar top, page arrows in dark discs at +-46 u
		float ty = barTop + 38;
		float shW = 128, shH = (H - 22) - ty + 16;
		MFD_SHADE.ensure(px + "|" + shH, () -> Gauges.shade(px, shW, shH, 0.55)).draw(g, cx - shW / 2, ty - 9, shW, shH);
		int pages = mfdPages();
		if (irChase && !wasChasing) {
			c.mfdPage = 3; // a chase started: its page
		}
		wasChasing = irChase;
		int page = Math.floorMod(c.mfdPage, pages);
		String title = tr(switch (pageKind(page)) {
			case 0 -> "laprace.hud.course_map";
			case 1 -> "laprace.hud.radar";
			case 2 -> "laprace.hud.session_best";
			case 3 -> "laprace.hud.fuel_page";
			default -> "laprace.hud.chase_page";
		});
		labelO(g, title, Labels.UI, 4.3, cx, ty - 2.15f, 0, WHITE);
		float ar = 3.4f + (float) (1 / px);
		ARROW_L.ensure(px, () -> Gauges.pageArrow(px, false)).draw(g, cx - 46 - ar, ty - ar, ar * 2, ar * 2);
		ARROW_R.ensure(px, () -> Gauges.pageArrow(px, true)).draw(g, cx + 46 - ar, ty - ar, ar * 2, ar * 2);
		float mfdTop = ty + 5, mfdBottom = H - 22;
		switch (pageKind(page)) {
			case 0 -> course(g, s, boat, cx, mfdTop, mfdBottom);
			case 1 -> {
				scissor(g, cx - 48, mfdTop, cx + 48, mfdBottom);
				radar(g, s, boat, cx, mfdTop + (mfdBottom - mfdTop) * 0.42f);
				g.disableScissor();
			}
			case 2 -> sessionTable(g, s, cx, mfdTop + 1);
			case 3 -> fuelPage(g, s, cx, mfdTop + 2);
			default -> chasePage(g, s, cx, mfdTop + 2);
		}
		for (int i = 0; i < pages; i++) {
			float dx = cx + (i - (pages - 1) / 2f) * 5;
			fill(g, dx - 0.8f, mfdBottom + 1, dx + 0.8f, mfdBottom + 2.6f, i == page ? WHITE : 0x60FFFFFF);
		}

		// ---- dials: centres at +-106 u, 320 u down in GT, radius 37
		float dl = cx - 106, dr = cx + 106, dy = base + 320;
		float half = Gauges.DIAL / 2;
		SPEED_DIAL.ensure(px + unit + maxShown, () -> Gauges.speedDial(px, unit, maxShown, step))
				.draw(g, dl - half, dy - half, Gauges.DIAL, Gauges.DIAL);
		RPM_DIAL.ensure(px, () -> Gauges.rpmDial(px, Engine.MAX_RPM, Engine.SHIFT_UP_RPM))
				.draw(g, dr - half, dy - half, Gauges.DIAL, Gauges.DIAL);
		ARC_WHITE.draw(g, dl, dy, Gauges.angle(0), Gauges.angle(speed / maxShown), px);
		(rpm >= Engine.SHIFT_UP_RPM - 900 ? ARC_RED : ARC_WHITE).draw(g, dr, dy, Gauges.angle(0),
				Gauges.angle(rpm / Engine.MAX_RPM), px);
		double fuelFrac = Engine.fuelOn() ? Engine.fuel / Engine.MAX_FUEL : 1;
		(fuelFrac < 0.2 ? SUB_ARC_RED : SUB_ARC).draw(g, dl, dy + Gauges.SUB_DY, 180, 180 + 180 * fuelFrac, px);
		// G-meter: the dot where the force is felt - braking pushes it up, speeding up down, a right turn left;
		// the outer ring is 2 g
		double gx = -BoatData.gLat, gy = -BoatData.gLong;
		double gm = Math.hypot(gx, gy);
		if (gm > 2) {
			gx *= 2 / gm;
			gy *= 2 / gm;
		}
		float gdx = (float) (dr + gx / 2 * Gauges.GM_R), gdy = (float) (dy + Gauges.GM_DY + gy / 2 * Gauges.GM_R);
		G_DOT.ensure(px, () -> Gauges.dot(px, 0xFFF6F6F6)).draw(g, gdx - 1.5f, gdy - 1.5f, 3, 3);
		String odo = String.format(Locale.ROOT, "%08.1f", Engine.odometerKm);
		odo = odo.substring(0, 3) + "," + odo.substring(3);
		ODO.draw(g, odo, dl, dy + Gauges.ODO_DY + (Gauges.ODO_H - 3.4f) / 2, 0, WHITE, K);

		// ---- brake (left) and throttle (right) bars beside the centre display
		if (c.showInputs) {
			pedal(g, cx - 62, base + 308, Engine.brake, false);
			pedal(g, cx + 57.5f, base + 308, Engine.throttle, true);
		}

		// ---- lamps under the speedometer, ping and g under the tachometer
		boolean fuelLow = Engine.fuelOn() && Engine.fuel < Engine.MAX_FUEL * 0.2;
		int lamp80 = Cruise.enabled ? (Cruise.recovering ? YELLOW : GREEN) : 0;
		int lampF = fuelLow && (s.now / 10) % 2 == 0 ? 0xFFE8302A : 0;
		if (!IceRacerLink.present()) {
			lamp(g, dl - 8, dy + 26, "80", lamp80);
			lamp(g, dl + 8, dy + 26, "F", lampF);
		} else {
			// a row in the dial's open bottom, a little lower (clear of its 0 and top-speed numbers): AP, 80 (, JAM) -
			// no F: with the autopilot there is no fuel
			boolean jam = dev.laprace.net.Backend.killACar();
			int n = jam ? 3 : 2;
			float ls = 10.4f, x0 = dl - ls * (n - 1) / 2, ly = dy + 28;
			lamp(g, x0, ly, "AP", irLamp(s));
			lamp(g, x0 + ls, ly, "80", lamp80);
			if (jam) {
				lamp(g, x0 + ls * 2, ly, "JAM", jamLamp(s, part));
			}
		}
		// total g centred under the G-meter: white up to 5 g, turning yellow by 10 g and red by 20 g
		double gTot = Math.hypot(BoatData.gLat, BoatData.gLong);
		int gCol = gTot <= 5 ? WHITE
				: gTot <= 10 ? blend(WHITE, YELLOW, (gTot - 5) / 5)
				: blend(YELLOW, RED, Math.min(1, (gTot - 10) / 10));
		labelO(g, String.format(Locale.ROOT, "%.1f G", gTot), Labels.UI, 3.8, dr, dy + Gauges.GM_DY + Gauges.GM_R + 1.6f, 0, gCol);
		int ping = BoatData.ping();
		if (ping >= 0) {
			labelO(g, ping + " ms", Labels.UI, 2.4, dr, dy + Gauges.GM_DY + Gauges.GM_R + 7.0f, 0, ping < 150 ? GREEN : ping < 300 ? YELLOW : RED);
		}
	}

	private static String unitLabel() {
		String u = BoatData.unitName();
		return switch (u) {
			case "km/h" -> tr("laprace.unit.kmh");
			case "mph" -> tr("laprace.unit.mph");
			case "m/s" -> tr("laprace.unit.ms");
			default -> u;
		};
	}

	/** GT pedal bar: a bar with a bracket line on its outer side, filled from the bottom. */
	private static void pedal(GuiGraphicsExtractor g, float x, float y, double v, boolean right) {
		float h = 20, w = 4.5f;
		int line = 0xD0FFFFFF;
		float bx = right ? x + w + 1.2f : x - 1.2f;
		fill(g, bx - 0.3f, y, bx + 0.3f, y + h, line);
		float tick = right ? -1.5f : 1.5f;
		fill(g, Math.min(bx, bx + tick), y, Math.max(bx, bx + tick), y + 0.6f, line);
		fill(g, Math.min(bx, bx + tick), y + h - 0.6f, Math.max(bx, bx + tick), y + h, line);
		fill(g, x, y, x + w, y + h, 0x40000000);
		float fh = (float) (h * Math.clamp(v, 0, 1));
		if (fh > 0) {
			fill(g, x, y + h - fh, x + w, y + h, 0xF0F2F2F2);
		}
	}

	/** Ice Racer's lamp: green driving or chasing, yellow off the ice or the target out of sight, red blinking recording. */
	private static int irLamp(RaceSession s) {
		if (IceRacerLink.recording()) {
			return (s.now / 10) % 2 == 0 ? 0xFFE8302A : 0;
		}
		if (IceRacerLink.chasing()) {
			return IceRacerLink.state() == 4 ? YELLOW : GREEN;
		}
		if (IceRacerLink.autopilot()) {
			return IceRacerLink.recovering() ? YELLOW : GREEN;
		}
		return 0;
	}

	/** The jammer's lamp (kill-a-car): red while it jams, blinking faster the harder; dim when too far to reach. */
	private static int jamLamp(RaceSession s, float part) {
		if (dev.laprace.net.Backend.jamTarget() == null) {
			return 0;
		}
		double lv = dev.laprace.net.Backend.jamLevel();
		if (lv <= 0) {
			return 0x70E8302A;
		}
		double period = 20 - 14 * lv; // ticks: 1 s far ... 0.3 s close up
		return ((s.now + part) % period) < period / 2 ? 0xFFE8302A : 0x70E8302A;
	}

	/** The chase page (Ice Racer): whom, how it goes, the gap and the distance, the jammer, the lap search. */
	private static void chasePage(GuiGraphicsExtractor g, RaceSession s, float cx, float y) {
		String who = IceRacerLink.target();
		if (who == null) {
			labelO(g, tr("laprace.hud.chase_none"), Labels.UI, 3.4, cx, y + 6, 0, GREY);
			labelO(g, tr("laprace.hud.chase_hint"), Labels.UI, 2.6, cx, y + 12, 0, GREY);
		} else {
			labelO(g, who, Labels.BOLD, 4.3, cx, y + 1, 0, WHITE);
			int st = IceRacerLink.state();
			String[] keys = {"catch", "behind", "beside", "ahead", "lost", "far"};
			int[] cols = {YELLOW, GREEN, GREEN, 0xFF6EE6F0, GREY, GREY};
			labelO(g, tr("laprace.hud.chase_" + keys[st]), Labels.UI, 3.0, cx, y + 7.5f, 0, cols[st]);
			double gap = IceRacerLink.gapMs(), dist = IceRacerLink.distance();
			if (st <= 3 && !Double.isNaN(gap)) {
				NUM.draw(g, String.format(Locale.ROOT, "%+.2f", gap / 1000), cx - 2, y + 12, 1, WHITE, K);
				labelO(g, dist >= 0 ? String.format(Locale.ROOT, "%.0f m", dist) : "", Labels.UI, 2.8, cx + 2, y + 14, -1, GREY);
			} else if (dist >= 0) {
				NUM.draw(g, String.format(Locale.ROOT, "%.0f m", dist), cx, y + 12, 0, WHITE, K);
			}
		}
		float by = y + 22;
		if (dev.laprace.net.Backend.jamTarget() != null) {
			double lv = dev.laprace.net.Backend.jamLevel();
			labelO(g, tr("laprace.hud.jam"), Labels.UI, 2.6, cx - 40, by, -1, 0xFFE8302A);
			fill(g, cx - 22, by + 0.6f, cx + 30, by + 3.2f, 0x50000000);
			fill(g, cx - 22, by + 0.6f, cx - 22 + (float) (52 * lv), by + 3.2f, 0xFFE8302A);
			labelO(g, Math.round(lv * 100) + "%", Labels.UI, 2.6, cx + 40, by, 1, WHITE);
			by += 6;
		}
		double sr = IceRacerLink.search();
		if (sr >= 0) {
			labelO(g, tr("laprace.hud.lap_search", Math.round(sr * 100)), Labels.UI, 2.6, cx, by, 0, YELLOW);
		}
	}

	private static void lamp(GuiGraphicsExtractor g, float x, float y, String l, int color) {
		outlined(g, x - 4.5f, y - 3.5f, x + 4.5f, y + 3.5f, color == 0 ? 0x30000000 : color, 0x90FFFFFF);
		label(g, l, Labels.BOLD, 2.6, x, y - 1.3f, 0, color == 0 ? 0xA0FFFFFF : WHITE);
	}

	private static int niceMax(double v) {
		int[] nice = {40, 60, 80, 100, 120, 160, 200, 240, 280, 320, 360, 400, 500};
		for (int n : nice) {
			if (n >= v - 1e-6) {
				return n;
			}
		}
		return (int) Math.ceil(v / 100) * 100;
	}

	// ------------------------------------------------------------------ centre display pages

	private static final Baked PIT_COURSE = new Baked("pit_course");
	private static final double[] PIT_BOX = new double[2];

	/**
	 * A clip rectangle given in the HUD's units, kept on the screen: the game crashes ("Scissor ... out of bounds")
	 * if a scissor reaches past the window by even a pixel - which a small window or a big HUD scale can make happen.
	 */
	private static void scissor(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1) {
		org.joml.Vector2f a = g.pose().transformPosition(x0, y0, new org.joml.Vector2f());
		org.joml.Vector2f b = g.pose().transformPosition(x1, y1, new org.joml.Vector2f());
		int W = g.guiWidth(), H = g.guiHeight();
		int l = Math.clamp((int) Math.ceil(Math.min(a.x, b.x)), 0, W), r = Math.clamp((int) Math.floor(Math.max(a.x, b.x)), 0, W);
		int t = Math.clamp((int) Math.ceil(Math.min(a.y, b.y)), 0, H), d = Math.clamp((int) Math.floor(Math.max(a.y, b.y)), 0, H);
		g.pose().pushMatrix();
		g.pose().identity();
		g.enableScissor(l, t, Math.max(l, r), Math.max(t, d));
		g.pose().popMatrix();
	}

	private static void course(GuiGraphicsExtractor g, RaceSession s, Entity boat, float cx, float top, float bottom) {
		double px = K;
		double head = heading(boat);
		double sc = Gauges.COURSE_SCALE;
		float myX = cx, myY = top + (bottom - top) * 0.72f;
		float rot = (float) (-90 - head);
		scissor(g, cx - 46, top, cx + 46, bottom);
		// the real road around us (its true width, read from the world), turned so that our travel points up
		TrackScan.draw(g, boat, myX, myY, rot, sc, 0x9EF4F4F4);
		// the pit lane (the road scan only covers the track itself): a narrower grey road
		PitLane lane = PitLane.of(s.track);
		if (lane != null) {
			double[] box = PIT_BOX;
			PIT_COURSE.ensure(lane.track + "|" + px + "|" + lane.pts.size(), () -> Gauges.pitCourse(px, lane.pts, box));
			float pivX = (float) ((Gauges.COURSE_PAD + (boat.getX() - box[0]) * sc) * px);
			float pivY = (float) ((Gauges.COURSE_PAD + (boat.getZ() - box[1]) * sc) * px);
			int a = 0x70;
			PIT_COURSE.drawRotated(g, myX, myY, rot, pivX, pivY, (float) px, (a << 24) | 0xB0B4B8);
		}
		ARROW_OTHER.ensure(px, () -> Gauges.arrow(px, 0xFF4AA8FF));
		double r = Math.toRadians(rot);
		double cos = Math.cos(r), sin = Math.sin(r);
		for (Racer o : s.racers.values()) {
			if (o.self || !o.inView) {
				continue;
			}
			double dx = (o.px - boat.getX()) * sc, dz = (o.pz - boat.getZ()) * sc;
			float ox = (float) (myX + dx * cos - dz * sin), oy = (float) (myY + dx * sin + dz * cos);
			double dir = Math.toDegrees(Math.atan2(o.pz - o.prevZ, o.px - o.prevX)) + rot;
			ARROW_OTHER.drawRotated(g, ox, oy, (float) dir, (float) (4 * px), (float) (3 * px), (float) (px * 1.1));
		}
		ARROW.ensure(px, () -> Gauges.arrow(px, 0xFFE8302A)).drawRotated(g, myX, myY, -90, (float) (4 * px),
				(float) (3 * px), (float) (px * 0.9));
		g.disableScissor();
	}

	private static void radar(GuiGraphicsExtractor g, RaceSession s, Entity boat, float cx, float cy) {
		double px = K;
		float r = Gauges.RADAR_R;
		float size = r * 2 + 2;
		double headDeg = heading(boat);
		// the real road around us, full width, turned so that our travel points up
		TrackScan.draw(g, boat, cx, cy, (float) (-90 - headDeg), Gauges.RADAR_SCALE, 0x47FFFFFF);
		RADAR.ensure(px, () -> Gauges.radar(px)).draw(g, cx - size / 2, cy - size / 2, size, size);
		double rot = Math.toRadians(-90 - headDeg);
		double cos = Math.cos(rot), sin = Math.sin(rot);
		double range = Gauges.RADAR_RANGE;
		ARROW_OTHER.ensure(px, () -> Gauges.arrow(px, 0xFF4AA8FF));
		for (Racer o : s.racers.values()) {
			if (o.self || !o.inView) {
				continue;
			}
			double dx = o.px - boat.getX(), dz = o.pz - boat.getZ();
			double rx = dx * cos - dz * sin, ry = dx * sin + dz * cos;
			if (Math.hypot(rx, ry) > range) {
				continue;
			}
			float ox = (float) (cx + rx / range * r), oy = (float) (cy + ry / range * r);
			double dir = Math.toDegrees(Math.atan2(o.pz - o.prevZ, o.px - o.prevX)) - 90 - headDeg;
			ARROW_OTHER.drawRotated(g, ox, oy, (float) dir, (float) (4 * px), (float) (3 * px), (float) (px * 2.1));
		}
		// GT: the car marker is about half as wide as the road band
		ARROW.ensure(px, () -> Gauges.arrow(px, 0xFFE8302A)).drawRotated(g, cx, cy, -90, (float) (4 * px), (float) (3 * px),
				(float) (px * 2.1));
	}

	private static void sessionTable(GuiGraphicsExtractor g, RaceSession s, float cx, float y) {
		Racer me = s.self;
		float x = cx - 46, w = 92, rowH = 7.3f;
		int n = s.sectorCount();
		for (int q = 0; q < Math.min(n, 3); q++) {
			int i = n - 1 - q;
			double st = -1;
			if (me != null && me.sectors != null) {
				if (me.sectorReached > i && me.sectors[i] > 0) {
					st = me.sectors[i];
				} else if (me.lastSectors != null) {
					st = me.lastSectors[i];
				}
			}
			String delta = "";
			int dc = 0;
			if (st > 0 && me.bestSectors != null && me.bestSectors[i] > 0) {
				double d = st - me.bestSectors[i];
				if (Math.abs(d) > 1e-6) {
					delta = Fmt.delta(d);
					dc = d < 0 ? 0xE01E6FD9 : 0xE0C8202A;
				}
			}
			boolean purple = st > 0 && s.sessionBestSectors != null && i < s.sessionBestSectors.length
					&& st <= s.sessionBestSectors[i] + 1e-6;
			row(g, x, y, w, rowH, "S" + (i + 1), Fmt.gt(st), purple ? 0xFFC58BFF : WHITE, delta, dc, false);
			y += rowH;
		}
		double last = me == null ? -1 : me.lastLap;
		String ld = "";
		int ldc = 0;
		if (me != null && last > 0 && me.bestLap > 0 && Math.abs(last - me.bestLap) > 1e-6) {
			ld = Fmt.delta(last - me.bestLap);
			ldc = 0xE0C8202A;
		}
		row(g, x, y, w, rowH, tr("laprace.hud.last"), Fmt.gt(last), WHITE, ld, ldc, false);
		y += rowH;
		double best = me != null && me.bestLap > 0 ? me.bestLap : s.pbLap;
		row(g, x, y, w, rowH, tr("laprace.hud.best"), Fmt.gt(best), 0xFF6BE58A, "", 0, true);
		y += rowH;
		row(g, x, y, w, rowH, tr("laprace.hud.opt"), Fmt.gt(me == null ? -1 : me.optimalLap()), WHITE, "", 0, false);
	}

	private static void row(GuiGraphicsExtractor g, float x, float y, float w, float h, String l, String time,
			int timeColor, String delta, int deltaColor, boolean green) {
		float ty = y + (h - 0.8f - 3.3f) / 2;
		// GT: grey caption box, dark time cell (the same darkness as the lap list's bands)
		fill(g, x, y, x + 18, y + h - 0.8f, green ? 0xE62DB84B : alpha(0xB84A4E52));
		label(g, l, Labels.UI, 3.0, x + 9, y + (h - 0.8f - 3.0f) / 2, 0, WHITE);
		fill(g, x + 18.5f, y, x + 58, y + h - 0.8f, alpha(0xA6000000));
		if (delta.isEmpty()) {
			// no delta: the dark cell carries on, fading out, where the delta box would be
			band(g, x + 58, y, x + w, y + h - 0.8f, alpha(0x70000000));
		}
		NUM_S.draw(g, time, x + 38.25f, ty, 0, timeColor, K);
		if (!delta.isEmpty()) {
			fill(g, x + 58.5f, y, x + w, y + h - 0.8f, deltaColor);
			NUM_S.draw(g, delta, x + 75.25f, ty, 0, WHITE, K);
		}
	}

	private static void fuelPage(GuiGraphicsExtractor g, RaceSession s, float cx, float y) {
		if (!Engine.fuelOn()) {
			labelO(g, tr("laprace.hud.fuel_off"), Labels.UI, 3.2, cx, y + 12, 0, GREY);
			return;
		}
		double frac = Engine.fuel / Engine.MAX_FUEL;
		fill(g, cx - 38, y + 3, cx + 38, y + 7, 0x50000000);
		fill(g, cx - 38, y + 3, cx - 38 + (float) (76 * frac), y + 7, frac < 0.2 ? 0xFFE8302A : 0xFFF2F2F2);
		NUM.draw(g, Math.round(frac * 100) + "%", cx, y + 10, 0, WHITE, K);
		String laps = tr("laprace.hud.fuel_laps", s.fuelPerLap > 0
				? String.format(Locale.ROOT, "%.1f", Engine.fuel / s.fuelPerLap) : "--");
		labelO(g, laps, Labels.UI, 3.2, cx, y + 18, 0, WHITE);
		if (s.fuelPerLap > 0) {
			labelO(g, tr("laprace.hud.fuel_per_lap", String.format(Locale.ROOT, "%.0f", s.fuelPerLap)), Labels.UI, 2.8,
					cx, y + 25, 0, GREY);
		}
	}

	// ------------------------------------------------------------------ side notifications

	/**
	 * Status cards at the side of the screen (instead of chat lines or Minecraft's top-right toasts): a dark card
	 * with a red edge, sliding in from the screen's edge, fading out at the end, newest on top.
	 */
	private static void cards(GuiGraphicsExtractor g, RaceSession s, float W, float H, float part) {
		if (Notify.CARDS.isEmpty()) {
			return;
		}
		boolean right = cfg().notifyWhere != Notify.Where.LEFT;
		double cap = 3.6;
		float h = 11, gap = 2, maxW = 150;
		float y = right ? H * 0.42f : H * 0.62f;
		for (int i = Notify.CARDS.size() - 1; i >= 0; i--) {
			Notify.Card c = Notify.CARDS.get(i);
			float age = s.now - c.born() + part;
			float in = Math.min(1f, age / 5f);
			float slide = 1 - (1 - in) * (1 - in) * (1 - in);
			float out = Math.clamp((Notify.LIFE - age) / 12f, 0f, 1f);
			if (out <= 0) {
				continue;
			}
			String text = trim(c.text(), Labels.UI, cap, maxW - 12);
			float w = (float) Labels.width(text, Labels.UI, cap, K) + 12;
			float x = right ? W - 6 - w + (1 - slide) * (w + 8) : 6 - (1 - slide) * (w + 8);
			int a = (int) (out * 255);
			fill(g, x, y, x + w, y + h, ((int) (out * 0xC0) << 24) | 0x101214);
			fill(g, right ? x : x + w - 1.5f, y, right ? x + 1.5f : x + w, y + h, (a << 24) | 0xE0282E);
			label(g, text, Labels.UI, cap, x + (right ? 7 : 5), y + (h - (float) cap) / 2, -1, (a << 24) | 0xFFFFFF);
			y += h + gap;
		}
	}

	// ------------------------------------------------------------------ centre

	/** GT warnings: red text with a red round icon, centred below the middle. */
	private static void messages(GuiGraphicsExtractor g, Font f, RaceSession s, float W) {
		float y = 140;
		for (RaceSession.Message m : s.messages) {
			double tw = Labels.width(m.text(), Labels.UI, 3.6, K);
			float x = W / 2 - (float) tw / 2 + 4;
			fill(g, x - 8.5f, y - 0.5f, x - 3.5f, y + 4.5f, m.color());
			label(g, "!", Labels.BOLD, 3.0, x - 6, y + 0.5f, 0, WHITE);
			labelO(g, m.text(), Labels.UI, 3.6, x, y, -1, m.color());
			y += 9;
		}
	}

	private static void countdown(GuiGraphicsExtractor g, RaceSession s, float W, float part) {
		double left = (s.countdownEnd - s.now - part) / 20.0;
		if (left <= 0 || left > 60) {
			return; // over, or not timed yet (a grid being formed, a rolling start on its way)
		}
		if (s.lightsFrom >= 0) {
			// start lights: five pods, a pair of red lights each coming on a second apart; all out = go
			float cx = W / 2, cy = 132, pw = 15, ph = 32, gap = 5;
			double on = (s.now + part - s.lightsFrom) / 20.0;
			float x0 = cx - (5 * pw + 4 * gap) / 2;
			dev.laprace.ui.Ui.fill(g, x0 - 5, cy - 5, 5 * pw + 4 * gap + 10, ph + 10, 6, alpha(0xC0000000), K);
			for (int i = 0; i < 5; i++) {
				float x = x0 + i * (pw + gap);
				dev.laprace.ui.Ui.fill(g, x, cy, pw, ph, 4, 0xFF15171A, K);
				boolean lit = on >= i;
				for (int j = 0; j < 2; j++) {
					float ly = cy + 3 + j * 15;
					dev.laprace.ui.Ui.fill(g, x + 2, ly, pw - 4, pw - 4, (pw - 4) / 2, lit ? 0xFFFF2A22 : 0xFF2C3036, K);
				}
			}
			labelO(g, tr("laprace.hud.laps_race", s.raceLaps), Labels.UI, 3.6, cx, cy + ph + 12, 0, WHITE);
			return;
		}
		int digit = (int) Math.ceil(left);
		double frac = left - Math.floor(left);
		if (frac == 0) {
			frac = 1;
		}
		float cx = W / 2, cy = 150;
		COUNT_RING.draw(g, cx, cy, -90, -90 + 360 * frac, K);
		COUNT.draw(g, String.valueOf(digit), cx, cy - 8, 0, WHITE, K);
		if (digit == cfg().countdownSeconds) {
			labelO(g, tr("laprace.hud.laps_race", s.raceLaps), Labels.UI, 3.6, cx, cy + 30, 0, WHITE);
		}
	}

	/**
	 * The gap to one's ghost (best lap) at this point of the lap, left of the lap timer as GT's tags: a dark tag with the
	 * word, the gap in a blue (ahead) or red (behind) one, as the sector splits. right: where the timer begins.
	 */
	private static void ghostGap(GuiGraphicsExtractor g, float right, float y, float h) {
		double gap = Ghost.gap;
		if (Double.isNaN(gap)) {
			return;
		}
		String d = (gap < 0 ? "-" : "+") + String.format(Locale.ROOT, "%.2f", Math.abs(gap));
		int dc = gap < 0 ? BLUE : 0xFFC8202A;
		double dw = Math.max(26, NUM.width(d, K) + 8);
		float x1 = right - (float) dw;
		fill(g, x1, y, right, y + h, (dc & 0xFFFFFF) | 0xE8000000);
		NUM.draw(g, d, right - (float) dw / 2, y + (h - 3.9f) / 2, 0, WHITE, K);
		String l = tr("laprace.hud.ghost");
		double lw = Labels.width(l, Labels.BOLD, 3.2, K) + 8;
		fill(g, x1 - 1 - (float) lw, y, x1 - 1, y + h, 0xE0202328);
		label(g, l, Labels.BOLD, 3.2, x1 - 1 - (float) lw / 2, y + (h - 3.2f) / 2, 0, WHITE);
	}

	/** The server's event we are in, between its sessions or in its qualifying: the time left, under the lap timer. */
	private static void eventBanner(GuiGraphicsExtractor g, float W) {
		ServerRace r = ServerRace.current();
		if (r == null || !r.mine()) {
			return;
		}
		long now = System.currentTimeMillis();
		String text;
		if ("break".equals(r.status) && r.breakEnd > 0) {
			text = tr("laprace.hud.race_in", ServerRace.clockOf(Math.max(0, r.breakEnd - now) / 1000));
		} else if (r.quali && "running".equals(r.status) && r.endAt > 0) {
			text = tr("laprace.hud.quali_left", ServerRace.clockOf(Math.max(0, r.endAt - now) / 1000));
		} else {
			return;
		}
		double tw = Labels.width(text, Labels.BOLD, 3.8, K) + 14;
		float x = (float) (W / 2 - tw / 2), y = 64;
		dev.laprace.ui.Ui.fill(g, x, y, (float) tw, 11, 5.5f, alpha(0xD8E8302A), K);
		label(g, text, Labels.BOLD, 3.8, W / 2, y + 3.6f, 0, WHITE);
	}

	private static void bigPopups(GuiGraphicsExtractor g, RaceSession s, float W) {
		for (RaceSession.Popup p : s.popups) {
			if (!p.big()) {
				continue;
			}
			double age = s.now - p.from();
			float y = 104;
			double tw = Labels.width(p.title(), Labels.DIGITS, 7, K) + (p.value().isEmpty() ? 0 : 30);
			float w = (float) ((tw + 30) * Math.min(1.0, (age + 1) / 4.0));
			fill(g, W / 2 - w / 2, y, W / 2 + w / 2, y + 18, alpha(0x80000000));
			if (age >= 3) {
				Labels.draw(g, p.title(), Labels.DIGITS, 7, 1.15, 0.2, W / 2 - (p.value().isEmpty() ? 0 : 15), y + 5.5f, 0,
						p.color(), K);
				if (!p.value().isEmpty()) {
					Labels.draw(g, p.value(), Labels.DIGITS, 7, 1.15, 0.2, W / 2 + (float) tw / 2 - 10, y + 5.5f, 0, WHITE, K);
				}
			}
		}
	}

	private static void results(GuiGraphicsExtractor g, Font f, RaceSession s, float W, float H) {
		List<Racer> order = s.order;
		float rowH = 11, w = 240;
		int rows = Math.min(order.size(), 12);
		float h = 30 + rows * rowH + 6;
		float x = W / 2 - w / 2, y = Math.max(100, H / 2 - h / 2 - 40);
		fill(g, x, y, x + w, y + h, alpha(0x99101214));
		fill(g, x, y, x + w, y + 14, 0xE8C8202A);
		label(g, tr("laprace.hud.results"), Labels.BOLD, 4.2, x + 6, y + 4.9f, -1, WHITE);
		label(g, s.track.displayName() + "  ·  " + s.raceLaps, Labels.UI, 3.8, x + w - 6, y + 5.1f, 1, WHITE);
		float hy = y + 18;
		label(g, tr("laprace.hud.col_pos"), Labels.UI, 2.8, x + 6, hy, -1, GREY);
		label(g, tr("laprace.hud.col_driver"), Labels.UI, 2.8, x + 30, hy, -1, GREY);
		label(g, tr("laprace.hud.col_time"), Labels.UI, 2.8, x + 130, hy, -1, GREY);
		label(g, tr("laprace.hud.col_best"), Labels.UI, 2.8, x + 185, hy, -1, GREY);
		for (int i = 0; i < rows; i++) {
			Racer r = order.get(i);
			float ry = y + 26 + i * rowH;
			if (r.self) {
				fill(g, x + 2, ry - 1, x + w - 2, ry + rowH - 1, 0x40FFFFFF);
			}
			int pc = i == 0 ? YELLOW : i == 1 ? 0xFFC8D0D8 : i == 2 ? 0xFFD08A4A : WHITE;
			float ty = ry + (rowH - 2 - 3.9f) / 2;
			NUM.draw(g, r.dnf ? "-" : String.valueOf(i + 1), x + 10, ty, 0, pc, K);
			fill(g, x + 22, ry, x + 24, ry + rowH - 2, r.self ? RED : r.color);
			label(g, trim(r.name, Labels.UI, 3.9, 95), Labels.UI, 3.9, x + 30, ty, -1, WHITE);
			String tt = r.dnf ? "DNF" : i == 0 ? Fmt.gt((r.finishTick - s.goTick) / 20.0) : s.gapText(r, i);
			label(g, tt, Labels.UI, 3.9, x + 130, ty, -1, r.dnf ? RED : WHITE);
			boolean fl = r.bestLap > 0 && Math.abs(r.bestLap - s.sessionBestLap) < 1e-6;
			NUM.draw(g, Fmt.gt(r.bestLap), x + 185, ty, -1, fl ? 0xFFC58BFF : GREY, K);
		}
	}
}
