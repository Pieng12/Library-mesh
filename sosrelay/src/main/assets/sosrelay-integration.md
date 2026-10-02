# Integrasi sosrelay AAR

AAR ini memakai Android framework API 26+ dan Kotlin stdlib 2.2.20.
AAR lokal tidak menyertakan dependency transitif: tambahkan keduanya di Gradle host:

```kotlin
implementation(files("libs/sosrelay-release.aar"))
implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
```

Gunakan Kotlin 2.2.20/toolchain yang dapat membaca metadata Kotlin 2.2,
minSdk >= 26, compileSdk >= 36, repository mavenCentral(). Tidak membutuhkan AndroidX,
Flutter, coroutine, atau WorkManager. Manifest izin digabung dari AAR;
host wajib meminta izin RelayPermissions.required() saat runtime.
SosRelay(applicationContext) { event -> ... } dipanggil di main thread.
checkCapabilities(), startScanning(), stopScanning(), sendTestPacket(),
stopAdvertising(), stopAll(), close() merupakan API publik.
Host harus memanggil stopAll()/close() saat keluar foreground.
PHY Coded diwajibkan, S=8 tidak dipilih/diverifikasi oleh API yang digunakan.
Contoh lengkap dan uji ESP32 terdapat di README.md proyek SOS-Mesh-Library.
