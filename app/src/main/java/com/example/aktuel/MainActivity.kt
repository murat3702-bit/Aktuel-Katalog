@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.aktuel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = Repo(filesDir)
        val store = OrderStore(this)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) { AppRoot(repo, store) }
            }
        }
    }
}

@Composable
fun AppRoot(repo: Repo, store: OrderStore) {
    var market by remember { mutableStateOf<Market?>(null) }
    var web by remember { mutableStateOf<Market?>(null) }
    var catalog by remember { mutableStateOf<Catalog?>(null) }

    BackHandler(enabled = market != null) {
        if (catalog != null) catalog = null else market = null
    }

    val c = catalog
    val m = market
    val w = web
    when {
        w != null -> WebScreen(w, onBack = { web = null })
        c != null -> Viewer(c, onBack = { catalog = null })
        m != null -> CatalogList(repo, m, onOpen = { catalog = it }, onBack = { market = null })
        else -> MarketList(store, onPick = { picked ->
            // webUrl'si olan market (Anpagross) uygulama içi tarayıcıda açılır
            if (picked.webUrl != null) web = picked else market = picked
        })
    }
}

// Reklam sunucuları: bu alan adlarına giden istekler boş cevapla kapatılır
private val AD_HOSTS = listOf(
    "googlesyndication.com", "doubleclick.net", "googleadservices.com", "2mdn.net",
    "adnxs.com", "adsafeprotected.com", "amazon-adsystem.com", "criteo.com", "criteo.net",
    "pubmatic.com", "rubiconproject.com", "taboola.com", "outbrain.com", "moatads.com",
    "adform.net", "smartadserver.com", "openx.net", "adservice.google.com",
)

private fun isAdHost(host: String): Boolean =
    AD_HOSTS.any { host == it || host.endsWith(".$it") } || host.startsWith("adservice.google.")

// Sayfadaki reklam alanlarını (Google reklam kutuları, sabit reklam şeritleri) gizler.
private const val HIDE_ADS_JS = """
(function(){
  if (window.__hideAds) return;
  window.__hideAds = true;
  var css = 'ins.adsbygoogle, iframe[id^="google_ads"], iframe[name^="google_ads"], ' +
    'iframe[src*="doubleclick"], iframe[src*="googlesyndication"], div[id^="google_ads"], ' +
    'div[id*="aswift"], .google-auto-placed, [id^="div-gpt-ad"], [class*="adsbygoogle"] ' +
    '{ display:none !important; height:0 !important; min-height:0 !important; }';
  var st = document.createElement('style');
  st.textContent = css;
  (document.head || document.documentElement).appendChild(st);
  function sweep(){
    var f = document.querySelectorAll('iframe');
    for (var i = 0; i < f.length; i++) {
      var key = (f[i].src || '') + ' ' + (f[i].id || '') + ' ' + (f[i].name || '');
      if (!/googleads|doubleclick|googlesyndication|google_ads|aswift|safeframe/i.test(key)) continue;
      var target = f[i];
      var n = f[i].parentElement;
      while (n && n !== document.body) {
        var cs = window.getComputedStyle(n);
        if (cs.position === 'fixed' || cs.position === 'absolute') {
          // en yakın konumlu kap; ekranın büyük kısmını kaplıyorsa (katalog kabı) dokunma
          if (n.getBoundingClientRect().height < window.innerHeight * 0.7) target = n;
          break;
        }
        n = n.parentElement;
      }
      target.style.setProperty('display', 'none', 'important');
    }
  }
  // Reklam yüklenmese bile geride kalan boş reklam çerçeveleri ve kapatma (X) düğmeleri
  var adRects = [];
  function isPos(cs){ return cs.position === 'fixed' || cs.position === 'absolute'; }
  function hideEl(el){ el.style.setProperty('display', 'none', 'important'); el.__a = true; }
  function sweepBoxes(){
    var H = window.innerHeight, W = window.innerWidth;
    var els = document.body ? document.body.getElementsByTagName('*') : [];
    var i, el, cs, r;
    for (i = 0; i < els.length; i++) {
      el = els[i];
      if (el.__a) continue;
      cs = window.getComputedStyle(el);
      if (cs.display === 'none') continue;
      r = el.getBoundingClientRect();
      // şerit biçimli: genişliğin çoğunu kaplar, alçak
      if (r.width < W * 0.8 || r.height < 30 || r.height > H * 0.2) continue;
      var isFrame = el.tagName === 'IFRAME';
      var emptyBox = isPos(cs) &&
        cs.backgroundColor !== 'rgba(0, 0, 0, 0)' && cs.backgroundColor !== 'transparent' &&
        (el.innerText || '').replace(/\s/g, '').length <= 2 &&
        !el.querySelector('canvas,video,img') && cs.backgroundImage === 'none';
      if (!isFrame && !emptyBox) continue;
      adRects.push({ t: r.top, b: r.bottom, l: r.left, r: r.right });
      hideEl(el);
      // kapatma düğmesi aynı kabın içindeyse küçük kabı da gizle
      var par = el.parentElement;
      if (par && par !== document.body && par !== document.documentElement) {
        var pr = par.getBoundingClientRect();
        if (pr.height < H * 0.25 && window.getComputedStyle(par).display !== 'none') hideEl(par);
      }
    }
    // gizlenen çerçevelerin yanındaki küçük yuvarlak kapatma düğmeleri
    if (adRects.length) {
      for (i = 0; i < els.length; i++) {
        el = els[i];
        if (el.__a) continue;
        cs = window.getComputedStyle(el);
        if (cs.display === 'none' || !isPos(cs)) continue;
        r = el.getBoundingClientRect();
        if (r.width < 16 || r.width > 56 || r.height < 16 || r.height > 56) continue;
        for (var k = 0; k < adRects.length; k++) {
          var a = adRects[k];
          if (r.top >= a.t - 50 && r.bottom <= a.b + 50 && r.right >= a.r - 60 && r.left <= a.r + 60) {
            hideEl(el);
            break;
          }
        }
      }
    }
  }
  sweep();
  sweepBoxes();
  setInterval(function(){ sweep(); sweepBoxes(); }, 800);
})();
"""

// FlipHTML5 sayfasındaki üst başlık ve alt gezinme çubuğunu bulup gizler.
// Arayüz sonradan oluştuğu için düzenli aralıkla tekrar çalışır.
private const val HIDE_CHROME_JS = """
(function(){
  if (window.__hideChrome) return;
  window.__hideChrome = true;
  function run(){
    var H = window.innerHeight, W = window.innerWidth;
    document.documentElement.style.setProperty('background-color', '#000', 'important');
    if (document.body) document.body.style.setProperty('background-color', '#000', 'important');
    var els = document.body ? document.body.getElementsByTagName('*') : [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.__h) continue;
      var cs = window.getComputedStyle(el);
      if (cs.display === 'none') continue;
      var r = el.getBoundingClientRect();
      // Neredeyse tüm ekranı kaplayan kapların arka plan rengini siyah yap (resim varsa dokunma)
      if (!el.__b && r.width >= W * 0.95 && r.height >= H * 0.85) {
        var bc = cs.backgroundColor;
        if (bc && bc !== 'rgba(0, 0, 0, 0)' && bc !== 'transparent') {
          if (bc !== 'rgb(0, 0, 0)') el.style.setProperty('background-color', '#000', 'important');
          el.__b = true;
        }
      }
      if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
      if (r.width < W * 0.9 || r.height < 20) continue;
      var txt = (el.innerText || '').toLowerCase();
      var isTop = r.top < 8 && r.height < H * 0.25 &&
        (txt.indexOf('fliphtml5') >= 0 || el.querySelector('a[href*="fliphtml5"]'));
      var isBottom = r.bottom > H - 8 && r.height < H * 0.5 && /\d+\s*\/\s*\d+/.test(txt);
      if (isTop || isBottom) {
        el.style.setProperty('display', 'none', 'important');
        el.__h = true;
      }
    }
  }
  run();
  setInterval(run, 700);
})();
"""

private fun onFramePage(url: String?, market: Market): Boolean =
    market.frameKeyword != null && url != null && url.contains(market.frameKeyword)

/**
 * Sayfadaki [keyword] içeren iframe'in adresini bulup WebView'de doğrudan onu açar.
 * Iframe geç yüklenebildiği için kısa aralıklarla birkaç kez dener.
 */
private fun openFrameDirectly(view: WebView, keyword: String, attempt: Int = 0) {
    val js = "(function(){var f=document.querySelectorAll('iframe');" +
        "for(var i=0;i<f.length;i++){var s=f[i].src||f[i].getAttribute('data-src')||'';" +
        "if(s.indexOf('" + keyword + "')>=0)return s;}return '';})()"
    view.evaluateJavascript(js) { raw ->
        val src = try {
            org.json.JSONTokener(raw ?: "\"\"").nextValue() as? String ?: ""
        } catch (e: Exception) {
            ""
        }
        if (src.startsWith("http")) {
            view.loadUrl(src)
        } else if (attempt < 8) {
            view.postDelayed({ openFrameDirectly(view, keyword, attempt + 1) }, 800)
        }
    }
}

/**
 * Marketin kendi sitesini uygulama içinde açar.
 * Geri tuşu önce tam ekrandan çıkar, sonra sayfa geçmişinde geri gider.
 * FlipHTML5 "tam ekran" düğmesi için WebChromeClient.onShowCustomView gerekir.
 */
@Composable
fun WebScreen(market: Market, onBack: () -> Unit) {
    var progress by remember { mutableIntStateOf(0) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val holder = remember { arrayOfNulls<WebView>(1) }
    val activity = LocalContext.current as? Activity
    val fullscreen = customView != null
    val hideBars = fullscreen || market.hideChrome

    // Tam ekranda (ve hideChrome açıkken) durum ve gezinme çubuklarını gizle
    DisposableEffect(hideBars) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (hideBars) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // AppRoot'taki BackHandler'dan sonra kayıtlı olduğu için önceliği bunda
    BackHandler {
        val wv = holder[0]
        if (customView != null) {
            wv?.webChromeClient?.onHideCustomView()
        } else if (wv != null && wv.canGoBack() && !onFramePage(wv.url, market)) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(if (market.hideChrome) Modifier.background(Color.Black) else Modifier)
    ) {
        Scaffold(
            containerColor = if (market.hideChrome) Color.Black else MaterialTheme.colorScheme.background,
            topBar = {
            if (!market.hideChrome) {
                TopAppBar(
                    title = { Text(market.label) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                    },
                    actions = {
                        IconButton(onClick = { holder[0]?.reload() }) { Icon(Icons.Default.Refresh, "Yenile") }
                    },
                )
            }
        }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            setBackgroundColor(0xFF000000.toInt())
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? {
                                    val host = request.url.host
                                    if (BLOCK_ADS && host != null && isAdHost(host)) {
                                        return WebResourceResponse(
                                            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)),
                                        )
                                    }
                                    return null
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    if (BLOCK_ADS) view.evaluateJavascript(HIDE_ADS_JS, null)
                                    val kw = market.frameKeyword ?: return
                                    if (url == null) return
                                    if (!url.contains(kw)) {
                                        // site sayfası: katalog çerçevesini bulup doğrudan aç
                                        openFrameDirectly(view, kw)
                                    } else if (market.hideChrome) {
                                        // katalog sayfası: başlık ve alt çubukları gizle
                                        view.evaluateJavascript(HIDE_CHROME_JS, null)
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                    customView = view
                                    customCallback = callback
                                }

                                override fun onHideCustomView() {
                                    customView = null
                                    customCallback?.onCustomViewHidden()
                                    customCallback = null
                                }
                            }
                            holder[0] = this
                            loadUrl(market.webUrl ?: "about:blank")
                        }
                    },
                    onRelease = { it.destroy(); holder[0] = null },
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Tam ekran görünümü her şeyin üstüne bindir
        val cv = customView
        if (cv != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> FrameLayout(ctx).apply { setBackgroundColor(0xFF000000.toInt()) } },
                update = { frame ->
                    if (cv.parent !== frame) {
                        (cv.parent as? ViewGroup)?.removeView(cv)
                        frame.removeAllViews()
                        frame.addView(
                            cv,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                },
            )
        }
    }
}

private const val GRID_PAD = 12
private const val GRID_GAP = 12

@Composable
fun MarketList(store: OrderStore, onPick: (Market) -> Unit) {
    val order = remember { mutableStateListOf<Market>().apply { addAll(store.load()) } }
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    var viewportH by remember { mutableIntStateOf(0) }
    var gridTop by remember { mutableFloatStateOf(0f) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) } // sürüklenen kutunun sol-üstü (grid koordinatı)

    Scaffold(topBar = { TopAppBar(title = { Text("Aktüel Katalog") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewportH = it.height }
                    .verticalScroll(scroll)
            ) {
                Text(
                    "Sıralamak için bir logoya uzun basıp sürükleyin",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = GRID_PAD.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )

                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GRID_PAD.dp)
                        .onGloballyPositioned { gridTop = it.positionInParent().y }
                ) {
                    val gapPx = with(density) { GRID_GAP.dp.toPx() }
                    val cw = (constraints.maxWidth - gapPx) / 2f
                    val ch = cw * 360f / 440f // logolar 440x360
                    val rows = (order.size + 1) / 2
                    val cwDp = with(density) { cw.toDp() }
                    val chDp = with(density) { ch.toDp() }
                    val totalH = rows * ch + (rows - 1).coerceAtLeast(0) * gapPx

                    fun slot(i: Int) = Offset((i % 2) * (cw + gapPx), (i / 2) * (ch + gapPx))

                    // Sürüklenen kutunun merkezine göre yeni sırayı hesapla
                    fun reorder() {
                        val key = draggingKey ?: return
                        val cur = order.indexOfFirst { it.key == key }
                        if (cur < 0) return
                        val cx = dragPos.x + cw / 2f
                        val cy = dragPos.y + ch / 2f
                        val col = (cx / (cw + gapPx)).toInt().coerceIn(0, 1)
                        val row = (cy / (ch + gapPx)).toInt().coerceIn(0, rows - 1)
                        val to = (row * 2 + col).coerceIn(0, order.lastIndex)
                        if (to != cur) order.add(to, order.removeAt(cur))
                    }

                    // Sürüklerken ekranın üst/alt kenarına yaklaşınca otomatik kaydır
                    LaunchedEffect(draggingKey) {
                        if (draggingKey == null) return@LaunchedEffect
                        val edge = with(density) { 72.dp.toPx() }
                        while (true) {
                            val topInView = gridTop + dragPos.y - scroll.value
                            val bottomInView = topInView + ch
                            val delta = when {
                                bottomInView > viewportH - edge -> 18f
                                topInView < edge -> -18f
                                else -> 0f
                            }
                            if (delta != 0f) {
                                val used = scroll.scrollBy(delta)
                                if (used != 0f) {
                                    dragPos = Offset(dragPos.x, dragPos.y + used)
                                    reorder()
                                }
                            }
                            delay(16)
                        }
                    }

                    Box(Modifier.fillMaxWidth().height(with(density) { totalH.toDp() })) {
                        order.forEach { m ->
                            key(m.key) {
                                val idx = order.indexOf(m)
                                val dragging = draggingKey == m.key
                                val target = slot(idx)
                                val animated by animateIntOffsetAsState(
                                    IntOffset(target.x.roundToInt(), target.y.roundToInt()),
                                    label = "slot",
                                )
                                val pos = if (dragging) {
                                    IntOffset(dragPos.x.roundToInt(), dragPos.y.roundToInt())
                                } else animated

                                Image(
                                    painter = painterResource(m.logo),
                                    contentDescription = m.label,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .offset { pos }
                                        .size(cwDp, chDp)
                                        .zIndex(if (dragging) 1f else 0f)
                                        .graphicsLayer {
                                            if (dragging) {
                                                scaleX = 1.06f
                                                scaleY = 1.06f
                                                alpha = 0.92f
                                            }
                                        }
                                        .clickable { onPick(m) }
                                        .pointerInput(m.key, cw) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    draggingKey = m.key
                                                    dragPos = slot(order.indexOf(m))
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragPos = Offset(dragPos.x + amount.x, dragPos.y + amount.y)
                                                    reorder()
                                                },
                                                onDragEnd = {
                                                    draggingKey = null
                                                    store.save(order)
                                                },
                                                onDragCancel = {
                                                    draggingKey = null
                                                    store.save(order)
                                                },
                                            )
                                        },
                                )
                            }
                        }
                    }
                }

                TextButton(
                    onClick = {
                        order.clear()
                        order.addAll(MARKETS)
                        store.save(order)
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Sıralamayı sıfırla") }
            }

            Text(
                "Geliştirici Murat BOSTANCI ®",
                modifier = Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

sealed interface UiState {
    data object Loading : UiState
    data class Ok(val list: List<Catalog>, val offline: Boolean) : UiState
    data class Err(val msg: String) : UiState
}

@Composable
fun CatalogList(repo: Repo, market: Market, onOpen: (Catalog) -> Unit, onBack: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var state by remember(market) { mutableStateOf<UiState>(UiState.Loading) }

    LaunchedEffect(market, tick) {
        state = UiState.Loading
        state = try {
            val (list, offline) = repo.load(market.key, force = tick > 0)
            UiState.Ok(list, offline)
        } catch (e: Exception) {
            UiState.Err(e.message ?: "Bağlantı hatası")
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(market.label) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
            },
            actions = {
                IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "Yenile") }
            },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Err -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Yüklenemedi: ${s.msg}", textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { tick++ }) { Text("Tekrar dene") }
                }
                is UiState.Ok -> Column {
                    if (s.offline) {
                        Text(
                            "Çevrimdışı: kayıtlı liste gösteriliyor",
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (s.list.isEmpty()) {
                        Text("Katalog bulunamadı", Modifier.padding(24.dp))
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(s.list, key = { it.id + it.cover }) { c ->
                            Card(onClick = { onOpen(c) }) {
                                Column {
                                    AsyncImage(
                                        model = c.cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                                    )
                                    Column(Modifier.padding(8.dp)) {
                                        Text(c.date, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                                        Text("${c.pages.size} sayfa", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Viewer(catalog: Catalog, onBack: () -> Unit) {
    val pager = rememberPagerState(pageCount = { catalog.pages.size })
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { index ->
            ZoomPage(
                url = catalog.pages[index],
                active = pager.currentPage == index,
                onZoomChange = { if (pager.currentPage == index) zoomed = it },
            )
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White)
            }
            Text(
                "${pager.currentPage + 1} / ${catalog.pages.size}  •  ${catalog.date}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun ZoomPage(url: String, active: Boolean, onZoomChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(active) {
        if (!active) { scale = 1f; offset = Offset.Zero }
    }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1f) / 2f
        val my = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    onZoomChange(scale > 1f)
                })
            }
            .pointerInput(Unit) {
                // Tek parmak + zoom yokken olayı tüketme: sayfa kaydırma çalışsın
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.size > 1
                        if (multi || scale > 1f) {
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            scale = (scale * z).coerceIn(1f, 5f)
                            offset = if (scale <= 1f) Offset.Zero else clamp(offset + p, scale)
                            onZoomChange(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).size(Size.ORIGINAL).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}
