package dev.laprace.car;

import dev.laprace.LapRaceClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The system's own "open file" window, for a PNG (the game has none): on Windows through PowerShell (on top of the
 * game), on a Mac through AppleScript, on Linux through zenity or kdialog. Off the game's thread; null if nothing was
 * chosen.
 */
public final class FilePick {
	private FilePick() {
	}

	public static CompletableFuture<Path> png(Path startIn) {
		return CompletableFuture.supplyAsync(() -> {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String dir = startIn == null ? "" : startIn.toAbsolutePath().toString();
			try {
				String[] cmd;
				if (os.contains("win")) {
					String ps = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;Add-Type -AssemblyName System.Windows.Forms;"
							+ "$o=New-Object System.Windows.Forms.Form -Property @{TopMost=$true;ShowInTaskbar=$false};"
							+ "$d=New-Object System.Windows.Forms.OpenFileDialog;$d.Filter='PNG (*.png)|*.png';$d.Title='Lap Race';"
							+ "$d.InitialDirectory='" + dir.replace("'", "''") + "';"
							+ "if($d.ShowDialog($o) -eq [System.Windows.Forms.DialogResult]::OK){[Console]::Out.Write($d.FileName)}";
					cmd = new String[] {"powershell", "-NoProfile", "-STA", "-Command", ps};
				} else if (os.contains("mac")) {
					cmd = new String[] {"osascript", "-e", "POSIX path of (choose file of type {\"png\", \"public.png\"})"};
				} else {
					cmd = new String[] {"zenity", "--file-selection", "--file-filter=*.png", "--filename=" + dir + "/"};
				}
				String out = run(cmd);
				if (out == null && !os.contains("win") && !os.contains("mac")) {
					out = run(new String[] {"kdialog", "--getopenfilename", dir, "*.png"});
				}
				if (out == null || out.isBlank()) {
					return null;
				}
				Path p = Path.of(out.trim());
				return Files.isRegularFile(p) ? p : null;
			} catch (Exception e) {
				LapRaceClient.LOG.warn("file pick: {}", e.toString());
				return null;
			}
		});
	}

	private static String run(String[] cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
			byte[] out = p.getInputStream().readAllBytes();
			p.waitFor();
			return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
		} catch (Exception e) {
			return null;
		}
	}
}
