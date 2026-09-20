package es.lorenzito.tele;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * TELE-LORENZITO para Android. © Lorenzo Moreno.
 * Abre la web de la tele (así se actualiza sola) y añade lo que una web no puede:
 * ventana flotante nativa que sigue abierta hasta que la cierres tú.
 */
public class MainActivity extends Activity {

    private static final String INICIO = "https://tenien112.github.io/television/";
    private static final int FONDO = Color.parseColor("#101A2E");

    private WebView web;
    private FrameLayout raiz;
    private WebChromeClient chrome;
    private View pantallaCompleta;
    private WebChromeClient.CustomViewCallback pantallaCb;
    private volatile boolean reproduciendo = false;
    private boolean salioDeFlotante = false;

    /** Puente con la web: la web avisa de si hay tele puesta y pide abrir enlaces o la ventana flotante. */
    public class Puente {
        @JavascriptInterface
        public void reproduciendo(final boolean si) {
            reproduciendo = si;
            runOnUiThread(() -> {
                if (si) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                ajustarFlotante();
            });
        }

        @JavascriptInterface
        public void flotante() {
            runOnUiThread(() -> entrarFlotante());
        }

        @JavascriptInterface
        public void abrir(final String url) {
            runOnUiThread(() -> abrirFuera(Uri.parse(url)));
        }
    }

    @Override
    protected void onCreate(Bundle guardado) {
        super.onCreate(guardado);
        getWindow().setStatusBarColor(FONDO);
        getWindow().setNavigationBarColor(FONDO);

        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(FONDO);
        web = new WebView(this);
        web.setBackgroundColor(FONDO);
        raiz.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(raiz);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " TELE-LORENZITO-APK");
        web.addJavascriptInterface(new Puente(), "AndroidTele");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("tenien112.github.io".equals(u.getHost())) return false;
                abrirFuera(u);   // las webs oficiales de las cadenas se abren en el navegador
                return true;
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {
                if (pantallaCompleta != null) { cb.onCustomViewHidden(); return; }
                pantallaCompleta = v;
                pantallaCb = cb;
                raiz.addView(v, new FrameLayout.LayoutParams(-1, -1));
                web.setVisibility(View.GONE);
                ocultarBarras(true);
            }

            @Override
            public void onHideCustomView() {
                if (pantallaCompleta == null) return;
                raiz.removeView(pantallaCompleta);
                pantallaCompleta = null;
                web.setVisibility(View.VISIBLE);
                ocultarBarras(false);
                if (pantallaCb != null) { pantallaCb.onCustomViewHidden(); pantallaCb = null; }
            }
        };
        web.setWebChromeClient(chrome);

        if (guardado != null) web.restoreState(guardado);
        else web.loadUrl(INICIO);
    }

    private void ocultarBarras(boolean ocultar) {
        getWindow().getDecorView().setSystemUiVisibility(ocultar
                ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                : 0);
    }

    private void abrirFuera(Uri u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* sin navegador */ }
    }

    // ---------- Ventana flotante ----------
    private PictureInPictureParams parametros() {
        PictureInPictureParams.Builder p = new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9));
        if (Build.VERSION.SDK_INT >= 31) p.setAutoEnterEnabled(reproduciendo);  // al salir con la tele puesta, pasa sola a flotante
        return p.build();
    }

    private void ajustarFlotante() {
        try { setPictureInPictureParams(parametros()); } catch (Exception e) { }
    }

    private void entrarFlotante() {
        try { enterPictureInPictureMode(parametros()); } catch (Exception e) { }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (reproduciendo && Build.VERSION.SDK_INT < 31) entrarFlotante();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean enFlotante, Configuration c) {
        super.onPictureInPictureModeChanged(enFlotante, c);
        web.evaluateJavascript("document.body.classList.toggle('modo-pip'," + enFlotante + ")", null);
        if (!enFlotante) salioDeFlotante = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        salioDeFlotante = false;   // has vuelto a la app a pantalla completa
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (salioDeFlotante) {     // has cerrado tú la ventana flotante con la X: se para la tele
            web.evaluateJavascript("var v=document.getElementById('video'); if (v) v.pause();", null);
            salioDeFlotante = false;
        }
    }

    // ---------- Botón atrás ----------
    @Override
    public void onBackPressed() {
        if (pantallaCompleta != null) { chrome.onHideCustomView(); return; }
        if (web.canGoBack()) { web.goBack(); return; }
        if (reproduciendo) { entrarFlotante(); return; }   // con la tele puesta, atrás la deja en ventana flotante
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle salida) {
        super.onSaveInstanceState(salida);
        web.saveState(salida);
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
