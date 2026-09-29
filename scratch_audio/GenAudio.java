import java.io.*;
import java.util.Random;

public class GenAudio {
    static final int SR = 22050;

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        new File(outDir).mkdirs();

        // --- Coin pickup: quick two-note "ding" ---
        writeWav(outDir + "/sfx_coin.wav", concat(
            square(988, 60, 0.8),
            square(1319, 90, 0.8)
        ));

        // --- Crash: noise burst with decay ---
        writeWav(outDir + "/sfx_crash.wav", noiseBurst(180, 0.9));

        // --- Lane switch: tiny blip ---
        writeWav(outDir + "/sfx_lane.wav", square(700, 40, 0.5));

        // --- Fuel low warning: alternating beep ---
        writeWav(outDir + "/sfx_fuel_low.wav", concat(
            square(880, 90, 0.7), silence(40),
            square(660, 90, 0.7), silence(40),
            square(880, 90, 0.7)
        ));

        // --- Game over: descending stinger ---
        writeWav(outDir + "/sfx_gameover.wav", concat(
            square(523, 140, 0.8),
            square(392, 140, 0.8),
            square(330, 140, 0.8),
            square(262, 220, 0.8)
        ));

        // --- New best / top-10: ascending fanfare ---
        writeWav(outDir + "/sfx_newbest.wav", concat(
            square(523, 80, 0.85),
            square(659, 80, 0.85),
            square(784, 80, 0.85),
            square(1047, 180, 0.85)
        ));

        // --- BGM loop: pentatonic riff (square lead) + sparse bass, mixed ---
        double C3 = 130.81, G3 = 196.00, A3 = 220.00;
        double C4 = 261.63, D4 = 293.66, E4 = 329.63, G4 = 392.00, A4 = 440.00;
        double C5 = 523.25, D5 = 587.33;
        double[] melody = { C4, E4, G4, C5, A4, G4, E4, D4, C4, E4, G4, C5, D5, C5, A4, G4 };
        double[] bass = { C3, C3, G3, G3, A3, A3, G3, G3 };
        short[][] parts = new short[melody.length * 2][];
        int idx = 0;
        int bassIdx = 0;
        for (int i = 0; i < melody.length; i++) {
            short[] lead = square(melody[i], 150, 0.55);
            short[] low = square(bass[(i / 2) % bass.length], 150, 0.4);
            parts[idx++] = mix(lead, low);
            parts[idx++] = silence(30);
        }
        writeWav(outDir + "/bgm_loop.wav", concat(parts));

        System.out.println("Done: wrote 7 wav files to " + outDir);
    }

    // ---- Waveform generators ----

    static short[] square(double freq, int durationMs, double amplitude) {
        int n = SR * durationMs / 1000;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            double val = Math.sin(2 * Math.PI * freq * t) >= 0 ? amplitude : -amplitude;
            out[i] = clamp(val);
        }
        envelope(out);
        return out;
    }

    static short[] triangle(double freq, int durationMs, double amplitude) {
        int n = SR * durationMs / 1000;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            double phase = (t * freq) % 1.0;
            double val = phase < 0.5 ? (4 * phase - 1) : (3 - 4 * phase);
            out[i] = clamp(val * amplitude);
        }
        envelope(out);
        return out;
    }

    static short[] noiseBurst(int durationMs, double amplitude) {
        int n = SR * durationMs / 1000;
        short[] out = new short[n];
        Random r = new Random(42);
        for (int i = 0; i < n; i++) {
            double decay = 1.0 - (i / (double) n);
            double val = (r.nextDouble() * 2 - 1) * amplitude * decay;
            out[i] = clamp(val);
        }
        return out;
    }

    static short[] silence(int durationMs) {
        return new short[SR * durationMs / 1000];
    }

    /** Sums two same-length sample buffers (e.g. lead + bass), clamping to avoid overflow. */
    static short[] mix(short[] a, short[] b) {
        int n = Math.min(a.length, b.length);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            int sum = a[i] + b[i];
            if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE;
            if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE;
            out[i] = (short) sum;
        }
        return out;
    }

    static short clamp(double v) {
        double s = v * Short.MAX_VALUE;
        if (s > Short.MAX_VALUE) s = Short.MAX_VALUE;
        if (s < Short.MIN_VALUE) s = Short.MIN_VALUE;
        return (short) s;
    }

    static void envelope(short[] samples) {
        int fade = Math.min(samples.length / 2, SR * 5 / 1000);
        for (int i = 0; i < fade; i++) {
            double mult = i / (double) fade;
            samples[i] = (short) (samples[i] * mult);
            samples[samples.length - 1 - i] = (short) (samples[samples.length - 1 - i] * mult);
        }
    }

    static short[] concat(short[]... arrays) {
        int total = 0;
        for (short[] a : arrays) total += a.length;
        short[] out = new short[total];
        int pos = 0;
        for (short[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }

    // ---- WAV writer ----

    static void writeWav(String path, short[] samples) throws IOException {
        int dataSize = samples.length * 2;
        int byteRate = SR * 2;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(path)))) {
            out.writeBytes("RIFF");
            writeIntLE(out, 36 + dataSize);
            out.writeBytes("WAVE");
            out.writeBytes("fmt ");
            writeIntLE(out, 16);
            writeShortLE(out, (short) 1);
            writeShortLE(out, (short) 1);
            writeIntLE(out, SR);
            writeIntLE(out, byteRate);
            writeShortLE(out, (short) 2);
            writeShortLE(out, (short) 16);
            out.writeBytes("data");
            writeIntLE(out, dataSize);
            for (short s : samples) writeShortLE(out, s);
        }
    }

    static void writeIntLE(DataOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    static void writeShortLE(DataOutputStream out, short v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }
}
