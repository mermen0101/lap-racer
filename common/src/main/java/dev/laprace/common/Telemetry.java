package dev.laprace.common;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The position log, as short as it goes: one file per player per day, a stream of records, each starting with its
 * type byte. Positions in 1/16 of a block, each sample stored as the change from the one before (a few bytes):
 * <pre>
 * 0x00 sample:   varint dt(ms)  zigzag-varint dx dy dz (1/16 block)  u8 yaw (256ths of a turn)
 *                varint speed (0.1 m/s)  u8 flags: bit0 in a boat, bit1 throttle, bit2 brake, bits4-7 gear (0..8)
 * 0x01 key:      i64 time (epoch ms)  i32 x y z (1/16 block)       - absolute; first record, then every minute
 * 0x02 track:    u8 length, UTF-8 name ("" = none)                 - when it changes
 * </pre>
 * About 9-10 bytes a sample: at 10 a second, ~350 KB an hour of driving.
 */
public final class Telemetry {
	public static final int SAMPLE = 0, KEY = 1, TRACK = 2;

	/** One decoded sample. */
	public record Sample(long time, double x, double y, double z, double yaw, double speed, boolean boat, boolean throttle,
			boolean brake, int gear, String track) {
	}

	private Telemetry() {
	}

	/** Writes one player's log. Not thread-safe: one writer per stream. */
	public static final class Writer {
		private final OutputStream out;
		private final ByteArrayOutputStream rec = new ByteArrayOutputStream(32);
		private long lastTime = -1, lastKey;
		private int lx, ly, lz;
		private String track = null;

		public Writer(OutputStream out) {
			this.out = out;
		}

		public void write(long time, double x, double y, double z, double yaw, double speed, boolean boat, boolean throttle,
				boolean brake, int gear, String trackName) throws IOException {
			int ix = (int) Math.round(x * 16), iy = (int) Math.round(y * 16), iz = (int) Math.round(z * 16);
			String tn = trackName == null ? "" : trackName;
			if (!tn.equals(track)) {
				byte[] b = tn.getBytes(StandardCharsets.UTF_8);
				rec.reset();
				rec.write(TRACK);
				rec.write(Math.min(255, b.length));
				rec.write(b, 0, Math.min(255, b.length));
				rec.writeTo(out);
				track = tn;
			}
			if (lastTime < 0 || time - lastKey >= 60_000 || time < lastTime) {
				rec.reset();
				rec.write(KEY);
				long64(rec, time);
				int32(rec, ix);
				int32(rec, iy);
				int32(rec, iz);
				rec.writeTo(out);
				lastKey = time;
				lastTime = time;
				lx = ix;
				ly = iy;
				lz = iz;
			}
			rec.reset();
			rec.write(SAMPLE);
			varint(rec, time - lastTime);
			varint(rec, zigzag(ix - lx));
			varint(rec, zigzag(iy - ly));
			varint(rec, zigzag(iz - lz));
			rec.write((int) Math.floorMod(Math.round(yaw / 360.0 * 256), 256));
			varint(rec, Math.max(0, Math.round(speed * 10)));
			rec.write((boat ? 1 : 0) | (throttle ? 2 : 0) | (brake ? 4 : 0) | (Math.clamp(gear, 0, 15) << 4));
			rec.writeTo(out);
			lastTime = time;
			lx = ix;
			ly = iy;
			lz = iz;
		}
	}

	/** Reads a log, one sample at a time. */
	public static void read(InputStream in, Consumer<Sample> to) throws IOException {
		DataInputStream d = new DataInputStream(in);
		long time = 0;
		int x = 0, y = 0, z = 0;
		String track = "";
		while (true) {
			int type = d.read();
			if (type < 0) {
				return;
			}
			try {
				switch (type) {
					case KEY -> {
						time = d.readLong();
						x = d.readInt();
						y = d.readInt();
						z = d.readInt();
					}
					case TRACK -> {
						byte[] b = new byte[d.readUnsignedByte()];
						d.readFully(b);
						track = new String(b, StandardCharsets.UTF_8);
					}
					case SAMPLE -> {
						time += readVarint(d);
						x += unzigzag(readVarint(d));
						y += unzigzag(readVarint(d));
						z += unzigzag(readVarint(d));
						double yaw = d.readUnsignedByte() * 360.0 / 256;
						double speed = readVarint(d) / 10.0;
						int f = d.readUnsignedByte();
						to.accept(new Sample(time, x / 16.0, y / 16.0, z / 16.0, yaw, speed, (f & 1) != 0, (f & 2) != 0, (f & 4) != 0,
								f >> 4, track.isEmpty() ? null : track));
					}
					default -> throw new IOException("bad record type " + type);
				}
			} catch (EOFException e) {
				return; // a record cut short at the end (the writer was stopped mid-way)
			}
		}
	}

	private static long zigzag(long v) {
		return (v << 1) ^ (v >> 63);
	}

	private static long unzigzag(long v) {
		return (v >>> 1) ^ -(v & 1);
	}

	private static void varint(ByteArrayOutputStream o, long v) {
		while ((v & ~0x7FL) != 0) {
			o.write((int) ((v & 0x7F) | 0x80));
			v >>>= 7;
		}
		o.write((int) v);
	}

	private static long readVarint(DataInputStream d) throws IOException {
		long v = 0;
		for (int shift = 0; shift < 64; shift += 7) {
			int b = d.readUnsignedByte();
			v |= (long) (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return v;
			}
		}
		throw new IOException("varint too long");
	}

	private static void int32(ByteArrayOutputStream o, int v) {
		o.write(v >>> 24);
		o.write(v >>> 16);
		o.write(v >>> 8);
		o.write(v);
	}

	private static void long64(ByteArrayOutputStream o, long v) {
		int32(o, (int) (v >>> 32));
		int32(o, (int) v);
	}
}
