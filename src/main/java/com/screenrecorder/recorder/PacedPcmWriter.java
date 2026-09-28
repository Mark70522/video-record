package com.screenrecorder.recorder;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes raw PCM so that the file position always equals "elapsed wall-clock time since t0".
 *
 * Why: the video is timestamped by FFmpeg with the wall clock, but a raw PCM file has no
 * timestamps at all - byte N simply means "N / bytesPerSecond seconds after the start".
 * Every source of drift therefore has to be corrected while writing:
 *   - audio starting later than the first video frame          -> leading silence is inserted
 *   - WASAPI loopback delivering nothing while no app plays sound -> silence is inserted
 *   - pause (caller simply stops writing)                        -> silence is inserted on resume
 *   - the sound card clock running slightly fast                 -> excess samples are dropped
 * Corrections are only applied when the error exceeds {@code toleranceMs}, so normal buffer
 * jitter never causes clicks; over a long recording the file stays within that tolerance.
 */
public class PacedPcmWriter implements PcmSink, Closeable {

    private final OutputStream out;
    private final long   t0;               // epoch ms of the first video frame
    private final long   bytesPerSecond;
    private final int    blockAlign;       // channels * 2 bytes
    private final long   toleranceBytes;
    private final byte[] zeros = new byte[16384];

    private long written;
    private long padded;
    private long dropped;
    private boolean closed;

    public PacedPcmWriter(OutputStream out, int sampleRate, int channels, long t0Millis, int toleranceMs) {
        this.out            = out;
        this.t0             = t0Millis;
        this.blockAlign     = channels * 2;
        this.bytesPerSecond = (long) sampleRate * blockAlign;
        this.toleranceBytes = align(bytesPerSecond * toleranceMs / 1000);
    }

    /** {@code nowMillis} is the wall-clock time at which the last sample in {@code buf} was captured. */
    @Override
    public synchronized void write(byte[] buf, int off, int len, long nowMillis) throws IOException {
        if (closed || len <= 0) return;
        long expected  = expectedBytes(nowMillis);
        long behind    = expected - (written + len);

        if (behind > toleranceBytes) {
            pad(align(behind));                       // gap: fill with silence first
        } else if (-behind > toleranceBytes) {
            long drop = align(-behind);               // ahead: drop the oldest samples
            dropped += Math.min(drop, len);
            if (drop >= len) return;
            off += drop;
            len -= drop;
        }
        out.write(buf, off, len);
        written += len;
    }

    /** Pads with silence up to {@code nowMillis} so every track ends at the same instant. */
    public synchronized void finish(long nowMillis) throws IOException {
        if (closed) return;
        long behind = expectedBytes(nowMillis) - written;
        if (behind > 0) pad(align(behind));
        out.flush();
        out.close();
        closed = true;
    }

    @Override
    public void close() throws IOException {
        finish(System.currentTimeMillis());
    }

    public long bytesWritten() { return written; }
    public long bytesPadded()  { return padded; }
    public long bytesDropped() { return dropped; }

    public String stats() {
        return String.format("written=%.1fs padded=%.2fs dropped=%.2fs",
                written / (double) bytesPerSecond, padded / (double) bytesPerSecond,
                dropped / (double) bytesPerSecond);
    }

    // ------------------------------------------------------------------ helpers

    private long expectedBytes(long nowMillis) {
        long ms = nowMillis - t0;
        if (ms <= 0) return 0;
        return align(ms * bytesPerSecond / 1000);
    }

    private void pad(long n) throws IOException {
        while (n > 0) {
            int chunk = (int) Math.min(n, zeros.length);
            out.write(zeros, 0, chunk);
            n       -= chunk;
            written += chunk;
            padded  += chunk;
        }
    }

    private long align(long bytes) {
        return bytes - (bytes % blockAlign);
    }
}
