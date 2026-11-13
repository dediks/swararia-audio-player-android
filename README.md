# Swararia Audio Player — Android

Aplikasi Android native untuk menyiapkan, mengelola, dan memutar audio lokal, kini dilengkapi **Mode Server FOH + Download/Sync Lagu SHA-256** langsung dari HP Android maupun browser Web di jaringan LAN/Hotspot. Target penggunaan: soundman dan rental sound system hajatan. Aplikasi tetap dapat memutar file lokal tanpa laptop maupun internet; klien sinkronisasi otomatis pada repository **Swararia Desktop** belum ditambahkan (sinkronisasi dari laptop memakai antarmuka Web HTTPS bawaan HP Server).

## Status pengembangan

Baseline source publik terakhir yang diverifikasi adalah **0.2.0-spike**. Baseline lokal **0.4.1-spike** ada pada `87932e8`. Kandidat terbaru (**0.6.0 Server Mode & SHA-256 Sync** pada debug package `versionCode=6` / `0.5.0-spike`) menambahkan kemampuan **Jadikan HP Ini Server** di tab **Remote** (server HTTPS EC P-256 + QR pairing `/v1/*` untuk sesama Swararia Android, antarmuka Web FOH `/web/*` dengan PIN 8 karakter & anti-CSRF `__Host-` cookie untuk browser HP/iPhone/Laptop, kontrol `Play`/`Pause`/`Prev`/`Next`/`Fade 3s`/`Stop` + ganti grup/lagu langsung pada `PlaybackService`, serta **download & upload/sync bebas duplikat `SHA-256`** dengan Room **schema v3 → v4**) di atas fitur Desktop Remote client & grouping Library lokal (`0.5.0`): **108 unit tests** (`0` failures), `lintDebug` (`0` Error/Fatal), `assembleDebug`, APK contract (`4/4`), migrasi SQLite `v2 -> v3 -> v4` (`9/9`), wiring/source boundary (`4/4` & `5/5`), dan wire probe Desktop (`7/7`) lulus pada mesin build; uji interaktif UI Compose, Hotspot/Wi-Fi HP ke HP/Laptop, dan pemutaran audio fisik tetap perlu diuji langsung pengguna. Ini build debug/pengujian, bukan rilis produksi. Tidak ada push otomatis. Detail fitur server & sync: [UPDATE-0.6.0.md](UPDATE-0.6.0.md); detail Desktop Remote client & Keystore auto-reconnect: [UPDATE-0.5.0.md](UPDATE-0.5.0.md).

- Android 10+ (minSdk 29), targetSdk 36; Kotlin/Compose, Media3, Room dan MediaStore/SAF.
- Pustaka: pencarian lokal (judul, artis, album, genre), mode pengelompokan **Semua / Playlist / Artis / Album / Format**, manajemen playlist custom (buat, rename, hapus, tambah/keluarkan lagu), grup collapsible, pemutaran antrean per grup, urutan terbaru/A–Z, rename file dan riwayat undo.
- Mini-player, pemutar lengkap, antrean dan background media session.
- Tambah audio melalui tautan HTTPS berizin, pencarian metadata YouTube/YouTube Music, atau impor salinan file lokal.
- Logo Swararia dan font Inter lokal; artwork abstrak merupakan dekorasi fallback, bukan sampul album yang terverifikasi.
- Fungsi lokal tidak memerlukan server, akun, Python atau Termux di HP. Pencarian/unduhan online memerlukan internet. Desktop Remote membutuhkan PC dengan Native Remote v1 aktif dan jaringan LAN yang sama; bukan pemindahan engine desktop ke HP.

0.3.0 menambahkan default YouTube Music, filter lagu/playlist, urutan hasil termuat, metadata kanal/durasi, pemuatan hasil tambahan, preview online hingga 30 detik, mode Loop/Continue/Once dan Fade 3s. Prioritas kanal memakai sinyal terverifikasi/indikasi artis, bukan bukti label asli atau hak penggunaan. Playlist dibuka untuk memilih lagu; bukan unduh massal.

0.4.0 memperbarui UI Search/Playlist/Library dan satu playback surface, Preview hingga dua menit, artwork asli yang tersedia dengan cache offline, multiselect lagu termuat, queue download berurutan/persisten, Cancel batch/Retry failed, serta Delete salinan terkelola. Room bermigrasi 1→2 tanpa reset tracks/rename history. Detail instalasi, acceptance HP, bukti verifikasi dan batas rollback: `UPDATE-0.4.0.md`. Build/host tests bukan bukti runtime di HP.

0.4.1 memindahkan resolusi/player Preview ke service, menambahkan notifikasi awal yang dapat dibatalkan, reconnect controller, pemulihan queue lokal paused, serta numeric startup timing. Cap tetap 120 detik; tidak ada dependency/schema baru. Pengguna melaporkan Preview bisa di-minimize; lock/rotasi dan perbaikan latensi belum diverifikasi lengkap. Detail dan acceptance historis: `UPDATE-0.4.1.md`; hasil di `verification/checks-0.4.1.json`.

0.5.0 memakai ulang Compose, komponen/ikon/tema, manual DI, Coroutines, Jackson dan HTTPS bawaan untuk client Remote, menambahkan **`DesktopRemoteQrScanner`** berbasis Google Code Scanner (`com.google.android.gms:play-services-code-scanner:16.1.0`) dengan metadata `com.google.mlkit.vision.DEPENDENCIES=barcode_ui` **tanpa izin `CAMERA` di aplikasi**, menambahkan **`AndroidKeystoreDesktopRemoteStore`** (`AndroidKeyStore`, `AES/GCM/NoPadding` dalam `noBackupFilesDir`, tanpa `SharedPreferences`/backup cloud) agar sesi Remote yang sudah disetujui dapat **auto-reconnect** ketika aplikasi ditutup penuh/process mati (tetap fail-closed: kontrol terkunci hingga `GET /v1/session` sukses, dan kredensial dihapus otomatis pada `Putuskan`, revoke, kedaluwarsa, atau sertifikat PC berubah), serta memperluas Room **schema v2 → v3** secara aditif (kolom `artist`, `album`, `genre`, `metadataCheckedAt` pada `tracks` serta tabel `playlists` & `playlist_tracks`) tanpa me-reset pustaka maupun riwayat rename. Invite sekali-pakai tetap berada di RAM saja; Remote mengendalikan output PC (`Play`/`Pause`/`Prev`/`Next`/`Fade 3s`/`Stop`, pilih lagu `select_track`, dan ganti grup folder `select_group`), bukan streaming ke HP. Tab **Remote** menyediakan tombol utama **`Scan QR dari PC`** untuk memindai QR `swararia1|...` dan langsung pairing, katalog lagu/grup PC yang disanitasi dari `GET /v1/tracks`, serta kolom tempel teks manual sebagai fallback. Jangan kirim secret lewat chat. Desktop source Native Remote v1 terbaru diperlukan; installer/browser remote HTTP lama tidak otomatis kompatibel. Probe wire mandiri: `TMPDIR="$PWD/.cache/tmp" SWARARIA_DESKTOP_REPO=/home/dedik/project/audio-library-starter python3 verification/run_desktop_remote_checks.py`. JVM wire tests bukan bukti runtime kamera/Android/Windows/audio fisik.

Branch `refactor/architecture-foundation` merapikan dependency/state dan memisahkan UI tanpa menerapkan mockup baru. Pada repository ini, build memakai toolchain/cache yang sudah ada: 33 unit test, kompilasi, kedua APK ABI, kontrak APK dan signature lulus. Lint: 15 warning, tidak ada Error/Fatal. Batas dan alur pengembangan dijelaskan di `ARCHITECTURE.md` serta `CONTRIBUTING.md`. Review versi 0.3.0 lulus sesudah perbaikan temuan awal; review refactor juga lulus dan dicatat terpisah pada `verification/architecture-review.json`.

Pengguna melaporkan 0.1.0, 0.2.0, dan fungsi 0.3.0 bekerja pada Samsung M15 Android 16, tetapi UX 0.3.0 perlu diperbaiki. Laporan itu bukan verifikasi independen setiap fitur/kasus. **Build refactor belum diuji pada HP.** Pengambilan stream preview pada jaringan build dibatasi anti-bot YouTube; penanganan error tersedia. Lihat `DEVICE-TEST.md`.

## Batasan penting

- Online ingest membutuhkan halaman memori perangkat 4KB. Lima library ARM64 FFmpeg bawaan belum kompatibel 16KB; import/player lokal masih dapat dicoba. ZIP alignment bukan bukti kompatibilitas ELF 16KB.
- Tidak ada cookies/login akun publik, DRM bypass, klien desktop sync otomatis, QC/stem splitting atau jaminan resume sesudah force-stop.
- Gunakan hanya audio dan sumber yang penggunaannya diizinkan.
- **Audit lisensi/public-distribution belum tuntas.** Source JAR dependency bukan bukti kelengkapan corresponding source native. Baca `THIRD-PARTY-NOTICES.md`; jangan menganggap proyek otomatis berlisensi MIT atau seluruhnya proprietary. Publikasi source bukan sertifikasi bahwa APK siap didistribusikan secara komersial.

## Build pada Linux x86_64

Butuh Python 3.12+. Pelajari dan setujui persyaratan SDK sebelum menjalankan setup. Script setup mengunduh JDK 21, Gradle 8.13, SDK platform 36 dan Build Tools 36.0.0 yang dipin/checksum ke direktori project-local. Tidak memerlukan root, Android Studio, emulator atau NDK. Tidak ada toolchain di dalam Git.

```sh
python3 setup_toolchain.py
bash build.sh compileDebugKotlin
bash build.sh -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8" testDebugUnitTest
bash build.sh -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8" assembleDebug
bash build.sh -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8" -Pandroid.experimental.lint.heapSize=512m lintDebug
python3 -m unittest test_engine_payload test_apk_contract -v
python3 verify_artifacts.py
```

Proyek ini memakai `build.sh` (pengganti `gradlew`) agar otomatis mengarahkan JDK, SDK, Gradle 8.13, dan cache ke `.toolchain/` serta `.cache/` tanpa Android Studio. Tambahan `-Dorg.gradle.jvmargs=...` menaikkan batas `MaxMetaspaceSize` sementara dari default `gradle.properties` (256m) bila host mengalami `OutOfMemoryError: Metaspace`.

### Pemasangan ke HP dari Terminal WSL2 (Tanpa Android Studio)

1. Pasang `platform-tools` (`adb`) secara lokal ke `.toolchain/`:
   - Linux WSL: `JAVA_HOME="$PWD/.toolchain/jdk" ANDROID_SDK_ROOT="$PWD/.toolchain/android-sdk" .toolchain/android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools"`
   - Windows host (agar kabel USB terdeteksi langsung dari terminal WSL2 tanpa `usbipd`): unduh/ekstrak `platform-tools-latest-windows.zip` ke `.toolchain/platform-tools-win/adb.exe`.
2. Aktifkan **Opsi developer → Debugging USB** di HP (pastikan *Auto Blocker* / *Pemblokir Otomatis* nonaktif selama install debug APK), sambungkan kabel USB, lalu pasang & jalankan:
   ```sh
   ADB_WIN="$PWD/.toolchain/platform-tools-win/adb.exe"
   $ADB_WIN devices -l
   $ADB_WIN install -r "$(wslpath -w app/build/outputs/apk/debug/app-arm64-v8a-debug.apk)"
   $ADB_WIN shell am start -n com.swararia.audiolab.spike/com.swararia.audiolab.MainActivity
   ```
   *(Jika mengganti APK dari mesin/keystore lain dan muncul `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, jalankan `$ADB_WIN uninstall com.swararia.audiolab.spike` sekali terlebih dahulu; file audio di `Music/SwarariaAudioLab` tetap aman meski database internal app ter-reset. Update berikutnya dari laptop ini cukup memakai `install -r`.)*
3. Opsional — mengaktifkan ADB Wi-Fi port tetap (`5555`) lewat kabel sekali:
   ```sh
   $ADB_WIN tcpip 5555
   $ADB_WIN connect <IP_WIFI_HP>:5555
   ```
   *(USB dan Wi-Fi `:5555` tetap dapat berjalan bersamaan; jika keduanya aktif serentak di `adb devices`, pilih target spesifik dengan `-s <serial>`, atau kembalikan ke USB-only lewat `$ADB_WIN -s <serial> usb`. Port tetap `5555` lebih stabil dibanding port acak *Wireless debugging* Android 11+ untuk upload APK ~92 MB.)*

Jalankan build/test dan lint terpisah pada host dengan RAM terbatas. Wrapper downloader dipin 0.19.0; override yt-dlp resmi 2026.10.26 diperiksa melalui `engine-lock.json` serta byte resource di APK.

Tes kontrak source/aset tanpa toolchain Android:

```sh
python test_ui_source_contracts.py
python test_ui_integration.py
```

## Git, APK, dan signing

- Branch `main` untuk baseline yang diperiksa; fitur berikutnya dikerjakan pada branch terpisah dan direview sebelum digabung.
- APK/AAB, toolchain, cache, konfigurasi lokal, kredensial dan signing key tidak dimasukkan ke Git.
- Jangan memakai debug key hasil generate baru untuk meng-update APK lama dan berharap identitasnya sama. Backup signer lama disimpan privat di luar repository; rencana signing produksi harus ditentukan terpisah.
- Jangan uninstall untuk mencoba update jika data pustaka/riwayat perlu dipertahankan. Downgrade versionCode melalui installer biasa belum dijamin.
- Backup lokal bukan off-host disaster recovery. Penghapusan repository tidak menarik kembali salinan source yang sudah diunduh pihak lain.

Repository desktop tidak diubah oleh persiapan baseline ini. Penambahan fitur download dari desktop memerlukan rencana dan pengujian terpisah.
