package com.screenrecorder.recorder;

import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java AVI muxer (MJPEG video + PCM audio).
 *
 * Strategy: write a fixed-size placeholder header at open time,
 * then overwrite it completely in finish() with the correct values.
 * This avoids fragile offset arithmetic and stale-class bugs.
 */
public class AviWriter {

    private final int    width;
    private final int    height;
    private final int    sampleRate;
    private final int    audioChannels;
    private final int    blockAlign;    // audioChannels * 2  (16-bit PCM)

    private final RandomAccessFile raf;

    // Number of bytes occupied by the complete AVI header (written twice).
    // Must stay constant between writeHeader() and finish().
    private final int headerSize;

    // Counters updated as frames/samples are written
    private int  videoFrames  = 0;
    private long audioSamples = 0;   // in samples (not bytes)

    // idx1 entries: [chunkTag-as-int, flags, offsetFromMoviBase, size]
    private final List<long[]> index = new ArrayList<>();

    // JPEG encoder – reused across frames
    private final ImageWriter     jpegWriter;
    private final ImageWriteParam jpegParam;
    private static final float    JPEG_QUALITY = 0.5f;

    // ------------------------------------------------------------------ ctor

    public AviWriter(String path, int width, int height,
                     int sampleRate, int audioChannels) throws IOException {
        this.width         = width;
        this.height        = height;
        this.sampleRate    = sampleRate;
        this.audioChannels = audioChannels;
        this.blockAlign    = audioChannels * 2;

        jpegWriter = ImageIO.getImageWritersByFormatName("jpg").next();
        jpegParam  = jpegWriter.getDefaultWriteParam();
        jpegParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        jpegParam.setCompressionQuality(JPEG_QUALITY);

        raf = new RandomAccessFile(path, "rw");
        raf.setLength(0);

        // Write placeholder header (fps=1 placeholder; overwritten in finish())
        headerSize = writeFullHeader(1, 0, 0, 0);
    }

    // ------------------------------------------------------------------ write API

    public synchronized void writeVideoFrame(BufferedImage frame) throws IOException {
        byte[] jpeg   = toJpeg(frame);
        long   offset = raf.getFilePointer() - headerSize - 4; // -4: skip "movi" fourCC counted above
        // offset relative to start of movi LIST data (after "movi" tag)
        // Actually: off_moviBase = headerSize - 4  (movi tag is last 4 bytes of header)
        long   moviBase = headerSize - 4;
        offset = raf.getFilePointer() - moviBase;

        raf.write("00dc".getBytes());
        writeInt(jpeg.length);
        raf.write(jpeg);
        if (jpeg.length % 2 != 0) raf.write(0);

        index.add(new long[]{ 0, 0x10, offset, jpeg.length }); // [isAudio=0, flags, off, sz]
        videoFrames++;
    }

    public synchronized void writeAudioSamples(byte[] pcm, int length) throws IOException {
        long moviBase = headerSize - 4;
        long offset   = raf.getFilePointer() - moviBase;

        raf.write("01wb".getBytes());
        writeInt(length);
        raf.write(pcm, 0, length);
        if (length % 2 != 0) raf.write(0);

        index.add(new long[]{ 1, 0x00, offset, length }); // [isAudio=1, flags, off, sz]
        audioSamples += length / blockAlign;
    }

    // ------------------------------------------------------------------ finish

    public synchronized void finish(long actualElapsedMs) throws IOException {
        long moviEnd      = raf.getFilePointer();
        long moviBase     = headerSize - 4;
        long moviDataSize = moviEnd - moviBase; // bytes after "movi" tag

        // --- write idx1 ---
        raf.write("idx1".getBytes());
        writeInt(index.size() * 16);
        for (long[] e : index) {
            raf.write(e[0] == 0 ? "00dc".getBytes() : "01wb".getBytes());
            writeInt((int) e[1]);  // flags
            writeInt((int) e[2]);  // offset from movi base
            writeInt((int) e[3]);  // size
        }
        long fileEnd = raf.getFilePointer();

        // --- compute actual frame rate ---
        int fps;
        if (actualElapsedMs > 0 && videoFrames > 0) {
            double actualFps = videoFrames / (actualElapsedMs / 1000.0);
            fps = (int) Math.round(actualFps);
            if (fps < 1) fps = 1;
            System.out.printf("Actual FPS: %.2f  => using %d fps in header%n", actualFps, fps);
        } else {
            fps = 15;
        }

        // --- rewrite header from offset 0 with correct values ---
        raf.seek(0);
        int writtenHdrSize = writeFullHeader(fps, videoFrames, moviDataSize, fileEnd);
        assert writtenHdrSize == headerSize : "Header size mismatch: " + writtenHdrSize + " vs " + headerSize;

        jpegWriter.dispose();
        raf.close();
    }

    // ------------------------------------------------------------------ header writer

    /**
     * Writes (or overwrites) the complete fixed-size AVI header starting at
     * the current file position.  Returns the number of bytes written.
     *
     * @param fps           video frame rate (dwMicroSecPerFrame = 1_000_000/fps)
     * @param totalFrames   avih.dwTotalFrames and video strh.dwLength
     * @param moviDataSize  bytes in the movi LIST after the "movi" fourCC
     * @param fileSize      total file size (for RIFF size field); 0 = placeholder
     */
    private int writeFullHeader(int fps, int totalFrames,
                                long moviDataSize, long fileSize) throws IOException {
        long start = raf.getFilePointer();

        int byteRate   = sampleRate * blockAlign;
        int usec       = 1_000_000 / Math.max(fps, 1);

        // ---- RIFF AVI ----
        w4("RIFF");
        wI((int)(fileSize > 0 ? fileSize - 8 : 0));   // patched: total file - 8
        w4("AVI ");

        // ---- LIST hdrl ----
        // We'll patch its size at the end; for now write placeholder
        long hdrlListOff = raf.getFilePointer();
        w4("LIST");
        wI(0);   // hdrl list size — patched below
        w4("hdrl");

        // avih (56 bytes of data)
        w4("avih");
        wI(56);
        wI(usec);             // dwMicroSecPerFrame
        wI(0);                // dwMaxBytesPerSec
        wI(0);                // dwPaddingGranularity
        wI(0x10 | 0x20);     // dwFlags: HASINDEX | MUSTUSEINDEX
        wI(totalFrames);      // dwTotalFrames
        wI(0);                // dwInitialFrames
        wI(2);                // dwStreams
        wI(0);                // dwSuggestedBufferSize
        wI(width);
        wI(height);
        wI(0); wI(0); wI(0); wI(0);   // dwReserved[4]

        // ---- Video stream LIST strl ----
        long vstrlOff = raf.getFilePointer();
        w4("LIST"); wI(0); w4("strl");   // size patched below

        // video strh (56 bytes of data)
        w4("strh"); wI(56);
        w4("vids");           // fccType
        w4("MJPG");           // fccHandler
        wI(0);                // dwFlags
        wS(0);                // wPriority
        wS(0);                // wLanguage
        wI(0);                // dwInitialFrames
        wI(1);                // dwScale
        wI(fps);              // dwRate   (fps = dwRate / dwScale)
        wI(0);                // dwStart
        wI(totalFrames);      // dwLength
        wI(0);                // dwSuggestedBufferSize
        wI(-1);               // dwQuality
        wI(0);                // dwSampleSize
        wS(0); wS(0); wS((short)width); wS((short)height);

        // video strf = BITMAPINFOHEADER (40 bytes)
        w4("strf"); wI(40);
        wI(40);               // biSize
        wI(width);
        wI(height);
        wS(1);                // biPlanes
        wS(24);               // biBitCount
        w4("MJPG");           // biCompression
        wI(width * height * 3);
        wI(0); wI(0); wI(0); wI(0);

        patchListSize(vstrlOff);

        // ---- Audio stream LIST strl ----
        long astrlOff = raf.getFilePointer();
        w4("LIST"); wI(0); w4("strl");   // size patched below

        // audio strh (56 bytes of data)
        w4("strh"); wI(56);
        w4("auds");           // fccType
        wI(0);                // fccHandler (none for PCM)
        wI(0);                // dwFlags
        wS(0);                // wPriority
        wS(0);                // wLanguage
        wI(0);                // dwInitialFrames
        wI(blockAlign);       // dwScale   (one audio block per tick)
        wI(byteRate);         // dwRate    (bytes/sec)
        wI(0);                // dwStart
        wI((int) audioSamples); // dwLength  (total samples)
        wI(byteRate);         // dwSuggestedBufferSize
        wI(-1);               // dwQuality
        wI(blockAlign);       // dwSampleSize
        wS(0); wS(0); wS(0); wS(0);

        // audio strf = WAVEFORMATEX (18 bytes)
        w4("strf"); wI(18);
        wS(1);                // wFormatTag = PCM
        wS(audioChannels);
        wI(sampleRate);
        wI(byteRate);
        wS(blockAlign);
        wS(16);               // wBitsPerSample
        wS(0);                // cbSize

        patchListSize(astrlOff);

        // Patch hdrl LIST size
        patchListSize(hdrlListOff);

        // ---- LIST movi ----
        w4("LIST");
        wI((int)(moviDataSize > 0 ? moviDataSize + 4 : 0)); // +4 for "movi" fourCC
        w4("movi");
        // movi data follows immediately after — caller writes it

        long end = raf.getFilePointer();
        return (int)(end - start);
    }

    private void patchListSize(long listStart) throws IOException {
        long cur = raf.getFilePointer();
        // LIST chunk: "LIST"(4) + size(4) + fourCC(4) + data
        // size field covers: fourCC(4) + data
        raf.seek(listStart + 4);
        wI((int)(cur - listStart - 8 + 4));
        raf.seek(cur);
    }

    // ------------------------------------------------------------------ low-level helpers

    private void w4(String s) throws IOException { raf.write(s.getBytes()); }
    private void wI(int v) throws IOException {
        raf.write( v        & 0xFF);
        raf.write((v >>  8) & 0xFF);
        raf.write((v >> 16) & 0xFF);
        raf.write((v >> 24) & 0xFF);
    }
    private void wS(int v) throws IOException {
        raf.write( v       & 0xFF);
        raf.write((v >> 8) & 0xFF);
    }

    // Keep old names so callers still compile
    private void writeInt(int v)   throws IOException { wI(v); }
    private void writeShort(int v) throws IOException { wS(v); }

    // ------------------------------------------------------------------ JPEG encoder

    private byte[] toJpeg(BufferedImage img) throws IOException {
        BufferedImage bgr;
        if (img.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            bgr = img;
        } else {
            bgr = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            bgr.getGraphics().drawImage(img, 0, 0, null);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
        jpegWriter.setOutput(new MemoryCacheImageOutputStream(baos));
        jpegWriter.write(null, new IIOImage(bgr, null, null), jpegParam);
        return baos.toByteArray();
    }
}
