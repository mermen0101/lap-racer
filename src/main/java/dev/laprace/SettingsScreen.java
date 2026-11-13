package dev.laprace;

import dev.laprace.gfx.Labels;
import dev.laprace.ui.El;
import dev.laprace.ui.Ui;
import dev.laprace.ui.UiScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Lap Race settings (from Mod Menu): the sections on the left, the section's settings on the right as switches, sliders
 * and choices; what the one under the mouse does is said at the bottom. Every change takes at once; saved on closing.
 */
public final class SettingsScreen extends UiScreen {
	private static final float ROW = 24, CTRL_W = 150;

	private record Row(String key, El el) {
	}

	private record Section(String key, List<Row> rows) {
	}

	private static int open;

	private final Screen parent;
	private final List<Section> sections = new ArrayList<>();
	private final int oldSectors = LapConfig.INSTANCE.sectors;
	private float px, py, pw, ph, listX, listW;
	private float scroll, scrollTo;
	private final List<Float> baseY = new ArrayList<>();
	private final List<El> rowEls = new ArrayList<>();
	private String hoverKey;

	public SettingsScreen(Screen parent) {
		super(Component.translatable("laprace.config.title"));
		this.parent = parent;
	}

	private static String t(String key) {
		return I18n.get("laprace.config." + key);
	}

	// ------------------------------------------------------------------ what there is

	private List<Section> build() {
		LapConfig c = LapConfig.INSTANCE;
		RaceSession session = RaceSession.INSTANCE;
		List<Section> out = new ArrayList<>();

		List<Row> race = new ArrayList<>();
		race.add(new Row("gap_to_leader", new El.Segments(new String[] {t("gap_to_leader.ahead"), t("gap_to_leader.leader")},
				() -> c.gapToLeader ? 1 : 0, i -> c.gapToLeader = i == 1)));
		race.add(choice("ghost_with", LapConfig.GhostWith.values(), () -> c.ghostWith, v -> c.ghostWith = v));
		race.add(slider("ghost_opacity", 15, 90, () -> c.ghostOpacity, v -> c.ghostOpacity = v, v -> v + "%"));
		race.add(toggle("sounds", () -> c.sounds, v -> c.sounds = v));
		out.add(new Section("cat.race", race));

		List<Row> line = new ArrayList<>();
		line.add(choice("line_mode", LapConfig.LineMode.values(), () -> c.lineMode, v -> c.lineMode = v));
		line.add(choice("line_style", LapConfig.LineStyle.values(), () -> c.lineStyle, v -> c.lineStyle = v));
		line.add(choice("line_reach", LapConfig.LineReach.values(), () -> c.lineReach, v -> c.lineReach = v));
		line.add(choice("line_color", LapConfig.LineColor.values(), () -> c.lineColor, v -> c.lineColor = v));
		out.add(new Section("cat.line", line));

		List<Row> track = new ArrayList<>();
		if (!session.tracks.isEmpty()) {
			track.add(new Row("track", new El.Stepper(() -> session.track == null ? "—" : session.track.displayName(), d -> {
				List<Track> ts = new ArrayList<>(session.tracks);
				int i = Math.max(0, ts.indexOf(session.track));
				session.setTrack(ts.get(Math.floorMod(i + d, ts.size())));
			})));
		}
		out.add(new Section("cat.track", track));

		List<Row> hud = new ArrayList<>();
		hud.add(new Row("ui_theme", new El.Segments(new String[] {t("ui_theme.graphite"), t("ui_theme.track"), t("ui_theme.glass"), t("ui_theme.light")},
				() -> c.uiTheme, i -> c.uiTheme = i)));
		hud.add(toggle("show_hud", () -> c.showHud, v -> c.showHud = v));
		hud.add(slider("hud_scale", 50, 200, () -> (int) Math.round(c.hudScale * 100), v -> c.hudScale = v / 100.0, v -> v + "%"));
		hud.add(toggle("gt_fonts", () -> c.gtFonts, v -> c.gtFonts = v));
		// what is on the screen: full, the least, or one's own pick (its switches shown then only)
		hud.add(choice("hud_preset", LapConfig.HudPreset.values(), () -> c.hudPreset, v -> {
			c.hudPreset = v;
			c.applyHudPreset();
			rebuildWidgets();
		}));
		if (c.hudPreset == LapConfig.HudPreset.CUSTOM) {
			hud.add(toggle("show_board", () -> c.showBoard, v -> c.showBoard = v));
			hud.add(toggle("show_timing", () -> c.showTiming, v -> c.showTiming = v));
			hud.add(toggle("show_map", () -> c.showMap, v -> c.showMap = v));
			hud.add(toggle("show_lap_list", () -> c.showLapList, v -> c.showLapList = v));
			hud.add(toggle("show_popups", () -> c.showPopups, v -> c.showPopups = v));
		}
		out.add(new Section("cat.hud", hud));

		List<Row> speedo = new ArrayList<>();
		speedo.add(toggle("show_speedo", () -> c.showSpeedo, v -> c.showSpeedo = v));
		speedo.add(choice("unit", LapConfig.SpeedUnit.values(), () -> c.speedUnit, v -> c.speedUnit = v));
		speedo.add(slider("speedo_max", 50, 400, () -> c.speedoMaxKmh, v -> c.speedoMaxKmh = v, v -> v + " " + net.minecraft.client.resources.language.I18n.get("laprace.unit.kmh")));
		speedo.add(toggle("show_inputs", () -> c.showInputs, v -> c.showInputs = v));
		speedo.add(toggle("fuel", () -> c.fuel, v -> c.fuel = v));
		speedo.add(slider("fuel_rate", 1, 20, () -> (int) Math.round(c.fuelRate * 4), v -> c.fuelRate = v / 4.0,
				v -> "×" + (v % 4 == 0 ? Integer.toString(v / 4) : String.format(Locale.ROOT, "%.2f", v / 4.0).replaceAll("0$", ""))));
		out.add(new Section("cat.speedo", speedo));

		List<Row> car = new ArrayList<>();
		car.add(toggle("car_model", () -> c.carModel, v -> c.carModel = v));
		// the liveries: in the garage, each as the car in it
		car.add(new Row("garage", new El.Button(() -> I18n.get("laprace.garage.open"), El.Style.GHOST,
				() -> minecraft.gui.setScreen(new GarageScreen(this)))));
		car.add(slider("car_camera", -90, 50, () -> c.carCamera, v -> c.carCamera = v, v -> String.format(Locale.ROOT, "%+.2f", v / 100.0)));
		out.add(new Section("cat.car", car));

		List<Row> pits = new ArrayList<>();
		pits.add(choice("pit_mode", LapConfig.PitMode.values(), () -> c.pitMode, v -> c.pitMode = v));
		out.add(new Section("cat.pits", pits));

		List<Row> sound = new ArrayList<>();
		sound.add(toggle("engine_sound", () -> c.engineSound, v -> c.engineSound = v));
		sound.add(slider("engine_volume", 0, 100, () -> c.engineVolume, v -> c.engineVolume = v, v -> v + "%"));
		out.add(new Section("cat.sound", sound));

		List<Row> online = new ArrayList<>();
		online.add(toggle("online", () -> c.online, v -> c.online = v));
		online.add(choice("proxy", LapConfig.ProxyMode.values(), () -> c.proxy, v -> {
			c.proxy = v;
			rebuildWidgets();
		}));
		if (c.proxy == LapConfig.ProxyMode.MANUAL) { // (the address matters only for one's own proxy)
			online.add(text("proxy_address", () -> c.proxyAddress, v -> c.proxyAddress = v.trim()));
		}
		out.add(new Section("cat.online", online));
		return out;
	}

	private static Row toggle(String key, java.util.function.BooleanSupplier get, Consumer<Boolean> set) {
		return new Row(key, new El.Toggle(get, set, () -> I18n.get(get.getAsBoolean() ? "laprace.ui.on" : "laprace.ui.off")));
	}

	private static Row slider(String key, int min, int max, java.util.function.IntSupplier get, java.util.function.IntConsumer set, IntFunction<String> text) {
		return new Row(key, new El.Slider(min, max, get, set, text));
	}

	/** Opens a folder in the system's file manager. */
	private static void openFolder(java.nio.file.Path dir) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String[] cmd = os.contains("win") ? new String[] {"explorer", dir.toAbsolutePath().toString()}
				: os.contains("mac") ? new String[] {"open", dir.toAbsolutePath().toString()} : new String[] {"xdg-open", dir.toAbsolutePath().toString()};
		try {
			new ProcessBuilder(cmd).start();
		} catch (Exception e) {
			dev.laprace.LapRaceClient.LOG.warn("open folder: {}", e.toString());
		}
	}

	/** An enum: a segmented choice if it has few values, otherwise ‹ value ›. */
	private static <E extends Enum<E>> Row choice(String key, E[] values, Supplier<E> get, Consumer<E> set) {
		String[] names = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			names[i] = t(key + "." + values[i].name().toLowerCase(Locale.ROOT));
		}
		if (values.length <= 3) {
			return new Row(key, new El.Segments(names, () -> get.get().ordinal(), i -> set.accept(values[i])));
		}
		return new Row(key, new El.Stepper(() -> names[get.get().ordinal()], d -> set.accept(values[Math.floorMod(get.get().ordinal() + d, values.length)])));
	}

	private Row text(String key, Supplier<String> get, Consumer<String> set) {
		EditBox box = new EditBox(font, 0, 0, 100, 10, Component.literal(""));
		box.setMaxLength(200);
		box.setValue(get.get());
		box.setResponder(set);
		addWidget(box);
		return new Row(key, new El.Text(box));
	}

	/** Development: scrolled to f (0 top .. 1 bottom); whether there is anything to scroll. */
	public boolean devScroll(float f) {
		scroll = scrollTo = maxScroll() * f;
		return maxScroll() > 0;
	}

	/** Development: show section n; the first text field there (its control), or null. */
	public El.Text devSection(int n) {
		open = n;
		rebuildWidgets();
		for (El e : els) {
			if (e instanceof El.Text t) {
				return t;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void layout() {
		sections.clear();
		sections.addAll(build());
		open = Math.clamp(open, 0, sections.size() - 1);
		pw = Math.min(width - 20, 470);
		ph = Math.min(height - 20, 300);
		px = (width - pw) / 2f;
		py = (height - ph) / 2f;
		float side = 112;
		float step = Math.min(19, (ph - 40 - 34) / sections.size());
		for (int i = 0; i < sections.size(); i++) {
			int n = i;
			add(new El.Tab(t(sections.get(i).key()), () -> open == n, () -> {
				if (open != n) {
					open = n;
					scroll = scrollTo = 0;
					rebuildWidgets();
				}
			}), px + 8, py + 40 + i * step, side - 12, step - 2);
		}
		add(new El.Button(() -> I18n.get("gui.done"), El.Style.PRIMARY, this::onClose), px + 8, py + ph - 28, side - 12, 20);
		listX = px + side + 8;
		listW = pw - side - 8 - 14;
		clipY0 = py + 40;
		clipY1 = py + ph - 34;
		baseY.clear();
		rowEls.clear();
		float y = clipY0 + 2;
		for (Row r : sections.get(open).rows()) {
			El e = r.el();
			e.clip = true;
			float ch = e instanceof El.Toggle ? 14 : 18;
			float cw = e instanceof El.Toggle ? CTRL_W : e instanceof El.Button ? 90 : CTRL_W;
			add(e, listX + listW - cw - 6, y + (ROW - ch) / 2, cw, ch);
			baseY.add(y + (ROW - ch) / 2);
			rowEls.add(e);
			y += ROW;
		}
	}

	private float maxScroll() {
		return Math.max(0, sections.get(open).rows().size() * ROW + 4 - (clipY1 - clipY0));
	}

	@Override
	protected void place() {
		scroll = Ui.ease(scroll, scrollTo);
		for (int i = 0; i < rowEls.size(); i++) {
			rowEls.get(i).y = baseY.get(i) - scroll;
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (mx >= listX && mx < listX + listW && my >= clipY0 && my < clipY1) {
			scrollTo = Math.clamp(scrollTo - (float) dy * ROW, 0, maxScroll());
			return true;
		}
		return super.mouseScrolled(mx, my, dx, dy);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	protected void back(GuiGraphicsExtractor g, double mx, double my, double k) {
		Ui.card(g, px, py, pw, ph, k);
		// the side column, a shade darker
		g.fill(Math.round(px + 112), Math.round(py + 8), Math.round(px + 112) + 1, Math.round(py + ph - 8), Ui.LINE);
		Ui.text(g, "LAP RACE", Labels.BOLD, 3.6, px + 12, py + 12, -1, Ui.ACCENT, k);
		Ui.title(g, I18n.get("laprace.ui.settings"), Labels.BOLD, 6.4, px + 12, py + 20, -1, Ui.FG, k);
		Section s = sections.get(open);
		Ui.title(g, t(s.key()), Labels.BOLD, 7, listX, py + 16, -1, Ui.FG, k);

		hoverKey = null;
		g.enableScissor(0, Math.round(clipY0), width, Math.round(clipY1));
		float y = clipY0 + 2 - scroll;
		for (Row r : s.rows()) {
			if (r.el() != null && !fits(r.el().y, r.el().h)) {
				y += ROW;
				continue;
			}
			boolean hov = mx >= listX && mx < listX + listW && my >= y && my < y + ROW && my >= clipY0 && my < clipY1;
			if (hov) {
				hoverKey = r.key();
				Ui.fill(g, listX - 4, y, listW + 4, ROW, 5, Ui.CARD3 & 0x00FFFFFF | 0x90000000, k);
			}
			Ui.text(g, t(r.key()), Labels.UI, 4.4, listX + 4, y + (ROW - 4.4f) / 2, -1, hov ? Ui.FG : Ui.mix(Ui.FG, Ui.MUTED, 0.25f), k);
			y += ROW;
		}
		g.disableScissor();
		// a scroll bar when it does not all fit
		float max = maxScroll();
		if (max > 0) {
			float band = clipY1 - clipY0, bh = band * band / (band + max);
			Ui.fill(g, px + pw - 9, clipY0 + (band - bh) * scroll / max, 3, bh, 1.5f, Ui.CARD3, k);
		}
	}

	@Override
	protected void front(GuiGraphicsExtractor g, double mx, double my, double k) {
		// what the setting under the mouse does
		String tip = hoverKey == null ? null : "laprace.config." + hoverKey + ".tip";
		if (hoverKey != null && hoverKey.equals("replace_boathud") && !BoatData.BOATHUD) {
			tip = "laprace.config.replace_boathud.missing";
		}
		float y0 = clipY1 + 4;
		g.fill(Math.round(listX), Math.round(y0) - 2, Math.round(listX + listW), Math.round(y0) - 1, Ui.LINE);
		if (tip != null && !I18n.get(tip).equals(tip)) {
			float y = y0 + 3;
			for (String line : wrap(I18n.get(tip), listW - 8, k)) {
				if (y > py + ph - 8) {
					break;
				}
				Ui.text(g, line, Labels.UI, 3.8, listX + 4, y, -1, Ui.MUTED, k);
				y += 6.5f;
			}
		}
	}

	private static List<String> wrap(String s, float w, double k) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			String next = line.isEmpty() ? word : line + " " + word;
			if (!line.isEmpty() && Ui.width(next, Labels.UI, 3.8, k) > w) {
				out.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(next);
			}
		}
		if (!line.isEmpty()) {
			out.add(line.toString());
		}
		return out;
	}

	@Override
	public void onClose() {
		LapConfig.save();
		RaceSession session = RaceSession.INSTANCE;
		if (LapConfig.INSTANCE.sectors != oldSectors) {
			Track keep = session.track;
			session.setTrack(null);
			session.setTrack(keep);
		}
		minecraft.gui.setScreen(parent);
	}
}
