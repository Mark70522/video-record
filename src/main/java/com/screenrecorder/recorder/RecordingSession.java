package com.screenrecorder.recorder;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.*;

/**
 * Records screen + mic + system audio using FFmpeg (gdigrab) and outputs MP4.
 *
 * Flow:
 *   start()  -> FFmpeg gdigrab -> tmp_*_video.ts
 *              javax.sound mic  -> tmp_*_mic.pcm
 *              WASAPI loopback  -> tmp_*_sys.pcm
 *   stop()   -> mix PCM files  -> tmp_*_mixed.pcm
 *            -> FFmpeg mux TS + PCM -> recording_*.mp4
 *            -> delete temp files
 */
public class RecordingSession {

    public enum State { IDLE, RECORDING, PAUSED }

    private static final int MIC_BITS  = 16;
    private static final int AUDIO_BUF = 4096;

    // ffmpegPath: empty string = use "ffmpeg" from system PATH
    private String ffmpegPath = "";

    private State  state = State.IDLE;
    private String currentOutputPath;
    private final String outputDirectory;

    private long startTime;
    private long pausedDuration;
    private long pauseStart;

    private Process        ffmpegProc;
    private Thread         micThread;
    private TargetDataLine micLine;
    private WasapiLoopback wasapi;

    private File         tmpVideo;
    private File         wasapiTmp;
    private File         micTmp;
    private OutputStream wasapiOut;
    private OutputStream micOut;

    private final AtomicBoolean paused = new AtomicBoolean(false);

    private int     finalAudioRate;
    private int     finalAudioCh;
    private boolean hasSystemAudio;

    private final String  micDeviceName;
    private final boolean wasapiAvailable;

    public RecordingSession(String outputDirectory) {
        this.outputDirectory = outputDirectory;
        new File(outputDirectory).mkdirs();

        micDeviceName = detectMicName();

        WasapiLoopback probe = new WasapiLoopback();
        wasapiAvailable = probe.init();
        if (wasapiAvailable) probe.stop();

        System.out.println("Mic    : " + (micDeviceName != null ? micDeviceName : "not found"));
        System.out.println("WASAPI : " + (wasapiAvailable ? "available" : "not available"));
    }

    // ------------------------------------------------------------------ config

    public void setFfmpegPath(String path) {
        this.ffmpegPath = (path == null) ? "" : path.trim();
    }

    private String ffmpeg() {
        return ffmpegPath.isEmpty() ? "ffmpeg" : ffmpegPath;
    }

    // ------------------------------------------------------------------ start

    public void start() throws Exception {
        if (state != State.IDLE) return;

        String ts   = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String base = outputDirectory + File.separator + "tmp_" + ts;

        currentOutputPath = outputDirectory + File.separator + "recording_" + ts + ".mp4";
        tmpVideo  = new File(base + "_video.ts");
        wasapiTmp = new File(base + "_sys.pcm");
        micTmp    = new File(base + "_mic.pcm");

        // init system audio
        int audioRate = 44100, audioCh = 2;
        wasapi = new WasapiLoopback();
        boolean hasSys = wasapiAvailable && wasapi.init();
        if (hasSys) { audioRate = wasapi.sampleRate; audioCh = wasapi.channels; }
        finalAudioRate = audioRate;
        finalAudioCh   = audioCh;
        hasSystemAudio = hasSys;

        wasapiOut = hasSys ? new BufferedOutputStream(new FileOutputStream(wasapiTmp)) : null;
        micOut    = new BufferedOutputStream(new FileOutputStream(micTmp));

        paused.set(false);

        // Launch FFmpeg: capture desktop -> MPEG-TS
        // -pix_fmt yuv420p : required for broad compatibility with H.264
        // redirectInput(INHERIT) : keeps stdin detached so the JVM pipe does not
        //   cause FFmpeg to exit immediately on Windows
        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg(),
                "-f", "gdigrab",
                "-framerate", "15",
                "-draw_mouse", "1",
                "-i", "desktop",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-pix_fmt", "yuv420p",
                "-crf", "23",
                "-f", "mpegts",
                "-y",
                tmpVideo.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        ffmpegProc = pb.start();

        // Collect FFmpeg log; also used to detect immediate startup failure
        StringBuilder ffmpegLog = new StringBuilder();
        Thread logThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(ffmpegProc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[ffmpeg] " + line);
                    synchronized (ffmpegLog) { ffmpegLog.append(line).append('\n'); }
                }
            } catch (IOException ignored) {}
        }, "FFmpeg-Log");
        logThread.setDaemon(true);
        logThread.start();

        // Give FFmpeg ~2 seconds to initialise; if it exits immediately it failed
        Thread.sleep(2000);
        if (!ffmpegProc.isAlive()) {
            int code = ffmpegProc.exitValue();
            String log;
            synchronized (ffmpegLog) { log = ffmpegLog.toString(); }
            // Show the last 10 lines of FFmpeg output in the error
            String[] lines = log.split("\n");
            int start = Math.max(0, lines.length - 10);
            StringBuilder tail = new StringBuilder();
            for (int i = start; i < lines.length; i++)
                tail.append(lines[i]).append('\n');
            throw new RuntimeException(
                "FFmpeg exited immediately (code " + code + ").\n\n" + tail);
        }

        // Start mic capture
        startMicThread(audioRate, audioCh);

        // Start system audio capture
        if (hasSys) wasapi.start(wasapiOut);

        state          = State.RECORDING;
        startTime      = System.currentTimeMillis();
        pausedDuration = 0;
        System.out.println("Recording started -> " + currentOutputPath);
    }

    private void startMicThread(int rate, int ch) {
        AudioFormat fmt  = new AudioFormat(rate, MIC_BITS, ch, true, false);
        DataLine.Info di = new DataLine.Info(TargetDataLine.class, fmt);
        if (!AudioSystem.isLineSupported(di)) {
            fmt = new AudioFormat(44100, MIC_BITS, 2, true, false);
            di  = new DataLine.Info(TargetDataLine.class, fmt);
        }
        try {
            micLine = (TargetDataLine) AudioSystem.getLine(di);
            micLine.open(fmt, AUDIO_BUF * 4);
            micLine.start();
        } catch (Exception e) {
            System.err.println("Mic unavailable: " + e.getMessage());
            micLine = null;
            return;
        }
        micThread = new Thread(() -> {
            byte[] buf = new byte[AUDIO_BUF];
            while (micLine.isOpen()) {
                int n = micLine.read(buf, 0, buf.length);
                if (n <= 0 || paused.get()) continue;
                try { micOut.write(buf, 0, n); }
                catch (IOException e) { break; }
            }
        }, "Mic-Capture");
        micThread.setDaemon(true);
        micThread.start();
    }

    // ------------------------------------------------------------------ pause / resume

    public void pause() {
        if (state != State.RECORDING) return;
        paused.set(true);
        if (wasapi != null) wasapi.pause();
        state      = State.PAUSED;
        pauseStart = System.currentTimeMillis();
    }

    public void resume() {
        if (state != State.PAUSED) return;
        pausedDuration += System.currentTimeMillis() - pauseStart;
        paused.set(false);
        if (wasapi != null) wasapi.resume();
        state = State.RECORDING;
    }

    // ------------------------------------------------------------------ stop

    public String stop() throws Exception {
        if (state == State.IDLE) return null;
        state = State.IDLE;

        // Stop FFmpeg and audio captures IN PARALLEL to save time
        Thread ffmpegStopper = new Thread(this::stopFfmpeg, "FFmpeg-Stopper");
        ffmpegStopper.start();

        // Stop audio while FFmpeg is shutting down
        if (wasapi  != null) wasapi.stop();
        if (micLine != null) { micLine.stop(); micLine.close(); }
        if (micThread != null) micThread.join(2000);

        if (wasapiOut != null) { wasapiOut.flush(); wasapiOut.close(); }
        micOut.flush(); micOut.close();

        // Wait for FFmpeg to finish
        ffmpegStopper.join(12000);

        if (!tmpVideo.exists() || tmpVideo.length() == 0)
            throw new RuntimeException("FFmpeg produced no video. Check FFmpeg path.");

        // Mux: FFmpeg handles audio mixing internally (amix), no Java mixing needed
        System.out.println("Muxing to MP4...");
        muxFinal();

        // Cleanup temp files
        tmpVideo.delete();
        if (wasapiTmp != null) wasapiTmp.delete();
        micTmp.delete();

        System.out.println("Saved: " + currentOutputPath);
        return currentOutputPath;
    }

    private void stopFfmpeg() {
        if (ffmpegProc == null || !ffmpegProc.isAlive()) return;
        try {
            // TS survives a hard kill, so just force-terminate immediately
            long pid = ffmpegProc.pid();
            new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(3, TimeUnit.SECONDS);
            ffmpegProc.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            ffmpegProc.destroyForcibly();
        }
    }

    private void muxFinal() throws Exception {
        boolean hasMic = micTmp.exists()    && micTmp.length()    > 0;
        boolean hasSys = hasSystemAudio && wasapiTmp != null
                      && wasapiTmp.exists() && wasapiTmp.length() > 0;

        ProcessBuilder pb;

        if (hasMic && hasSys) {
            // Two audio inputs: mix them with amix inside FFmpeg (no Java mixing needed)
            pb = new ProcessBuilder(
                    ffmpeg(),
                    "-i",  tmpVideo.getAbsolutePath(),
                    "-f",  "s16le", "-ar", String.valueOf(finalAudioRate),
                    "-ac", String.valueOf(finalAudioCh),
                    "-i",  micTmp.getAbsolutePath(),
                    "-f",  "s16le", "-ar", String.valueOf(finalAudioRate),
                    "-ac", String.valueOf(finalAudioCh),
                    "-i",  wasapiTmp.getAbsolutePath(),
                    "-filter_complex", "[1:a][2:a]amix=inputs=2:duration=shortest[aout]",
                    "-map", "0:v",
                    "-map", "[aout]",
                    "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "192k",
                    "-shortest",
                    "-y", currentOutputPath
            );
        } else if (hasMic) {
            pb = new ProcessBuilder(
                    ffmpeg(),
                    "-i",  tmpVideo.getAbsolutePath(),
                    "-f",  "s16le", "-ar", String.valueOf(finalAudioRate),
                    "-ac", String.valueOf(finalAudioCh),
                    "-i",  micTmp.getAbsolutePath(),
                    "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "192k",
                    "-shortest",
                    "-y", currentOutputPath
            );
        } else if (hasSys) {
            pb = new ProcessBuilder(
                    ffmpeg(),
                    "-i",  tmpVideo.getAbsolutePath(),
                    "-f",  "s16le", "-ar", String.valueOf(finalAudioRate),
                    "-ac", String.valueOf(finalAudioCh),
                    "-i",  wasapiTmp.getAbsolutePath(),
                    "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "192k",
                    "-shortest",
                    "-y", currentOutputPath
            );
        } else {
            // No audio at all
            pb = new ProcessBuilder(
                    ffmpeg(),
                    "-i",  tmpVideo.getAbsolutePath(),
                    "-c:v", "copy",
                    "-an",
                    "-y", currentOutputPath
            );
        }

        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null)
                System.out.println("[ffmpeg-mux] " + line);
        }
        int code = p.waitFor();
        if (code != 0)
            throw new RuntimeException("FFmpeg mux failed (exit " + code + ")");
    }

    // ------------------------------------------------------------------ mic detection

    private static String detectMicName() {
        AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
        DataLine.Info di = new DataLine.Info(TargetDataLine.class, fmt);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer mx = AudioSystem.getMixer(mi);
            if (mx.isLineSupported(di)) {
                String name = mi.getName();
                if (!name.toLowerCase().contains("primary")) return name;
            }
        }
        for (Mixer.Info mi : AudioSystem.getMixerInfo())
            if (AudioSystem.getMixer(mi).getTargetLineInfo().length > 0)
                return mi.getName();
        return null;
    }

    // ------------------------------------------------------------------ getters

    public long    getElapsedMs() {
        if (state == State.IDLE) return 0;
        long now = System.currentTimeMillis();
        long p   = pausedDuration + (state == State.PAUSED ? now - pauseStart : 0);
        return now - startTime - p;
    }
    public State   getState()          { return state; }
    public String  getMicDeviceName()  { return micDeviceName; }
    public boolean isWasapiAvailable() { return wasapiAvailable; }
}
