package com.delivery.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

public class MainActivity extends AppCompatActivity implements RecognitionListener {

    private static final int REQ_AUDIO = 101;

    private WebView webView;
    private Model model;
    private SpeechService speechService;
    private boolean modelReady = false;
    private boolean startPending = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage for the app
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        // JS <-> Native bridge, exposed as window.AndroidVoice
        webView.addJavascriptInterface(new VoiceBridge(), "AndroidVoice");

        webView.loadUrl("file:///android_asset/index.html");

        // Prepare offline model + mic permission up front
        ensureAudioPermission();
        initModel();
    }

    /* ============ Permissions ============ */
    private void ensureAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && startPending) {
            startPending = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginRecognition();
            } else {
                jsStatus("\u062f\u0633\u062a\u0631\u0633\u06cc \u0645\u06cc\u06a9\u0631\u0648\u0641\u0648\u0646 \u0631\u062f \u0634\u062f");
            }
        }
    }

    /* ============ Vosk model ============ */
    private void initModel() {
        // Unpacks assets/model-fa -> internal storage, once.
        StorageService.unpack(this, "model-fa", "model",
                (m) -> {
                    model = m;
                    modelReady = true;
                },
                (e) -> jsStatus("\u062e\u0637\u0627 \u062f\u0631 \u0628\u0627\u0631\u06af\u0630\u0627\u0631\u06cc \u0645\u062f\u0644 \u0635\u0648\u062a\u06cc"));
    }

    /* ============ JS bridge ============ */
    private class VoiceBridge {
        @JavascriptInterface
        public void start() {
            ui.post(() -> {
                if (ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    startPending = true;
                    ensureAudioPermission();
                    return;
                }
                beginRecognition();
            });
        }

        @JavascriptInterface
        public void stop() {
            ui.post(MainActivity.this::stopRecognition);
        }
    }

    private void beginRecognition() {
        if (!modelReady || model == null) {
            jsStatus("\u0645\u062f\u0644 \u0635\u0648\u062a\u06cc \u062f\u0631 \u062d\u0627\u0644 \u0622\u0645\u0627\u062f\u0647\u200c\u0633\u0627\u0632\u06cc\u002e\u002e\u002e \u06a9\u0645\u06cc \u0635\u0628\u0631 \u06a9\u0646\u06cc\u062f");
            return;
        }
        if (speechService != null) return; // already running
        try {
            Recognizer rec = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(rec, 16000.0f);
            speechService.startListening(this);
        } catch (Exception e) {
            jsStatus("\u0645\u06cc\u06a9\u0631\u0648\u0641\u0648\u0646 \u0641\u0639\u0627\u0644 \u0646\u0634\u062f");
        }
    }

    private void stopRecognition() {
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
        }
    }

    /* ============ Vosk RecognitionListener ============ */
    @Override
    public void onResult(String hypothesis) {
        // Fires per final utterance -> truly continuous, no ding-ding.
        String text = extractText(hypothesis);
        if (text != null && !text.isEmpty()) {
            jsResult(text);
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        String text = extractText(hypothesis);
        if (text != null && !text.isEmpty()) {
            jsResult(text);
        }
    }

    @Override
    public void onPartialResult(String hypothesis) { /* ignore interim */ }

    @Override
    public void onError(Exception e) {
        jsStatus("\u062e\u0637\u0627\u06cc \u0645\u06cc\u06a9\u0631\u0648\u0641\u0648\u0646");
    }

    @Override
    public void onTimeout() { /* keep listening; SpeechService continues */ }

    private String extractText(String hypothesisJson) {
        try {
            JSONObject o = new JSONObject(hypothesisJson);
            if (o.has("text")) return o.getString("text").trim();
        } catch (Exception ignored) {}
        return null;
    }

    /* ============ Call back into the web app ============ */
    private void jsResult(final String text) {
        final String safe = jsonEscape(text);
        ui.post(() -> webView.evaluateJavascript(
                "window.__nativeVoiceResult && window.__nativeVoiceResult(\"" + safe + "\")", null));
    }

    private void jsStatus(final String msg) {
        final String safe = jsonEscape(msg);
        ui.post(() -> webView.evaluateJavascript(
                "window.__nativeVoiceStatus && window.__nativeVoiceStatus(\"" + safe + "\")", null));
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    @Override
    protected void onDestroy() {
        stopRecognition();
        if (model != null) { model.close(); model = null; }
        super.onDestroy();
    }
}
