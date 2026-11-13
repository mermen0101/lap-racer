package dev.laprace.cruise;

import java.util.Arrays;

/**
 * Open-addressing map long -> float without boxing (the planner asks for tens of thousands of floor blocks a
 * tick; a HashMap of boxed Longs and Floats made that the slowest part of the autopilot).
 */
final class LongFloatMap {
	private static final long EMPTY = Long.MIN_VALUE;
	private long[] keys;
	private float[] vals;
	private int size;
	private int mask;

	LongFloatMap(int capacity) {
		int cap = Integer.highestOneBit(Math.max(16, capacity) - 1) << 1;
		keys = new long[cap];
		vals = new float[cap];
		Arrays.fill(keys, EMPTY);
		mask = cap - 1;
	}

	private static int mix(long k) {
		k ^= k >>> 33;
		k *= 0xff51afd7ed558ccdL;
		k ^= k >>> 33;
		return (int) k;
	}

	/** The value, or NaN if absent. */
	float get(long k) {
		int i = mix(k) & mask;
		while (true) {
			long kk = keys[i];
			if (kk == k) {
				return vals[i];
			}
			if (kk == EMPTY) {
				return Float.NaN;
			}
			i = (i + 1) & mask;
		}
	}

	void put(long k, float v) {
		if (size * 2 >= keys.length) {
			grow();
		}
		int i = mix(k) & mask;
		while (true) {
			long kk = keys[i];
			if (kk == k) {
				vals[i] = v;
				return;
			}
			if (kk == EMPTY) {
				keys[i] = k;
				vals[i] = v;
				size++;
				return;
			}
			i = (i + 1) & mask;
		}
	}

	int size() {
		return size;
	}

	void clear() {
		Arrays.fill(keys, EMPTY);
		size = 0;
	}

	private void grow() {
		long[] ok = keys;
		float[] ov = vals;
		keys = new long[ok.length * 2];
		vals = new float[ok.length * 2];
		Arrays.fill(keys, EMPTY);
		mask = keys.length - 1;
		size = 0;
		for (int i = 0; i < ok.length; i++) {
			if (ok[i] != EMPTY) {
				put(ok[i], ov[i]);
			}
		}
	}
}
