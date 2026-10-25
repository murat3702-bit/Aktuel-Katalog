package com.example.aktuel

import android.content.Context
import androidx.annotation.DrawableRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

const val BASE_URL = "https://www.aktuelstudio2.com/"
private const val CACHE_HOURS = 6L

data class Market(val key: String, val label: String, @DrawableRes val logo: Int)

data class Catalog(
    val id: String,
    val name: String,
    val date: String,        // ekranda gösterilen metin ("22-28 Ekim 2026 (...)")
    val startDate: Long?,    // başlangıç tarihi (epoch ms), çözülemezse null
    val endDate: Long?,      // bitiş tarihi (epoch ms), çözülemezse null
    val cover: String,
    val pages: List<String>,
)

// key = <key>.json dosya adı
val MARKETS = listOf(
    Market("bim", "BİM", R.drawable.logo_bim),
    Market("sok", "ŞOK", R.drawable.logo_sok),
    Market("a101", "A101", R.drawable.logo_a101),
    Market("migros", "Migros", R.drawable.logo_migros),
    Market("carrefoursa", "CarrefourSA", R.drawable.logo_carrefoursa),
    Market("hakmar", "Hakmar", R.drawable.logo_hakmar),
    Market("ozkuruslar", "Özkuruşlar", R.drawable.logo_ozkuruslar),
    Market("kim", "Kim", R.drawable.logo_kim),
    Market("happycenter", "Happy Center", R.drawable.logo_happycenter),
    Market("cagri", "Çağrı Market", R.drawable.logo_cagri),
)

// ---------- Tarih çözümleme ----------

private val TR = Locale("tr", "TR")
private val MONTHS = listOf(
    "ocak", "şubat", "mart", "nisan", "mayıs", "haziran",
    "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık",
)

// "17-30 Ekim 2026", "29 Ekim-30 Kasım 2026", "24 Mart-31 Aralık 2026 (Detaylı Ürünler)"
private val RANGE =
    Regex("""(\d{1,2})\s*([^\d\s\-–()]+)?\s*[-–]\s*(\d{1,2})\s+([^\d\s\-–()]+)\s+(\d{4})""")

// "24 Ekim 2026"
private val SINGLE = Regex("""(\d{1,2})\s+([^\d\s\-–()]+)\s+(\d{4})""")

private fun monthIndex(name: String) = MONTHS.indexOf(name.lowercase(TR))

private fun ts(y: Int, m: Int, d: Int, endOfDay: Boolean): Long =
    Calendar.getInstance(TimeZone.getTimeZone("Europe/Istanbul")).apply {
        clear()
        if (endOfDay) set(y, m, d, 23, 59, 59) else set(y, m, d, 0, 0, 0)
    }.timeInMillis

/** @return başlangıç ve bitiş zamanı; çözülemeyenler ("Şok Net", "Güncel Satış Günleri") null */
fun parseDates(text: String): Pair<Long?, Long?> {
    val t = text.replace("\n", " ")

    RANGE.find(t)?.let { r ->
        val d1 = r.groupValues[1].toInt()
        val m1raw = r.groupValues[2]
        val d2 = r.groupValues[3].toInt()
        val m2 = monthIndex(r.groupValues[4])
        val y2 = r.groupValues[5].toInt()
        val m1 = if (m1raw.isEmpty()) m2 else monthIndex(m1raw)
        if (m1 >= 0 && m2 >= 0) {
            val y1 = if (m1 > m2) y2 - 1 else y2 // Aralık-Ocak gibi yıl geçişi
            return ts(y1, m1, d1, false) to ts(y2, m2, d2, true)
        }
    }
    SINGLE.find(t)?.let { r ->
        val m = monthIndex(r.groupValues[2])
        if (m >= 0) {
            val d = r.groupValues[1].toInt()
            val y = r.groupValues[3].toInt()
            return ts(y, m, d, false) to ts(y, m, d, true)
        }
    }
    return null to null
}

// "a (1).webp" gibi adreslerdeki boşlukları kodla
private fun enc(u: String) = u.trim().replace(" ", "%20")

fun parseCatalogs(text: String): List<Catalog> {
    val arr = JSONArray(text.trim().removePrefix("\uFEFF"))
    val out = mutableListOf<Catalog>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        // imagesize'a güvenme, url_XX anahtarlarını sırayla topla
        val pages = o.keys().asSequence()
            .filter { it.startsWith("url_") }
            .sortedBy { it.removePrefix("url_").toIntOrNull() ?: Int.MAX_VALUE }
            .map { enc(o.getString(it)) }
            .toList()
        if (pages.isEmpty()) continue
        val date = o.optString("tarih").replace("\n", " ")
        val (start, end) = parseDates(date)
        out += Catalog(
            id = o.optString("id", i.toString()),
            name = o.optString("name"),
            date = date,
            startDate = start,
            endDate = end,
            cover = enc(o.optString("kapak", pages[0])),
            pages = pages,
        )
    }
    // En yeni başlangıç tarihi en üstte. Eşitlikte bitişi geç olan üstte.
    // Tarihi çözülemeyenler en sona (kendi aralarında JSON sırası korunur).
    return out.sortedWith(
        compareByDescending<Catalog> { it.startDate ?: Long.MIN_VALUE }
            .thenByDescending { it.endDate ?: Long.MIN_VALUE }
    )
}

// ---------- Ana ekran market sırası (kalıcı) ----------

class OrderStore(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun load(): List<Market> {
        val saved = sp.getString("market_order", "").orEmpty().split(",").filter { it.isNotBlank() }
        val byKey = MARKETS.associateBy { it.key }
        val ordered = saved.mapNotNull { byKey[it] }.distinct()
        // sonradan eklenen marketler listenin sonuna düşer
        return ordered + MARKETS.filter { it !in ordered }
    }

    fun save(list: List<Market>) {
        sp.edit().putString("market_order", list.joinToString(",") { it.key }).apply()
    }
}

// ---------- Veri çekme ----------

class Repo(private val dir: File) {
    /** @return liste ve "çevrimdışı kayıt gösteriliyor mu" bilgisi */
    suspend fun load(key: String, force: Boolean): Pair<List<Catalog>, Boolean> =
        withContext(Dispatchers.IO) {
            val f = File(dir, "$key.json")
            val fresh = f.exists() &&
                System.currentTimeMillis() - f.lastModified() < CACHE_HOURS * 3_600_000L
            if (fresh && !force) return@withContext parseCatalogs(f.readText()) to false
            try {
                val conn = URL(BASE_URL + key + ".json").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                val txt = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val list = parseCatalogs(txt) // bozuksa önbelleğin üstüne yazma
                f.writeText(txt)
                list to false
            } catch (e: Exception) {
                if (f.exists()) parseCatalogs(f.readText()) to true else throw e
            }
        }
}
