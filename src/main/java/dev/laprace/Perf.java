package dev.laprace;

/** Development only (LAPRACE_DEMO=1): average cost of the autopilot tick and of the HUD frame, logged every 10 s. */
public final class Perf {
	public static final boolean ON = "1".equals(System.getenv("LAPRACE_DEMO"));
	private static long cruiseNs, cruiseN, hudNs, hudN, last;

	private Perf() {
	}

	public static void cruise(long ns) {
		cruiseNs += ns;
		cruiseN++;
		report();
	}

	public static void hud(long ns) {
		hudNs += ns;
		hudN++;
		report();
	}

	private static void report() {
		long now = System.currentTimeMillis();
		if (last == 0) {
			last = now;
		}
		if (now - last > 10_000) {
			LapRaceClient.LOG.info(String.format(java.util.Locale.ROOT, "[perf] autopilot %.3f ms/tick (%d), HUD %.3f ms/frame (%d)",
					cruiseN == 0 ? 0 : cruiseNs / 1e6 / cruiseN, cruiseN, hudN == 0 ? 0 : hudNs / 1e6 / hudN, hudN));
			cruiseNs = cruiseN = hudNs = hudN = 0;
			last = now;
		}
	}
}
