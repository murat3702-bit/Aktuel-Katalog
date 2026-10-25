@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.aktuel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.onSizeChanged
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
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) { AppRoot(repo) }
            }
        }
    }
}

@Composable
fun AppRoot(repo: Repo) {
    var market by remember { mutableStateOf<Market?>(null) }
    var catalog by remember { mutableStateOf<Catalog?>(null) }

    BackHandler(enabled = market != null) {
        if (catalog != null) catalog = null else market = null
    }

    val c = catalog
    val m = market
    when {
        c != null -> Viewer(c, onBack = { catalog = null })
        m != null -> CatalogList(repo, m, onOpen = { catalog = it }, onBack = { market = null })
        else -> MarketList(onPick = { market = it })
    }
}

@Composable
fun MarketList(onPick: (Market) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Aktüel Katalog") }) }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(MARKETS) { m ->
                Card(onClick = { onPick(m) }) {
                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                        Text(m.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                }
            }
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
