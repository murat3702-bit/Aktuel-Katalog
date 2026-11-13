package com.example.aktuel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Bir PDF kataloğu indirir, sayfalarını görsele çevirir ve görüntüleyicinin
 * kullanabileceği dosya adresleri (file://...) olarak döndürür.
 * Sonuç önbelleğe alınır; aynı adres tekrar açılınca yeniden indirilmez.
 */
object PdfCatalogs {
    private const val PAGE_WIDTH = 1500
    private const val KEEP_CATALOGS = 3

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** onProgress(done, total): indirme sırasında total = 0, sayfa çevirirken sayfa sayısı */
    suspend fun load(
        ctx: Context,
        url: String,
        userAgent: String?,
        referer: String?,
        onProgress: (Int, Int) -> Unit,
    ): List<String> = withContext(Dispatchers.IO) {
        val root = File(ctx.cacheDir, "pdf").apply { mkdirs() }
        val dir = File(root, sha1(url.substringBefore('#')))
        val marker = File(dir, "done")

        if (marker.exists()) {
            val cached = dir.listFiles { f -> f.name.startsWith("p_") }
                ?.sortedBy { it.name }.orEmpty()
            if (cached.isNotEmpty()) {
                dir.setLastModified(System.currentTimeMillis())
                return@withContext cached.map { it.toURI().toString() }
            }
        }

        dir.deleteRecursively()
        dir.mkdirs()
        onProgress(0, 0)

        // 1) indir
        val src = File(dir, "src.pdf")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (!userAgent.isNullOrBlank()) conn.setRequestProperty("User-Agent", userAgent)
            if (!referer.isNullOrBlank()) conn.setRequestProperty("Referer", referer)
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            conn.inputStream.use { input ->
                FileOutputStream(src).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        // indirilen şey gerçekten PDF mi (bazen hata sayfası gelir)
        val head = ByteArray(5)
        val readN = src.inputStream().use { it.read(head) }
        if (readN < 5 || String(head, Charsets.ISO_8859_1) != "%PDF-") {
            dir.deleteRecursively()
            throw java.io.IOException("Bağlantı bir PDF dosyası döndürmedi")
        }

        // 2) sayfaları görsele çevir
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val result = mutableListOf<String>()
        try {
            val total = renderer.pageCount
            for (i in 0 until total) {
                coroutineContext.ensureActive()
                onProgress(i, total)
                val page = renderer.openPage(i)
                try {
                    val scale = PAGE_WIDTH.toFloat() / page.width
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(PAGE_WIDTH, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE) // PdfRenderer şeffaf bırakır
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val f = File(dir, "p_%03d.jpg".format(i))
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    bmp.recycle()
                    result += f.toURI().toString()
                } finally {
                    page.close()
                }
            }
            onProgress(total, total)
        } finally {
            renderer.close()
            pfd.close()
        }

        src.delete() // yer kazanmak için orijinal PDF'i saklama
        marker.writeText("ok")

        // eski katalogları temizle
        root.listFiles()?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_CATALOGS)
            ?.forEach { it.deleteRecursively() }

        result
    }
}
