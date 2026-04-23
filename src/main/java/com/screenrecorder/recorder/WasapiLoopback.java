package com.screenrecorder.recorder;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.*;

import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures system audio (speakers / headphones) using Windows WASAPI loopback.
 * Works on all Windows 7+ machines with no additional drivers.
 *
 * Uses JNA to call the Windows Core Audio COM interfaces directly:
 *   IMMDeviceEnumerator -> IMMDevice -> IAudioClient -> IAudioCaptureClient
 */
public class WasapiLoopback {

    // ---- COM constants ----
    private static final int CLSCTX_ALL                    = 0x17;
    private static final int AUDCLNT_SHAREMODE_SHARED      = 0;
    private static final int AUDCLNT_STREAMFLAGS_LOOPBACK  = 0x00020000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT    = 0x2;
    private static final int eRender                       = 0;
    private static final int eConsole                      = 0;

    // ---- COM GUIDs ----
    private static final Guid.GUID CLSID_MMDeviceEnumerator =
            Ole32Util.getGUIDFromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
    private static final Guid.GUID IID_IMMDeviceEnumerator =
            Ole32Util.getGUIDFromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
    private static final Guid.GUID IID_IAudioClient =
            Ole32Util.getGUIDFromString("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}");
    private static final Guid.GUID IID_IAudioCaptureClient =
            Ole32Util.getGUIDFromString("{C8ADBD64-E71E-48a0-A4DE-185C395CD317}");

    // ---- WAVEFORMATEX structure ----
    public static class WAVEFORMATEX extends Structure {
        public short wFormatTag;
        public short nChannels;
        public int   nSamplesPerSec;
        public int   nAvgBytesPerSec;
        public short nBlockAlign;
        public short wBitsPerSample;
        public short cbSize;
        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList(
                "wFormatTag","nChannels","nSamplesPerSec",
                "nAvgBytesPerSec","nBlockAlign","wBitsPerSample","cbSize");
        }
    }

    // ---- detected audio format (set after init) ----
    public int sampleRate   = 44100;
    public int channels     = 2;
    public int bitsPerSample = 16;

    // ---- COM object pointers ----
    private Pointer enumerator;
    private Pointer device;
    private Pointer audioClient;
    private Pointer captureClient;
    private Pointer nativeFmtPtr;

    // ---- capture state ----
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused  = new AtomicBoolean(false);
    private Thread captureThread;
    private OutputStream output;

    // ------------------------------------------------------------------ init

    /**
     * Initialises WASAPI loopback on the default audio output device.
     * @return true if successful
     */
    public boolean init() {
        try {
            Ole32.INSTANCE.CoInitializeEx(null, 0 /* COINIT_MULTITHREADED */);

            // Create IMMDeviceEnumerator
            PointerByReference ppEnum = new PointerByReference();
            int hr = Ole32.INSTANCE.CoCreateInstance(
                    CLSID_MMDeviceEnumerator, null, CLSCTX_ALL,
                    IID_IMMDeviceEnumerator, ppEnum).intValue();
            if (failed(hr, "CoCreateInstance")) return false;
            enumerator = ppEnum.getValue();

            // GetDefaultAudioEndpoint(eRender, eConsole, &ppDevice)  [vtable index 4]
            PointerByReference ppDevice = new PointerByReference();
            hr = call(enumerator, 4, enumerator, eRender, eConsole, ppDevice);
            if (failed(hr, "GetDefaultAudioEndpoint")) return false;
            device = ppDevice.getValue();

            // IMMDevice::Activate(IID_IAudioClient, CLSCTX_ALL, null, &ppClient)  [vtable index 3]
            PointerByReference ppClient = new PointerByReference();
            hr = call(device, 3, device, IID_IAudioClient, CLSCTX_ALL, null, ppClient);
            if (failed(hr, "Activate")) return false;
            audioClient = ppClient.getValue();

            // IAudioClient::GetMixFormat(&ppFormat)  [vtable index 8]
            PointerByReference ppFmt = new PointerByReference();
            hr = call(audioClient, 8, audioClient, ppFmt);
            if (failed(hr, "GetMixFormat")) return false;
            nativeFmtPtr = ppFmt.getValue();

            // Read the format
            WAVEFORMATEX wfx = Structure.newInstance(WAVEFORMATEX.class, nativeFmtPtr);
            wfx.read();
            sampleRate    = wfx.nSamplesPerSec;
            channels      = wfx.nChannels;
            bitsPerSample = wfx.wBitsPerSample;
            System.out.printf("WASAPI format: %dHz %dch %dbit%n",
                    sampleRate, channels, bitsPerSample);

            // IAudioClient::Initialize(SHARED, LOOPBACK, 0, 0, pFormat, null)  [vtable index 3]
            hr = call(audioClient, 3,
                    audioClient,
                    AUDCLNT_SHAREMODE_SHARED,
                    AUDCLNT_STREAMFLAGS_LOOPBACK,
                    0L, 0L,          // hnsBufferDuration, hnsPeriodicity (REFERENCE_TIME = long)
                    nativeFmtPtr,
                    null);
            if (failed(hr, "Initialize")) return false;

            // IAudioClient::GetService(IID_IAudioCaptureClient, &ppCapture)  [vtable index 14]
            PointerByReference ppCapture = new PointerByReference();
            hr = call(audioClient, 14, audioClient, IID_IAudioCaptureClient, ppCapture);
            if (failed(hr, "GetService")) return false;
            captureClient = ppCapture.getValue();

            return true;

        } catch (Exception e) {
            System.err.println("WASAPI init error: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ capture

    public void start(OutputStream out) {
        this.output = out;
        running.set(true);
        paused.set(false);
        // IAudioClient::Start  [vtable index 10]
        call(audioClient, 10, audioClient);
        captureThread = new Thread(this::captureLoop, "WASAPI-Loopback");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private void captureLoop() {
        IntByReference packetSize = new IntByReference();
        IntByReference numFrames  = new IntByReference();
        IntByReference flags      = new IntByReference();
        PointerByReference ppData = new PointerByReference();

        while (running.get()) {
            try {
                // GetNextPacketSize  [vtable index 5]
                call(captureClient, 5, captureClient, packetSize);

                while (packetSize.getValue() > 0) {
                    // GetBuffer  [vtable index 3]
                    call(captureClient, 3, captureClient, ppData, numFrames, flags, null, null);

                    int frames        = numFrames.getValue();
                    int bytesPerFrame = channels * (bitsPerSample / 8);
                    int byteCount     = frames * bytesPerFrame;
                    boolean silent    = (flags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0;

                    if (!paused.get() && byteCount > 0) {
                        byte[] buf = silent
                                ? new byte[byteCount]
                                : ppData.getValue().getByteArray(0, byteCount);
                        // Convert 32-bit float to 16-bit PCM if necessary
                        if (bitsPerSample == 32) buf = floatTo16(buf);
                        output.write(buf);
                    }

                    // ReleaseBuffer  [vtable index 4]
                    call(captureClient, 4, captureClient, frames);

                    // GetNextPacketSize again
                    call(captureClient, 5, captureClient, packetSize);
                }

                Thread.sleep(10);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) System.err.println("WASAPI capture error: " + e.getMessage());
                break;
            }
        }
    }

    public void pause()  { paused.set(true); }
    public void resume() { paused.set(false); }

    public void stop() {
        running.set(false);
        if (audioClient != null) call(audioClient, 11, audioClient); // Stop
        if (captureThread != null) captureThread.interrupt();
        // Release COM objects (Release = vtable index 2)
        if (captureClient  != null) call(captureClient,  2, captureClient);
        if (audioClient    != null) call(audioClient,    2, audioClient);
        if (nativeFmtPtr   != null) Ole32.INSTANCE.CoTaskMemFree(nativeFmtPtr);
        if (device         != null) call(device,         2, device);
        if (enumerator     != null) call(enumerator,     2, enumerator);
        Ole32.INSTANCE.CoUninitialize();
    }

    // ------------------------------------------------------------------ helpers

    /** Invokes a COM vtable method and returns HRESULT. */
    private static int call(Pointer comObj, int vtableIndex, Object... args) {
        Pointer vtable  = comObj.getPointer(0);
        Pointer funcPtr = vtable.getPointer((long) vtableIndex * Native.POINTER_SIZE);
        return Function.getFunction(funcPtr).invokeInt(args);
    }

    private static boolean failed(int hr, String name) {
        if (hr != 0) {
            System.err.printf("WASAPI %s failed: 0x%08X%n", name, hr);
            return true;
        }
        return false;
    }

    /**
     * Converts IEEE 754 32-bit float PCM to 16-bit signed PCM.
     * WASAPI shared mode often returns float samples.
     */
    private static byte[] floatTo16(byte[] floatBuf) {
        int samples = floatBuf.length / 4;
        byte[] out  = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            // Little-endian float
            int bits  = ((floatBuf[i*4+3] & 0xFF) << 24)
                      | ((floatBuf[i*4+2] & 0xFF) << 16)
                      | ((floatBuf[i*4+1] & 0xFF) <<  8)
                      |  (floatBuf[i*4  ] & 0xFF);
            float f   = Float.intBitsToFloat(bits);
            short s   = (short) Math.max(-32768, Math.min(32767, (int)(f * 32767)));
            out[i*2  ] = (byte)(s & 0xFF);
            out[i*2+1] = (byte)((s >> 8) & 0xFF);
        }
        return out;
    }
}
