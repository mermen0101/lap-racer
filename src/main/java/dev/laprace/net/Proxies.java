package dev.laprace.net;

import dev.laprace.LapConfig;
import dev.laprace.LapRaceClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;

/**
 * The proxy to reach our server through (settings: none / the system's / host:port). A VPN app in "system proxy" mode
 * sets the system's, which Java does not follow by itself: read here from the Windows settings (or HTTPS_PROXY).
 * Only HTTP proxies (Java's client speaks no SOCKS); VPN apps' local ports are usually both.
 */
final class Proxies {
	private Proxies() {
	}

	/** The proxy for this server, or null: straight there. */
	static InetSocketAddress forServer(LapConfig c, String serverUrl) {
		String host = URI.create(serverUrl).getHost();
		if (host == null || host.equals("localhost") || host.startsWith("127.")) {
			return null;
		}
		String addr = switch (c.proxy) {
			case OFF -> null;
			case MANUAL -> c.proxyAddress;
			case SYSTEM -> system();
		};
		InetSocketAddress a = parse(addr);
		if (c.proxy != LapConfig.ProxyMode.OFF) {
			LapRaceClient.LOG.info("Lap Race server: proxy {}", a == null ? "none found, direct" : a.getHostString() + ":" + a.getPort());
		}
		return a;
	}

	/** A client for our server, through this proxy (null: as before, Java's default). */
	static java.net.http.HttpClient client(InetSocketAddress a) {
		var b = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(a == null ? 5 : 10));
		if (a != null) {
			b.proxy(ProxySelector.of(a));
		}
		return b.build();
	}

	/** "host:port", "http://host:port", "127.0.0.1:10809" -> the address; null if not one. */
	static InetSocketAddress parse(String s) {
		if (s == null) {
			return null;
		}
		s = s.trim().replaceFirst("^[a-zA-Z]+://", "").replaceFirst("/.*$", "");
		int i = s.lastIndexOf(':');
		if (i <= 0) {
			return null;
		}
		try {
			int port = Integer.parseInt(s.substring(i + 1));
			return port > 0 && port < 65536 ? InetSocketAddress.createUnresolved(s.substring(0, i), port) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The system's proxy: Windows' internet settings (where VPN apps put theirs), else HTTPS_PROXY / HTTP_PROXY. */
	private static String system() {
		if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
			try {
				Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings")
						.redirectErrorStream(true).start();
				String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				p.waitFor();
				boolean on = false;
				String server = null;
				for (String line : out.split("\\R")) {
					String[] f = line.trim().split("\\s+", 3);
					if (f.length == 3 && f[0].equals("ProxyEnable")) {
						on = f[2].trim().equals("0x1");
					} else if (f.length == 3 && f[0].equals("ProxyServer")) {
						server = f[2].trim();
					}
				}
				if (on && server != null) {
					// "host:port" or "http=host:port;https=host:port;socks=..."
					if (!server.contains("=")) {
						return server;
					}
					String http = null;
					for (String part : server.split(";")) {
						String[] kv = part.split("=", 2);
						if (kv.length == 2 && kv[0].equalsIgnoreCase("https")) {
							return kv[1];
						}
						if (kv.length == 2 && kv[0].equalsIgnoreCase("http")) {
							http = kv[1];
						}
					}
					return http;
				}
			} catch (Exception e) {
				LapRaceClient.LOG.debug("system proxy: {}", e.toString());
			}
		}
		for (String k : new String[] {"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"}) {
			String v = System.getenv(k);
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return null;
	}
}
