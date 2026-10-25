AKTÜEL KATALOG (kişisel kullanım)

Kurulum:
1. Android Studio (Koala veya yeni) kur.
2. File > Open ile bu klasörü aç, "Gradle sync" bitsin (internet gerekir).
3. Telefonu USB ile bağla (geliştirici modu + USB hata ayıklama açık) ve Run'a bas.
   APK için: Build > Build Bundle(s)/APK(s) > Build APK(s)
   Çıktı: app/build/outputs/apk/debug/app-debug.apk

Market eklemek/çıkarmak: app/src/main/java/com/example/aktuel/Data.kt içindeki MARKETS listesi
(logo: app/src/main/res/drawable-nodpi/logo_<market>.png, 440x360).
Ana ekranda logoya uzun basıp sürükleyerek sıralama değiştirilir, sıra kaydedilir.
Katalogları tarihe göre sıralama: Data.kt > parseDates / parseCatalogs.
Liste 6 saat önbelleklenir, sağ üstteki yenile ikonu zorla günceller.
Görseller diskte (1 GB'a kadar) saklanır.
