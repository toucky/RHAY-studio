package mg.appmada.rhaystudio;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * RHAY Studio Android shell.
 *
 * This is reconstructed from the current v5.10.23 native shell so that the
 * existing microphone, monitoring, export and permission paths stay separate
 * from the new offline Auto-Tune bridge.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_FILE = 4101;
    private static final int REQUEST_MICROPHONE = 4102;
    private static final int REQUEST_STORAGE = 4103;

    private WebView webView;
    private ValueCallback<Uri[]> pendingFileCallback;
    private RhayBridge rhayBridge;
    private GoldWebBridge goldBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF07101F);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);

        rhayBridge = new RhayBridge(this);
        webView.addJavascriptInterface(rhayBridge, "RhayAndroid");
        webView.addJavascriptInterface(new PermissionBridge(), "android");

        // New offline Auto-Tune bridge. It never touches AudioRecord/AudioTrack.
        goldBridge = new GoldWebBridge(this);
        webView.addJavascriptInterface(goldBridge, "RhayGold");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new RhayChromeClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void evaluateJavascript(final String script) {
        if (webView != null) {
            webView.post(() -> webView.evaluateJavascript(script, null));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE && pendingFileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            pendingFileCallback.onReceiveValue(result);
            pendingFileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (rhayBridge != null) rhayBridge.close();
        if (goldBridge != null) {
            try { goldBridge.clear(); } catch (Throwable ignored) {}
        }
        if (webView != null) {
            webView.removeJavascriptInterface("RhayAndroid");
            webView.removeJavascriptInterface("android");
            webView.removeJavascriptInterface("RhayGold");
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == 0;
        if (requestCode == REQUEST_MICROPHONE) {
            evaluateJavascript("window.__rhayMicPermission&&window.__rhayMicPermission(" + granted + ")");
        } else if (requestCode == REQUEST_STORAGE) {
            evaluateJavascript("window.__rhayStoragePermission&&window.__rhayStoragePermission(" + granted + ")");
        }
    }

    public final class PermissionBridge {
        @JavascriptInterface
        public void requestPermission(String name) {
            requestNamedPermission(name);
        }

        @JavascriptInterface
        public void requestNamedPermission(String name) {
            if (name == null) return;
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("audio") || n.contains("microphone")) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE));
            } else if (n.contains("storage") && Build.VERSION.SDK_INT <= 28) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE));
            }
        }
    }

    private final class RhayChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != 0) {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
                        request.deny();
                        return;
                    }
                }
                request.grant(request.getResources());
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view,
                                         ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = callback;

            Intent intent;
            try {
                intent = params.createIntent();
            } catch (Throwable ignored) {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
            }
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(intent, REQUEST_FILE);
                return true;
            } catch (Throwable t) {
                pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = null;
                Toast.makeText(MainActivity.this,
                        "Aucun gestionnaire de fichiers disponible", Toast.LENGTH_LONG).show();
                return false;
            }
        }
    }

    public static final class RhayBridge {
        private static final String PREFS = "rhay_studio";
        private static final int[] SAMPLE_RATES = {48000, 44100, 32000, 22050};

        private final Activity activity;
        private final Object micLock = new Object();
        private final Object saveLock = new Object();
        private final ByteArrayOutputStream micQueue = new ByteArrayOutputStream();
        private final SharedPreferences preferences;

        private AudioRecord audioRecord;
        private AudioTrack monitorTrack;
        private Thread micThread;
        private volatile boolean micRunning;
        private volatile boolean monitorEnabled;
        private volatile boolean recordingCapture;
        private volatile float monitorGain = 0.9f;
        private int sampleRate = 48000;

        private String exportFolder = "Rhay Studio";
        private OutputStream saveStream;
        private Uri saveUri;
        private File saveFile;

        RhayBridge(Activity activity) {
            this.activity = activity;
            this.preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        void close() {
            synchronized (micLock) { stopMicLocked(); }
            synchronized (saveLock) { discardOpenExport(); }
        }

        @JavascriptInterface
        public void askMic() {
            activity.runOnUiThread(() -> activity.requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE));
        }

        @JavascriptInterface
        public boolean micGranted() {
            return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == 0;
        }

        @JavascriptInterface
        public int micRate() { return sampleRate; }

        @JavascriptInterface
        public String micStart() {
            synchronized (micLock) {
                if (!micGranted()) {
                    askMic();
                    return "ERR:permission_microphone";
                }
                recordingCapture = true;
                synchronized (micQueue) { micQueue.reset(); }
                if (micRunning) return "";
                String err = startMicEngineLocked();
                if (!err.isEmpty()) recordingCapture = false;
                return err;
            }
        }

        @JavascriptInterface
        public String micStop() {
            synchronized (micLock) {
                recordingCapture = false;
                if (!monitorEnabled) stopMicLocked();
            }
            return micPull();
        }

        @JavascriptInterface
        public String micPull() {
            synchronized (micQueue) {
                byte[] bytes = micQueue.toByteArray();
                micQueue.reset();
                return Base64.encodeToString(bytes, Base64.NO_WRAP);
            }
        }

        @JavascriptInterface
        public void monitorGain(float gain) {
            monitorGain = Math.max(0f, Math.min(1f, gain));
            AudioTrack track = monitorTrack;
            if (track != null) track.setVolume(monitorGain);
        }

        @JavascriptInterface
        public void monitorSet(boolean enabled) {
            synchronized (micLock) {
                monitorEnabled = enabled;
                if (enabled) {
                    if (!micGranted()) {
                        askMic();
                        return;
                    }
                    if (!micRunning) {
                        String err = startMicEngineLocked();
                        if (!err.isEmpty()) {
                            monitorEnabled = false;
                            return;
                        }
                    }
                    ensureMonitorTrackLocked();
                } else {
                    stopMonitorTrackLocked();
                    if (!recordingCapture) stopMicLocked();
                }
            }
        }

        @JavascriptInterface
        public String monitorStatus() {
            if (!monitorEnabled) return "off";
            AudioTrack track = monitorTrack;
            if (track == null) return "pending";
            return track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING ? "active" : "inactive";
        }

        @JavascriptInterface
        public String prefGet(String key) {
            return preferences.getString(safeKey(key), "");
        }

        @JavascriptInterface
        public void prefSet(String key, String value) {
            preferences.edit().putString(safeKey(key), value == null ? "" : value).apply();
        }

        @JavascriptInterface
        public void copyText(String text) {
            final String value = text == null ? "" : text;
            activity.runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Rhay Studio", value));
                Toast.makeText(activity, "Copié", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public String setExportFolder(String folder) {
            exportFolder = sanitizeFolder(folder);
            return "";
        }

        @JavascriptInterface
        public String saveStart(String filename, String mime) {
            synchronized (saveLock) {
                discardOpenExport();
                String safeName = sanitizeFilename(filename);
                String safeMime = sanitizeMime(mime);
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                        values.put(MediaStore.MediaColumns.MIME_TYPE, safeMime);
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                                Environment.DIRECTORY_MUSIC + "/" + exportFolder);
                        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                        Uri collection = safeMime.startsWith("audio/")
                                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                : MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                        saveUri = activity.getContentResolver().insert(collection, values);
                        if (saveUri == null) return "ERR:creation_export";
                        saveStream = activity.getContentResolver().openOutputStream(saveUri, "w");
                    } else {
                        if (activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != 0) {
                            activity.runOnUiThread(() -> activity.requestPermissions(
                                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE));
                            return "ERR:permission_stockage";
                        }
                        File music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                        File dir = new File(music, exportFolder);
                        if (!dir.exists() && !dir.mkdirs()) return "ERR:dossier_export";
                        saveFile = new File(dir, safeName);
                        saveStream = new FileOutputStream(saveFile, false);
                    }
                    if (saveStream == null) {
                        discardOpenExport();
                        return "ERR:ouverture_export";
                    }
                    return "";
                } catch (Throwable t) {
                    discardOpenExport();
                    return errorMessage(t);
                }
            }
        }

        @JavascriptInterface
        public String saveChunk(String base64) {
            synchronized (saveLock) {
                if (saveStream == null) return "ERR:aucun_export_ouvert";
                try {
                    byte[] bytes = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
                    saveStream.write(bytes);
                    return "";
                } catch (Throwable t) {
                    discardOpenExport();
                    return errorMessage(t);
                }
            }
        }

        @JavascriptInterface
        public String saveEnd() {
            synchronized (saveLock) {
                if (saveStream == null) return "ERR:aucun_export_ouvert";
                try {
                    saveStream.flush();
                    saveStream.close();
                    saveStream = null;
                    if (Build.VERSION.SDK_INT >= 29 && saveUri != null) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        activity.getContentResolver().update(saveUri, values, null, null);
                    }
                    saveUri = null;
                    saveFile = null;
                    return "";
                } catch (Throwable t) {
                    discardOpenExport();
                    return errorMessage(t);
                }
            }
        }

        private String startMicEngineLocked() {
            try {
                int readBuffer = 0;
                for (int rate : SAMPLE_RATES) {
                    int min = AudioRecord.getMinBufferSize(rate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (min > 0) {
                        sampleRate = rate;
                        readBuffer = Math.max(min * 2, 8192);
                        break;
                    }
                }
                if (readBuffer == 0) return "ERR:microphone_non_disponible";

                audioRecord = new AudioRecord(
                        6, // VOICE_RECOGNITION: same native capture path as v5.10.23
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        readBuffer);
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release();
                    audioRecord = null;
                    return "ERR:initialisation_microphone";
                }
                audioRecord.startRecording();
                micRunning = true;
                if (monitorEnabled) ensureMonitorTrackLocked();

                int chunkBytes = Math.max(640,
                        Math.min(1920, Math.max(1, sampleRate / 100) * 2));
                micThread = new Thread(() -> readMicrophone(chunkBytes), "RhayMicrophone");
                micThread.setPriority(Thread.MAX_PRIORITY);
                micThread.start();
                return "";
            } catch (Throwable t) {
                stopMicLocked();
                return errorMessage(t);
            }
        }

        private void readMicrophone(int chunkBytes) {
            byte[] buffer = new byte[chunkBytes];
            while (micRunning && audioRecord != null) {
                int n;
                try {
                    n = audioRecord.read(buffer, 0, buffer.length);
                } catch (Throwable t) {
                    break;
                }
                if (n <= 0) continue;

                if (recordingCapture) {
                    synchronized (micQueue) { micQueue.write(buffer, 0, n); }
                }

                if (monitorEnabled) {
                    AudioTrack track = monitorTrack;
                    if (track == null) {
                        synchronized (micLock) {
                            ensureMonitorTrackLocked();
                            track = monitorTrack;
                        }
                    }
                    if (track != null) {
                        try {
                            int off = 0;
                            while (off < n && micRunning && monitorEnabled) {
                                int wrote = track.write(buffer, off, n - off, AudioTrack.WRITE_BLOCKING);
                                if (wrote <= 0) break;
                                off += wrote;
                            }
                        } catch (Throwable t) {
                            synchronized (micLock) { stopMonitorTrackLocked(); }
                        }
                    }
                }
            }
        }

        private void ensureMonitorTrackLocked() {
            if (!monitorEnabled || monitorTrack != null) return;
            try {
                int min = AudioTrack.getMinBufferSize(sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) return;
                int target = Math.max(1024, (sampleRate / 50) * 2);
                int bufferBytes = Math.max(min, target);

                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                AudioAttributes.Builder attrBuilder = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
                if (Build.VERSION.SDK_INT >= 29) {
                    attrBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL);
                    AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) am.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL);
                }
                AudioTrack.Builder builder = new AudioTrack.Builder()
                        .setAudioAttributes(attrBuilder.build())
                        .setAudioFormat(format)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bufferBytes);
                if (Build.VERSION.SDK_INT >= 26) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                }
                AudioTrack track = builder.build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    track.release();
                    return;
                }
                track.setVolume(monitorGain);
                track.play();
                monitorTrack = track;
            } catch (Throwable t) {
                stopMonitorTrackLocked();
            }
        }

        private void stopMonitorTrackLocked() {
            AudioTrack track = monitorTrack;
            monitorTrack = null;
            if (track == null) return;
            try { track.pause(); } catch (Throwable ignored) {}
            try { track.flush(); } catch (Throwable ignored) {}
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }

        private void stopMicLocked() {
            micRunning = false;
            recordingCapture = false;
            stopMonitorTrackLocked();
            AudioRecord record = audioRecord;
            audioRecord = null;
            if (record != null) {
                try { record.stop(); } catch (Throwable ignored) {}
                try { record.release(); } catch (Throwable ignored) {}
            }
            Thread thread = micThread;
            micThread = null;
            if (thread != null && thread != Thread.currentThread()) {
                try { thread.join(300); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        private void discardOpenExport() {
            if (saveStream != null) {
                try { saveStream.close(); } catch (Throwable ignored) {}
                saveStream = null;
            }
            if (saveUri != null) {
                try { activity.getContentResolver().delete(saveUri, null, null); } catch (Throwable ignored) {}
                saveUri = null;
            }
            if (saveFile != null && saveFile.exists()) {
                try { saveFile.delete(); } catch (Throwable ignored) {}
            }
            saveFile = null;
        }

        private static String safeKey(String key) {
            return key == null || key.trim().isEmpty() ? "default" : key;
        }

        private static String sanitizeFilename(String filename) {
            String s = filename == null ? "export.wav" : filename.trim();
            s = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            if (s.isEmpty() || ".".equals(s) || "..".equals(s)) s = "export.wav";
            if (s.length() > 120) s = s.substring(0, 120);
            return s;
        }

        private static String sanitizeFolder(String folder) {
            String raw = folder == null ? "Rhay Studio" : folder.trim();
            raw = raw.replace('\\\\', '/').replaceAll("/{2,}", "/");
            StringBuilder out = new StringBuilder();
            for (String part : raw.split("/")) {
                part = part.trim().replaceAll("[:*?\"<>|\\p{Cntrl}]", "_");
                if (part.isEmpty() || ".".equals(part) || "..".equals(part)) continue;
                if (out.length() > 0) out.append('/');
                out.append(part);
            }
            String s = out.length() == 0 ? "Rhay Studio" : out.toString();
            return s.length() > 120 ? s.substring(0, 120) : s;
        }

        private static String sanitizeMime(String mime) {
            if (mime != null && mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) return mime;
            return "application/octet-stream";
        }

        private static String errorMessage(Throwable t) {
            String m = t.getMessage();
            if (m == null || m.trim().isEmpty()) m = t.getClass().getSimpleName();
            return "ERR:" + m.replace('\n', ' ').replace('\r', ' ');
        }
    }
}
