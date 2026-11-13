# Update Android 0.6.0 — Mode Server FOH di HP, Web Remote Browser & Sync SHA-256

Status: Kandidat debug APK untuk uji perangkat privat (`versionCode=6`, `versionName=0.5.0-spike`, `applicationId=com.swararia.audiolab.spike`), bukan rilis produksi publik. Karena `versionCode` dan debug keystore sama, pemasangan melalui `adb install -r` menjaga seluruh lagu serta database Room bermigrasi aditif (`v3 → v4`) tanpa reset.

## Fitur Utama yang Ditambahkan

1. **Dual-Mode Tab Remote (`Kendalikan PC` | `Jadikan HP Ini Server`):**
   - Mode **`Kendalikan PC`** mempertahankan alur client `DesktopRemoteScreen` + Keystore auto-reconnect tanpa perubahan perilaku.
   - Mode **`Jadikan HP Ini Server`** (`RemoteServerScreen`) menjadikan HP ini sebagai pemutar audio panggung yang dapat dipantau dan dikendalikan dari HP Android lain maupun browser Web di jaringan Wi-Fi / Hotspot / USB tethering yang sama.

2. **Server HTTPS TLS Pinned Terintegrasi `PlaybackService`:**
   - Sertifikat EC P-256 *self-signed* ephemeral di RAM (`AndroidRemoteTlsIdentity`, `IP SAN` + `EKU serverAuth` + `CA=false`) tanpa menulis kunci privat ke penyimpanan permanen.
   - Endpoint HTTPS/1.1 lokal (`AndroidRemoteHttpListener`) berjalan pada port yang dapat dipilih (default `8443`, rentang `1024–65535`) dengan binding eksklusif ke alamat IPv4 privat/loopback terpilih.
   - Terintegrasi dengan `PlaybackService` (notifikasi foreground layanan musik / `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`) agar pemutaran dan remote control tetap hidup saat layar HP server dimatikan atau aplikasi diminimalkan.
   - Pembacaan `/v1/state` dan `/web/state` menyinkronkan posisi playback terbaru langsung dari Looper `PlaybackService`, dan otomatis memblokir perintah remote ketika HP server sedang memutar **Preview pencarian online**.

3. **Dua Bidang Klien Terpisah (`/v1/*` Native & `/web/*` Browser):**
   - **Aplikasi Swararia Android Lain (`/v1/*`):** menampilkan kode QR lokal (dibangkitkan pure Kotlin dengan `com.google.zxing:core:3.5.3` tanpa izin kamera di server) berformat `swararia1|IPv4:port|fingerprint|invite` (TTL 90 detik) yang dapat langsung dipindai melalui tombol `Scan QR dari PC` di perangkat FOH.
   - **Web Browser HP / iPhone / Laptop (`/` + `/web/*`):** aset UI FOH lokal (`assets/remote_server/{index.html,remote.css,remote.js}`) dengan PIN 8 karakter (TTL 90 detik, lockout otomatis sesudah 5 percobaan salah), cookie sesi `__Host-swararia_session` (`HttpOnly; Secure; SameSite=Strict`), header anti-CSRF `X-Swararia-Csrf` pada RAM JS (tanpa `localStorage`/`sessionStorage`), validasi `Origin` / `Sec-Fetch-Site`, serta CSP ketat tanpa `innerHTML`.

4. **Download, Upload & Sinkronisasi Koleksi Bebas Duplikat (`SHA-256`, Room `v3 → v4`):**
   - Migrasi aditif `AudioMigrations.MIGRATION_3_4` (`3.json → 4.json`) menambahkan kolom terindeks `sha256` pada tabel `tracks` dan backfill otomatis saat startup / server aktif melalui `MediaLibrary.scanMissingMetadata()`.
   - Browser FOH memiliki tab **Sync & Transfer**:
     - Unduh satu lagu atau seluruh lagu di suatu grup playlist ke perangkat FOH (`GET /web/media/{id}`, streaming buffer 64 KiB + `Content-Disposition` aman).
     - Unggah file/folder audio dari HP/Laptop FOH ke HP Server (`POST /web/library/upload`): browser menghitung digest WebCrypto `SHA-256`, membandingkannya dengan `/web/library/manifest`, melewati file yang sudah ada di HP Server (`deduplicated`), serta menautkannya ke playlist tujuan tanpa menduplikasi file fisik.
     - File baru yang benar-benar diunggah divalidasi streaming `SHA-256`-nya oleh HTTP listener, diperiksa trek audionya oleh `MediaExtractor` dan `MediaMetadataRetriever`, baru dipublikasikan atomik melalui `MediaStore` `IS_PENDING 1 → 0`.

5. **Kontrol Keamanan Acara Live:**
   - Toggle global **"Izinkan Transfer & Sync File"** berstatus **MATI secara default**.
   - Role **`view_only`** tidak pernah bisa menerima izin transfer/kontrol; hanya perangkat **`control`** yang diberi izin eksplisit per perangkat oleh operator HP Server.
   - Toggle **"Tahan Transfer Saat Audio Diputar"** (default **AKTIF**) menahan upload/download sementara lagu sedang berstatus `PLAYING`.
   - Listener menyediakan maksimal 16 slot koneksi dan membatasi transfer media berat di `Semaphore(2)` (`429 Retry-After: 2`) sehingga pembaruan status playback dan tombol `Play`/`Pause`/`Fade`/`Stop` tetap responsif.

## Batasan yang Diketahui

- `Swararia Desktop` di repository terpisah (`audio-library-starter`) **tidak diubah** pada tahap ini; sinkronisasi koleksi antar-HP/Laptop menggunakan UI Web FOH yang disajikan langsung oleh HP Server.
- Koneksi browser ke HTTPS lokal memakai sertifikat lokal sementara, sehingga browser FOH menampilkan peringatan keamanan sertifikat pertama kali (operator dapat mencocokkan SHA-256 fingerprint TLS yang tampil pada layar Server HP).
- Scanner dependensi & static security scanner lokal (`scan_dependencies`, `run_security_scanner`) tidak berjalan pada mesin build karena daemon ekstensi SecureCoder tidak aktif; seluruh kontrol keamanan diperiksa melalui review kode dan tes loopback/kontrak.

## Verifikasi yang Telah Dijalankan (Mesin Build)

- **Gradle Unit Tests (`testDebugUnitTest`):** `108` tests lulus across `25` suites (`0` failures, `0` errors, `0` skipped), termasuk suite baru `AndroidRemoteTlsIdentityTest`, `AndroidRemoteServerEngineTest`, dan `AndroidRemoteHttpLoopbackTest`.
- **Android Lint (`lintDebug`):** `53` Warning, `0` Error/Fatal (`0` peringatan baru pada `AndroidRemote*.kt` dan `RemoteServerScreen.kt`).
- **Build & Kontrak APK (`assembleDebug`, `verify_artifacts.py`, `test_apk_contract.py`):**
  - `app-arm64-v8a-debug.apk`: `106,988,110` bytes (`sha256: 86e7793349a981e9eac16ee2c679d7a30790228131abdfef534160b51e940e1b`), signed & zipaligned (`4/4` `test_apk_contract.py` lulus).
  - `app-armeabi-v7a-debug.apk`: `100,316,940` bytes (`sha256: c87dec4bb4efc12841becf3dae151ea0c296ba75a1971309edaeb268c5a8e831`).
- **Kontrak Host & Wire Probe:** `verification/test_data_layer.py` (`9/9`, termasuk migrasi SQL `3.json -> 4.json`), `verification/test_desktop_remote_wiring.py` (`4/4`), `test_architecture_wiring.py` (`5/5`), `test_ui_source_contracts.py` (`9/9`), `test_core.py` (`6/6`), `test_architecture.py` (`36/36`), `test_ui_rules.py` (`5/5`), `test_ui_integration.py` (`3/3`), `test_upgrade_03.py` (`7/7`), `test_engine_payload.py` (`1/1`), `verification/run_desktop_remote_checks.py` (`7/7` wire checks), dan `node --check app/src/main/assets/remote_server/remote.js`.

*Host JVM/loopback tests bukan bukti pengujian UI interaktif di layar HP, Hotspot/Wi-Fi fisik router acara, Safari/Chrome iOS/Android asli, atau ketahanan audio live berjam-jam; lihat checklist acceptance di [DEVICE-TEST.md](DEVICE-TEST.md).*

