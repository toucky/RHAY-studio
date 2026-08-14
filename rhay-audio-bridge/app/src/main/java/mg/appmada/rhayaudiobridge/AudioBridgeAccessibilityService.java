package mg.appmada.rhayaudiobridge;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class AudioBridgeAccessibilityService extends AccessibilityService {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private LinearLayout overlay;
    private TextView stateText;
    private Button powerButton;
    private Button modeButton;

    private AudioManager audioManager;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;

    private volatile boolean running = false;
    private volatile boolean stableMode = false;
    private volatile boolean silenced = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        showOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Deliberately does not inspect any content from the foreground app.
    }

    @Override
    public void onInterrupt() {
        stopMonitor();
    }

    @Override
    public void onDestroy() {
        stopMonitor();
        removeOverlay();
        super.onDestroy();
    }

    private void showOverlay() {
        if (overlay != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding(dp(7), dp(5), dp(7), dp(5));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(232, 18, 20, 24));
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), Color.argb(90, 255, 255, 255));
        overlay.setBackground(bg);

        TextView brand = new TextView(this);
        brand.setText("RHAY");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(11);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setPadding(dp(5), 0, dp(6), 0);
        overlay.addView(brand);

        stateText = new TextView(this);
        stateText.setText("OFF");
        stateText.setTextColor(Color.rgb(210, 214, 220));
        stateText.setTextSize(11);
        stateText.setPadding(0, 0, dp(6), 0);
        overlay.addView(stateText);

        powerButton = smallButton("ON");
        powerButton.setOnClickListener(v -> {
            if (running) stopMonitor();
            else startMonitor();
        });
        overlay.addView(powerButton);

        modeButton = smallButton("LOW");
        modeButton.setOnClickListener(v -> {
            stableMode = !stableMode;
            modeButton.setText(stableMode ? "SAFE" : "LOW");
            if (running) {
                stopMonitor();
                mainHandler.postDelayed(this::startMonitor, 180);
            }
        });
        overlay.addView(modeButton);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(8);
        params.y = dp(72);

        try {
            windowManager.addView(overlay, params);
        } catch (Exception e) {
            overlay = null;
            Toast.makeText(this, "Impossible d'afficher la bulle RHAY", Toast.LENGTH_LONG).show();
        }
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(10);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(34));
        lp.leftMargin = dp(3);
        b.setLayoutParams(lp);
        return b;
    }

    private synchronized void startMonitor() {
        if (running) return;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Ouvre Rhay Audio Bridge et autorise le microphone.");
            updateUi("MIC?", false, false);
            return;
        }

        AudioDeviceInfo output = chooseLowLatencyOutput();
        if (output == null) {
            toast("Branche un casque filaire ou USB avant d'activer le bridge.");
            updateUi("CASQUE?", false, false);
            return;
        }

        try {
            int sampleRate = resolveSampleRate();
            int minRec = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int minPlay = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            if (minRec <= 0 || minPlay <= 0) {
                throw new IllegalStateException("Configuration audio non supportée");
            }

            int baseBuffer = Math.max(Math.max(minRec, minPlay), 1024);
            int bufferBytes = stableMode ? baseBuffer * 2 : baseBuffer;

            AudioFormat inputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            AudioRecord.Builder recordBuilder = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
                    .setAudioFormat(inputFormat)
                    .setBufferSizeInBytes(bufferBytes);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                recordBuilder.setPrivacySensitive(false);
            }

            audioRecord = recordBuilder.build();

            AudioFormat outputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(outputFormat)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            AudioDeviceInfo input = chooseInputFor(output);
            if (input != null) audioRecord.setPreferredDevice(input);
            audioTrack.setPreferredDevice(output);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED ||
                    audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Initialisation audio impossible");
            }

            audioTrack.play();
            audioRecord.startRecording();
            running = true;
            silenced = false;
            updateUi("LIVE", true, false);

            final int chunkSamples = stableMode ? 512 : 256;
            Thread audioThread = new Thread(() -> audioLoop(chunkSamples), "RhayAudioBridge");
            audioThread.start();

        } catch (Throwable t) {
            running = false;
            releaseAudio();
            updateUi("ERR", false, false);
            toast("Bridge audio non démarré : " + safeMessage(t));
        }
    }

    private void audioLoop(int chunkSamples) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] buffer = new short[chunkSamples];
        long lastStateCheck = 0L;

        try {
            while (running) {
                AudioRecord rec = audioRecord;
                AudioTrack trk = audioTrack;
                if (rec == null || trk == null) break;

                int read = rec.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    int offset = 0;
                    while (offset < read && running) {
                        int written = trk.write(
                                buffer,
                                offset,
                                read - offset,
                                AudioTrack.WRITE_BLOCKING);
                        if (written <= 0) break;
                        offset += written;
                    }
                }

                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastStateCheck > 600) {
                    lastStateCheck = now;
                    boolean mutedNow = false;
                    try {
                        AudioRecordingConfiguration cfg = rec.getActiveRecordingConfiguration();
                        mutedNow = cfg != null && cfg.isClientSilenced();
                    } catch (Throwable ignored) {
                    }
                    if (mutedNow != silenced) {
                        silenced = mutedNow;
                        updateUi(mutedNow ? "MUTED" : "LIVE", true, mutedNow);
                    }
                }
            }
        } catch (Throwable t) {
            if (running) {
                updateUi("ERR", false, false);
                toast("Le flux audio a été interrompu : " + safeMessage(t));
            }
        } finally {
            running = false;
            releaseAudio();
            updateUi("OFF", false, false);
        }
    }

    private synchronized void stopMonitor() {
        if (!running && audioRecord == null && audioTrack == null) {
            updateUi("OFF", false, false);
            return;
        }

        running = false;
        AudioRecord rec = audioRecord;
        AudioTrack trk = audioTrack;

        try {
            if (rec != null && rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) rec.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (trk != null && trk.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) trk.stop();
        } catch (Throwable ignored) {
        }

        releaseAudio();
        updateUi("OFF", false, false);
    }

    private synchronized void releaseAudio() {
        AudioRecord rec = audioRecord;
        AudioTrack trk = audioTrack;
        audioRecord = null;
        audioTrack = null;

        if (rec != null) {
            try { rec.release(); } catch (Throwable ignored) {}
        }
        if (trk != null) {
            try { trk.release(); } catch (Throwable ignored) {}
        }
    }

    private int resolveSampleRate() {
        int rate = 48000;
        try {
            String nativeRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            if (nativeRate != null) {
                int parsed = Integer.parseInt(nativeRate);
                if (parsed >= 8000 && parsed <= 192000) rate = parsed;
            }
        } catch (Throwable ignored) {
        }
        return rate;
    }

    private AudioDeviceInfo chooseLowLatencyOutput() {
        if (audioManager == null) return null;
        AudioDeviceInfo usb = null;
        AudioDeviceInfo wired = null;

        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                usb = d;
                break;
            }
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    type == AudioDeviceInfo.TYPE_LINE_ANALOG) {
                wired = d;
            }
        }
        return usb != null ? usb : wired;
    }

    private AudioDeviceInfo chooseInputFor(AudioDeviceInfo output) {
        if (audioManager == null) return null;
        int outType = output != null ? output.getType() : -1;
        AudioDeviceInfo builtIn = null;

        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = d.getType();
            if ((outType == AudioDeviceInfo.TYPE_USB_HEADSET || outType == AudioDeviceInfo.TYPE_USB_DEVICE) &&
                    (type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE)) {
                return d;
            }
            if (outType == AudioDeviceInfo.TYPE_WIRED_HEADSET && type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                return d;
            }
            if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) builtIn = d;
        }
        return builtIn;
    }

    private void updateUi(String state, boolean isOn, boolean isMuted) {
        mainHandler.post(() -> {
            if (stateText != null) {
                stateText.setText(state);
                if (isMuted) stateText.setTextColor(Color.rgb(255, 176, 74));
                else if (isOn) stateText.setTextColor(Color.rgb(97, 232, 151));
                else stateText.setTextColor(Color.rgb(210, 214, 220));
            }
            if (powerButton != null) powerButton.setText(isOn ? "OFF" : "ON");
        });
    }

    private void removeOverlay() {
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Throwable ignored) {}
        }
        overlay = null;
    }

    private void toast(String text) {
        mainHandler.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    private String safeMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) return t.getClass().getSimpleName();
        return m;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
