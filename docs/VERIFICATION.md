# Hasil verifikasi

Verifikasi final dilakukan pada **3 Oktober 2026 (Asia/Jakarta)** di Windows, workspace `D:\Project\SOS-Mesh-Library`.

## Lingkungan

- Tidak ditemukan AGENTS.md yang berlaku pada workspace atau direktori induknya; AGENTS.md dalam proyek lain tidak berlaku di sini.
- JDK: Oracle Java 17.0.12, `C:\Program Files\Java\jdk-17`.
- SDK: `D:\Android`, platform API 36 dan Build Tools 35.0.0 tersedia.
- ADB: `D:\Android\platform-tools\adb.exe`.
- Toolchain: AGP 8.11.1, Kotlin 2.2.20, Gradle 8.14; tidak memakai Flutter/NDK.
- Wrapper telah dihasilkan ulang oleh task Gradle `wrapper`; skrip Windows/Unix dan JAR lengkap tersedia. Checksum SHA-256 distribusi Gradle 8.14 all dari services.gradle.org dipasang di `gradle-wrapper.properties`.
- Proyek lama hanya dibaca; penyalinan terbatas pada infrastruktur Wrapper, kemudian Wrapper diperbarui di workspace baru.

## Build dan pemeriksaan yang telah dijalankan

```powershell
.\gradlew.bat :sosrelay:assembleDebug :sosrelay:assembleRelease :app:assembleDebug :sosrelay:testDebugUnitTest :sosrelay:lintDebug :app:lintDebug --console=plain
```

Hasil final: **BUILD SUCCESSFUL**, exit code 0.

| Pemeriksaan | Hasil |
| --- | --- |
| AAR debug | `sosrelay/build/outputs/aar/sosrelay-debug.aar` terbentuk |
| AAR release | `sosrelay/build/outputs/aar/sosrelay-release.aar` terbentuk |
| APK debug | `app/build/outputs/apk/debug/app-debug.apk` terbentuk |
| Tes codec | 4 tests, 0 failures, 0 errors, 0 skipped |
| Lint library | 0 errors, 2 warnings saran pembaruan versi Gradle/stdlib |
| Lint app | 0 errors, 2 warnings: backup configuration Android 12+ dan ikon aplikasi demo |
| AAR ZIP | `classes.jar`, manifest, consumer rules, dan `assets/sosrelay-integration.md` ada |
| APK manifest via aapt | application ID `id.ac.usu.sosrelay.demo`, minSdk 26, target/compileSdk 36, launcher `.MainActivity`, izin BLE/location tergabung |
| APK signature via apksigner | Verifies; APK Signature Scheme v2 valid untuk Android 11 |

Kesalahan lint path Windows pada build awal telah diperbaiki menjadi `sdk.dir=D\:/Android`; bukan masalah yang masih tertunda. Gradle masih melaporkan fitur deprecated dari toolchain yang belum kompatibel dengan Gradle 9; proyek dikunci pada Gradle 8.14 yang berhasil dibangun. Warnings lint di atas tidak menghalangi build.

Tes codec memeriksa vector wire yang sesuai algoritma firmware ESP32-C3, koordinat signed int24, pembulatan ala C++ `lround`, batas epoch, ACK flags/status, serta penolakan payload malformed. Tes JVM ini tidak mengeksekusi Bluetooth stack Android dan bukan bukti radio interoperabilitas fisik.

## Pengujian perangkat fisik

**Belum dilakukan:** pemasangan/launch pada OPPO, dialog izin sebenarnya, deteksi kemampuan OPPO, scanning melalui radio, advertising melalui radio, penerimaan ESP32-C3, dan pengiriman ESP32-C3 ke HP.

`adb devices -l` kosong saat diperiksa. Port COM3/4/5/6 yang ditemukan di Windows merupakan `Standard Serial over Bluetooth link`; tidak ada port ESP32-C3 yang teridentifikasi pada pemeriksaan ini. Tidak dilakukan flash firmware atau koneksi serial ke port tersebut.

Langkah berikutnya: hubungkan OPPO lewat USB data dan otorisasi USB debugging; pasang APK dengan perintah README. Hubungkan ESP32-C3 dan identifikasi port USB serial yang benar; gunakan firmware referensi dan command README. Periksa kemampuan nyata HP sebelum uji PHY Coded. Dukungan Coded/extended tidak bisa dibuktikan lewat build, dan dukungan Coded tidak berarti coding S=8/125 kbps telah diverifikasi.
