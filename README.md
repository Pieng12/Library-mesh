# SOS Relay — library BLE Kotlin dan aplikasi contoh

Proyek native Android yang dapat dikerjakan lewat VS Code dan PowerShell. `sosrelay` menghasilkan AAR; `app` memakai library melalui `implementation(project(":sosrelay"))` dan menghasilkan APK. Minimum Android 8/API 26; Android 11/API 30 termasuk didukung. Kemampuan radio OPPO A9 2020 **harus diperiksa pada HP**, tidak disimpulkan dari versi Android atau nama chipset.

## Struktur dan cakupan

- `sosrelay/src/main/kotlin/id/ac/usu/sosrelay/SosRelay.kt`: inisialisasi dengan application context, kemampuan perangkat, scanning, extended advertising, callback, cleanup.
- `RelayPermissions.kt`: daftar/pemeriksaan izin; dialog tetap tanggung jawab aplikasi host.
- `RelayPacket.kt`: model publik dan codec `resqmesh-ble17-v1` kompatibel dengan firmware referensi.
- `sosrelay/src/test/`: tes byte wire, koordinat bertanda, ACK flags, validasi, dan batas epoch.
- `app/src/main/kotlin/id/ac/usu/sosrelay/demo/MainActivity.kt`: lima tombol, permintaan izin, log dibatasi 200 entri, lifecycle foreground.
- `gradle/wrapper/`, `gradlew`, `gradlew.bat`: Gradle Wrapper lengkap.
- `docs/VERIFICATION.md`: hasil verifikasi build dan status pengujian perangkat.

Komunikasi berhenti pada `Activity.onStop()`. Tidak ada background service, wake lock, atau background location. Saat aplikasi dibuka lagi, tekan tombol untuk memulai komunikasi kembali. Scanning dan advertising dapat berjalan bersamaan jika controller mendukung; kegagalan controller dilaporkan. Tidak ada pairing, GATT, jaminan delivery, retransmission aplikasi, relay/multi-hop, ACK otomatis, Basic Flooding, Trickle, ataupun metrik penelitian. Codec dapat membaca ACK firmware tetapi library tidak menjalankan protokol ACK.

## Lingkungan Windows

Versi yang digunakan: **JDK 17, AGP 8.11.1, Kotlin 2.2.20, Gradle 8.14, compileSdk/targetSdk 36, minSdk 26**. Konfigurasi mengikuti toolchain proyek lama tanpa plugin Flutter. AGP 8.11 memerlukan minimal Gradle 8.13 dan JDK 17 ([dokumentasi resmi](https://developer.android.com/build/releases/agp-8-11-0-release-notes)). JDK dan SDK Android Studio boleh digunakan langsung tanpa membuka IDE; Gradle global tidak diperlukan.

Lingkungan yang ditemukan saat pembuatan: `C:\Program Files\Java\jdk-17`, SDK `D:\Android`, ADB `D:\Android\platform-tools\adb.exe`. SDK lain juga ada di `%LOCALAPPDATA%\Android\Sdk`, tetapi proyek ini memakai `D:\Android`. Sesuaikan path dengan instalasi Anda:

```powershell
Set-Location 'D:\Project\SOS-Mesh-Library'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'D:\Android'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
# local.properties bersifat lokal dan tidak masuk Git.
'sdk.dir=D\:/Android' | Set-Content -Encoding ascii local.properties
java -version
adb version
.\gradlew.bat --version
```

Pastikan Android SDK Platform 36, Build Tools 35.0.0, platform-tools, dan lisensi SDK tersedia. Jika belum, dari terminal:

```powershell
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

Gradle akan mengunduh dependency dari Google Maven/Maven Central pada build awal. VS Code cukup digunakan sebagai editor; plugin Android Studio/Flutter tidak diperlukan.

## Build, instalasi, dan log

```powershell
# AAR debug dan APK debug
.\gradlew.bat :sosrelay:assembleDebug --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
# AAR release untuk dibagikan (tidak memerlukan signing key)
.\gradlew.bat :sosrelay:assembleRelease --console=plain
# Tes codec dan lint kedua modul
.\gradlew.bat :sosrelay:testDebugUnitTest :sosrelay:lintDebug :app:lintDebug --console=plain

adb devices -l
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n id.ac.usu.sosrelay.demo/.MainActivity
adb logcat -v time 'SosRelayDemo:I' '*:S'
```

Aktifkan Developer options dan USB debugging di OPPO, hubungkan kabel data, lalu terima dialog otorisasi RSA di HP. Jika `adb devices` kosong, cek kabel, mode USB, dan driver Windows. Jika ada lebih dari satu perangkat, gunakan `adb -s SERIAL ...`. Build berhasil tidak berarti BLE telah diuji melalui radio.

Hasil:

- `sosrelay/build/outputs/aar/sosrelay-debug.aar`
- `sosrelay/build/outputs/aar/sosrelay-release.aar`
- `app/build/outputs/apk/debug/app-debug.apk`
- `sosrelay/build/reports/tests/testDebugUnitTest/index.html`
- `{sosrelay,app}/build/reports/lint-results-debug.html`

## Integrasi AAR ke proyek lain

Salin `sosrelay-release.aar` ke `app/libs/` proyek host. AAR lokal **tidak membawa metadata dependency transitif**. Host memakai Kotlin 2.2.20 atau toolchain yang dapat membaca metadata Kotlin 2.2, minSdk >= 26, compileSdk >= 36, dan repository `mavenCentral()` untuk stdlib:

```kotlin
dependencies {
    implementation(files("libs/sosrelay-release.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
}
```

Library hanya bergantung pada API framework Android dan Kotlin stdlib; tidak memerlukan AndroidX, Flutter, coroutine, WorkManager, atau Activity khusus. Petunjuk dependency juga disertakan di dalam AAR sebagai `assets/sosrelay-integration.md`.

Manifest library akan digabung ke manifest host: Bluetooth legacy sampai API 30, SCAN/CONNECT/ADVERTISE untuk API 31+, serta coarse/fine location. Library sengaja tidak memakai `neverForLocation`, karena flag tersebut dapat memfilter sebagian beacon ([izin Bluetooth Android](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)). Kebijakan awal konservatif: semua izin `RelayPermissions.required()` harus terpenuhi sebelum start scan/advertise; layanan Lokasi juga harus hidup sebelum scanning. Android 12+ meminta coarse dan fine bersama agar pengguna dapat memilih Lokasi tepat. Tidak meminta notification/background location. Host harus meminta izin runtime dan tidak cukup hanya mendeklarasikan manifest.

Contoh API yang tersedia, dipanggil dari main thread dalam Activity host:

```kotlin
import id.ac.usu.sosrelay.*

private var radio: SosRelay? = null

override fun onStart() {
    super.onStart()
    radio = SosRelay(applicationContext) { event ->
        when (event) {
            is RelayEvent.Received -> {
                android.util.Log.i("HostBle", "${event.packet} RSSI=${event.rssi}")
            }
            is RelayEvent.Error -> android.util.Log.e("HostBle", "${event.code}: ${event.message}")
            is RelayEvent.Status -> android.util.Log.i("HostBle", event.message)
            is RelayEvent.Capabilities -> android.util.Log.i("HostBle", event.value.toString())
        }
    }
    radio?.checkCapabilities() // Nilai kemampuan nullable jika izin kurang/Bluetooth mati.
}

private fun startScanFromButton() {
    val missing = RelayPermissions.missing(this)
    if (missing.isNotEmpty()) {
        requestPermissions(RelayPermissions.required(), 100)
        return // Tunggu hasil; jangan mulai komunikasi di sini.
    }
    radio?.startScanning() // Library juga memvalidasi Bluetooth, Lokasi, dan Coded PHY.
}

private fun sendFromButton() {
    if (RelayPermissions.missing(this).isEmpty()) {
        radio?.sendTestPacket() // Default sender CRC32("sosrelay-demo"), koordinat contoh.
        // Atau radio?.sendTestPacket(PacketCodec.testPacket("nama-node"))
    }
}

private fun stopFromButton() {
    radio?.stopScanning()
    radio?.stopAdvertising()
    // radio?.stopAll() juga tersedia.
}

override fun onStop() {
    radio?.close() // Idempotent; dispose receiver dan operasi. Buat instance baru saat masuk lagi.
    radio = null
    super.onStop()
}
```

Untuk penanganan hasil izin yang lengkap lihat `MainActivity.kt`: aksi hanya dilanjutkan jika izin lengkap dan host masih berada di foreground. Callback berjalan pada main thread; jangan melakukan pekerjaan berat di listener. Jangan memulai/menahan beberapa instance radio sekaligus.

## Kontrak radio dan paket ESP32-C3

Referensi dibaca dari `D:\PKM\Project\pkmproject`, terutama `android/.../NativeBleConfig.kt`, `CodedRadioPolicy.kt`, `NativeBleAdvertiser.kt`, `NativeBlePermissions.kt`, dan `firmware/esp32c3/{include/Protocol.h,src/Protocol.cpp,src/CodedRadio.cpp,src/main.cpp}`. Proyek referensi tidak diubah. Wrapper disalin sebagai infrastruktur build lalu diperbarui lewat task wrapper; implementasi library ditulis terpisah agar tidak membawa ketergantungan Flutter/service/penelitian.

Scanning memakai LOW_LATENCY, report delay 0, `setLegacy(false)`, `PHY_LE_CODED`, filter manufacturer ID `0xFFFF` dengan prefix `52 4D`. Tidak memakai filter UUID `21FE` dari konfigurasi Android lama karena advertiser firmware saat ini hanya mengirim manufacturer data. PHY Coded wajib pada tahap ini; perangkat tanpa Coded ditolak dengan error yang jelas, tanpa fallback 1M. Pengaturan scan mengikuti [ScanSettings.Builder](https://developer.android.com/reference/android/bluetooth/le/ScanSettings.Builder).

TX memakai extended advertising, non-connectable, non-scannable, primary/secondary Coded, interval 400 × 0,625 ms = 250 ms, requested power medium (-7 dBm). Daya aktual dari callback ditampilkan. `onAdvertisingSetStarted` sukses berarti controller menerima operasi, bukan bukti ESP32 menerima paket. API [AdvertisingSetParameters.Builder](https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters.Builder) yang dipakai memilih Coded tetapi tidak menetapkan/membuktikan coding S=8/125 kbps. Log menyatakan S=8 belum terverifikasi; RX mencatat primary/secondary PHY dari `ScanResult`, bukan coding scheme.

Payload aplikasi tepat 17 byte; nilai multi-byte big-endian, kecuali company ID di AD structure yang ditambahkan Android:

| Offset | Isi |
| --- | --- |
| 0–1 | Magic `52 4D` (`RM`) |
| 2–5 | CRC32 sender, uint32 |
| 6–8 | uint24 detik relatif `1780272000` (2026-06-01 UTC) |
| 9–11 | Latitude int24 bertanda × 10000 |
| 12–14 | Longitude int24 bertanda × 10000 |
| 15 | Status: 0 cancelled, 1 active, 2 resolved |
| 16 | bit7 ACK, bit6 from-server, bit0–5 hop |

ACK tidak boleh berstatus active; koordinat ACK diabaikan seperti firmware. API codec mengharuskan sender uint32, hop 0–63, koordinat valid, dan timestamp dalam `[epoch, epoch + 2^24)`. Tidak melakukan wrap timestamp; epoch protokol perlu diperbarui secara serentak pada Android/ESP32 setelah rentang tersebut habis. Pengiriman default memakai waktu HP, sehingga tanggal HP harus benar. Paket uji merupakan SOS ACTIVE dengan koordinat contoh `-6.2001, 106.8167`, hop 0; gunakan hanya untuk pengujian. Manufacturer ID `FFFF` mengikuti firmware uji, bukan ID vendor produksi yang telah dialokasikan.

Android `addManufacturerData(0xFFFF, payload17)` menambahkan company ID sekali; jangan memasukkan `FF FF` ke payload. Android `getManufacturerSpecificData(0xFFFF)` sudah membuang ID. Di firmware raw manufacturer data panjangnya 19 byte; full AD structure menjadi `14 FF FF FF 52 4D ...`. Paket invalid ditolak; tidak ada deduplication penelitian, sehingga RX berulang memang ditampilkan.

## Uji OPPO → ESP32-C3 dan ESP32-C3 → OPPO

1. Pasang APK, buka aplikasi dan tekan **Periksa kemampuan perangkat**. Terima izin Lokasi tepat (Android 11: saat aplikasi digunakan). Hidupkan Bluetooth dan Lokasi melalui Pengaturan HP. Catat BLE, extended, Coded, dan advertiser. Jika Coded/extended tidak didukung, firmware dan Android versi lebih baru tidak dapat menambahkan kemampuan hardware/controller tersebut.
2. Siapkan ESP32-C3 dengan firmware referensi yang memakai NimBLE-Arduino 2.5.1 serta `CONFIG_BT_NIMBLE_EXT_ADV=1`; firmware BLE legacy umum tidak cukup. Petunjuk ini untuk command firmware lama yang benar-benar tersedia. Flash/build firmware bukan bagian proyek Android ini; tidak dilakukan otomatis. Hubungkan ESP32 serial 115200 baud, gunakan terminal serial VS Code/PlatformIO atau PowerShell berikut. Ganti COM5 sesuai perangkat dan tutup monitor serial lain agar port tidak terkunci:

```powershell
[System.IO.Ports.SerialPort]::GetPortNames()
$blePort = [System.IO.Ports.SerialPort]::new('COM5', 115200)
$blePort.NewLine = "`n"
$blePort.DtrEnable = $false
$blePort.RtsEnable = $false
$blePort.Open()
$bleClock = @{ command='clock_sync'; wall_time_ms=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
$blePort.WriteLine(($bleClock | ConvertTo-Json -Compress))
$blePort.WriteLine('{"command":"configure_session","node_id":"esp32-test","role":"OBSERVER","mode":"basic","radio_mode":"coded","protocol_active":false,"rx_burst_gap_ms":1000,"protocol_version":"resqmesh-ble17-v1","protocol_epoch_id":"resqmesh-2026-06-01","protocol_epoch_seconds":1780272000}')
$blePort.WriteLine('{"command":"start_trial","trial_id":"android-tx-test"}')
$blePort.WriteLine('{"command":"readiness"}')
# Jalankan ReadExisting lagi bila respons belum muncul; setiap command harus ok:true.
$blePort.ReadExisting()
```

3. Untuk **HP → ESP32**, tekan **Kirim paket uji**. Tunggu log advertising aktif. Setelah beberapa detik baca `$blePort.ReadExisting()` dan cari `BLE_PACKET_RECEIVED`, sender CRC, koordinat, status, hop 0, dan RSSI. Role OBSERVER dengan `protocol_active:false` menghindari relay/ACK; `TOPOLOGY_IGNORED` sesudah event RX pada konfigurasi ini wajar. Firmware baru mencatat RX saat observation window dibuka oleh `start_trial`. Tekan **Hentikan advertising** dan akhiri window:

```powershell
$blePort.WriteLine('{"command":"end_observation_window"}')
$blePort.ReadExisting()
```

4. Untuk **ESP32 → HP**, tekan **Mulai scanning**, lalu gunakan source firmware referensi:

```powershell
$bleClock = @{ command='clock_sync'; wall_time_ms=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
$blePort.WriteLine(($bleClock | ConvertTo-Json -Compress))
$blePort.WriteLine('{"command":"reset_trial"}')
$blePort.WriteLine('{"command":"configure_session","node_id":"esp32-test","role":"SOURCE","mode":"basic","radio_mode":"coded","protocol_active":true,"rx_burst_gap_ms":1000,"protocol_version":"resqmesh-ble17-v1","protocol_epoch_id":"resqmesh-2026-06-01","protocol_epoch_seconds":1780272000}')
$blePort.WriteLine('{"command":"start_trial","trial_id":"android-rx-test"}')
$blePort.WriteLine('{"command":"trigger_sos","latitude":3.5952,"longitude":98.6722}')
$blePort.ReadExisting()
```

5. Pastikan tiap command mendapat `ok:true`, firmware menampilkan `SOS_CREATED`/TX, dan HP menampilkan RX dengan koordinat `3.5952, 98.6722`, hop 1, status ACTIVE serta RSSI. Mode `basic` di sini hanya digunakan untuk membuat sumber paket dari firmware referensi; **bukan pengujian atau implementasi Basic Flooding Android**. Gunakan jarak dekat dulu; pencatatan PHY Coded tidak membuktikan S=8 di udara.
6. Tekan **Hentikan scanning**, lalu pastikan log RX berhenti. Uji juga Bluetooth mati, izin ditolak, dan meninggalkan aplikasi: operasi harus berhenti atau menghasilkan error tanpa crash. Kembalikan konfigurasi ESP32 ke keadaan aman:

```powershell
$blePort.WriteLine('{"command":"end_observation_window"}')
$blePort.WriteLine('{"command":"reset_trial"}')
$blePort.ReadExisting()
$blePort.Close()
$blePort.Dispose()
```

Jika tidak ada RX: periksa semua izin, Lokasi, Bluetooth, capability Coded, format/ID manufacturer, extended advertising firmware, command readiness, observation window, dan radio start acknowledgment. Hindari menekan start/stop berulang dengan cepat karena Android dapat membatasi scanning. Penerimaan fisik dan kecocokan radio belum bisa dipastikan oleh build atau tes codec saja; lihat hasil aktual pada `docs/VERIFICATION.md`.
