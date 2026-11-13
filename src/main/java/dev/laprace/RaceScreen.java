package dev.laprace;

import com.google.gson.JsonObject;
import dev.laprace.gfx.Labels;
import dev.laprace.net.Backend;
import dev.laprace.ui.El;
import dev.laprace.ui.Ui;
import dev.laprace.ui.UiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The race key (R): the server's race. None yet: set up an event - a race, a qualifying, a qualifying and its race
 * after a break, a time trial - on a track, with everything about it (lengths, the start, the grid's order, the finish,
 * points) and create it. One on: who is in, join or leave; its host starts or cancels it. Running: the standings.
 * Offline (or as practice for oneself), a local race on this computer as before.
 */
public final class RaceScreen extends UiScreen {
	static final String[] STARTS = {"self", "grid", "rolling"};
	static final String[] FORMATS = {"race", "quali", "weekend", "time"};
	static final String[] ORDERS = {"quali", "reverse", "signup", "random"};
	private static final float PAD = 16, ROW = 22, GAP = 6;

	// ------------------------------------------------------------------ the event to create (kept while the game runs)

	private static boolean tour;
	private static String pick;
	private static int format;
	private static int qualiMin = 10, qualiMaxLaps;
	private static boolean flagLap = true;
	private static int breakSec = 120;
	private static boolean byTime;
	private static int laps = 5, raceMin = 10;
	private static int start;
	private static int rollKmh = 80, countdownSec = 10, gridWaitSec = 120;
	private static boolean lights;
	private static int gridOrder;
	private static int finishSec;
	private static boolean points = true, fastestLapPoint;
	private static int maxDrivers = 20;
	private static boolean fuel;
	/** The racing line allowed in it. */
	private static boolean line = true;
	/** The radios fail on their own: 0 never, 1 now and then, 2 often. */
	private static int radioFaults;
	private static int fuelRate10 = 10, fuelStart = 100;
	private static int pits, pitPenaltySec = 30;

	private static boolean hasQuali() {
		return format != 0;
	}

	private static boolean hasRace() {
		return format == 0 || format == 2;
	}

	private static JsonObject opts() {
		JsonObject o = new JsonObject();
		o.addProperty("format", FORMATS[format]);
		o.addProperty("qualiMin", qualiMin);
		o.addProperty("qualiMaxLaps", qualiMaxLaps);
		o.addProperty("flagLap", flagLap);
		o.addProperty("breakSec", breakSec);
		o.addProperty("byTime", byTime);
		o.addProperty("laps", laps);
		o.addProperty("raceMin", raceMin);
		o.addProperty("start", STARTS[start]);
		o.addProperty("rollKmh", rollKmh);
		o.addProperty("countdownSec", countdownSec);
		o.addProperty("lights", lights);
		o.addProperty("line", line);
		o.addProperty("radioFaults", radioFaults);
		o.addProperty("gridWaitSec", gridWaitSec);
		o.addProperty("gridOrder", ORDERS[gridOrder]);
		o.addProperty("finishSec", finishSec);
		o.addProperty("points", points);
		o.addProperty("fastestLapPoint", fastestLapPoint);
		o.addProperty("maxDrivers", maxDrivers);
		o.addProperty("fuel", fuel);
		o.addProperty("fuelRate10", fuelRate10);
		o.addProperty("fuelStart", fuelStart);
		o.addProperty("pits", pits);
		o.addProperty("pitPenaltySec", pitPenaltySec);
		return o;
	}

	/** The tracks a server race can be held on (routes 1-6). */
	private static List<Track> raceTracks() {
		return RaceSession.INSTANCE.tracks.stream().filter(t -> t.name.matches("route[1-6]"))
				.sorted(java.util.Comparator.comparing(t -> t.name)).toList();
	}

	/** The track a new race is set up on: the one picked, else the one we are on (if races go there), else the first. */
	private static Track chosen() {
		List<Track> ts = raceTracks();
		for (Track t : ts) {
			if (t.name.equals(pick)) {
				return t;
			}
		}
		Track cur = RaceSession.INSTANCE.track;
		if (cur != null && ts.contains(cur)) {
			return cur;
		}
		return ts.isEmpty() ? null : ts.get(0);
	}

	// ------------------------------------------------------------------ the screen

	/** One line of the setup: a section's title (el null) or a setting. */
	private record Row(String label, El el) {
	}

	private String builtFor;
	private float W, cx0, cy0, ch;
	private float listY;
	private final List<Row> rows = new ArrayList<>();
	private final List<Float> rowY = new ArrayList<>();
	private float scroll, scrollTo, listH;

	public RaceScreen() {
		super(Component.translatable("laprace.race.title"));
	}

	public static void open(Minecraft mc) {
		mc.gui.setScreen(new RaceScreen());
	}

	private static String stamp() {
		ServerRace r = ServerRace.current();
		return Backend.state() + "|" + (r == null ? "-" : r.id + r.status + r.drivers.size() + r.mine()) + "|" + RaceSession.INSTANCE.racing()
				+ "|" + format + start + byTime + points + lights + pits + fuel + line + radioFaults + Backend.admin();
	}

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	private static String mmss(int sec) {
		return ServerRace.clockOf(sec);
	}

	private void section(String key) {
		rows.add(new Row(tr(key), null));
	}

	private void set(String key, El el) {
		rows.add(new Row(tr(key), el));
	}

	private El.Stepper stepper(java.util.function.Supplier<String> value, java.util.function.IntConsumer step) {
		return new El.Stepper(value, step);
	}

	private El.Toggle toggle(java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
		return new El.Toggle(get, set, () -> tr(get.getAsBoolean() ? "laprace.ui.on" : "laprace.ui.off"));
	}

	/** With fuel on: how fast it burns, how full the tanks are at the start. */
	private void fuelRows() {
		if (!fuel) {
			return;
		}
		set("laprace.ui.fuel_rate", stepper(() -> "×" + (fuelRate10 % 10 == 0 ? Integer.toString(fuelRate10 / 10) : fuelRate10 / 10 + "." + fuelRate10 % 10),
				d -> fuelRate10 = Math.clamp(fuelRate10 + d * 5, 5, 50)));
		set("laprace.ui.fuel_start", stepper(() -> fuelStart + "%", d -> fuelStart = Math.clamp(fuelStart + d * 10, 10, 100)));
	}

	/** The setup's settings, those that apply to the format and start chosen. */
	private void buildSetup() {
		rows.clear();
		section("laprace.ui.sec.event");
		set("laprace.ui.track", stepper(() -> chosen() == null ? "—" : chosen().displayName(), d -> {
			List<Track> ts = raceTracks();
			if (!ts.isEmpty()) {
				pick = ts.get(Math.floorMod(Math.max(0, ts.indexOf(chosen())) + d, ts.size())).name;
			}
		}));
		if (Backend.admin()) {
			set("laprace.ui.kind", new El.Segments(new String[] {tr("laprace.race.kind.amateur"), tr("laprace.race.kind.tour")},
					() -> tour ? 1 : 0, i -> tour = i == 1));
		} else {
			tour = false;
		}
		set("laprace.ui.format", new El.Segments(new String[] {tr("laprace.ui.f.race"), tr("laprace.ui.f.quali"), tr("laprace.ui.f.weekend"), tr("laprace.ui.f.time")},
				() -> format, i -> format = i));
		set("laprace.ui.max_drivers", stepper(() -> Integer.toString(maxDrivers), d -> maxDrivers = Math.clamp(maxDrivers + d, 2, 40)));

		if (!hasRace()) {
			// (a race has its own fuel row with the pits)
			section("laprace.ui.sec.fuel");
			set("laprace.ui.fuel", toggle(() -> fuel, v -> fuel = v));
			fuelRows();
		}
		if (hasQuali()) {
			section(format == 3 ? "laprace.ui.sec.time" : "laprace.ui.sec.quali");
			set("laprace.ui.duration", stepper(() -> tr("laprace.quali.minutes", qualiMin), d -> qualiMin = Math.clamp(qualiMin + d, 1, 60)));
			set("laprace.ui.flag_lap", toggle(() -> flagLap, v -> flagLap = v));
			set("laprace.ui.max_laps", stepper(() -> qualiMaxLaps == 0 ? tr("laprace.ui.unlimited") : Integer.toString(qualiMaxLaps),
					d -> qualiMaxLaps = Math.clamp(qualiMaxLaps + d, 0, 30)));
		}
		if (format == 2) {
			section("laprace.ui.sec.break");
			set("laprace.ui.break", stepper(() -> breakSec == 0 ? tr("laprace.ui.none") : mmss(breakSec), d -> breakSec = Math.clamp(breakSec + d * 30, 0, 1800)));
		}
		if (hasRace()) {
			section("laprace.ui.sec.race");
			set("laprace.ui.length", new El.Segments(new String[] {tr("laprace.ui.by_laps"), tr("laprace.ui.by_time")}, () -> byTime ? 1 : 0, i -> byTime = i == 1));
			if (byTime) {
				set("laprace.ui.race_time", stepper(() -> tr("laprace.ui.min_plus_lap", raceMin), d -> raceMin = Math.clamp(raceMin + d, 1, 180)));
			} else {
				set("laprace.ui.laps", stepper(() -> tr("laprace.race.laps", laps), d -> laps = Math.clamp(laps + d, 1, 99)));
			}
			section("laprace.ui.sec.start");
			set("laprace.ui.start", new El.Segments(new String[] {tr("laprace.ui.start.self"), tr("laprace.ui.start.grid"), tr("laprace.ui.start.rolling")},
					() -> start, i -> start = i));
			if (start == 2) {
				set("laprace.ui.roll_speed", stepper(() -> rollKmh + " " + net.minecraft.client.resources.language.I18n.get("laprace.unit.kmh"), d -> rollKmh = Math.clamp(rollKmh + d * 5, 30, 80)));
			} else {
				set("laprace.ui.signal", new El.Segments(new String[] {tr("laprace.ui.signal.count"), tr("laprace.ui.signal.lights")},
						() -> lights ? 1 : 0, i -> lights = i == 1));
				if (!lights) {
					set("laprace.ui.countdown", stepper(() -> countdownSec + " " + net.minecraft.client.resources.language.I18n.get("laprace.unit.s"), d -> countdownSec = Math.clamp(countdownSec + d, 3, 30)));
				}
			}
			if (start == 1) {
				set("laprace.ui.grid_wait", stepper(() -> mmss(gridWaitSec), d -> gridWaitSec = Math.clamp(gridWaitSec + d * 30, 30, 600)));
			}
			if (start != 0) {
				set("laprace.ui.grid_order", stepper(() -> tr("laprace.ui.order." + ORDERS[gridOrder]), d -> gridOrder = Math.floorMod(gridOrder + d, ORDERS.length)));
			}
			set("laprace.ui.radio_faults", new El.Segments(new String[] {tr("laprace.ui.radio_faults.0"), tr("laprace.ui.radio_faults.1"), tr("laprace.ui.radio_faults.2")},
					() -> radioFaults, i -> radioFaults = i));
			set("laprace.ui.line", new El.Segments(new String[] {tr("laprace.ui.line.on"), tr("laprace.ui.line.off")}, () -> line ? 0 : 1, i -> line = i == 0));
			section("laprace.ui.sec.pits");
			set("laprace.ui.fuel", toggle(() -> fuel, v -> fuel = v));
			fuelRows();
			set("laprace.ui.pits", stepper(() -> pits == 0 ? tr("laprace.ui.none_pits") : Integer.toString(pits), d -> pits = Math.clamp(pits + d, 0, 5)));
			if (pits > 0) {
				set("laprace.ui.pit_penalty", stepper(() -> pitPenaltySec == 0 ? tr("laprace.ui.dsq") : "+" + pitPenaltySec + " " + net.minecraft.client.resources.language.I18n.get("laprace.unit.s"),
						d -> pitPenaltySec = Math.clamp(pitPenaltySec + d * 5, 0, 300)));
			}
			section("laprace.ui.sec.finish");
			set("laprace.ui.finish_time", stepper(() -> finishSec == 0 ? tr("laprace.ui.auto") : mmss(finishSec),
					d -> finishSec = Math.clamp(finishSec + d * 30, 0, 900)));
			set("laprace.ui.points", toggle(() -> points, v -> points = v));
			if (points) {
				set("laprace.ui.fastest_point", toggle(() -> fastestLapPoint, v -> fastestLapPoint = v));
			}
		}
	}

	@Override
	protected void layout() {
		builtFor = stamp();
		rows.clear();
		rowY.clear();
		ServerRace r = ServerRace.current();
		boolean online = Backend.state() == Backend.State.ONLINE;
		RaceSession s = RaceSession.INSTANCE;
		float bottomBtn = s.serverRace ? 0 : 34;

		if (online && r == null) {
			// the setup: a wide card, the settings scrolling between the header and the create button
			// (the local race's button goes beside the create one: the whole height for the settings)
			W = Math.min(width - 20, 400);
			ch = Math.min(height - 20, 380);
			cx0 = (width - W) / 2f;
			cy0 = Math.max(10, (height - ch) / 2f);
			clipY0 = cy0 + 58;
			clipY1 = cy0 + ch - 42;
			buildSetup();
			float y = clipY0 + 2, ctrlW = Math.min(230, W - 2 * PAD - 120);
			for (Row row : rows) {
				if (row.el() == null) {
					y += rowY.isEmpty() ? 0 : 4;
					rowY.add(y);
					y += 14;
					continue;
				}
				El e = row.el();
				e.clip = true;
				float eh = e instanceof El.Toggle ? 14 : 18;
				add(e, cx0 + W - PAD - ctrlW, y + (ROW - eh) / 2 - scroll, ctrlW, eh); // (where the list is scrolled to)
				rowY.add(y);
				y += ROW + 2;
			}
			listH = y - clipY0;
			scrollTo = Math.clamp(scrollTo, 0, maxScroll());
			El.Button create = add(new El.Button(() -> tr(format == 2 ? "laprace.ui.create_weekend" : format == 1 ? "laprace.quali.create"
					: format == 3 ? "laprace.ui.create_time" : "laprace.race.create"), El.Style.PRIMARY, () -> {
				Track t = chosen();
				if (t != null) {
					ServerRace.create(tour ? "tour" : "amateur", t.name, opts());
				}
			}), cx0 + PAD, cy0 + ch - 34, W - 2 * PAD - (s.serverRace ? 0 : 124), 24);
			create.enabled = chosen() != null;
			if (!s.serverRace) {
				add(new El.Button(() -> tr(s.racing() ? "laprace.ui.local_stop" : "laprace.ui.local"), El.Style.GHOST, () -> {
					s.toggleRace(minecraft);
					onClose();
				}), cx0 + W - PAD - 118, cy0 + ch - 34, 118, 24);
			}
		} else {
			W = 300;
			float inner = online ? 22 + Math.max(1, r.drivers.size()) * 17 + 12 + 26 : 30;
			ch = 62 + inner + PAD;
			cx0 = (width - W) / 2f;
			cy0 = Math.max(10, (height - ch - bottomBtn) / 2f);
			clipY0 = clipY1 = 0;
			if (online) {
				float x = cx0 + PAD, w = W - 2 * PAD, y = cy0 + 62;
				listY = y + 22;
				y = listY + Math.max(1, r.drivers.size()) * 17 + 12;
				boolean open = "open".equals(r.status) || "break".equals(r.status);
				boolean boss = r.hosting() || Backend.admin();
				List<El> btns = new ArrayList<>();
				if (open) {
					boolean mine = r.mine();
					btns.add(new El.Button(() -> tr(mine ? "laprace.race.leave" : "laprace.race.join"), mine ? El.Style.GHOST : El.Style.PRIMARY,
							() -> ServerRace.simple(mine ? "leave" : "join")));
				}
				if (boss && open) {
					btns.add(new El.Button(() -> tr("laprace.race.start"), El.Style.PRIMARY, () -> ServerRace.simple("start")));
				}
				if (boss) {
					btns.add(new El.Button(() -> tr(r.quali ? "laprace.quali.cancel" : "laprace.race.cancel"), El.Style.DANGER, () -> ServerRace.simple("cancel")));
				}
				float bw = (w - (btns.size() - 1) * 6) / Math.max(1, btns.size());
				for (int i = 0; i < btns.size(); i++) {
					add(btns.get(i), x + i * (bw + 6), y, bw, 24);
				}
				if (btns.isEmpty()) {
					ch -= 26;
				}
			}
		}
		// a race of one's own on this computer (practice; offline too)
		if (!s.serverRace && !(online && r == null)) {
			add(new El.Button(() -> tr(s.racing() ? "laprace.race.local_stop" : "laprace.race.local"), El.Style.GHOST, () -> {
				s.toggleRace(minecraft);
				onClose();
			}), cx0 + 40, cy0 + ch + 12, W - 80, 20);
		}
	}

	private float maxScroll() {
		return Math.max(0, listH + 4 - (clipY1 - clipY0));
	}

	@Override
	protected void place() {
		if (clipY1 <= clipY0) {
			return;
		}
		float was = scroll;
		scroll = Ui.ease(scroll, scrollTo);
		float d = scroll - was;
		if (d != 0) {
			for (El e : els) {
				if (e.clip) {
					e.y -= d;
				}
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (clipY1 > clipY0 && my >= clipY0 && my < clipY1) {
			scrollTo = Math.clamp(scrollTo - (float) dy * (ROW + 2) * 1.5f, 0, maxScroll());
			return true;
		}
		return super.mouseScrolled(mx, my, dx, dy);
	}

	@Override
	public void tick() {
		if (!stamp().equals(builtFor)) {
			rebuildWidgets();
		}
	}

	/** What kind of event, in a few words: the kicker over the title. */
	private static String kindOf(ServerRace r) {
		String f = switch (r.format) {
			case "weekend" -> r.quali ? tr("laprace.ui.f.weekend") + " · " + tr("laprace.quali.format.quali") : tr("laprace.ui.f.weekend") + " · " + tr("laprace.ui.f.race");
			case "time" -> tr("laprace.ui.f.time");
			case "quali" -> tr("laprace.quali.format.quali");
			default -> tr("laprace.ui.f.race");
		};
		return (r.tour() ? tr("laprace.race.kind.tour") : tr("laprace.race.kind.amateur")) + " · " + f;
	}

	/** Its settings, in a line. */
	private static String describe(ServerRace r) {
		List<String> p = new ArrayList<>();
		if (r.quali) {
			p.add(tr("laprace.quali.minutes", r.laps));
			if (r.opt("qualiMaxLaps", 0) > 0) {
				p.add(tr("laprace.ui.max_laps_n", r.opt("qualiMaxLaps", 0)));
			}
			if (r.chained) {
				p.add(tr("laprace.ui.then_race"));
			}
		} else {
			p.add(r.byTime() ? tr("laprace.ui.min_plus_lap", r.opt("raceMin", 10)) : tr("laprace.race.laps", r.laps));
			p.add(tr("laprace.race.start." + r.start));
			if (r.opt("pits", 0) > 0) {
				p.add(tr("laprace.ui.pits_n", r.opt("pits", 0)));
			}
		}
		if (r.optOn("fuel")) {
			int rate = r.opt("fuelRate10", 10);
			p.add(tr("laprace.ui.fuel_on") + (rate != 10 ? " ×" + (rate % 10 == 0 ? Integer.toString(rate / 10) : rate / 10 + "." + rate % 10) : ""));
		}
		p.add(tr("laprace.race.host", r.hostName));
		return String.join(" · ", p);
	}

	@Override
	protected void back(GuiGraphicsExtractor g, double mx, double my, double k) {
		Ui.card(g, cx0, cy0, W, ch, k);
		ServerRace r = ServerRace.current();
		boolean online = Backend.state() == Backend.State.ONLINE;
		float x = cx0 + PAD, x1 = cx0 + W - PAD;
		Track t = r != null ? trackOf(r.track) : chosen();

		// header: a red kicker, the title, the track's shape on the right
		String kicker = !online ? tr("laprace.ui.offline") : r == null ? tr("laprace.ui.new") : kindOf(r);
		Ui.text(g, kicker.toUpperCase(Locale.ROOT), Labels.BOLD, 3.6, x, cy0 + PAD, -1, online ? Ui.ACCENT : Ui.MUTED, k);
		String title = r != null ? Track.displayName(r.track) : t != null ? t.displayName() : tr("laprace.race.title");
		Ui.title(g, title, Labels.BOLD, 9, x, cy0 + PAD + 9, -1, Ui.FG, k);
		String sub = r != null ? describe(r) : t != null ? String.format(Locale.ROOT, "%.2f ", t.length / 1000.0) + tr("laprace.unit.km") : tr("laprace.msg.no_track");
		Ui.text(g, sub, Labels.UI, 4.2, x, cy0 + PAD + 28, -1, Ui.MUTED, k);
		if (t != null) {
			Ui.track(g, t, x1 - 64, cy0 + 8, 64, 44, k);
		}
		g.fill(Math.round(x), Math.round(cy0 + 54), Math.round(x1), Math.round(cy0 + 54) + 1, Ui.LINE);

		if (!online) {
			Ui.text(g, tr("laprace.race.offline"), Labels.UI, 4.4, cx0 + W / 2, cy0 + 70, 0, Ui.MUTED, k);
		} else if (r == null) {
			// the setup's sections and labels, scrolled with the controls
			g.enableScissor(0, Math.round(clipY0), width, Math.round(clipY1));
			for (int i = 0; i < rows.size(); i++) {
				Row row = rows.get(i);
				float y = rowY.get(i) - scroll;
				if (row.el() == null ? !fits(y, 14) : !fits(row.el().y, row.el().h)) {
					continue;
				}
				if (row.el() == null) {
					Ui.text(g, row.label().toUpperCase(Locale.ROOT), Labels.BOLD, 3.6, x, y + 5, -1, Ui.ACCENT, k);
					g.fill(Math.round(x + (float) Ui.width(row.label().toUpperCase(Locale.ROOT), Labels.BOLD, 3.6, k) + 6), Math.round(y + 7),
							Math.round(x1), Math.round(y + 7) + 1, Ui.LINE);
				} else {
					Ui.text(g, row.label(), Labels.UI, 4.3, x, y + (ROW - 4.3f) / 2, -1, Ui.mix(Ui.FG, Ui.MUTED, 0.25f), k);
				}
			}
			g.disableScissor();
			float max = maxScroll();
			if (max > 0) {
				float band = clipY1 - clipY0, bh = band * band / (band + max);
				Ui.fill(g, cx0 + W - 7, clipY0 + (band - bh) * scroll / max, 3, bh, 1.5f, Ui.CARD3, k);
			}
			g.fill(Math.round(x), Math.round(clipY1) + 1, Math.round(x1), Math.round(clipY1) + 2, Ui.LINE);
		} else {
			// status pill + time left
			boolean running = "running".equals(r.status);
			String st = tr(r.quali && running ? "laprace.quali.running" : "laprace.race.status." + r.status);
			float sy = cy0 + 62;
			float pw = (float) Ui.width(st, Labels.BOLD, 3.8, k) + 14;
			int col = running ? Ui.ACCENT : "open".equals(r.status) ? Ui.GREEN : Ui.GOLD;
			Ui.fill(g, x, sy, pw, 12, 6, col & 0x30FFFFFF, k);
			Ui.ring(g, x, sy, pw, 12, 6, 0.6f, col, k);
			Ui.text(g, st, Labels.BOLD, 3.8, x + pw / 2, sy + 4.1f, 0, col, k);
			long nowMs = System.currentTimeMillis();
			String right;
			if (r.quali && r.endAt > 0) {
				right = tr("laprace.quali.left", ServerRace.clockOf(Math.max(0, r.endAt - nowMs) / 1000));
			} else if ("break".equals(r.status) && r.breakEnd > 0) {
				right = tr("laprace.ui.race_in", ServerRace.clockOf(Math.max(0, r.breakEnd - nowMs) / 1000));
			} else if (r.timeEnd > 0 && running) {
				right = r.timeEnd > nowMs ? tr("laprace.quali.left", ServerRace.clockOf((r.timeEnd - nowMs) / 1000)) : tr("laprace.ui.last_lap");
			} else {
				right = tr("laprace.ui.drivers", r.drivers.size() + " / " + r.opt("maxDrivers", 20));
			}
			Ui.text(g, right, Labels.BOLD, 4.2, x1, sy + 4, 1, Ui.FG, k);
			List<ServerRace.Driver> ds = r.drivers;
			float y = listY;
			if (ds.isEmpty()) {
				Ui.text(g, tr("laprace.race.nobody"), Labels.UI, 4.4, cx0 + W / 2, y + 5, 0, Ui.DIM, k);
			}
			boolean numbered = running || "grid".equals(r.status) || "formation".equals(r.status) || "countdown".equals(r.status) || "break".equals(r.status);
			String me = minecraft.player == null ? "" : minecraft.player.getGameProfile().name();
			for (ServerRace.Driver d : ds) {
				boolean self = d.name().equalsIgnoreCase(me);
				Ui.fill(g, x, y, x1 - x, 15, 4, self ? 0x26E8302A : Ui.CARD2, k);
				if (numbered) {
					Ui.fill(g, x + 2, y + 2, 15, 11, 3, d.pos() == 1 ? Ui.ACCENT : Ui.CARD3, k);
					Ui.text(g, Integer.toString(d.pos()), Labels.BOLD, 4.2, x + 9.5f, y + 5.4f, 0, 0xFFFFFFFF, k);
				}
				Ui.text(g, d.name(), Labels.BOLD, 4.4, x + (numbered ? 22 : 7), y + 5.3f, -1, d.finished() ? Ui.GOLD : Ui.FG, k);
				if (d.name().equalsIgnoreCase(r.hostName)) {
					float nw = (float) Ui.width(d.name(), Labels.BOLD, 4.4, k);
					Ui.text(g, tr("laprace.ui.host").toUpperCase(Locale.ROOT), Labels.BOLD, 3, x + (numbered ? 22 : 7) + nw + 5, y + 6, -1, Ui.DIM, k);
				}
				String rt = "";
				if (running && r.quali) {
					rt = d.best() > 0 ? Fmt.time(d.best() / 1000.0) : "—";
				} else if (running) {
					rt = d.finished() ? Fmt.time(d.time() / 1000.0) : tr("laprace.race.lap_n", d.laps());
					if (r.opt("pits", 0) > 0) {
						rt = tr("laprace.ui.pit_count", d.pits(), r.opt("pits", 0)) + "   " + rt;
					}
				}
				Ui.text(g, rt, Labels.UI, 4.2, x1 - 6, y + 5.4f, 1, Ui.MUTED, k);
				y += 17;
			}
		}
	}

	@Override
	protected void front(GuiGraphicsExtractor g, double mx, double my, double k) {
		String err = ServerRace.error();
		if (err != null) {
			String m = tr("laprace.race.err." + err);
			float w = (float) Ui.width(m, Labels.BOLD, 4.4, k) + 20;
			float y = Math.max(2, cy0 - 22);
			Ui.fill(g, (width - w) / 2, y, w, 16, 8, 0xF0E8302A, k);
			Ui.text(g, m, Labels.BOLD, 4.4, width / 2f, y + 5.8f, 0, 0xFFFFFFFF, k);
		}
	}

	private static Track trackOf(String name) {
		for (Track t : RaceSession.INSTANCE.tracks) {
			if (t.name.equals(name)) {
				return t;
			}
		}
		return null;
	}
}
