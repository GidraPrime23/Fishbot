import com.fishbot.Vision;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

public class VisionTest {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        double fps = Double.parseDouble(a[1]);
        double bx = Double.parseDouble(a[2]), by = Double.parseDouble(a[3]); // кнопка (доли)
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".png"));
        Arrays.sort(fs);
        String cur = ""; int gameFrames = 0, biteFrames = 0, miss = 0;
        for (int i = 0; i < fs.length; i++) {
            BufferedImage im = ImageIO.read(fs[i]);
            int w = im.getWidth(), h = im.getHeight();
            double sc = h / 1220.0;
            int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            // зона: ищем только в правой половине (как в приложении)
            int x0 = (int) (w * 0.5);
            int rw = w - x0;
            int[] sub = new int[rw * h];
            for (int y = 0; y < h; y++) System.arraycopy(px, y * w + x0, sub, y * rw, rw);
            int[] z = Vision.findZone(sub, rw, h, sc);
            String st = "OTHER";
            String extra = "";
            if (z != null) {
                double m = Vision.findMarker(sub, rw, h, z, sc);
                st = "GAME"; gameFrames++;
                extra = String.format(" zone=(%d,%d) c=%.0f marker=%.0f", z[2], z[3], (z[2] + z[3]) / 2.0, m);
                if (Double.isNaN(m)) miss++;
            } else {
                // кнопка: область вокруг неё
                int cx = (int) (bx * w), cy = (int) (by * h);
                int rx0 = Math.max(0, cx - (int) (.07 * w)), ry0 = Math.max(0, cy - (int) (.13 * h));
                int rx1 = Math.min(w, cx + (int) (.07 * w)), ry1 = Math.min(h, cy + (int) (.10 * h));
                int rww = rx1 - rx0, rhh = ry1 - ry0;
                int[] r = new int[rww * rhh];
                for (int y = 0; y < rhh; y++) System.arraycopy(px, (ry0 + y) * w + rx0, r, y * rww, rww);
                if (Vision.hasBite(r, rww, rhh, sc)) { st = "BITE"; biteFrames++; }
            }
            String key = st;
            if (!key.equals(cur)) { System.out.printf("%6.2fs %s%s%n", i / fps, st, extra); cur = key; }
        }
        System.out.printf("frames=%d game=%d bite=%d markerMissing=%d%n", fs.length, gameFrames, biteFrames, miss);
    }
}
