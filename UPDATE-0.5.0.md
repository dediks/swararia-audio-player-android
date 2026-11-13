# Update Android0.5.0-spike — Desktop Remote v1

Status: APK/debug candidate for private device testing, not a production/public release.
Android source base: `87932e8812bb594fc13de38f8c907e964b7037a6`.
Branch: `feature/android-desktop-remote-0.5`. VersionCode6; applicationId tetap `com.swararia.audiolab.spike`.

## Perubahan dan reuse

Tab **Remote** menambahkan pairing, menunggu persetujuan PC, status/judul lagu, View-only/Control serta Play/Pause/Stop. **Audio tetap keluar di PC**; ini bukan streaming koleksi desktop ke HP atau Android server.

Menggunakan tema, logo, `UiIcons`, `GlyphButton`, `EmptyMessage`, Compose, `AppContainer`/manual constructor injection, pola state owner/ViewModel, Coroutines, Jackson dan `HttpsURLConnection` yang sudah tersedia. Ditambah adapter **`DesktopRemoteQrScanner`** dengan Google Code Scanner (`com.google.android.gms:play-services-code-scanner:16.1.0`) serta `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="barcode_ui" />` pada `AndroidManifest.xml` — tetap **tanpa izin `CAMERA`**, tanpa service baru, tanpa DI framework, dan tanpa perubahan Room schema2. Engine, resource/aset, storage dan Preview service dipertahankan. Pemutar HP pada tab Remote diberi label terpisah.

## Persiapan PC dan pairing

1. Pasang APK sebagai **update**, tanpa uninstall atau clear data jika berasal dari keystore yang sama. Jika mengganti APK build lama dari mesin/keystore berbeda (ditolak `INSTALL_FAILED_UPDATE_INCOMPATIBLE`), lakukan `uninstall com.swararia.audiolab.spike` sekali terlebih dahulu (audio tersimpan di `Music/SwarariaAudioLab` tetap aman meski database Room privat reset). Pilih ARM64 untuk Samsung M15.
2. PC harus menjalankan **source desktop Native Remote v1** yang kompatibel (`audio-library-starter`, termasuk dukungan endpoint & bridge WSL bila dijalankan di WSL2). Installer/browser remote lama tidak otomatis kompatibel. Windows TLS desktop memerlukan runtime terpatched yang dijelaskan dalam `SECURE_REMOTE.md` desktop; installer baru belum dibangun dalam pekerjaan ini.
3. Sambungkan PC dan HP ke LAN/Wi-Fi/hotspot yang sama. Buka Player pada PC agar pemutarnya siap. Pada PC buka **Android Remote → Aktifkan HTTPS** dengan IPv4 LAN PC yang benar, kemudian buat invite (QR + teks pairing).
4. Di Android buka **Remote**, ketuk **`Scan QR dari PC`** untuk memindai QR `swararia1|IPv4:port|fingerprint|invite` menggunakan Google Code Scanner (`Barcode.FORMAT_QR_CODE`, auto-zoom aktif) dan langsung menyambung, atau tempel teks pairing manual di bawahnya sebagai fallback. Formatnya **bukan URL web**. Pada APK sideload, pemakaian pertama `Scan QR dari PC` dapat mengunduh modul `barcode_ui` Play Services bila belum ter-cache di HP (timeout 25 detik). Jangan mengirim kode, QR, token atau screenshot pairing melalui Telegram.
5. Jika memakai input teks manual, tekan **Connect** (hasil scan QR yang valid langsung memanggil `DesktopRemoteSession.connect(...)` sesudah UI `ON_RESUME`), kemudian setujui perangkat pada PC sebagai Control atau View-only. Invite sekali-pakai berlaku 90 detik; jika habis/hilang respons, buat invite baru, jangan mengulang token lama.
6. Pilih lagu di PC terlebih dahulu. Judul dan statusnya tampil di HP; gunakan Play/Pause/Stop. Native API tidak menyediakan nama PC, sehingga aplikasi menampilkan Desktop + alamat endpoint yang sebenarnya, bukan nama rekaan.

Tidak ada kebutuhan akun/cloud atau akses internet publik untuk jalur kontrol ini. Jangan mengaktifkan port-forward/bridge atau mematikan firewall. Bila firewall Windows/client-isolation Wi-Fi menghalangi koneksi, tinjau aturan private/LAN secara terpisah; VPS uji bukan pengganti LAN laptopmu.

## Keamanan dan hasil perintah

- Fingerprint sertifikat session dari layar PC diverifikasi sebelum invite/token dikirim. Kepercayaan TLS hanya pada request ke pasangan itu, memeriksa masa berlaku/self-signature/IP SAN serta mempertahankan hostname verification. Tidak memasang CA sistem, trust-all atau global cleartext/SSL override.
- Token/invite berada di RAM, tidak disimpan ke Room/preferences/Bundle/saved state, tidak dicatat dalam log dan bukan bagian URL. String rahasia tidak diklaim di-zeroize secara aman dari RAM.
- Socket connect/read mempunyai batas idle4detik, respons maksimum16KiB, dan batas total request8detik dengan cancellation/disconnect cleanup. Durasi cleanup/platform dapat berbeda; JVM slow-drip/cancel telah diuji, bukan pengukuran respons HP.
- Accepted/Pending bukan Applied. Timeout/hasil ambigu menjadi Unknown, bukan diklaim berhasil. Reconnect/Refresh hanya GET; **POST tidak dikirim ulang otomatis**. Tombol ditahan selama perintah outstanding.
- Polling hanya ketika layar Remote aktif/resumed. Identitas owner mencegah dispose Activity lama memutus owner baru. Home/Back/Disconnect bukan perintah Stop ke PC atau pemutar HP. Credential tetap RAM selama proses hidup; proses mati/restart Desktop/sertifikat berubah membutuhkan pairing ulang.
- Role View-only, revoke dan expiry ditangani fail-closed. Desktop offline dan status yang tidak fresh menahan kontrol.

## Verifikasi yang benar-benar dijalankan

Laporan final: `verification/checks-0.5.json`, `verification/update-compatibility-0.5.json`, dan `verification/desktop-remote-0.5-review.json`.

- **Baseline 0.5.0 awal (sebelum QR scanner):** **21 perintah independen**, **88 unit tests**, `Lint 34 Warning, 0 Error/Fatal`, serta artefak review `verification/checks-0.5.json`, `verification/update-compatibility-0.5.json`, dan `verification/desktop-remote-0.5-review.json`.
- **Verifikasi terbaru setelah penambahan QR scanner (`DesktopRemoteQrScanner`):**
  - **91 unit tests** (`0` failures, `0` errors, `0` skipped across 21 suites; mencakup 3 test baru `DesktopRemoteQrScannerTest`, `DesktopRemoteProtocolTest` 2, `DesktopRemoteSessionTest` 5, `DesktopRemoteTlsTest` 2).
  - **`lintDebug`:** `BUILD SUCCESSFUL` dengan `0` error dan `0` warning pada file/manifest QR scanner baru (`46` pre-existing warnings proyek).
  - **`assembleDebug` & kontrak:** kedua ABI APK (`app-arm64-v8a-debug.apk` `96,181,341` bytes dan `app-armeabi-v7a-debug.apk` `89,510,171` bytes) lulus tanda tangan & zipalign (`verify_artifacts.py`, keystore `.cache/android/debug.keystore`), `aapt2` mengonfirmasi `com.google.mlkit.vision.DEPENDENCIES=barcode_ui` tanpa `android.permission.CAMERA`, serta lulus `test_apk_contract.py` (`4/4`), `test_ui_source_contracts.py` (`9/9`), `test_ui_integration.py` (`3/3`), `verification/test_desktop_remote_wiring.py` (`2/2`), `test_architecture.py` (`36/36`), `test_architecture_wiring.py` (`5/5`), `test_ui_rules.py` (`4/4`), `test_upgrade_03.py` (`7/7`), dan `test_engine_payload.py` (`1/1`).
  - **Deployment awal di HP target (`SM_M156B` / Samsung M15 Android 16, serial `RRCX602LKTY`):** APK ARM64 berhasil dipasang lewat `.toolchain/platform-tools-win/adb.exe` via USB (`versionCode=6`, `versionName=0.5.0-spike`), `MainActivity` berjalan (`PID` terverifikasi), dan jalur ADB ganda USB + Wi-Fi (`tcpip 5555`) aktif bersamaan.
- **Wire probe (`verification/run_desktop_remote_checks.py`):** mengompilasi kode Kotlin protocol/HTTPS memakai toolchain/cache lokal dan berbicara dengan backend Desktop HTTPS asli pada loopback (`PASS`): pairing/approval/capabilities/state, accepted→rejected, pin/IP salah, revoke/view-only, respons POST hilang tanpa replay, slow-drip deadline dan cancellation.
- Master audio pada wire fixture disimulasikan dan menolak perintah karena tidak ada audio fisik. Ini membuktikan jalur transport/protokol, **bukan pemindaian kamera QR, modul Play Services, suara di speaker, atau runtime Remote UI penuh di HP**. Loopback hanya di test driver melalui refleksi; parser produksi tetap menolak endpoint di luar IPv4 LAN privat.

## Acceptance fisik yang MASIH perlu dilakukan

- Samsung M15 Android 16 + PC desktop terbaru pada LAN sama: menguji **`Scan QR dari PC`** (termasuk download modul `barcode_ui` Play Services bila pertama kali dan fallback teks manual), pairing, approve/view-only/revoke/expiry dan Play/Pause/Stop benar-benar mengubah output PC, bukan HP.
- Rotasi, pindah tab, Home/layar mati, putus/sambung Wi-Fi, restart desktop dan background/process death tidak me-replay perintah atau menghentikan musik tanpa aksi pengguna.
- Preview/minimize dan Library offline, queue, rename/history, Download/Delete tetap bekerja; periksa koleksi sebelum/sesudah update.
- Font besar, keyboard, landscape dan label output HP/Desktop terbaca. Build/lint/source contracts bukan bukti rendering/layout di perangkat.

## Source/manual push, paket dan rollback

Source diserahkan privat; tidak ada push/merge/release otomatis. ZIP source bukan salinan data HP dan tidak berisi keystore/toolchain/cache/APK. Gunakan source lengkap untuk review/perbandingan terhadap checkout milikmu sebelum commit/push sendiri; jangan menimpa perubahan pribadi atau data Library. Riwayat lokal dari0.3–0.5 mungkin belum ada di GitHub; jangan menganggap patch dari baseline lokal0.4.1 otomatis cocok dengan main publik0.2.

APK ARM64 dikemas menjadi dua bagian ZIP standar untuk Telegram tanpa mengubah bytes signed APK. Simpan kedua file dengan nama asli dalam satu folder, buka `.zip.001` memakai extractor yang sebelumnya sudah digunakan, lalu pasang APK sebagai update. Source ZIP terpisah.

Backup source sebelum perubahan: bundle privat di VPS; source restore pada scratch clone dan baseline commit sudah diperiksa. Ini bukan backup/restore data HP. Jangan uninstall/clear data untuk rollback. APK lama dengan versionCode lebih rendah mungkin ditolak; bila diperlukan buat recovery build dari source lama dengan signer/appId sama dan versionCode lebih tinggi, lalu uji kompatibilitas data terlebih dahulu.

Batas native FFmpeg ARM64 4KB dan GPL corresponding-source/public-distribution tetap belum tuntas. Tidak ada klaim installer Windows baru, latensi YouTube lebih cepat, streaming full-song atau kesiapan distribusi publik.
