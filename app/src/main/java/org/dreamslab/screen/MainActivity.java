package org.dreamslab.screen;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A full-screen page (assets/index.html) served as https://dreams.local/ so the
 * camera works in it. Photos, clips and songs are served from the app's folders
 * at https://dreams.local/media/<kind>/<name>.
 */
public class MainActivity extends Activity {
    static final String HOST = "dreams.local";
    private static final int REQ_CAMERA = 11;
    private static final int REQ_USB = 12;
    private static final String PREFS = "screen";

    private WebView web;
    private Music music;
    private PermissionRequest pendingCamera;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        music = new Music(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        web.setBackgroundColor(0xFF2B0F54);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return serve(req);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !HOST.equals(req.getUrl().getHost());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() { @Override public void run() { grantCamera(request); } });
            }
        });
        web.addJavascriptInterface(new Bridge(), "DreamsApp");
        setContentView(web);
        web.loadUrl("https://" + HOST + "/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        web.onResume();
        web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep the clock running when someone switches to another app,
        // but leave the page paused so videos stop.
        web.onPause();
    }

    @Override
    protected void onDestroy() {
        music.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()", null);
    }

    // ---------- camera ----------

    private void grantCamera(PermissionRequest request) {
        boolean wantsCamera = false;
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCamera = true;
        }
        if (!wantsCamera) { request.deny(); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        } else {
            pendingCamera = request;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && pendingCamera != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingCamera.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingCamera.deny();
            }
            pendingCamera = null;
        }
    }

    // ---------- USB stick ----------

    private void chooseUsbFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_USB);
        } catch (Exception e) {
            js("window.onUsbDone && window.onUsbDone({error:'nopicker'})");
        }
    }

    /** Tries the folder chosen last time first, so the TA usually only taps once. */
    private void copyFromUsb() {
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString("usbTree", null);
        if (last != null) {
            final Uri tree = Uri.parse(last);
            new Thread(new Runnable() { @Override public void run() {
                Library.CopyResult r = null;
                try {
                    // Fails quickly if this stick is not plugged in.
                    getContentResolver().query(android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                        android.provider.DocumentsContract.getTreeDocumentId(tree)), null, null, null, null).close();
                    r = Library.copyFromTree(MainActivity.this, tree);
                } catch (Exception ignored) { }
                if (r != null) {
                    js("window.onUsbDone && window.onUsbDone(" + r.toJson() + ")");
                } else {
                    runOnUiThread(new Runnable() { @Override public void run() { chooseUsbFolder(); } });
                }
            }}).start();
        } else {
            chooseUsbFolder();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_USB) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            js("window.onUsbDone && window.onUsbDone({error:'cancelled'})");
            return;
        }
        final Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("usbTree", tree.toString()).apply();
        new Thread(new Runnable() { @Override public void run() {
            Library.CopyResult r;
            try { r = Library.copyFromTree(MainActivity.this, tree); }
            catch (Exception e) { js("window.onUsbDone && window.onUsbDone({error:'read'})"); return; }
            js("window.onUsbDone && window.onUsbDone(" + r.toJson() + ")");
        }}).start();
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() { @Override public void run() { web.evaluateJavascript(code, null); } });
    }

    // ---------- serving the page and media ----------

    private WebResourceResponse serve(WebResourceRequest req) {
        Uri u = req.getUrl();
        if (!HOST.equals(u.getHost())) return null;
        String path = u.getPath() == null ? "/" : u.getPath();
        try {
            if (path.startsWith("/media/")) {
                String[] parts = path.substring(7).split("/", 2);
                if (parts.length < 2) return notFound();
                File f = Library.file(this, parts[0], Uri.decode(parts[1]));
                if (f == null) return notFound();
                return fileResponse(f, req.getRequestHeaders());
            }
            String asset = path.equals("/") ? "index.html" : path.substring(1);
            if (asset.contains("..")) return notFound();
            InputStream in = getAssets().open(asset);
            WebResourceResponse r = new WebResourceResponse(Library.mimeFor(asset), "utf-8", in);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            r.setResponseHeaders(h);
            return r;
        } catch (Exception e) {
            return notFound();
        }
    }

    private WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    /** Supports byte ranges so videos can start and loop smoothly. */
    private WebResourceResponse fileResponse(File f, Map<String, String> headers) throws Exception {
        long len = f.length();
        String range = null;
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
        }
        String mime = Library.mimeFor(f.getName());
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Cache-Control", "no-cache");
        if (range != null && range.startsWith("bytes=")) {
            String[] se = range.substring(6).split("-", 2);
            long start = se[0].isEmpty() ? 0 : Long.parseLong(se[0].trim());
            long end = (se.length > 1 && !se[1].trim().isEmpty()) ? Long.parseLong(se[1].trim()) : len - 1;
            if (end >= len) end = len - 1;
            if (start > end) start = 0;
            final long count = end - start + 1;
            final FileInputStream in = new FileInputStream(f);
            long skipped = 0;
            while (skipped < start) {
                long k = in.skip(start - skipped);
                if (k <= 0) break;
                skipped += k;
            }
            InputStream limited = new InputStream() {
                long left = count;
                @Override public int read() throws java.io.IOException {
                    if (left <= 0) return -1;
                    int b = in.read();
                    if (b >= 0) left--;
                    return b;
                }
                @Override public int read(byte[] b, int off, int n) throws java.io.IOException {
                    if (left <= 0) return -1;
                    int got = in.read(b, off, (int) Math.min(n, left));
                    if (got > 0) left -= got;
                    return got;
                }
                @Override public void close() throws java.io.IOException { in.close(); }
            };
            h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
            h.put("Content-Length", String.valueOf(count));
            WebResourceResponse r = new WebResourceResponse(mime, null, limited);
            r.setStatusCodeAndReasonPhrase(206, "Partial Content");
            r.setResponseHeaders(h);
            return r;
        }
        h.put("Content-Length", String.valueOf(len));
        WebResourceResponse r = new WebResourceResponse(mime, null, new FileInputStream(f));
        r.setResponseHeaders(h);
        return r;
    }

    // ---------- functions the page calls as DreamsApp.something() ----------

    public class Bridge {
        @JavascriptInterface public String list() { return Library.listJson(MainActivity.this); }

        @JavascriptInterface public String savePhoto(String dataUrl) {
            try {
                String b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
                return Library.savePhoto(MainActivity.this, Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface public boolean remove(String kind, String name) {
            return Library.delete(MainActivity.this, kind, name);
        }

        @JavascriptInterface public void copyFromUsb() {
            runOnUiThread(new Runnable() { @Override public void run() { MainActivity.this.copyFromUsb(); } });
        }

        @JavascriptInterface public void chooseUsbFolder() {
            runOnUiThread(new Runnable() { @Override public void run() { MainActivity.this.chooseUsbFolder(); } });
        }

        @JavascriptInterface public void musicPlay() { music.play(); }
        @JavascriptInterface public void musicPause() { music.pause(); }
        @JavascriptInterface public void musicNext() { music.next(); }
        @JavascriptInterface public void musicStop() { music.stop(); }
        @JavascriptInterface public void musicVolume(float v) { music.setVolume(v); }
        @JavascriptInterface public String musicStatus() { return music.statusJson(); }

        @JavascriptInterface public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }
}
