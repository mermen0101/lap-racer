package dev.laprace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings, config/laprace/settings.json; edited in Mod Menu (Cloth Config screen). */
public final class LapConfig {
	public enum SpeedUnit { BOATHUD, KMH, MPH, MS }

	/** Pit stop: driven by the autopilot once you turn into the lane (GT), or all by hand. */
	public enum PitMode { AUTO, MANUAL }

	public enum Accent {
		RED(0xFFE8343A), BLUE(0xFF2F8CFF), YELLOW(0xFFFFC21A), GREEN(0xFF35D07F), PURPLE(0xFFB45CFF), WHITE(0xFFE8E8E8);

		public final int argb;

		Accent(int argb) {
			this.argb = argb;
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// ---- race
	public int raceLaps = 3;
	/** A local race starts with lights (five red, out after a random pause) instead of a countdown. */
	public boolean startLights;
	/** The look of the mod's screens: graphite, track, glass, light (Ui.THEMES). */
	public int uiTheme = 1;
	/** A see-through car drives one's best lap of the track (outside the server's races). */
	public boolean ghost = true;
	/** Whom the ghost drives: our best lap, the track record, nobody; how see-through it is (%: how much shows). */
	public GhostWith ghostWith = GhostWith.MINE;
	public int ghostOpacity = 45;

	public enum GhostWith {
		MINE, RECORD, OFF
	}
	/** The racing line: whether and when it shows, how it looks, how far ahead, by what it is coloured. */
	public LineMode lineMode = LineMode.FULL;
	public LineStyle lineStyle = LineStyle.CHEVRONS;
	public LineReach lineReach = LineReach.MEDIUM;
	public LineColor lineColor = LineColor.SPEED;

	public enum LineMode {
		FULL, BRAKING, OFF
	}

	public enum LineStyle {
		CHEVRONS, RIBBON, ARROWS
	}

	public enum LineReach {
		SHORT, MEDIUM, LONG
	}

	public enum LineColor {
		SPEED, PROFILE
	}
	public int countdownSeconds = 5;
	/** After the winner finishes, the others have this long to finish (then DNF). */
	public int finishTimeoutSeconds = 60;
	public int resultsSeconds = 20;
	public int sectors = 3;
	public boolean gapToLeader = true;
	// ---- track
	public boolean autoTrack = true;
	public String lastTrack = "route1";
	public boolean trackOthers = true;
	/** A boat this close to the centre line joins the board. */
	public int joinDistance = 20;
	/** Further than this from the centre line in practice: the lap is not timed. */
	public int offTrackDistance = 25;
	// ---- HUD
	public boolean showHud = true;
	/** What the HUD shows: everything, the least (time, speed), or one's own pick of its parts. */
	public HudPreset hudPreset = HudPreset.FULL;

	public enum HudPreset {
		FULL, MINIMAL, CUSTOM
	}

	/** The parts shown as the preset has them (one's own pick: left as it is). */
	public void applyHudPreset() {
		if (hudPreset == HudPreset.FULL || hudPreset == HudPreset.MINIMAL) {
			boolean full = hudPreset == HudPreset.FULL;
			showBoard = full;
			showMap = full;
			showLapList = full;
			showTiming = true;
			showPopups = true;
		}
	}
	public boolean showBoard = true;
	public int boardRows = 10;
	public boolean showTiming = true;
	public boolean showMap = true;
	public boolean showSpeedo = true;
	public boolean showInputs = true;
	public boolean showPopups = true;
	public boolean replaceBoatHud = true;
	public SpeedUnit speedUnit = SpeedUnit.BOATHUD;
	public int speedoMaxKmh = 250;
	public Accent accent = Accent.RED;
	public double hudScale = 1.0;
	public int panelOpacity = 100;
	public boolean sounds = true;
	public boolean chatLapTimes = false;
	public boolean showLapList = true;
	/** Inter / Orbitron instead of the game's pixel font. */
	public boolean gtFonts = true;
	/** Where short status messages show: cards at the right / left of the HUD, AlinLib toasts, or the chat. */
	public Notify.Where notifyWhere = Notify.Where.RIGHT;
	/** Centre display page: 0 radar, 1 session best. */
	public int mfdPage = 0;
	/** Virtual fuel (see Engine): burnt by the distance driven, filled standing in a pit box (black / yellow concrete). */
	public boolean fuel = true;
	/** How fast fuel burns outside the server's events (1 = a full tank for ~27 km flat out). */
	public double fuelRate = 1.0;
	// ---- 80 km/h autopilot
	public int cruiseSteeringCalm = 10;
	// ---- sound
	/** The boat's engine sound (a turbocharged V8). */
	public boolean engineSound = true;
	/** Its volume, 0..100. */
	public int engineVolume = 50;
	// ---- Lap Race server (docs/server-plan.md)
	/** Connect to the Lap Race server: sign in with the Minecraft account, send laps and the live position. */
	public boolean online = true;
	public String serverUrl = dev.laprace.common.Protocol.DEFAULT_URL;
	/** The old default (the organiser's computer) moved to the real server once. */
	public boolean serverMoved = false;

	/** How the mod reaches our server: directly, through the system's proxy (a VPN app's), or through this one. */
	public enum ProxyMode { OFF, SYSTEM, MANUAL }

	public ProxyMode proxy = ProxyMode.OFF;
	/** host:port of an HTTP proxy (MANUAL), e.g. a VPN app's local one. */
	public String proxyAddress = "127.0.0.1:10809";
	// ---- car
	/** Every boat drawn as the F1 car (the Figura avatar F1 by CoBeHok), with its driver. */
	public boolean carModel = true;
	public Livery carLivery = Livery.DEFAULT;
	/** A livery from the catalog (someone's approved picture, its hash), put on our car; null: carLivery. */
	public String carLiveryHash;
	/** The name of one's own livery (sent with it, shown in the catalog). */
	public String ownLiveryTitle = "";
	/** Which of our own liveries (MyLiveries) is on our car, by its id. */
	public String myLiveryId;
	/** Camera height in the car (hundredths of a block, as the avatar's: -0.3 by default, -0.9..0.5). */
	public int carCamera = -30;

	public enum Livery {
		DEFAULT, REDBULL, RENO, CUSTOM
	}

	// ---- pits
	public PitMode pitMode = PitMode.AUTO;
	/** Pit lane speed limit (km/h). */
	public int pitLimitKmh = 80;
	/** The box chosen on each track (track name -> box number). */
	public java.util.Map<String, Integer> pitBoxes = new java.util.HashMap<>();
	/** Standing time of the automatic stop (s), plus up to 6 s for an empty tank. */
	public int pitStopSeconds = 4;
	/** After the finish: drive a cool-down lap and park in the box. */
	public boolean cooldownLap = true;
	public boolean cruiseCameraFollowsTravel = true;

	public static LapConfig INSTANCE = new LapConfig();

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("laprace").resolve("settings.json");
	}

	public static void load() {
		try {
			Path f = file();
			if (Files.exists(f)) {
				LapConfig c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), LapConfig.class);
				if (c != null) {
					INSTANCE = c;
				}
			}
		} catch (Exception e) {
			INSTANCE = new LapConfig();
		}
		INSTANCE.clamp();
	}

	public static void save() {
		INSTANCE.clamp();
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(INSTANCE), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LapRaceClient.LOG.warn("settings could not be saved", e);
		}
	}

	private void clamp() {
		carCamera = Math.clamp(carCamera, -90, 50);
		if (serverUrl == null || serverUrl.isBlank()) {
			serverUrl = dev.laprace.common.Protocol.DEFAULT_URL;
		}
		if (!serverMoved) {
			if (serverUrl.equals("http://localhost:8080")) {
				serverUrl = dev.laprace.common.Protocol.DEFAULT_URL;
			}
			serverMoved = true;
		}
		if (proxy == null) {
			proxy = ProxyMode.OFF;
		}
		if (proxyAddress == null) {
			proxyAddress = "";
		}
		if (ghostWith == null) {
			ghostWith = ghost ? GhostWith.MINE : GhostWith.OFF;
		}
		if (!ghost) { // (the old switch, off: the ghost off)
			ghostWith = GhostWith.OFF;
			ghost = true;
		}
		ghostOpacity = Math.clamp(ghostOpacity, 15, 90);
		if (hudPreset == null) {
			hudPreset = HudPreset.CUSTOM; // (an old file: its parts as they were)
		}
		applyHudPreset();
		if (lineMode == null) {
			lineMode = LineMode.FULL;
		}
		if (lineStyle == null) {
			lineStyle = LineStyle.CHEVRONS;
		}
		if (lineReach == null) {
			lineReach = LineReach.MEDIUM;
		}
		if (lineColor == null) {
			lineColor = LineColor.SPEED;
		}
		if (carLivery == null) {
			carLivery = Livery.DEFAULT;
		}
		raceLaps = Math.clamp(raceLaps, 1, 99);
		fuelRate = Math.clamp(fuelRate, 0.25, 5.0);
		countdownSeconds = Math.clamp(countdownSeconds, 3, 10);
		finishTimeoutSeconds = Math.clamp(finishTimeoutSeconds, 5, 600);
		resultsSeconds = Math.clamp(resultsSeconds, 5, 120);
		// no longer settings (the same for everyone): whatever an old file says
		sectors = 3;
		panelOpacity = 100;
		boardRows = 10;
		autoTrack = true;
		trackOthers = true;
		joinDistance = 20;
		offTrackDistance = 25;
		replaceBoatHud = true;
		chatLapTimes = false;
		notifyWhere = Notify.Where.RIGHT;
		cruiseSteeringCalm = 10;
		cruiseCameraFollowsTravel = true;
		pitLimitKmh = 80;
		pitStopSeconds = 4;
		cooldownLap = true;
		joinDistance = Math.clamp(joinDistance, 3, 100);
		offTrackDistance = Math.clamp(offTrackDistance, 3, 200);
		boardRows = Math.clamp(boardRows, 3, 20);
		speedoMaxKmh = Math.clamp(speedoMaxKmh, 50, 400);
		hudScale = Math.clamp(hudScale, 0.5, 2.0);
		panelOpacity = Math.clamp(panelOpacity, 0, 100);
		cruiseSteeringCalm = Math.clamp(cruiseSteeringCalm, 0, 40);
		pitLimitKmh = Math.clamp(pitLimitKmh, 20, 80);
		if (pitBoxes == null) {
			pitBoxes = new java.util.HashMap<>();
		}
		pitStopSeconds = Math.clamp(pitStopSeconds, 1, 30);
		engineVolume = Math.clamp(engineVolume, 0, 100);
		if (pitMode == null) {
			pitMode = PitMode.AUTO;
		}
		if (speedUnit == null) {
			speedUnit = SpeedUnit.BOATHUD;
		}
		if (notifyWhere == null) {
			notifyWhere = Notify.Where.RIGHT;
		}
		if (accent == null) {
			accent = Accent.RED;
		}
	}
}
