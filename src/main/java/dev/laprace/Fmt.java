package dev.laprace;

/**
 * Times as 8:04.199 (minutes, seconds, thousandths). Over an hour (race clock): 1:12:43.560.
 */
public final class Fmt {
	private Fmt() {
	}

	/** Lap / race time; --:--.--- for none. */
	public static String gt(double seconds) {
		char minSep = ':';
		char dec = '.';
		if (seconds < 0 || Double.isNaN(seconds)) {
			return "--" + minSep + "--" + dec + "---";
		}
		long ms = Math.round(seconds * 1000);
		long h = ms / 3600000;
		long m = (ms / 60000) % 60;
		long rest = ms % 60000;
		StringBuilder sb = new StringBuilder(12);
		if (h > 0) {
			sb.append(h).append(':');
			if (m < 10) {
				sb.append('0');
			}
			sb.append(m).append(':');
		} else {
			sb.append(m).append(minSep);
		}
		long sec = rest / 1000;
		if (sec < 10) {
			sb.append('0');
		}
		sb.append(sec).append(dec);
		long milli = rest % 1000;
		if (milli < 100) {
			sb.append('0');
		}
		if (milli < 10) {
			sb.append('0');
		}
		return sb.append(milli).toString();
	}

	/** Old name kept for the chat messages. */
	public static String time(double seconds) {
		return gt(seconds);
	}

	/** +0.453 / -9.092 (minutes when over one). */
	public static String delta(double seconds) {
		if (Double.isNaN(seconds)) {
			return "";
		}
		String sign = seconds < 0 ? "-" : "+";
		double a = Math.abs(seconds);
		if (a >= 60) {
			return sign + gt(a);
		}
		long ms = Math.round(a * 1000);
		StringBuilder sb = new StringBuilder(8).append(sign).append(ms / 1000).append('.');
		long milli = ms % 1000;
		if (milli < 100) {
			sb.append('0');
		}
		if (milli < 10) {
			sb.append('0');
		}
		return sb.append(milli).toString();
	}
}
