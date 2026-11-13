@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.aktuel

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File

/** Marketin sayfasındaki bir katalog kartı. [index]: sayfadaki "Online Katalog" düğmesinin sırası. */
data class WebItem(
    val index: Int,
    val title: String,
    val date: String,
    val img: String,
    val startDate: Long?,
    val endDate: Long?,
)

// Sayfadaki "Online Katalog" düğmelerini bulan ortak parça (bağlantı ya da düğme olabilir).
private const val BTNS_JS = """
function btns(){
  var all = document.querySelectorAll('a, button, [role="button"]');
  var out = [];
  for (var i = 0; i < all.length; i++) {
    var t = (all[i].innerText || '').trim().toLowerCase();
    if (t.indexOf('online katalog') === 0 && t.length < 30) out.push(all[i]);
  }
  return out;
}
"""

// Her düğmenin bulunduğu kartın başlığını, tarihini ve kapak görselini toplar.
private const val EXTRACT_JS = "(function(){" + BTNS_JS + """
  var b = btns(), out = [];
  for (var i = 0; i < b.length; i++) {
    var card = b[i], img = null;
    for (var k = 0; k < 8 && card.parentElement; k++) {
      card = card.parentElement;
      img = card.querySelector('img');
      if (img) break;
    }
    var h = card.querySelector('h1,h2,h3,h4,h5');
    var p = card.querySelector('p');
    out.push({
      index: i,
      title: h ? h.innerText.trim() : '',
      date: p ? p.innerText.trim() : '',
      img: img ? (img.currentSrc || img.src) : ''
    });
  }
  return JSON.stringify(out);
})()"""

// n. kartın "Online Katalog" düğmesine basar (site PDF'i kendi koduyla indirtir).
private fun clickJs(index: Int): String =
    "(function(i){" + BTNS_JS + "var b = btns(); if (b[i]) b[i].click();})(" + index + ")"

private val DATE_TEXT =
    Regex("""\d{1,2}\s*[^\d\s\-–()]*\s*[-–]\s*\d{1,2}\s+[^\d\s\-–()]+\s+\d{4}""")

fun parseWebItems(json: String): List<WebItem> {
    val arr = JSONArray(json)
    val out = mutableListOf<WebItem>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val index = o.optInt("index", -1)
        if (index < 0) continue // eski biçimli önbellek kaydı
        val date = o.optString("date").replace("\n", " ")
        val (start, end) = parseDates(date)
        out += WebItem(
            index = index,
            title = o.optString("title").ifBlank { "Katalog" },
            date = DATE_TEXT.find(date)?.value ?: date.take(40),
            img = o.optString("img"),
            startDate = start,
            endDate = end,
        )
    }
    // yeni başlangıç tarihi üstte; tarihi çözülemeyenler sona
    return out.sortedWith(
        compareByDescending<WebItem> { it.startDate ?: Long.MIN_VALUE }
            .thenByDescending { it.endDate ?: Long.MIN_VALUE }
    )
}

/** Sayfa tamamen yüklense bile kartlar sonradan oluşabilir; birkaç kez dener. */
private fun pollItems(view: WebView, attempt: Int, onDone: (String?) -> Unit) {
    view.evaluateJavascript(EXTRACT_JS) { raw ->
        val json = try {
            JSONTokener(raw ?: "null").nextValue() as? String
        } catch (e: Exception) {
            null
        }
        val ok = json != null && runCatching { parseWebItems(json).isNotEmpty() }.getOrDefault(false)
        if (ok) {
            onDone(json)
        } else if (attempt < 12) {
            view.postDelayed({ pollItems(view, attempt + 1, onDone) }, 700)
        } else {
            onDone(null)
        }
    }
}

/**
 * Marketin kendi sayfasını görünmez bir tarayıcıda açıp katalog kartlarını okur ve
 * diğer marketlerdeki gibi kapak kartları olarak gösterir. Karta dokununca, o kartın
 * "Online Katalog" düğmesine arka planda basılır, site PDF'i indirmeye başlar; uygulama
 * bu indirmeyi yakalayıp PDF'i sayfa sayfa görüntüleyicide açar.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebCatalogList(
    market: Market,
    onOpenPdf: (url: String, userAgent: String?, label: String) -> Unit,
    onOpenSite: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val cacheFile = remember { File(ctx.filesDir, "list_" + market.key + ".json") }
    var entries by remember {
        mutableStateOf(runCatching { parseWebItems(cacheFile.readText()) }.getOrDefault(emptyList()))
    }
    var loading by remember { mutableStateOf(true) }
    var ready by remember { mutableStateOf(false) } // sayfa yüklendi, düğmelere basılabilir
    var failed by remember { mutableStateOf(false) }
    val holder = remember { arrayOfNulls<WebView>(1) }
    val started = remember { BooleanArray(1) }
    val waiting = remember { BooleanArray(1) } // bir karta basıldı, PDF indirmesi bekleniyor
    val pendingLabel = remember { arrayOf("") }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun finish(json: String?) {
        loading = false
        if (json != null) {
            entries = parseWebItems(json)
            ready = true
            failed = false
            runCatching { cacheFile.writeText(json) }
        } else if (entries.isEmpty()) {
            failed = true
        }
    }

    fun reload() {
        loading = true
        ready = false
        failed = false
        started[0] = false
        holder[0]?.loadUrl(market.webUrl ?: "about:blank")
    }

    fun openCard(item: WebItem) {
        val wv = holder[0]
        if (wv == null || !ready) {
            toast("Sayfa hâlâ yükleniyor, birkaç saniye sonra tekrar dokunun")
            return
        }
        pendingLabel[0] = item.title + " • " + item.date
        waiting[0] = true
        wv.evaluateJavascript(clickJs(item.index), null)
        wv.postDelayed({
            if (waiting[0]) {
                waiting[0] = false
                toast("Katalog bağlantısı alınamadı. Sağ üstten siteyi açmayı deneyin")
            }
        }, 10_000)
    }

    BackHandler { onBack() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(market.label) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
            },
            actions = {
                TextButton(onClick = onOpenSite) { Text("Site") }
                IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, "Yenile") }
            },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            // Görünmez tarayıcı: sayfayı yükleyip kartları okur ve düğmelere basar
            AndroidView(
                modifier = Modifier.size(1.dp).alpha(0f),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // düğme yeni pencere açmaya çalışırsa aynı tarayıcıda açılsın
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        setDownloadListener { dlUrl, ua, _, _, _ ->
                            if (!waiting[0]) return@setDownloadListener
                            waiting[0] = false
                            if (dlUrl.startsWith("blob:") || dlUrl.startsWith("data:")) {
                                toast("Bu katalog uygulamada açılamıyor (tarayıcıya özel indirme)")
                            } else {
                                onOpenPdf(dlUrl, ua, pendingLabel[0])
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (started[0]) return
                                started[0] = true
                                pollItems(view, 0) { json -> finish(json) }
                            }
                        }
                        holder[0] = this
                        loadUrl(market.webUrl ?: "about:blank")
                    }
                },
                onRelease = { it.destroy(); holder[0] = null },
            )

            when {
                entries.isNotEmpty() -> Column {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(entries, key = { it.index.toString() + it.title }) { item ->
                            Card(onClick = { openCard(item) }) {
                                Column {
                                    AsyncImage(
                                        model = item.img,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                                    )
                                    Column(Modifier.padding(8.dp)) {
                                        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                                        Text(item.date, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (failed) "Katalog düğmeleri sayfada bulunamadı." else "Katalog bulunamadı.",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reload() }) { Text("Tekrar dene") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenSite) { Text("Siteyi aç") }
                }
            }
        }
    }
}
