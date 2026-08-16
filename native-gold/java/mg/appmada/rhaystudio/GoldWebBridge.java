package mg.appmada.rhaystudio;

import android.content.Context;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Pont WebView -> moteur RHAY CLEAN GOLD.
 *
 * Aucun AudioRecord/AudioTrack n'est utilisé ici : ce pont traite uniquement
 * un clip déjà présent dans la WebView et travaille dans le cache de l'app.
 */
public final class GoldWebBridge {
    private static final int MAX_PULL_BYTES = 256 * 1024;

    private final Context context;
    private final Object lock = new Object();

    private File inputFile;
    private File outputFile;
    private FileOutputStream upload;
    private FileInputStream resultRead;
    private int sampleRate;
    private int channels;
    private int frames;

    private volatile boolean busy = false;
    private volatile int progress = 0;
    private volatile String phase = "idle";
    private volatile String error = "";
    private volatile boolean done = false;

    public GoldWebBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return GoldEngine.isAvailable();
    }

    @JavascriptInterface
    public String engineError() {
        return GoldEngine.loadError();
    }

    @JavascriptInterface
    public boolean beginUpload(String token, int sr, int ch, int expectedFrames) {
        synchronized (lock) {
            if (busy || sr < 8000 || ch < 1 || ch > 8 || expectedFrames <= 0) return false;
            closeQuietly(upload);
            closeQuietly(resultRead);
            upload = null;
            resultRead = null;

            String safe = token == null ? "clip" : token.replaceAll("[^A-Za-z0-9_-]", "_");
            if (safe.length() > 48) safe = safe.substring(0, 48);
            File dir = new File(context.getCacheDir(), "rhay-gold");
            if (!dir.exists() && !dir.mkdirs()) return false;
            inputFile = new File(dir, safe + ".f32");
            outputFile = new File(dir, safe + ".gold.f32");
            if (outputFile.exists()) outputFile.delete();

            try {
                upload = new FileOutputStream(inputFile, false);
            } catch (IOException e) {
                error = e.toString();
                return false;
            }
            sampleRate = sr;
            channels = ch;
            frames = expectedFrames;
            busy = false;
            done = false;
            progress = 0;
            phase = "upload";
            error = "";
            return true;
        }
    }

    @JavascriptInterface
    public boolean appendUpload(String base64) {
        synchronized (lock) {
            if (upload == null || base64 == null) return false;
            try {
                byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
                upload.write(bytes);
                return true;
            } catch (Throwable t) {
                error = t.toString();
                return false;
            }
        }
    }

    @JavascriptInterface
    public boolean finishUpload() {
        synchronized (lock) {
            if (upload == null) return false;
            try {
                upload.flush();
                upload.close();
                upload = null;
                long expected = (long) frames * channels * 4L;
                if (inputFile.length() != expected) {
                    error = "PCM size mismatch: " + inputFile.length() + " != " + expected;
                    return false;
                }
                phase = "ready";
                return true;
            } catch (IOException e) {
                error = e.toString();
                return false;
            }
        }
    }

    /**
     * regionsJson: [{"startFrame":123,"endFrame":456,"pitchScale":1.023}, ...]
     * Les régions doivent être calculées par le tracking musical RHAY.
     */
    @JavascriptInterface
    public boolean process(String regionsJson) {
        synchronized (lock) {
            if (busy || inputFile == null || !inputFile.exists() || !GoldEngine.isAvailable()) {
                return false;
            }
            busy = true;
            done = false;
            progress = 1;
            phase = "loading";
            error = "";
        }

        final String json = regionsJson == null ? "[]" : regionsJson;
        new Thread(() -> runProcess(json), "rhay-gold-render").start();
        return true;
    }

    @JavascriptInterface
    public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("busy", busy);
            o.put("done", done);
            o.put("progress", progress);
            o.put("phase", phase);
            o.put("error", error);
            o.put("resultBytes", outputFile != null && outputFile.exists() ? outputFile.length() : 0);
            return o.toString();
        } catch (Throwable t) {
            return "{\"busy\":false,\"done\":false,\"progress\":0,\"phase\":\"error\",\"error\":\"status\"}";
        }
    }

    @JavascriptInterface
    public long resultSizeBytes() {
        return outputFile != null && outputFile.exists() ? outputFile.length() : 0L;
    }

    @JavascriptInterface
    public boolean resultBegin() {
        synchronized (lock) {
            if (busy || !done || outputFile == null || !outputFile.exists()) return false;
            closeQuietly(resultRead);
            try {
                resultRead = new FileInputStream(outputFile);
                return true;
            } catch (IOException e) {
                error = e.toString();
                return false;
            }
        }
    }

    /** Retourne une chaîne Base64 vide à la fin. */
    @JavascriptInterface
    public String resultPull(int requestedBytes) {
        synchronized (lock) {
            if (resultRead == null) return "";
            int count = Math.max(4096, Math.min(MAX_PULL_BYTES, requestedBytes));
            byte[] data = new byte[count];
            try {
                int n = resultRead.read(data);
                if (n <= 0) {
                    closeQuietly(resultRead);
                    resultRead = null;
                    return "";
                }
                return Base64.encodeToString(n == data.length ? data : Arrays.copyOf(data, n), Base64.NO_WRAP);
            } catch (IOException e) {
                error = e.toString();
                closeQuietly(resultRead);
                resultRead = null;
                return "";
            }
        }
    }

    @JavascriptInterface
    public void clear() {
        synchronized (lock) {
            if (busy) return;
            closeQuietly(upload);
            closeQuietly(resultRead);
            upload = null;
            resultRead = null;
            if (inputFile != null) inputFile.delete();
            if (outputFile != null) outputFile.delete();
            inputFile = null;
            outputFile = null;
            progress = 0;
            phase = "idle";
            error = "";
            done = false;
        }
    }

    private void runProcess(String regionsJson) {
        try {
            phase = "decode";
            float[] source = readFloatFile(inputFile, frames * channels);
            float[] output = source.clone();

            JSONArray regions = new JSONArray(regionsJson);
            int count = regions.length();
            int padFrames = Math.max(1, Math.round(sampleRate * 0.120f));
            int maxFade = Math.max(1, Math.round(sampleRate * 0.050f));

            for (int ri = 0; ri < count; ri++) {
                JSONObject r = regions.getJSONObject(ri);
                int coreStart = clamp(r.getInt("startFrame"), 0, frames);
                int coreEnd = clamp(r.getInt("endFrame"), coreStart, frames);
                double pitchScale = r.getDouble("pitchScale");
                int coreFrames = coreEnd - coreStart;
                if (coreFrames < Math.round(sampleRate * 0.060f) ||
                        !Double.isFinite(pitchScale) || pitchScale < 0.25 || pitchScale > 4.0 ||
                        Math.abs(pitchScale - 1.0) < 0.00001) {
                    progress = count == 0 ? 95 : 5 + (int) Math.round(88.0 * (ri + 1) / count);
                    continue;
                }

                int p0 = Math.max(0, coreStart - padFrames);
                int p1 = Math.min(frames, coreEnd + padFrames);
                int segmentFrames = p1 - p0;
                float[] segment = new float[segmentFrames * channels];
                System.arraycopy(source, p0 * channels, segment, 0, segment.length);

                phase = "pitch " + (ri + 1) + "/" + count;
                float[] tuned = GoldEngine.processRegion(segment, channels, sampleRate, pitchScale);
                if (tuned == null || tuned.length != segment.length) {
                    throw new IllegalStateException("native region length mismatch");
                }

                int a = coreStart - p0;
                int b = coreEnd - p0;
                double origEnergy = 1e-12;
                double tuneEnergy = 1e-12;
                long samples = 0;
                for (int f = a; f < b; f++) {
                    for (int c = 0; c < channels; c++) {
                        float ov = source[(p0 + f) * channels + c];
                        float tv = tuned[f * channels + c];
                        origEnergy += ov * ov;
                        tuneEnergy += tv * tv;
                        samples++;
                    }
                }
                double gain = Math.sqrt(origEnergy / tuneEnergy);
                double minGain = Math.pow(10.0, -2.0 / 20.0);
                double maxGain = Math.pow(10.0,  2.0 / 20.0);
                gain = Math.max(minGain, Math.min(maxGain, gain));

                int fadeFrames = Math.min(maxFade, Math.max(1, coreFrames / 3));
                for (int f = 0; f < coreFrames; f++) {
                    double w = 1.0;
                    if (f < fadeFrames) {
                        double x = (double) f / Math.max(1, fadeFrames - 1);
                        w = 0.5 - 0.5 * Math.cos(Math.PI * x);
                    } else if (f >= coreFrames - fadeFrames) {
                        double x = (double) (coreFrames - 1 - f) / Math.max(1, fadeFrames - 1);
                        w = 0.5 - 0.5 * Math.cos(Math.PI * x);
                    }
                    int globalFrame = coreStart + f;
                    int localFrame = a + f;
                    for (int c = 0; c < channels; c++) {
                        int oi = globalFrame * channels + c;
                        float dry = output[oi];
                        float wet = (float) (tuned[localFrame * channels + c] * gain);
                        output[oi] = (float) (dry * (1.0 - w) + wet * w);
                    }
                }

                progress = count == 0 ? 95 : 5 + (int) Math.round(88.0 * (ri + 1) / count);
            }

            phase = "write";
            writeFloatFile(outputFile, output);
            progress = 100;
            phase = "done";
            done = true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            phase = "error";
            done = false;
        } finally {
            busy = false;
        }
    }

    private static float[] readFloatFile(File f, int expectedSamples) throws IOException {
        long bytes = f.length();
        if (bytes != (long) expectedSamples * 4L || bytes > Integer.MAX_VALUE) {
            throw new IOException("invalid PCM size");
        }
        byte[] raw = new byte[(int) bytes];
        try (FileInputStream in = new FileInputStream(f)) {
            int p = 0;
            while (p < raw.length) {
                int n = in.read(raw, p, raw.length - p);
                if (n < 0) throw new IOException("unexpected EOF");
                p += n;
            }
        }
        float[] out = new float[expectedSamples];
        FloatBuffer fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        fb.get(out);
        return out;
    }

    private static void writeFloatFile(File f, float[] values) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(values);
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(bb.array());
            out.flush();
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }
}
