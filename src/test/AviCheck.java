import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AviCheck {
    static RandomAccessFile f;

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "C:\\Users\\admin\\Desktop\\test_output.avi";
        f = new RandomAccessFile(path, "r");
        System.out.println("File size: " + f.length());

        System.out.println("RIFF tag:  " + tag(0));
        System.out.println("RIFF size: " + i32(4) + "  (file-8=" + (f.length()-8) + ")");
        System.out.println("AVI  tag:  " + tag(8));
        System.out.println();

        System.out.println("hdrl LIST: " + tag(12) + " size=" + i32(16) + " type=" + tag(20));
        System.out.println("avih tag:  " + tag(24) + " size=" + i32(28));
        int usec = i32(32);
        System.out.println("  dwMicroSecPerFrame: " + usec + " => " + (usec>0 ? 1000000.0/usec : 0) + " fps");
        System.out.println("  dwMaxBytesPerSec:   " + i32(36));
        System.out.println("  dwPaddingGranularity:" + i32(40));
        System.out.println("  dwFlags:            0x" + Integer.toHexString(i32(44)));
        System.out.println("  dwTotalFrames:      " + i32(48));
        System.out.println("  dwInitialFrames:    " + i32(52));
        System.out.println("  dwStreams:          " + i32(56));
        System.out.println("  dwWidth:            " + i32(64));
        System.out.println("  dwHeight:           " + i32(68));
        System.out.println();

        // Video strl at 88
        System.out.println("Video strl LIST: " + tag(88) + " size=" + i32(92) + " type=" + tag(96));
        System.out.println("Video strh: " + tag(100) + " size=" + i32(104));
        System.out.println("  fccType:    " + tag(108));
        System.out.println("  fccHandler: " + tag(112));
        System.out.println("  dwFlags:    " + i32(116));
        System.out.println("  wPriority:  " + i16(120));
        System.out.println("  wLanguage:  " + i16(122));
        System.out.println("  dwInitialFrames: " + i32(124));
        System.out.println("  dwScale:    " + i32(128));
        System.out.println("  dwRate:     " + i32(132));
        System.out.println("  dwStart:    " + i32(136));
        System.out.println("  dwLength:   " + i32(140));
        System.out.println();

        // strf at 164
        System.out.println("Video strf: " + tag(164) + " size=" + i32(168));
        System.out.println("  biCompression: " + tag(188));
        System.out.println("  biWidth:       " + i32(176));
        System.out.println("  biHeight:      " + i32(180));
        System.out.println();

        // Audio strl at 212
        System.out.println("Audio strl LIST: " + tag(212) + " size=" + i32(216) + " type=" + tag(220));
        System.out.println("Audio strh: " + tag(224) + " size=" + i32(228));
        System.out.println("  fccType:    " + tag(232));
        System.out.println("  dwScale:    " + i32(248));
        System.out.println("  dwRate:     " + i32(252));
        System.out.println("  dwLength:   " + i32(260));
        System.out.println("  dwSampleSize:" + i32(272));
        System.out.println();

        // strf WAVEFORMATEX at 288
        System.out.println("Audio strf: " + tag(288) + " size=" + i32(292));
        System.out.println("  wFormatTag:     " + i16(296));
        System.out.println("  nChannels:      " + i16(298));
        System.out.println("  nSamplesPerSec: " + i32(300));
        System.out.println("  nAvgBytesPerSec:" + i32(304));
        System.out.println("  nBlockAlign:    " + i16(308));
        System.out.println("  wBitsPerSample: " + i16(310));
        System.out.println();

        // movi LIST at 314
        System.out.println("movi LIST: " + tag(314) + " size=" + i32(318) + " type=" + tag(322));
        System.out.println("  first chunk: " + tag(326) + " size=" + i32(330));

        f.close();
    }

    static String tag(int off) throws Exception {
        f.seek(off); byte[] b = new byte[4]; f.read(b);
        return new String(b, "ASCII");
    }
    static int i32(int off) throws Exception {
        f.seek(off); byte[] b = new byte[4]; f.read(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
    static int i16(int off) throws Exception {
        f.seek(off); byte[] b = new byte[2]; f.read(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort();
    }
}
