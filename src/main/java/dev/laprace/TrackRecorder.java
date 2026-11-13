package dev.laprace;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Records a new track: start on the start/finish line, drive one lap down the middle in the race direction, stop
 * near where you started. Saved to config/laprace/tracks/track_DATE.json and picked at once.
 */
public final class TrackRecorder {
	public static boolean recording;
	private static final List<double[]> PTS = new ArrayList<>();

	private TrackRecorder() {
	}

	public static int points() {
		return PTS.size();
	}

	public static void toggle(Minecraft mc) {
		if (!recording) {
			if (mc.player == null || RaceSession.boatOf(mc.player) == null) {
				RaceSession.say(mc, Component.translatable("laprace.msg.rec_no_boat"));
				return;
			}
			PTS.clear();
			recording = true;
			RaceSession.say(mc, Component.translatable("laprace.msg.rec_start"));
			return;
		}
		recording = false;
		if (PTS.size() < 50) {
			RaceSession.say(mc, Component.translatable("laprace.msg.rec_short"));
			return;
		}
		double[] a = PTS.get(0);
		double[] b = PTS.get(PTS.size() - 1);
		if (Math.hypot(a[0] - b[0], a[2] - b[2]) > 40) {
			RaceSession.say(mc, Component.translatable("laprace.msg.rec_open"));
			return;
		}
		// cut the overlap if it ran on past the start
		int cut = PTS.size();
		for (int i = PTS.size() - 1; i > PTS.size() / 2; i--) {
			double[] p = PTS.get(i);
			if (Math.hypot(a[0] - p[0], a[2] - p[2]) < 2) {
				cut = i;
				break;
			}
		}
		List<double[]> pts = new ArrayList<>(PTS.subList(0, cut));
		String name = "track_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
		try {
			Track.save(name, pts);
		} catch (IOException e) {
			RaceSession.say(mc, Component.translatable("laprace.msg.io_error", e.getMessage()));
			return;
		}
		RaceSession s = RaceSession.INSTANCE;
		s.reloadTracks();
		for (Track t : s.tracks) {
			if (t.name.equals(name)) {
				s.setTrack(t);
				LapConfig.save();
			}
		}
		RaceSession.say(mc, Component.translatable("laprace.msg.rec_saved", name, pts.size()));
	}

	public static void tick(Minecraft mc) {
		if (!recording || mc.player == null) {
			return;
		}
		Entity boat = RaceSession.boatOf(mc.player);
		if (boat == null) {
			return;
		}
		double[] last = PTS.isEmpty() ? null : PTS.get(PTS.size() - 1);
		if (last == null || Math.hypot(boat.getX() - last[0], boat.getZ() - last[2]) >= 1.0) {
			PTS.add(new double[] {boat.getX(), boat.getY(), boat.getZ()});
		}
	}
}
