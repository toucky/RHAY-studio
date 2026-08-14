package mg.appmada.rhayaudiobridge;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 1201;
    private TextView micStatus;
    private TextView serviceStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(246, 247, 249));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Rhay Audio Bridge");
        title.setTextSize(27);
        title.setTextColor(Color.rgb(16, 18, 22));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Bridge micro live vers le casque sans lancer une deuxième capture écran. Conçu pour laisser le DAW et le screen recorder fonctionner ensemble.");
        subtitle.setTextSize(16);
        subtitle.setTextColor(Color.rgb(75, 79, 88));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(10);
        subLp.bottomMargin = dp(24);
        root.addView(subtitle, subLp);

        addSection(root, "1. Autoriser le microphone");
        micStatus = addStatus(root);
        Button micButton = addButton(root, "AUTORISER LE MICRO");
        micButton.setOnClickListener(v -> requestMic());

        addSection(root, "2. Activer Rhay Audio Bridge");
        serviceStatus = addStatus(root);
        Button accessibilityButton = addButton(root, "OUVRIR ACCESSIBILITÉ");
        accessibilityButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        addSection(root, "3. Utilisation");
        TextView guide = new TextView(this);
        guide.setText("• Branche un casque filaire ou USB.\n" +
                "• Active Rhay Audio Bridge dans Accessibilité.\n" +
                "• Une petite bulle RHAY reste au-dessus du DAW.\n" +
                "• Appuie sur ON pour entendre le micro en live.\n" +
                "• LOW = latence minimale. SAFE = buffer plus stable.\n" +
                "• Dans ton screen recorder, choisis Audio interne.\n" +
                "• N'active pas le micro du screen recorder si tu veux éviter une seconde prise micro.");
        guide.setTextSize(16);
        guide.setLineSpacing(0f, 1.18f);
        guide.setTextColor(Color.rgb(50, 54, 62));
        LinearLayout.LayoutParams guideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        guideLp.topMargin = dp(8);
        root.addView(guide, guideLp);

        TextView note = new TextView(this);
        note.setText("Important : le bridge refuse le haut-parleur du téléphone pour éviter le larsen. Utilise un casque filaire/USB pour la latence la plus faible.");
        note.setTextSize(14);
        note.setTextColor(Color.rgb(119, 64, 20));
        note.setPadding(dp(14), dp(14), dp(14), dp(14));
        note.setBackgroundColor(Color.rgb(255, 244, 224));
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(24);
        root.addView(note, noteLp);

        setContentView(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            refreshStatus();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
    }

    private void refreshStatus() {
        if (micStatus == null || serviceStatus == null) return;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean service = isAccessibilityServiceEnabled();
        micStatus.setText(mic ? "✓ Micro autorisé" : "○ Micro non autorisé");
        micStatus.setTextColor(mic ? Color.rgb(20, 125, 74) : Color.rgb(170, 72, 42));
        serviceStatus.setText(service ? "✓ Service actif" : "○ Service non actif");
        serviceStatus.setTextColor(service ? Color.rgb(20, 125, 74) : Color.rgb(170, 72, 42));
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName expected = new ComponentName(this, AudioBridgeAccessibilityService.class);
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName current = ComponentName.unflattenFromString(splitter.next());
            if (current != null && current.equals(expected)) return true;
        }
        return false;
    }

    private void addSection(LinearLayout root, String text) {
        TextView section = new TextView(this);
        section.setText(text);
        section.setTextSize(18);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setTextColor(Color.rgb(25, 27, 31));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(8);
        root.addView(section, lp);
    }

    private TextView addStatus(LinearLayout root) {
        TextView status = new TextView(this);
        status.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        root.addView(status, lp);
        return status;
    }

    private Button addButton(LinearLayout root, String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52));
        lp.bottomMargin = dp(18);
        root.addView(button, lp);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
