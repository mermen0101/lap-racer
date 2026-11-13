package dev.laprace.car;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.laprace.LapRaceClient;
import dev.laprace.gfx.Canvas;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A picture of the car in a livery, drawn here in software (a small rasteriser: the car's own quads, its texture
 * as the game samples it, the site's camera and light): for the garage's cards. The same view as the site's preview,
 * three quarters from the front left; drawn twice as large and halved, for smooth edges.
 */
public final class CarPicture {
	/** Per quad: 4 corners of x y z u v, then the normal. */
	private static List<float[]> quads;
	private static float cx, cy, cz, radius;

	private CarPicture() {
	}

	private static synchronized void load() {
		if (quads != null) {
			return;
		}
		List<float[]> out = new ArrayList<>();
		float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		try (InputStream in = CarPicture.class.getResourceAsStream("/assets/laprace/models/f1.json")) {
			JsonObject d = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (var ge : d.getAsJsonArray("groups")) {
				JsonObject g = ge.getAsJsonObject();
				String name = g.get("name").getAsString();
				if (name.startsWith("ExhaustFire") || name.startsWith("SteeringWheelUI")) {
					continue;
				}
				for (var qe : g.getAsJsonArray("quads")) {
					JsonObject q = qe.getAsJsonObject();
					if (!"car".equals(q.get("tex").getAsString())) {
						continue;
					}
					float[] f = new float[23];
					JsonArray vs = q.getAsJsonArray("v");
					for (int i = 0; i < 4; i++) {
						JsonArray v = vs.get(i).getAsJsonArray();
						for (int c = 0; c < 5; c++) {
							f[i * 5 + c] = v.get(c).getAsFloat();
						}
						for (int c = 0; c < 3; c++) {
							min[c] = Math.min(min[c], f[i * 5 + c]);
							max[c] = Math.max(max[c], f[i * 5 + c]);
						}
					}
					JsonArray n = q.getAsJsonArray("n");
					f[20] = n.get(0).getAsFloat();
					f[21] = n.get(1).getAsFloat();
					f[22] = n.get(2).getAsFloat();
					out.add(f);
				}
			}
		} catch (Exception e) {
			LapRaceClient.LOG.warn("car picture: {}", e.toString());
		}
		cx = (min[0] + max[0]) / 2;
		cy = (min[1] + max[1]) / 2;
		cz = (min[2] + max[2]) / 2;
		radius = (float) Math.hypot(Math.hypot(max[0] - min[0], max[1] - min[1]), max[2] - min[2]) / 2;
		quads = out;
	}

	/** A PNG's pixels (ARGB), or null. */
	public static int[][] texture(byte[] png) {
		try {
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
			if (img == null) {
				return null;
			}
			int w = img.getWidth(), h = img.getHeight();
			int[] px = img.getRGB(0, 0, w, h, null, 0, w);
			return new int[][] {px, {w, h}};
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * The car in this texture (from texture()), w x h pixels, turned yaw / pitch (radians); zoom 1: room for any turn,
	 * more: closer (for a view that stays put).
	 */
	public static Canvas draw(int[][] tex, int w, int h, float yaw, float pitch, float zoom) {
		load();
		int s = 2, W = w * s, H = h * s;
		int[] color = new int[W * H];
		float[] depth = new float[W * H];
		int[] px = tex[0];
		int tw = tex[1][0], th = tex[1][1];
		float cyw = (float) Math.cos(yaw), syw = (float) Math.sin(yaw), cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
		float fov = 0.62f, aspect = (float) W / H, t = (float) (1 / Math.tan(fov / 2));
		float half = (float) Math.min(fov / 2, Math.atan(Math.tan(fov / 2) * aspect));
		float dist = (float) (radius / Math.sin(half) * 1.04 / zoom);
		float lx = 0.35f, ly = 1f, lz = 0.55f, ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
		lx /= ll;
		ly /= ll;
		lz /= ll;
		float[] sx = new float[4], sy = new float[4], iw = new float[4], uu = new float[4], vv = new float[4];
		for (float[] q : quads) {
			for (int i = 0; i < 4; i++) {
				float x = q[i * 5] - cx, y = q[i * 5 + 1] - cy, z = q[i * 5 + 2] - cz;
				// turned about Y, then about X (as the site's), then moved back from the camera
				float x1 = cyw * x + syw * z, z1 = -syw * x + cyw * z;
				float y2 = cp * y - sp * z1, z2 = sp * y + cp * z1 - dist;
				float w1 = -z2;
				sx[i] = ((t / aspect) * x1 / w1 * 0.5f + 0.5f) * W;
				sy[i] = (1 - (t * y2 / w1 * 0.5f + 0.5f)) * H;
				iw[i] = 1 / w1;
				uu[i] = q[i * 5 + 3] * iw[i];
				vv[i] = q[i * 5 + 4] * iw[i];
			}
			float nx = q[20], ny = q[21], nz = q[22];
			float nx1 = cyw * nx + syw * nz, nz1 = -syw * nx + cyw * nz;
			float ny2 = cp * ny - sp * nz1, nz2 = sp * ny + cp * nz1;
			float light = 0.62f + 0.38f * Math.max(0, nx1 * lx + ny2 * ly + nz2 * lz) + 0.08f * Math.abs(nx1);
			tri(color, depth, W, H, sx, sy, iw, uu, vv, 0, 1, 2, px, tw, th, light);
			tri(color, depth, W, H, sx, sy, iw, uu, vv, 0, 2, 3, px, tw, th, light);
		}
		// halved: each pixel the mean of four (alpha-weighted)
		Canvas c = new Canvas(w, h);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int a = 0, r = 0, g = 0, b = 0;
				for (int dy = 0; dy < s; dy++) {
					for (int dx = 0; dx < s; dx++) {
						int p = color[(y * s + dy) * W + x * s + dx];
						int pa = p >>> 24;
						a += pa;
						r += ((p >> 16) & 255) * pa;
						g += ((p >> 8) & 255) * pa;
						b += (p & 255) * pa;
					}
				}
				if (a > 0) {
					c.set(x, y, (a / (s * s)) << 24 | (r / a) << 16 | (g / a) << 8 | b / a);
				}
			}
		}
		return c;
	}

	private static void tri(int[] color, float[] depth, int W, int H, float[] sx, float[] sy, float[] iw, float[] uu, float[] vv, int a, int b, int c,
			int[] px, int tw, int th, float light) {
		float area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sx[c] - sx[a]) * (sy[b] - sy[a]);
		if (Math.abs(area) < 1e-6f) {
			return;
		}
		int x0 = Math.max(0, (int) Math.floor(Math.min(sx[a], Math.min(sx[b], sx[c]))));
		int x1 = Math.min(W - 1, (int) Math.ceil(Math.max(sx[a], Math.max(sx[b], sx[c]))));
		int y0 = Math.max(0, (int) Math.floor(Math.min(sy[a], Math.min(sy[b], sy[c]))));
		int y1 = Math.min(H - 1, (int) Math.ceil(Math.max(sy[a], Math.max(sy[b], sy[c]))));
		for (int y = y0; y <= y1; y++) {
			float py = y + 0.5f;
			for (int x = x0; x <= x1; x++) {
				float pxf = x + 0.5f;
				float wa = ((sx[b] - pxf) * (sy[c] - py) - (sx[c] - pxf) * (sy[b] - py)) / area;
				float wb = ((sx[c] - pxf) * (sy[a] - py) - (sx[a] - pxf) * (sy[c] - py)) / area;
				float wc = 1 - wa - wb;
				if (wa < 0 || wb < 0 || wc < 0) {
					continue;
				}
				float z = wa * iw[a] + wb * iw[b] + wc * iw[c];
				int i = y * W + x;
				if (z <= depth[i]) {
					continue;
				}
				float u = (wa * uu[a] + wb * uu[b] + wc * uu[c]) / z, v = (wa * vv[a] + wb * vv[b] + wc * vv[c]) / z;
				int tx = Math.clamp((int) (u * tw), 0, tw - 1), ty = Math.clamp((int) (v * th), 0, th - 1);
				int p = px[ty * tw + tx];
				if ((p >>> 24) < 128) {
					continue;
				}
				depth[i] = z;
				int r = Math.min(255, (int) (((p >> 16) & 255) * light)), g = Math.min(255, (int) (((p >> 8) & 255) * light)), bl = Math.min(255, (int) ((p & 255) * light));
				color[i] = 0xFF000000 | r << 16 | g << 8 | bl;
			}
		}
	}
}
