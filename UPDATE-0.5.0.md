# Update Android0.5.0-spike — Desktop Remote v1

Status: APK/debug candidate for private device testing, not a production/public release.
Android source base: `87932e8812bb594fc13de38f8c907e964b7037a6`.
Branch: `feature/android-desktop-remote-0.5`. VersionCode6; applicationId tetap `com.swararia.audiolab.spike`.

## Perubahan dan reuse

Tab **Remote** menambahkan pairing, menunggu persetujuan PC, status/judul lagu, View-only/Control, tombol **`Play`/`Pause`/`Prev`/`Next`/`Fade 3s`/`Stop`**, daftar lagu & pemilihan lagu PC (`GET /v1/tracks` + `select_track`), serta pengelompokan folder PC (`FilterChip` grup + mode `Kelompokkan per folder` + `select_group`). **Audio tetap keluar di PC**; ini bukan streaming koleksi desktop ke HP atau Android server.

Tab **Library** HP juga ditingkatkan dengan sistem grouping lokal profesional via migrasi aditif Room **v2 → v3** (`AudioMigrations.MIGRATION_2_3`, skema `3.json`): ekstraksi metadata `artist`/`album`/`genre` otomatis menggunakan `MediaMetadataRetriever` saat impor/publikasi unduhan dan lazy backfill `scanMissingMetadata()`, mode grouping **Semua / Playlist / Artis / Album / Format**, manajemen **Playlist** kustom (`playlists` & `playlist_tracks`, buat/ganti nama/hapus, dialog masukkan/tambah banyak lagu), bagian grup yang dapat dilipat (`▶ Putar grup`), serta pemutaran antrean per grup/playlist aktif.

Menggunakan tema, logo, `UiIcons`, `GlyphButton`, `EmptyMessage`, Compose, `AppContainer`/manual constructor injection, pola state owner/ViewModel, Coroutines, Jackson dan `HttpsURLConnection` yang sudah tersedia. Ditambah adapter **`DesktopRemoteQrScanner`** dengan Google Code Scanner (`com.google.android.gms:play-services-code-scanner:16.1.0`) serta `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="barcode_ui" />` pada `AndroidManifest.xml` — tetap **tanpa izin `CAMERA`**, tanpa service baru, tanpa DI framework. Engine, resource/aset, storage dan Preview service dipertahankan. Pemutar HP pada tab Remote diberi label terpisah.

## Persiapan PC dan pairing

1. Pasang APK sebagai **update**, tanpa uninstall atau clear data jika berasal dari keystore yang sama. Jika mengganti APK build lama dari mesin/keystore berbeda (ditolak `INSTALL_FAILED_UPDATE_INCOMPATIBLE`), lakukan `uninstall com.swararia.audiolab.spike` sekali terlebih dahulu (audio tersimpan di `Music/SwarariaAudioLab` tetap aman meski database Room privat reset). Pilih ARM64 untuk Samsung M15.
2. PC harus menjalankan **source desktop Native Remote v1** yang kompatibel (`audio-library-starter`, termasuk dukungan endpoint & bridge WSL bila dijalankan di WSL2). Installer/browser remote lama tidak otomatis kompatibel. Windows TLS desktop memerlukan runtime terpatched yang dijelaskan dalam `SECURE_REMOTE.md` desktop; installer baru belum dibangun dalam pekerjaan ini.
3. Sambungkan PC dan HP ke LAN/Wi-Fi/hotspot yang sama. Buka Player pada PC agar pemutarnya siap. Pada PC buka **Android Remote → Aktifkan HTTPS** dengan IPv4 LAN PC yang benar, kemudian buat invite (QR + teks pairing).
4. Di Android buka **Remote**, ketuk **`Scan QR dari PC`** untuk memindai QR `swararia1|IPv4:port|fingerprint|invite` menggunakan Google Code Scanner (`Barcode.FORMAT_QR_CODE`, auto-zoom aktif) dan langsung menyambung, atau tempel teks pairing manual di bawahnya sebagai fallback. Formatnya **bukan URL web**. Pada APK sideload, pemakaian pertama `Scan QR dari PC` dapat mengunduh modul `barcode_ui` Play Services bila belum ter-cache di HP (timeout 25 detik). Jangan mengirim kode, QR, token atau screenshot pairing melalui Telegram.
5. Jika memakai input teks manual, tekan **Connect** (hasil scan QR yang valid langsung memanggil `DesktopRemoteSession.connect(...)` sesudah UI `ON_RESUME`), kemudian setujui perangkat pada PC sebagai Control atau View-only. Invite sekali-pakai berlaku 90 detik; jika habis/hilang respons, buat invite baru, jangan mengulang token lama.
6. Pilih lagu dan grup playlist dari PC atau langsung dari tab **Remote** di HP (`GET /v1/tracks`, `FilterChip` folder, `select_track`, `select_group`), lalu kendalikan `Play`/`Pause`/`Prev`/`Next`/`Fade 3s`/`Stop`. Native API tidak menyediakan nama PC, sehingga aplikasi menampilkan Desktop + alamat endpoint yang sebenarnya, bukan nama rekaan. *(Pastikan server Swararia PC `web_ui.py` di-restart dan tab browser di-refresh agar dukungan katalog grup & perintah terbaru aktif.)*

Tidak ada kebutuhan akun/cloud atau akses internet publik untuk jalur kontrol ini. Jangan mengaktifkan port-forward/bridge atau mematikan firewall. Bila firewall Windows/client-isolation Wi-Fi menghalangi koneksi, tinjau aturan private/LAN secara terpisah; VPS uji bukan pengganti LAN laptopmu.

## Keamanan dan hasil perintah

- Fingerprint sertifikat session dari layar PC diverifikasi sebelum invite/token dikirim. Kepercayaan TLS hanya pada request ke pasangan itu, memeriksa masa berlaku/self-signature/IP SAN serta mempertahankan hostname verification. Tidak memasang CA sistem, trust-all atau global cleartext/SSL override.
- Token/invite berada di RAM, tidak disimpan ke Room/preferences/Bundle/saved state, tidak dicatat dalam log dan bukan bagian URL. String rahasia tidak diklaim di-zeroize secara aman dari RAM.
- Socket connect/read mempunyai batas idle 4 detik, respons maksimum 16 KiB (`98_304` byte untuk `/v1/tracks`), dan batas total request 8 detik dengan penutupan langsung `SSLSocket` aktif + `disconnect()` cleanup pada cancellation. Durasi cleanup/platform dapat berbeda; JVM slow-drip/cancel telah diuji, bukan pengukuran respons HP.
- Accepted/Pending bukan Applied. Timeout/hasil ambigu menjadi Unknown, bukan diklaim berhasil. Reconnect/Refresh hanya GET; **POST tidak dikirim ulang otomatis**. Tombol ditahan selama perintah outstanding.
- Polling hanya ketika layar Remote aktif/resumed. Identitas owner mencegah dispose Activity lama memutus owner baru. Home/Back/Disconnect bukan perintah Stop ke PC atau pemutar HP. Credential tetap RAM selama proses hidup; proses mati/restart Desktop/sertifikat berubah membutuhkan pairing ulang.
- Role View-only, revoke dan expiry ditangani fail-closed. Desktop offline dan status yang tidak fresh menahan kontrol.

## Verifikasi yang benar-benar dijalankan

Laporan final: `verification/checks-0.5.json`, `verification/update-compatibility-0.5.json`, dan `verification/desktop-remote-0.5-review.json`.

- **Baseline 0.5.0 awal (sebelum QR scanner):** **21 perintah independen**, **88 unit tests**, `Lint 34 Warning, 0 Error/Fatal`, serta artefak review `verification/checks-0.5.json`, `verification/update-compatibility-0.5.json`, dan `verification/desktop-remote-0.5-review.json`.
- **Verifikasi terbaru (Fade Out 3s, Remote UI & Tracking, Remote Folder Grouping, Local Library Grouping + Room v3):**
  - **97 unit tests** (`0` failures, `0` errors, `0` skipped via `testDebugUnitTest`; termasuk `DesktopRemoteFadeOutTest`, `LibraryRulesTest`, `UiPresentationTest`, `DesktopRemoteQrScannerTest`, `DesktopRemoteProtocolTest`, `DesktopRemoteSessionTest`, `DesktopRemoteTlsTest`).
  - **`lintDebug` & `assembleDebug`:** `BUILD SUCCESSFUL`; kedua ABI APK (`app-arm64-v8a-debug.apk` `98,202,740` bytes dan `app-armeabi-v7a-debug.apk` `91,531,570` bytes) lulus tanda tangan & zipalign (`verify_artifacts.py`, keystore `.cache/android/debug.keystore`), serta lulus `test_apk_contract.py` (`4/4`), `verification/test_data_layer.py` (`8/8`, memverifikasi migrasi `2.json` → `MIGRATION_2_3_STATEMENTS` → `3.json`, `PRAGMA table_info`/`foreign_key_list`, dan `ON DELETE CASCADE`), `verification/test_desktop_remote_wiring.py` (`2/2`), `test_ui_source_contracts.py` (`9/9`), `test_ui_integration.py` (`3/3`), `test_architecture.py` (`36/36`), `test_architecture_wiring.py` (`5/5`), dan `test_upgrade_03.py` (`7/7`). *(Sebelum edit penutup socket di `DesktopRemoteHttpsApi.kt`, `test_core.py` `6/6`, `test_ui_rules.py` `5/5`, dan `test_engine_payload.py` `1/1` juga lulus.)*
  - **Deployment di HP target (`SM_M156B` / Samsung M15 Android 16, serial `RRCX602LKTY`):** APK ARM64 (`sha256: 77b8bd2fb600da376507a807caeb15292be5b778501d636b02c6d5790cefbe71`) berhasil dipasang ulang lewat `.toolchain/platform-tools-win/adb.exe install -r` (`versionCode=6`, `versionName=0.5.0-spike`), cold launch `com.swararia.audiolab.spike/com.swararia.audiolab.MainActivity` sukses (`PID 26291`, tanpa crash di logcat), serta jalur ADB ganda USB + Wi-Fi (`tcpip 5555`) terdokumentasi di `README.md`.
- **Wire probe (`verification/run_desktop_remote_checks.py`):** mengompilasi `DesktopRemoteProtocol.kt` dan `DesktopRemoteHttpsApi.kt` serta menguji langsung backend Desktop HTTPS asli pada loopback (`PASS`): wrong fingerprint/IP, pairing/approval/capabilities/state, accepted→rejected, ambiguous response tanpa auto-replay, slow-drip total deadline (`8s < 11s`), active `SSLSocket` cancellation (`< 6s`), dan view-only/revoke.
- Master audio pada wire fixture disimulasikan dan menolak perintah karena tidak ada audio fisik. Ini membuktikan jalur transport/protokol, **bukan pemindaian kamera QR, modul Play Services, suara di speaker, atau runtime Remote UI penuh di HP**. Loopback hanya di test driver melalui refleksi; parser produksi tetap menolak endpoint di luar IPv4 LAN privat.

## Acceptance fisik yang MASIH perlu dilakukan

- Samsung M15 Android 16 + PC desktop terbaru (`web_ui.py` sudah di-restart + browser di-refresh) pada LAN sama: menguji **`Scan QR dari PC`** (termasuk download modul `barcode_ui` Play Services bila pertama kali dan fallback teks manual), pairing, approve/view-only/revoke/expiry, pemilihan grup folder (`FilterChip` / `select_group`), pemilihan lagu (`select_track`), serta `Play`/`Pause`/`Prev`/`Next`/`Fade 3s`/`Stop` benar-benar mengubah output PC, bukan HP.
- Library lokal HP (`v2 → v3` migrasi menjaga koleksi & riwayat lama): menguji chip `Semua` / `Playlist` / `Artis` / `Album` / `Format`, pembuatan/rename/hapus playlist kustom, tambah/keluarkan lagu, ekstraksi tag artis/album otomatis pada file lokal maupun unduhan baru, serta memutar lagu dari dalam grup/playlist menjaga antrean tetap dalam grup tersebut (`device_execution_tested: false`, `ui_render_tested: false`).
- Rotasi, pindah tab, Home/layar mati, putus/sambung Wi-Fi, restart desktop dan background/process death tidak me-replay perintah atau menghentikan musik tanpa aksi pengguna.
- Preview/minimize dan Library offline, queue, rename/history, Download/Delete tetap bekerja; periksa koleksi sebelum/sesudah update.
- Font besar, keyboard, landscape dan label output HP/Desktop terbaca. Build/lint/source contracts bukan bukti rendering/layout di perangkat.

## Source/manual push, paket dan rollback

Source diserahkan privat; tidak ada push/merge/release otomatis. ZIP source bukan salinan data HP dan tidak berisi keystore/toolchain/cache/APK. Gunakan source lengkap untuk review/perbandingan terhadap checkout milikmu sebelum commit/push sendiri; jangan menimpa perubahan pribadi atau data Library. Riwayat lokal dari0.3–0.5 mungkin belum ada di GitHub; jangan menganggap patch dari baseline lokal0.4.1 otomatis cocok dengan main publik0.2.

APK ARM64 dikemas menjadi dua bagian ZIP standar untuk Telegram tanpa mengubah bytes signed APK. Simpan kedua file dengan nama asli dalam satu folder, buka `.zip.001` memakai extractor yang sebelumnya sudah digunakan, lalu pasang APK sebagai update. Source ZIP terpisah.

Backup source sebelum perubahan: bundle privat di VPS; source restore pada scratch clone dan baseline commit sudah diperiksa. Ini bukan backup/restore data HP. Jangan uninstall/clear data untuk rollback. APK lama dengan versionCode lebih rendah mungkin ditolak; bila diperlukan buat recovery build dari source lama dengan signer/appId sama dan versionCode lebih tinggi, lalu uji kompatibilitas data terlebih dahulu.

Batas native FFmpeg ARM64 4KB dan GPL corresponding-source/public-distribution tetap belum tuntas. Tidak ada klaim installer Windows baru, latensi YouTube lebih cepat, streaming full-song atau kesiapan distribusi publik.
