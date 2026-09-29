import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

public class ResizeFeatureGraphic {
    public static void main(String[] args) throws Exception {
        File inFile = new File(args[0]);
        File outFile = new File(args[1]);
        int targetW = 1024, targetH = 500;

        BufferedImage src = ImageIO.read(inFile);
        int w = src.getWidth(), h = src.getHeight();

        // Crop (centered) to the exact target aspect ratio first, so the final resize is
        // a uniform scale with no stretch/squish distortion.
        double targetRatio = (double) targetW / targetH;
        double srcRatio = (double) w / h;
        int cropW = w, cropH = h;
        if (srcRatio > targetRatio) {
            cropW = (int) Math.round(h * targetRatio);
        } else {
            cropH = (int) Math.round(w / targetRatio);
        }
        int x = (w - cropW) / 2;
        int y = (h - cropH) / 2;
        BufferedImage cropped = src.getSubimage(x, y, cropW, cropH);

        // Draw onto an opaque RGB canvas (Play Store feature graphic must have no alpha
        // channel) at the exact target size.
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(cropped, 0, 0, targetW, targetH, null);
        g.dispose();

        ImageIO.write(out, "png", outFile);
        System.out.println("Wrote " + outFile.getPath() + " (" + targetW + "x" + targetH + ", no alpha)");
    }
}
