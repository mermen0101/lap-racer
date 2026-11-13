package dev.laprace.api;

import dev.laprace.RaceSession;
import dev.laprace.net.Backend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What other mods (Ice Racer) can ask Lap Race. Ice Racer reaches it through reflection (it runs without Lap Race too),
 * so the signatures here only use plain Java types and must not change.
 */
public final class LapRaceApi {
	private LapRaceApi() {
	}

	/** Signed in to our server. */
	public static boolean online() {
		return Backend.state() == Backend.State.ONLINE;
	}

	/** The route this player is on ("route3"), or null. */
	public static String track() {
		RaceSession s = RaceSession.INSTANCE;
		return s.track == null ? null : s.track.name;
	}

	/**
	 * Our dashboard is on screen in a boat (Ice Racer then shows its state there - see IceRacerLink - and draws no panel
	 * of its own).
	 */
	public static boolean dashboard() {
		dev.laprace.LapConfig c = dev.laprace.LapConfig.INSTANCE;
		return c.showHud && c.showSpeedo;
	}

	/** This player is a kill-a-car (may jam radios). */
	public static boolean canJam() {
		return Backend.killACar();
	}

	/**
	 * Everyone else on our server, as last seen (10 times a second): maps with uuid, name (String), x, y, z, yaw, v
	 * (blocks/s), track (String or null), boat (Boolean), ts (epoch ms of the position, the sender's clock), age (ms
	 * since it arrived here).
	 */
	public static List<Map<String, Object>> players() {
		Backend.wantOthers();
		List<Map<String, Object>> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Backend.Other o : Backend.others()) {
			Map<String, Object> m = new HashMap<>();
			m.put("uuid", o.uuid());
			m.put("name", o.name());
			m.put("x", o.x());
			m.put("y", o.y());
			m.put("z", o.z());
			m.put("yaw", o.yaw());
			m.put("v", o.v());
			m.put("track", o.track());
			m.put("boat", o.boat());
			m.put("ts", o.ts());
			m.put("age", now - o.arrived());
			out.add(m);
		}
		return out;
	}

	/** Kill-a-car: jam this player's radio (uuid), or stop (null). How hard depends on how close we are to them. */
	public static void jam(String uuid) {
		Backend.jam(uuid);
	}

	/** Kill-a-car: whom we jam (uuid), or null. */
	public static String jamTarget() {
		return Backend.jamTarget();
	}

	/** Kill-a-car: the name of whom we jam, or null. */
	public static String jamName() {
		return Backend.jamName();
	}

	/** Kill-a-car: how hard we jam them now, 0..1 (the server's word). */
	public static double jamLevel() {
		return Backend.jamLevel();
	}

	/** Not the player driving the car now (another mod's autopilot): this lap does not count. Call every tick it drives. */
	public static void assisted() {
		RaceSession.INSTANCE.assistedLap = true;
	}
}
