# Update Android0.5.0-spike — Desktop Remote v1

Status: APK/debug candidate for private device testing, not a production/public release.
Android source base: `87932e8812bb594fc13de38f8c907e964b7037a6`.
Branch: `feature/android-desktop-remote-0.5`. VersionCode6; applicationId tetap `com.swararia.audiolab.spike`.

## Perubahan dan reuse

Tab **Remote** menambahkan pairing, menunggu persetujuan PC, status/judul lagu, View-only/Control serta Play/Pause/Stop. **Audio tetap keluar di PC**; ini bukan streaming koleksi desktop ke HP atau Android server.

Menggunakan tema, logo, `UiIcons`, `GlyphButton`, `EmptyMessage`, Compose, `AppContainer`/manual constructor injection, pola state owner/ViewModel, Coroutines, Jackson dan `HttpsURLConnection` yang sudah tersedia. Tidak menambah dependency, permission, service, DI framework, scanner SDK atau perubahan Room schema2. Engine, resource/aset, storage dan Preview service dipertahankan. Pemutar HP pada tab Remote diberi label terpisah.

## Persiapan PC dan pairing

1. Pasang APK sebagai **update**, tanpa uninstall atau clear data. Periksa koleksi/rename history tetap ada. Pilih ARM64 untuk SamsungM15.
2. PC harus menjalankan **source desktop Native Remote v1** commit `3f872241e526cf839cd843a90cfd568424cb1149` atau versi kompatibel. Installer/browser remote lama tidak otomatis kompatibel. Windows TLS desktop memerlukan runtime terpatched yang dijelaskan dalam `SECURE_REMOTE.md` desktop; installer baru belum dibangun dalam pekerjaan ini.
3. Sambungkan PC dan HP ke LAN/Wi-Fi/hotspot yang sama. Buka Player pada PC agar pemutarnya siap. Pada PC pilih **HP Remote → Aktifkan HTTPS** dengan IPv4 LAN PC yang benar, kemudian buat invite.
4. Di Android buka **Remote**, lalu tempel teks pairing lengkap yang ditampilkan PC. Formatnya `swararia1|IPv4:port|fingerprint|invite`, **bukan URL web**. QR desktop berisi teks; bila scanner yang sudah ada di HP menyediakan Copy text, hasilnya dapat ditempel. Scanner QR di dalam Swararia **belum dibuat**, tidak ada library/izin kamera baru. Jangan mengirim kode, QR, token atau screenshot pairing melalui Telegram.
5. Tekan **Connect**, kemudian setujui perangkat pada PC sebagai Control atau View-only. Invite sekali-pakai berlaku90detik; jika habis/hilang respons, buat invite baru, jangan mengulang token lama.
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

- **21 perintah independen**: Gradle unit/lint/assemble offline, regresi arsitektur/search/data/batch/core/UI, plugin/engine/APK, Preview, wiring Remote dan Kotlin-to-Desktop wire probe.
- **88 unit tests**, tanpa failure/error/skipped. Lint34Warning,0Error/Fatal.
- Wire probe mengompilasi kode Kotlin protocol/HTTPS yang sebenarnya memakai toolchain/cache existing dan berbicara dengan backend Desktop HTTPS asli pada loopback: pairing/approval/capabilities/state, accepted→rejected, pin/IP salah, revoke/view-only, respons POST hilang tanpa replay, slow-drip deadline dan cancellation.
- Master audio pada wire fixture disimulasikan dan menolak perintah karena tidak ada audio fisik. Ini membuktikan jalur transport/protokol, **bukan suara di speaker atau Android/Windows runtime**. Loopback hanya di test driver melalui refleksi; parser produksi tetap menolak endpoint di luar IPv4 LAN privat.
- Source protected87file, dependencies/manifest/schema dan byte engine/native dibandingkan dengan baseline. Kedua ABI signed, signer sama dengan0.4.1, native/engine identik, kelas Remote ada dalam DEX dan APK lama tidak berubah.
- Independent review runtime/wire code lulus tanpa security/logic blocker. Hasil awal delapan tes dalam file TDD adalah checkpoint historis; checkpoint final dicatat terpisah.

## Acceptance fisik yang MASIH perlu dilakukan

- SamsungM15Android16 + PC desktop terbaru pada LAN sama: pairing, approve/view-only/revoke/expiry dan Play/Pause/Stop benar-benar mengubah output PC, bukan HP.
- Rotasi, pindah tab, Home/layar mati, putus/sambung Wi-Fi, restart desktop dan background/process death tidak me-replay perintah atau menghentikan musik tanpa aksi pengguna.
- Preview/minimize dan Library offline, queue, rename/history, Download/Delete tetap bekerja; periksa koleksi sebelum/sesudah update.
- Font besar, keyboard, landscape dan label output HP/Desktop terbaca. Build/lint/source contracts bukan bukti rendering/layout di perangkat.

## Source/manual push, paket dan rollback

Source diserahkan privat; tidak ada push/merge/release otomatis. ZIP source bukan salinan data HP dan tidak berisi keystore/toolchain/cache/APK. Gunakan source lengkap untuk review/perbandingan terhadap checkout milikmu sebelum commit/push sendiri; jangan menimpa perubahan pribadi atau data Library. Riwayat lokal dari0.3–0.5 mungkin belum ada di GitHub; jangan menganggap patch dari baseline lokal0.4.1 otomatis cocok dengan main publik0.2.

APK ARM64 dikemas menjadi dua bagian ZIP standar untuk Telegram tanpa mengubah bytes signed APK. Simpan kedua file dengan nama asli dalam satu folder, buka `.zip.001` memakai extractor yang sebelumnya sudah digunakan, lalu pasang APK sebagai update. Source ZIP terpisah.

Backup source sebelum perubahan: bundle privat di VPS; source restore pada scratch clone dan baseline commit sudah diperiksa. Ini bukan backup/restore data HP. Jangan uninstall/clear data untuk rollback. APK lama dengan versionCode lebih rendah mungkin ditolak; bila diperlukan buat recovery build dari source lama dengan signer/appId sama dan versionCode lebih tinggi, lalu uji kompatibilitas data terlebih dahulu.

Batas native FFmpeg ARM64 4KB dan GPL corresponding-source/public-distribution tetap belum tuntas. Tidak ada klaim installer Windows baru, latensi YouTube lebih cepat, streaming full-song atau kesiapan distribusi publik.
