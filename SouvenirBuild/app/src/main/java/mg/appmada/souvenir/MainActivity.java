package mg.appmada.souvenir;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER = 501;
    private final Map<String, HttpURLConnection> active = new ConcurrentHashMap<>();
    private volatile byte[] lastResult;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(Intent.createChooser(i, "Choisir une photo"), FILE_CHOOSER);
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER && filePathCallback != null) {
            Uri[] res = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) res = new Uri[]{data.getData()};
            filePathCallback.onReceiveValue(res);
            filePathCallback = null;
        }
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private void complete(String job, String error, String base64) {
        try {
            JSONObject payload = new JSONObject();
            if (error != null) payload.put("error", error);
            if (base64 != null) payload.put("url", "data:image/jpeg;base64," + base64);
            js("window.nativeComplete(" + JSONObject.quote(job) + "," + payload.toString() + ")");
        } catch (Exception e) {
            js("window.nativeComplete(" + JSONObject.quote(job) + ",{error:'Erreur interne'})");
        }
    }

    private String friendlyError(int status, String body, String requestId) {
        String message = "";
        String code = "";
        try {
            JSONObject o = new JSONObject(body);
            Object err = o.opt("error");
            if (err instanceof JSONObject) {
                JSONObject eo = (JSONObject) err;
                message = eo.optString("message", "");
                code = eo.optString("code", "");
            } else if (err != null) {
                message = String.valueOf(err);
            }
            if (message.isEmpty()) message = o.optString("message", "");
        } catch (Exception ignored) {
            if (body != null && !body.trim().isEmpty()) message = body.trim();
        }

        if (status == 401) {
            message = "Clé API invalide ou refusée. Vérifiez la clé dans Réglages.";
        } else if (status == 429) {
            message = "Limite API atteinte ou crédit indisponible. Vérifiez votre compte API OpenAI.";
        } else if (status == 403 && (message == null || message.isEmpty())) {
            message = "Accès au modèle image refusé pour cette clé API.";
        } else if (message == null || message.isEmpty()) {
            message = "Erreur API HTTP " + status;
        }

        String lower = message.toLowerCase();
        if (lower.contains("billing") || lower.contains("quota") || lower.contains("credit balance")) {
            message = "Crédit ou facturation API indisponible. Vérifiez le solde et la facturation du compte API OpenAI.";
        }

        if (!code.isEmpty()) message += " (" + code + ")";
        if (requestId != null && !requestId.isEmpty()) message += "\nRéférence : " + requestId;
        return message;
    }

    private static class ApiResult {
        int status;
        String body;
        String requestId;
        String base64;
    }

    private ApiResult callImageEdit(String job, String key, byte[] image, JSONObject req) throws Exception {
        HttpURLConnection conn = null;
        ApiResult result = new ApiResult();
        try {
            String boundary = "----Souvenir" + System.currentTimeMillis();
            conn = (HttpURLConnection) new URL("https://api.openai.com/v1/images/edits").openConnection();
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(300000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Accept", "application/json");
            active.put(job, conn);

            try (OutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
                String[] keys = {"model","prompt","quality","size","output_format"};
                for (String k : keys) {
                    if (!req.has(k)) continue;
                    write(out, "--" + boundary + "\r\n");
                    write(out, "Content-Disposition: form-data; name=\"" + k + "\"\r\n\r\n");
                    write(out, String.valueOf(req.get(k)) + "\r\n");
                }

                write(out, "--" + boundary + "\r\n");
                write(out, "Content-Disposition: form-data; name=\"image[]\"; filename=\"original.jpg\"\r\n");
                write(out, "Content-Type: image/jpeg\r\n\r\n");
                out.write(image);
                write(out, "\r\n--" + boundary + "--\r\n");
                out.flush();
            }

            result.status = conn.getResponseCode();
            result.requestId = conn.getHeaderField("x-request-id");
            InputStream in = result.status >= 200 && result.status < 300 ? conn.getInputStream() : conn.getErrorStream();
            result.body = readAll(in);

            if (result.status >= 200 && result.status < 300) {
                JSONObject response = new JSONObject(result.body);
                if (!response.has("data") || response.getJSONArray("data").length() == 0) {
                    throw new IOException("L’API n’a retourné aucune image.");
                }
                result.base64 = response.getJSONArray("data").getJSONObject(0).optString("b64_json", "");
                if (result.base64.isEmpty()) throw new IOException("L’API n’a retourné aucune image exploitable.");
            }
            return result;
        } finally {
            active.remove(job);
            if (conn != null) conn.disconnect();
        }
    }

    private boolean shouldFallback(ApiResult r) {
        if (r == null) return false;
        if (r.status != 400 && r.status != 404) return false;
        String t = (r.body == null ? "" : r.body).toLowerCase();
        return t.isEmpty() || t.contains("bad request") || t.contains("model") || t.contains("unsupported") || t.contains("parameter");
    }

    public class Bridge {
        @JavascriptInterface public boolean storeKey(String key) {
            getSharedPreferences("souvenir", MODE_PRIVATE).edit().putString("api_key", key == null ? "" : key).apply();
            return true;
        }

        @JavascriptInterface public String readKey() {
            return getSharedPreferences("souvenir", MODE_PRIVATE).getString("api_key", "");
        }

        @JavascriptInterface public void cancel(String job) {
            HttpURLConnection c = active.remove(job);
            if (c != null) c.disconnect();
        }

        @JavascriptInterface public void restore(String json) {
            new Thread(() -> {
                String job = "";
                try {
                    JSONObject root = new JSONObject(json);
                    job = root.getString("job");
                    String key = root.getString("key").trim();
                    String dataUrl = root.getString("image");
                    JSONObject req = root.getJSONObject("request");
                    String b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
                    byte[] image = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);

                    ApiResult response = callImageEdit(job, key, image, req);

                    if (shouldFallback(response)) {
                        js("window.nativeRetry(" + JSONObject.quote(job) + ")");
                        JSONObject fallback = new JSONObject(req.toString());
                        fallback.put("model", "gpt-image-1.5");
                        response = callImageEdit(job, key, image, fallback);
                    }

                    if (response.status < 200 || response.status >= 300) {
                        throw new IOException(friendlyError(response.status, response.body, response.requestId));
                    }

                    lastResult = android.util.Base64.decode(response.base64, android.util.Base64.DEFAULT);
                    complete(job, null, response.base64);
                } catch (Exception e) {
                    complete(job, e.getMessage() == null ? "Erreur de restauration." : e.getMessage(), null);
                } finally {
                    if (!job.isEmpty()) active.remove(job);
                }
            }).start();
        }

        @JavascriptInterface public void saveResult() {
            new Thread(() -> {
                boolean ok = false;
                try {
                    byte[] bytes = lastResult;
                    if (bytes == null) throw new IOException("Aucun résultat");
                    String name = "Souvenir-" + System.currentTimeMillis() + ".jpg";
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Souvenir");
                    }
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IOException("Impossible de créer le fichier");
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IOException("Impossible d'écrire le fichier");
                        out.write(bytes);
                    }
                    ok = true;
                } catch (Exception ignored) {}
                final boolean result = ok;
                js("window.nativeSaved(" + result + ")");
            }).start();
        }

        private void write(OutputStream out, String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }

        private String readAll(InputStream in) throws IOException {
            if (in == null) return "";
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }
}
