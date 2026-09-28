package com.screenrecorder.recorder;

import java.io.IOException;

/** Receives captured 16-bit PCM together with the wall-clock time at which the buffer ended. */
public interface PcmSink {
    void write(byte[] buf, int off, int len, long nowMillis) throws IOException;
}
