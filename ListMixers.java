
import javax.sound.sampled.*;
public class ListMixers {
    public static void main(String[] args) {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info info : mixers) {
            System.out.println("NAME: " + info.getName());
            System.out.println("DESC: " + info.getDescription());
            Mixer m = AudioSystem.getMixer(info);
            Line.Info[] targets = m.getTargetLineInfo();
            System.out.println("  TargetLines (capture): " + targets.length);
            for (Line.Info li : targets) System.out.println("    -> " + li);
            System.out.println();
        }
    }
}
