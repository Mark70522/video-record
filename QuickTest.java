
import com.screenrecorder.recorder.*;
public class QuickTest {
    public static void main(String[] args) throws Exception {
        RecordingSession s = new RecordingSession("E:/ScreenRecorder/test_out");
        System.out.println("Starting 3s recording...");
        s.start();
        Thread.sleep(3000);
        String f = s.stop();
        System.out.println("Saved: " + f);
        // Check file size
        java.io.File file = new java.io.File(f);
        System.out.println("File size: " + file.length() + " bytes");
    }
}
