package com.screenrecorder;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Persists application settings to %APPDATA%\ScreenRecorder\config.properties
 */
public class AppConfig {

    private static final String APP_NAME   = "ScreenRecorder";
    private static final String FILE_NAME  = "config.properties";

    private static final String KEY_FFMPEG     = "ffmpeg.path";
    private static final String KEY_OUTPUT_DIR = "output.dir";

    private final Path configFile;
    private final Properties props = new Properties();

    public AppConfig() {
        String appData = System.getenv("APPDATA");
        if (appData == null) appData = System.getProperty("user.home");
        configFile = Paths.get(appData, APP_NAME, FILE_NAME);
        load();
    }

    private void load() {
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Failed to load config: " + e.getMessage());
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(configFile.getParent());
            try (OutputStream out = Files.newOutputStream(configFile)) {
                props.store(out, "ScreenRecorder config");
            }
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }

    public String getFfmpegPath() {
        return props.getProperty(KEY_FFMPEG, "");
    }

    public void setFfmpegPath(String path) {
        props.setProperty(KEY_FFMPEG, path == null ? "" : path.trim());
        save();
    }

    public String getOutputDir() {
        String def = System.getProperty("user.home") + File.separator + "Desktop"
                   + File.separator + "Recordings";
        return props.getProperty(KEY_OUTPUT_DIR, def);
    }

    public void setOutputDir(String dir) {
        props.setProperty(KEY_OUTPUT_DIR, dir == null ? "" : dir.trim());
        save();
    }
}
