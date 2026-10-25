package com.example.aktuel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val BASE_URL = "https://www.aktuelstudio2.com/"
private const val CACHE_HOURS = 6L

data class Market(val key: String, val label: String)

data class Catalog(
    val id: String,
    val name: String,
    val date: String,
    val cover: String,
    val pages: List<String>,
)

// key = <key>.json dosya adı
val MARKETS = listOf(
    Market("bim", "BİM"),
    Market("sok", "ŞOK"),
    Market("a101", "A101"),
    Market("hakmar", "Hakmar"),
    Market("ozkuruslar", "Özkuruşlar"),
    Market("kim", "Kim"),
    Market("happycenter", "Happy Center"),
    Market("cagri", "Çağrı Market"),
)

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
        out += Catalog(
            id = o.optString("id", i.toString()),
            name = o.optString("name"),
            date = o.optString("tarih").replace("\n", " "),
            cover = enc(o.optString("kapak", pages[0])),
            pages = pages,
        )
    }
    return out
}

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
