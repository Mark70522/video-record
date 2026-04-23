import com.screenrecorder.recorder.AviWriter;
import java.awt.image.BufferedImage;
import java.awt.*;

/**
 * Minimal AVI writer test — generates a 3-second, 5-fps, 320x240 test clip
 * with a solid color video and silence, then dumps the header fields.
 */
public class AviTest {
    public static void main(String[] args) throws Exception {
        String out = "C:\\Users\\admin\\Desktop\\test_output.avi";
        int W = 320, H = 240, FPS = 5, SAMPLE_RATE = 44100, CH = 2;

        AviWriter w = new AviWriter(out, W, H, SAMPLE_RATE, CH);

        // Write 15 frames (3 seconds at 5fps)
        for (int i = 0; i < 15; i++) {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(i * 17, 100, 200));
            g.fillRect(0, 0, W, H);
            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.BOLD, 40));
            g.drawString("Frame " + i, 80, 130);
            g.dispose();
            w.writeVideoFrame(img);
            System.out.println("Wrote frame " + i);
        }

        // Write 3 seconds of silence (44100 * 2ch * 2bytes * 3sec)
        int silenceBytes = SAMPLE_RATE * CH * 2 * 3;
        byte[] silence = new byte[silenceBytes];
        w.writeAudioSamples(silence, silenceBytes);
        System.out.println("Wrote audio silence: " + silenceBytes + " bytes");

        w.finish(3000); // 3000ms actual recording time
        System.out.println("Saved: " + out);
        System.out.println("Open in Windows Media Player and check if it plays for 3 seconds.");
    }
}
