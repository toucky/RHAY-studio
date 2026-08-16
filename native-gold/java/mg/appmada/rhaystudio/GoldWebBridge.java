package mg.appmada.rhaystudio;

import android.content.Context;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * Pont WebView -> moteur RHAY Auto-Tune CLEAN.
 *
 * Aucun AudioRecord/AudioTrack n'est utilisé ici : ce pont traite uniquement
 * un clip déjà présent dans la WebView et travaille dans le cache de l'app.
 * Le rendu utilise UNE SEULE instance Rubber Band continue pour le clip entier.
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
     * curveJson = {
     *   "controlFrames": 2880,
     *   "pitchScales": [1.0, 1.0123, ...]
     * }
     *
     * La courbe est calculée par le tracking musical RHAY. Elle est envoyée à
     * une seule instance Rubber Band. Aucun découpage par note et aucun crossfade.
     */
    @JavascriptInterface
    public boolean processCurve(String curveJson) {
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

        final String json = curveJson == null ? "{}" : curveJson;
        new Thread(() -> runProcessCurve(json), "rhay-gold-render").start();
        return true;
    }

    /** Ancien nom conservé pour éviter une casse pendant la migration. */
    @JavascriptInterface
    public boolean process(String curveJson) {
        return processCurve(curveJson);
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
                return Base64.encodeToString(
                        n == data.length ? data : Arrays.copyOf(data, n), Base64.NO_WRAP);
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

    private void runProcessCurve(String curveJson) {
        try {
            phase = "decode";
            progress = 5;
            float[] source = readFloatFile(inputFile, frames * channels);

            JSONObject cfg = new JSONObject(curveJson);
            int controlFrames = cfg.optInt("controlFrames", Math.max(1, Math.round(sampleRate * 0.060f)));
            if (controlFrames <= 0) throw new IllegalArgumentException("controlFrames invalide");

            JSONArray arr = cfg.optJSONArray("pitchScales");
            if (arr == null || arr.length() == 0) {
                throw new IllegalArgumentException("courbe de pitch vide");
            }

            /*
             * JSON.stringify convertit NaN/Infinity en null. Sur quelques micro-zones
             * YIN incertaines, une ancienne courbe pouvait donc contenir null et
             * JSONArray#getDouble levait une JSONException, rendant Auto-Tune
             * totalement inaccessible. Ici on garde simplement le dernier ratio
             * valide (1.0 au départ). Cela conserve une trajectoire continue et
             * n'ajoute ni dry/wet ni nouvelle voix.
             */
            double[] scales = new double[arr.length()];
            double previous = 1.0;
            for (int i = 0; i < scales.length; i++) {
                double v = previous;
                if (!arr.isNull(i)) {
                    v = arr.optDouble(i, previous);
                }
                if (!Double.isFinite(v) || v < 0.25 || v > 4.0) {
                    v = previous;
                }
                scales[i] = v;
                previous = v;
            }

            phase = "Rubber Band continu";
            progress = 18;
            float[] output = GoldEngine.processCurve(
                    source, channels, sampleRate, scales, controlFrames);
            if (output == null || output.length != source.length) {
                throw new IllegalStateException("native curve length mismatch");
            }

            progress = 92;
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

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }
}
