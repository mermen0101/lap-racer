package dev.laprace;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The road around the boat, read from the world, for the radar and the course map: their true width and bends.
 * <ul>
 *   <li>Road = blue or packed ice, or a snow marking; plain ice is run-off, not road. Narrow strips of anything
 *       else inside the road (paint, kerbs, a chequered line) count as road.</li>
 *   <li>Only the track being driven: every road block is tied to the nearest point of the current track's line,
 *       and only the stretch within {@link #AHEAD} blocks ahead of / behind the boat is shown - other parts of the
 *       circuit passing near are left out.</li>
 *   <li>Window of (2 * HALF + 1)^2 blocks round the boat, re-read a few thousand blocks a tick in a cycle (chunks
 *       that load later fill in), shifted as the boat moves.</li>
 *   <li>The picture is 2 px a block with a smooth anti-aliased edge (the road's outline traced between block
 *       centres), built on a worker thread; the game thread only copies it into the texture.</li>
 * </ul>
 */
public final class TrackScan {
	public static final int HALF = 112;
	public static final int SIZE = HALF * 2 + 1;
	/** Image pixels per block. */
	private static final int P = 2;
	private static final int IMG = SIZE * P;
	private static final int BUDGET = 3500;
	private static final int RECENTRE = 36;
	/** Blocks of the track ahead of and behind the boat that are shown. */
	private static final double AHEAD = 120;
	private static final Identifier ID = Identifier.fromNamespaceAndPath("laprace", "baked/track_scan");

	/** Per block: 0 not road, 1 road (as read). */
	private static final byte[] ROAD = new byte[SIZE * SIZE];
	/** Per block: progress along the current track of the nearest line point (for road blocks), NaN otherwise. */
	private static final float[] PROG = new float[SIZE * SIZE];
	/** Per block: index of that nearest line point (-1 none), the search hint for the next block. */
	private static final int[] IDX = new int[SIZE * SIZE];
	private static int x0 = Integer.MIN_VALUE, z0 = Integer.MIN_VALUE, y = Integer.MIN_VALUE;
	private static Level level;
	private static Track track;
	private static int cursor;
	private static long lastBuild = -100;
	private static double builtS = Double.NaN;
	private static boolean changed;
	private static LinearTexture tex;
	/** Window origin the texture now shows (it may lag the live window by a build). */
	private static int texX0 = Integer.MIN_VALUE, texZ0 = Integer.MIN_VALUE;
	private static final BlockPos.MutableBlockPos MP = new BlockPos.MutableBlockPos();
	private static final Track.Projection PROJ = new Track.Projection();
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Lap Race track scan");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static Future<Built> pending;

	private record Built(int[] argb, int x0, int z0) {
	}

	private TrackScan() {
	}

	private static final class LinearTexture extends DynamicTexture {
		LinearTexture(NativeImage img) {
			super(ID::toString, img);
			this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}

	public static void tick(Minecraft mc, long now) {
		Entity boat = mc.player == null ? null : RaceSession.boatOf(mc.player);
		RaceSession s = RaceSession.INSTANCE;
		if (boat == null || mc.level == null || s.track == null) {
			return;
		}
		int bx = Mth.floor(boat.getX()), bz = Mth.floor(boat.getZ());
		int by = Mth.floor(boat.getY() + 0.01);
		if (mc.level != level || s.track != track || Math.abs(by - y) > 2) {
			level = mc.level;
			track = s.track;
			y = by;
			x0 = bx - HALF;
			z0 = bz - HALF;
			Arrays.fill(ROAD, (byte) 0);
			Arrays.fill(PROG, Float.NaN);
			Arrays.fill(IDX, -1);
			cursor = 0;
			changed = true;
		} else if (Math.abs(bx - (x0 + HALF)) > RECENTRE || Math.abs(bz - (z0 + HALF)) > RECENTRE) {
			shift(bx - HALF - x0, bz - HALF - z0);
		}
		for (int n = 0; n < BUDGET; n++) {
			int i = cursor;
			cursor = (cursor + 1) % ROAD.length;
			int x = x0 + i % SIZE, z = z0 + i / SIZE;
			byte v = road(x, z) ? (byte) 1 : 0;
			if (ROAD[i] != v) {
				ROAD[i] = v;
				changed = true;
			}
			if (v != 0 && Float.isNaN(PROG[i])) {
				// start the search from the block on the left if it is road (same surface); else search the whole
				// line, so a block never gets tied to another part of the circuit that happens to be near the hint
				int h = i % SIZE > 0 && ROAD[i - 1] != 0 ? IDX[i - 1] : -1;
				track.project(x + 0.5, z + 0.5, h, PROJ);
				IDX[i] = PROJ.idx;
				// further than 22 blocks from the line: not this track's road (another circuit, a car park)
				PROG[i] = PROJ.off <= 22 ? (float) PROJ.s : -1f;
				changed = true;
			}
		}
		// collect a finished picture
		if (pending != null && pending.isDone()) {
			try {
				upload(mc, pending.get());
			} catch (Exception e) {
				LapRaceClient.LOG.warn("track scan picture failed", e);
			}
			pending = null;
		}
		// start a new one: twice a second at most, when the scan changed or we moved along the track
		Racer me = s.self;
		double sNow = me != null ? me.s : Double.NaN;
		boolean moved = Double.isNaN(builtS) || Double.isNaN(sNow) || Math.abs(sNow - builtS) > 6;
		if (pending == null && now - lastBuild >= 10 && (changed || moved)) {
			lastBuild = now;
			changed = false;
			builtS = sNow;
			byte[] keep = include(sNow);
			int bx0 = x0, bz0 = z0;
			pending = WORKER.submit(() -> new Built(picture(keep), bx0, bz0));
		}
	}

	private static boolean road(int x, int z) {
		MP.set(x, y - 1, z);
		BlockState floor = level.getBlockState(MP);
		if (floor.is(Blocks.BLUE_ICE) || floor.is(Blocks.PACKED_ICE)) {
			return true;
		}
		MP.set(x, y, z);
		return level.getBlockState(MP).is(Blocks.SNOW);
	}

	/** The blocks to draw: road (with narrow gaps filled) on the stretch of the track around progress sNow. */
	private static byte[] include(double sNow) {
		double L = track.length;
		byte[] out = new byte[ROAD.length];
		for (int z = 0; z < SIZE; z++) {
			for (int x = 0; x < SIZE; x++) {
				int i = z * SIZE + x;
				boolean r = ROAD[i] != 0 || gap(x, z);
				if (!r) {
					continue;
				}
				float p = PROG[i];
				if (Float.isNaN(p)) {
					p = nearbyProg(x, z); // a filled gap: take its neighbours' place on the track
				}
				if (Float.isNaN(p) || p < 0) {
					continue;
				}
				if (Double.isNaN(sNow)) {
					out[i] = 1;
					continue;
				}
				double d = Math.abs(p - sNow) % L;
				d = Math.min(d, L - d);
				if (d <= AHEAD) {
					out[i] = 1;
				}
			}
		}
		return out;
	}

	private static boolean gap(int x, int z) {
		return (near(x, z, -1, 0) && near(x, z, 1, 0)) || (near(x, z, 0, -1) && near(x, z, 0, 1));
	}

	private static boolean near(int x, int z, int dx, int dz) {
		for (int k = 1; k <= 3; k++) {
			int xx = x + dx * k, zz = z + dz * k;
			if (xx < 0 || zz < 0 || xx >= SIZE || zz >= SIZE) {
				return false;
			}
			if (ROAD[zz * SIZE + xx] != 0) {
				return true;
			}
		}
		return false;
	}

	private static float nearbyProg(int x, int z) {
		for (int k = 1; k <= 3; k++) {
			int[][] d = {{k, 0}, {-k, 0}, {0, k}, {0, -k}};
			for (int[] o : d) {
				int xx = x + o[0], zz = z + o[1];
				if (xx >= 0 && zz >= 0 && xx < SIZE && zz < SIZE) {
					float p = PROG[zz * SIZE + xx];
					if (!Float.isNaN(p)) {
						return p;
					}
				}
			}
		}
		return Float.NaN;
	}

	/**
	 * The picture (worker thread): the road's outline traced between block centres - a bilinear blend of the
	 * blocks around each pixel, cut at one half with a one-pixel soft edge - so edges are smooth and sharp, not
	 * stair-stepped and not blurred.
	 */
	private static int[] picture(byte[] keep) {
		int[] out = new int[IMG * IMG];
		for (int py = 0; py < IMG; py++) {
			double fz = (py + 0.5) / P - 0.5;
			int iz = (int) Math.floor(fz);
			double tz = fz - iz;
			for (int px = 0; px < IMG; px++) {
				double fx = (px + 0.5) / P - 0.5;
				int ix = (int) Math.floor(fx);
				double tx = fx - ix;
				double v00 = at(keep, ix, iz), v10 = at(keep, ix + 1, iz), v01 = at(keep, ix, iz + 1), v11 = at(keep, ix + 1, iz + 1);
				double v = (v00 * (1 - tx) + v10 * tx) * (1 - tz) + (v01 * (1 - tx) + v11 * tx) * tz;
				double a = Math.clamp((v - 0.5) * P * 1.6 + 0.5, 0, 1);
				out[py * IMG + px] = ((int) Math.round(a * 255) << 24) | 0xFFFFFF;
			}
		}
		return out;
	}

	private static double at(byte[] keep, int x, int z) {
		if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
			return 0;
		}
		return keep[z * SIZE + x];
	}

	private static void shift(int dx, int dz) {
		byte[] oldR = ROAD.clone();
		float[] oldP = PROG.clone();
		int[] oldI = IDX.clone();
		Arrays.fill(ROAD, (byte) 0);
		Arrays.fill(PROG, Float.NaN);
		Arrays.fill(IDX, -1);
		for (int z = 0; z < SIZE; z++) {
			int oz = z + dz;
			if (oz < 0 || oz >= SIZE) {
				continue;
			}
			for (int x = 0; x < SIZE; x++) {
				int ox = x + dx;
				if (ox >= 0 && ox < SIZE) {
					ROAD[z * SIZE + x] = oldR[oz * SIZE + ox];
					PROG[z * SIZE + x] = oldP[oz * SIZE + ox];
					IDX[z * SIZE + x] = oldI[oz * SIZE + ox];
				}
			}
		}
		x0 += dx;
		z0 += dz;
		changed = true;
	}

	private static void upload(Minecraft mc, Built b) {
		if (tex == null) {
			tex = new LinearTexture(new NativeImage(IMG, IMG, true));
			mc.getTextureManager().register(ID, tex);
		}
		NativeImage img = tex.getPixels();
		int[] a = b.argb();
		for (int i = 0; i < a.length; i++) {
			img.setPixel(i % IMG, i / IMG, a[i]);
		}
		tex.upload();
		texX0 = b.x0();
		texZ0 = b.z0();
	}

	/** Draws the road around the boat at (cx, cy), turned by {@code deg}, at {@code unitsPerBlock}, tinted. */
	public static void draw(GuiGraphicsExtractor g, Entity boat, float cx, float cy, float deg, double unitsPerBlock, int color) {
		if (tex == null || texX0 == Integer.MIN_VALUE) {
			return;
		}
		float pivX = (float) ((boat.getX() - texX0) * P), pivY = (float) ((boat.getZ() - texZ0) * P);
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate((float) Math.toRadians(deg));
		g.pose().scale((float) (unitsPerBlock / P), (float) (unitsPerBlock / P));
		g.pose().translate(-pivX, -pivY);
		g.blit(RenderPipelines.GUI_TEXTURED, ID, 0, 0, 0f, 0f, IMG, IMG, IMG, IMG, color);
		g.pose().popMatrix();
	}
}
