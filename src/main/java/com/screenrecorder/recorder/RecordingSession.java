package com.screenrecorder.recorder;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.*;

/**
 * Records screen + mic + system audio using FFmpeg (gdigrab) and outputs MP4.
 *
 * Flow:
 *   start()  -> FFmpeg gdigrab -> tmp_*_video.ts
 *              javax.sound mic  -> tmp_*_mic.pcm   (PacedPcmWriter, aligned to t0)
 *              WASAPI loopback  -> tmp_*_sys.pcm   (PacedPcmWriter, aligned to t0)
 *   stop()   -> FFmpeg mux TS + PCM (amix) -> recording_*.mp4
 *            -> delete temp files
 *
 * Synchronisation model
 * ---------------------
 * FFmpeg stamps every video frame with the wall clock and prints the epoch time of the
 * first frame ("start: 1790606477.193109") when it opens the gdigrab input. That value is
 * t0. Both audio tracks are written through PacedPcmWriter, which keeps the file position
 * equal to (wall clock - t0) by inserting silence for gaps (late start, WASAPI loopback
 * silence, pause) and dropping samples when a device clock runs fast. Because every byte
 * of PCM then maps to a fixed instant after t0, the tracks line up with the video without
 * any offsets at mux time.
 *
 * Pause: the video keeps recording (FFmpeg cannot pause gdigrab) and the audio writers fill
 * the gap with silence, so the timeline stays continuous. The paused intervals are
 * remembered and cut out of both video and audio at mux time.
 */
public class RecordingSession {

    public enum State { IDLE, RECORDING, PAUSED }

    private static final int MIC_BITS       = 16;
    private static final int AUDIO_BUF      = 4096;
    private static final int SYNC_TOLERANCE_MS = 60;     // correction threshold for PacedPcmWriter
    private static final int FFMPEG_START_TIMEOUT_S = 15;

    // "Duration: N/A, start: 1790606477.193109, bitrate: ..." printed for the gdigrab input
    private static final Pattern START_LINE = Pattern.compile("start:\\s*([0-9]+(?:\\.[0-9]+)?)");

    // ffmpegPath: empty string = use "ffmpeg" from system PATH
    private String ffmpegPath = "";

    private State  state = State.IDLE;
    private String currentOutputPath;
    private final String outputDirectory;

    private long startTime;
    private long pausedDuration;
    private long pauseStart;

    private Process        ffmpegProc;
    private OutputStream   ffmpegStdin;
    private Thread         micThread;
    private TargetDataLine micLine;
    private WasapiLoopback wasapi;

    private File tmpVideo;
    private File wasapiTmp;
    private File micTmp;
    private PacedPcmWriter wasapiOut;
    private PacedPcmWriter micOut;

    private final AtomicBoolean paused  = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Epoch millis of the first video frame; the time origin of every track. */
    private long t0;
    /** Paused intervals in seconds relative to t0: {start, end}. */
    private final List<double[]> pauses = new ArrayList<>();

    private int     micRate, micCh;          // format the mic line actually opened with
    private int     sysRate, sysCh;          // format WASAPI delivers (after float->16bit)
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
        pauses.clear();
        paused.set(false);
        running.set(true);

        // 1. Prepare audio devices first (open, but do not capture yet) so that they can
        //    start the instant we know t0.
        wasapi = new WasapiLoopback();
        hasSystemAudio = wasapiAvailable && wasapi.init();
        if (hasSystemAudio) { sysRate = wasapi.sampleRate; sysCh = wasapi.channels; }

        openMicLine(hasSystemAudio ? sysRate : 44100, hasSystemAudio ? sysCh : 2);

        // 2. Launch FFmpeg: capture desktop -> MPEG-TS
        // -pix_fmt yuv420p : required for broad compatibility with H.264
        // -vf crop=trunc(iw/2)*2:trunc(ih/2)*2 : force EVEN width/height.
        //   gdigrab "desktop" grabs the whole virtual desktop (all monitors).
        //   With an extended/multi-monitor or DPI-scaled layout the combined
        //   size is often an ODD number of pixels, which libx264 + yuv420p
        //   reject -> the encoder aborts and the file is unplayable. Cropping
        //   one row/column makes any virtual-desktop size encodable.
        // -g 30 : a keyframe at least every ~2s keeps the file seekable and
        //   limits how much tail is lost if FFmpeg is force-killed.
        // stdin is left as a PIPE (default) so stop() can send 'q' for a
        //   graceful shutdown; we never close it early (an EOF on stdin would
        //   make FFmpeg quit immediately on Windows).
        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg(),
                "-hide_banner",
                "-f", "gdigrab",
                "-framerate", "15",
                "-draw_mouse", "1",
                "-i", "desktop",
                "-vf", "crop=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-pix_fmt", "yuv420p",
                "-crf", "28",
                "-g", "30",
                "-f", "mpegts",
                "-y",
                tmpVideo.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        long launchWall = System.currentTimeMillis();
        ffmpegProc  = pb.start();
        ffmpegStdin = ffmpegProc.getOutputStream();

        // 3. Read FFmpeg's log. The first "start: <epoch seconds>" line is the wall-clock
        //    time of the first captured frame = t0 for all tracks.
        final StringBuilder ffmpegLog = new StringBuilder();
        final CountDownLatch startSeen = new CountDownLatch(1);
        final long[] startEpochMs = { -1 };
        Thread logThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(ffmpegProc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[ffmpeg] " + line);
                    synchronized (ffmpegLog) { ffmpegLog.append(line).append('\n'); }
                    if (startSeen.getCount() > 0) {
                        Matcher m = START_LINE.matcher(line);
                        if (m.find()) {
                            startEpochMs[0] = Math.round(Double.parseDouble(m.group(1)) * 1000.0);
                            startSeen.countDown();
                        }
                    }
                }
            } catch (IOException ignored) {}
            startSeen.countDown();   // process ended without a start line
        }, "FFmpeg-Log");
        logThread.setDaemon(true);
        logThread.start();

        startSeen.await(FFMPEG_START_TIMEOUT_S, TimeUnit.SECONDS);

        if (!ffmpegProc.isAlive()) {
            int code = ffmpegProc.exitValue();
            String log;
            synchronized (ffmpegLog) { log = ffmpegLog.toString(); }
            closeAudioDevices();
            throw new RuntimeException(
                "FFmpeg exited immediately (code " + code + ").\n\n" + tail(log, 10));
        }

        if (startEpochMs[0] > 0) {
            t0 = startEpochMs[0];
        } else {
            // Should not happen with a normal FFmpeg build; fall back to the launch time.
            t0 = launchWall;
            System.err.println("FFmpeg did not report a start timestamp, using launch time as t0");
        }
        System.out.printf("t0 = %d (first video frame, %d ms after launch)%n", t0, t0 - launchWall);

        // 4. Start the audio captures, every byte aligned to t0.
        micOut = (micLine != null)
                ? new PacedPcmWriter(new BufferedOutputStream(new FileOutputStream(micTmp)),
                                     micRate, micCh, t0, SYNC_TOLERANCE_MS)
                : null;
        wasapiOut = hasSystemAudio
                ? new PacedPcmWriter(new BufferedOutputStream(new FileOutputStream(wasapiTmp)),
                                     sysRate, sysCh, t0, SYNC_TOLERANCE_MS)
                : null;

        if (micLine != null) startMicThread();
        if (hasSystemAudio) wasapi.start(wasapiOut);

        state          = State.RECORDING;
        startTime      = System.currentTimeMillis();
        pausedDuration = 0;
        System.out.println("Recording started -> " + currentOutputPath);
    }

    /** Opens the mic line without starting it; records the format it really got. */
    private void openMicLine(int wantRate, int wantCh) {
        AudioFormat fmt  = new AudioFormat(wantRate, MIC_BITS, wantCh, true, false);
        DataLine.Info di = new DataLine.Info(TargetDataLine.class, fmt);
        if (!AudioSystem.isLineSupported(di)) {
            fmt = new AudioFormat(44100, MIC_BITS, 2, true, false);
            di  = new DataLine.Info(TargetDataLine.class, fmt);
        }
        try {
            micLine = (TargetDataLine) AudioSystem.getLine(di);
            micLine.open(fmt, AUDIO_BUF * 4);
            AudioFormat got = micLine.getFormat();
            micRate = (int) got.getSampleRate();
            micCh   = got.getChannels();
            System.out.printf("Mic format: %dHz %dch%n", micRate, micCh);
        } catch (Exception e) {
            System.err.println("Mic unavailable: " + e.getMessage());
            micLine = null;
        }
    }

    private void startMicThread() {
        micLine.start();
        micThread = new Thread(() -> {
            byte[] buf = new byte[AUDIO_BUF];
            while (running.get() && micLine.isOpen()) {
                int n = micLine.read(buf, 0, buf.length);
                if (n <= 0) continue;
                // While paused the samples are discarded; PacedPcmWriter fills the gap with
                // silence on resume so the file stays aligned with the video.
                if (paused.get()) continue;
                try { micOut.write(buf, 0, n, System.currentTimeMillis()); }
                catch (IOException e) { break; }
            }
        }, "Mic-Capture");
        micThread.setDaemon(true);
        micThread.start();
    }

    private void closeAudioDevices() {
        if (wasapi != null && hasSystemAudio) { try { wasapi.stop(); } catch (Exception ignored) {} }
        if (micLine != null) { try { micLine.close(); } catch (Exception ignored) {} micLine = null; }
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
        long now = System.currentTimeMillis();
        pausedDuration += now - pauseStart;
        pauses.add(new double[]{ (pauseStart - t0) / 1000.0, (now - t0) / 1000.0 });
        paused.set(false);
        if (wasapi != null) wasapi.resume();
        state = State.RECORDING;
    }

    // ------------------------------------------------------------------ stop

    public String stop() throws Exception {
        if (state == State.IDLE) return null;
        long stopWall = System.currentTimeMillis();
        if (state == State.PAUSED) {
            pauses.add(new double[]{ (pauseStart - t0) / 1000.0, (stopWall - t0) / 1000.0 });
        }
        state = State.IDLE;
        running.set(false);

        // Stop FFmpeg and audio captures IN PARALLEL to save time
        Thread ffmpegStopper = new Thread(this::stopFfmpeg, "FFmpeg-Stopper");
        ffmpegStopper.start();

        // Stop audio while FFmpeg is shutting down
        if (wasapi != null && hasSystemAudio) wasapi.stop();
        if (micLine != null) { micLine.stop(); micLine.close(); }
        if (micThread != null) micThread.join(2000);

        // Pad both tracks to the same end instant
        if (wasapiOut != null) { wasapiOut.finish(stopWall); System.out.println("sys audio: " + wasapiOut.stats()); }
        if (micOut    != null) { micOut.finish(stopWall);    System.out.println("mic audio: " + micOut.stats()); }

        // Wait for FFmpeg to finish. Must outlast stopFfmpeg()'s graceful
        // window (10s) + force-kill fallback (3s+3s) so the mux never starts
        // while FFmpeg is still writing the TS.
        ffmpegStopper.join(20000);

        if (!tmpVideo.exists() || tmpVideo.length() == 0)
            throw new RuntimeException("FFmpeg produced no video. Check FFmpeg path.");

        System.out.println("Muxing to MP4..." + (pauses.isEmpty() ? "" : " (cutting " + pauses.size() + " paused interval(s))"));
        muxFinal();

        // Cleanup temp files
        tmpVideo.delete();
        if (wasapiTmp != null) wasapiTmp.delete();
        if (micTmp != null) micTmp.delete();

        System.out.println("Saved: " + currentOutputPath);
        return currentOutputPath;
    }

    private void stopFfmpeg() {
        if (ffmpegProc == null || !ffmpegProc.isAlive()) return;
        try {
            // Graceful stop: send 'q' on stdin so FFmpeg flushes the encoder,
            // writes the buffered frames and a clean stream tail. A hard kill
            // truncates whatever is still buffered, which grows with recording
            // length -> longer recordings end up with a broken tail that some
            // players refuse to open. Only force-kill if the graceful quit hangs.
            if (ffmpegStdin != null) {
                try {
                    ffmpegStdin.write('q');
                    ffmpegStdin.flush();
                } catch (IOException ignored) {}
            }
            if (!ffmpegProc.waitFor(10, TimeUnit.SECONDS)) {
                // Graceful quit timed out -> hard kill. Java 8 has no Process.pid()
                // (that's Java 9+), so use destroyForcibly() instead of taskkill /PID.
                ffmpegProc.destroyForcibly();
                ffmpegProc.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            ffmpegProc.destroyForcibly();
        } finally {
            try { if (ffmpegStdin != null) ffmpegStdin.close(); }
            catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------------ mux

    private void muxFinal() throws Exception {
        boolean hasMic = micOut    != null && micTmp.exists()    && micTmp.length()    > 0;
        boolean hasSys = wasapiOut != null && wasapiTmp.exists() && wasapiTmp.length() > 0;

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg());
        cmd.add("-hide_banner");
        cmd.add("-i"); cmd.add(tmpVideo.getAbsolutePath());

        // Each PCM file starts exactly at t0 (= first video frame), so no -itsoffset is needed.
        List<String> audioLabels = new ArrayList<>();
        int inputIdx = 1;
        if (hasMic) {
            cmd.add("-f"); cmd.add("s16le");
            cmd.add("-ar"); cmd.add(String.valueOf(micRate));
            cmd.add("-ac"); cmd.add(String.valueOf(micCh));
            cmd.add("-i"); cmd.add(micTmp.getAbsolutePath());
            audioLabels.add("[" + (inputIdx++) + ":a]");
        }
        if (hasSys) {
            cmd.add("-f"); cmd.add("s16le");
            cmd.add("-ar"); cmd.add(String.valueOf(sysRate));
            cmd.add("-ac"); cmd.add(String.valueOf(sysCh));
            cmd.add("-i"); cmd.add(wasapiTmp.getAbsolutePath());
            audioLabels.add("[" + (inputIdx++) + ":a]");
        }

        StringBuilder fc = new StringBuilder();
        String videoMap = "0:v";
        String audioMap = null;

        if (audioLabels.size() == 2) {
            // Mix mic + system audio. normalize=0 keeps each at its real level (the default
            // halves both); the limiter only acts if both peak at the same instant.
            fc.append(audioLabels.get(0)).append(audioLabels.get(1))
              .append("amix=inputs=2:duration=first:dropout_transition=0:normalize=0,")
              .append("alimiter=limit=0.95:level=false[a0]");
            audioMap = "[a0]";
        } else if (audioLabels.size() == 1) {
            audioMap = audioLabels.get(0);
        }

        boolean reencodeVideo = false;
        if (!pauses.isEmpty()) {
            // Remove the paused intervals from both streams and rebuild continuous timestamps.
            String keep = "not(" + pauseExpr() + ")";
            if (fc.length() > 0) fc.append(';');
            fc.append("[0:v]select='").append(keep).append("',setpts=N/FRAME_RATE/TB[v]");
            videoMap = "[v]";
            reencodeVideo = true;
            if (audioMap != null) {
                fc.append(';').append(audioMap)
                  .append("aselect='").append(keep).append("',asetpts=N/SR/TB[a]");
                audioMap = "[a]";
            }
        }

        if (fc.length() > 0) { cmd.add("-filter_complex"); cmd.add(fc.toString()); }
        cmd.add("-map"); cmd.add(videoMap);
        if (audioMap != null) { cmd.add("-map"); cmd.add(audioMap); } else { cmd.add("-an"); }

        if (reencodeVideo) {
            cmd.add("-c:v"); cmd.add("libx264");
            cmd.add("-preset"); cmd.add("veryfast");
            cmd.add("-pix_fmt"); cmd.add("yuv420p");
            cmd.add("-crf"); cmd.add("28");
        } else {
            cmd.add("-c:v"); cmd.add("copy");
        }
        if (audioMap != null) {
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add("160k");
        }
        cmd.add("-movflags"); cmd.add("+faststart");
        cmd.add("-y"); cmd.add(currentOutputPath);

        System.out.println("[ffmpeg-mux] " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder log = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println("[ffmpeg-mux] " + line);
                log.append(line).append('\n');
            }
        }
        int code = p.waitFor();
        if (code != 0)
            throw new RuntimeException("FFmpeg mux failed (exit " + code + ").\n\n" + tail(log.toString(), 10));
    }

    /** between(t,a,b)+between(t,c,d)+... : > 0 while inside any paused interval. */
    private String pauseExpr() {
        StringBuilder sb = new StringBuilder();
        for (double[] p : pauses) {
            if (sb.length() > 0) sb.append('+');
            sb.append(String.format(Locale.ROOT, "between(t,%.3f,%.3f)", p[0], p[1]));
        }
        return sb.toString();
    }

    private static String tail(String log, int lines) {
        String[] all = log.split("\n");
        int start = Math.max(0, all.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < all.length; i++) sb.append(all[i]).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ mic detection

    private static String detectMicName() {
        AudioFormat fmt = new AudioFormat(44100, 16, 2, true, false);
        DataLine.Info di = new DataLine.Info(TargetDataLine.class, fmt);
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer mx = AudioSystem.getMixer(mi);
            if (mx.isLineSupported(di)) {
                String name = mi.getName();
                if (!name.toLowerCase().contains("primary")) return fixDeviceName(name);
            }
        }
        for (Mixer.Info mi : AudioSystem.getMixerInfo())
            if (AudioSystem.getMixer(mi).getTargetLineInfo().length > 0)
                return fixDeviceName(mi.getName());
        return null;
    }

    // Windows 下 javax.sound 返回的设备名是系统 ANSI 码页(简体中文=GBK)的字节,
    // 被 JVM 按 Latin-1 解码 → 一串 Âó¿¸ç 之类的乱码。
    // 仅当整串都落在 Latin-1 范围(≤0xFF)且含高位字节(>0x7F)这一乱码特征时,
    //   按 GBK 还原;纯英文名或本就是真正 CJK 的名字保持不动,避免误伤。
    private static String fixDeviceName(String s) {
        if (s == null || s.isEmpty()) return s;
        boolean hasHigh = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0xFF) return s;        // 含真正的非 Latin-1 字符(如已正确的中文)→ 不动
            if (c > 0x7F) hasHigh = true;  // 高位 Latin-1 字符 = 乱码嫌疑
        }
        if (!hasHigh) return s;            // 纯 ASCII → 不动
        try {
            return new String(s.getBytes("ISO-8859-1"), "GBK");
        } catch (Exception e) {
            return s;
        }
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
