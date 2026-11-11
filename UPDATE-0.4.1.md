# Update privat 0.4.1-spike — Preview background dan pengukuran startup

Status: seluruh **20 gate offline lulus**, **79 unit test lulus** (0 failure/error/skipped), lint **33 Warning / 0 Error-Fatal**, dan review independen runtime/helper/delta lulus. Kandidat lokal **belum diuji di HP**; ini bukan rilis publik/produksi. Tidak ada push. Bukti aktual: `verification/checks-0.4.1.json`, `verification/preview-background-0.4.1-review.json` dan manifest paket final.

## Scope dan checkpoint

- Basis terverifikasi: `246e1462cf531fad47b98a4a16447db2aa09b018` (0.4.0), branch `feature/preview-background-latency-0.4.1`.
- Checkpoint sebelum 0.4.1: `.checkpoints/before-preview-background-0.4.1.zip`, SHA256 `a0e91bef25e1a87b8c36997513588f73580ad225921d7586a9ca02139aeb235a`.
- Perubahan parsial dari pekerjaan yang terhenti juga disimpan sebelum dilanjutkan: `.checkpoints/resume-preview-background-0.4.1-partial.zip` (19 berkas), SHA256 `69220b598f16102369903659ba54f57a7d91743ee348b17010e66639ca078963`.
- Tidak memasang dependency/tool/SDK baru. Tidak mengubah schema Room v2, ID aplikasi, permission/service manifest, payload engine/native, atau signer.
- `versionCode 5`, `versionName 0.4.1-spike`; ID tetap `com.swararia.audiolab.spike`.

## Yang berubah

- Resolusi dan pemutaran Preview dimiliki `PlaybackService`, bukan Activity/Compose. UI hanya mengirim public video ID dan membaca status/player. Home, lock dan rotasi tidak dimaksudkan menghentikan resolusi atau playback.
- Notifikasi awal menampilkan Resolving dan Cancel Preview; setelah sumber siap, satu notifikasi media menangani playback. Cancel mempunyai identitas request/generation unik agar action lama tidak menghentikan Preview pengganti.
- Satu ExoPlayer dipakai bergantian untuk lokal dan Preview: tidak ada dua audio aktif. Preview memakai clip sampai 120 detik dan tidak mengaktifkan loop; jika lagu lebih pendek, selesai lebih awal.
- Antrean/track/posisi lokal disimpan sementara di RAM dan dikembalikan **paused** sesudah stop/end/error. Mode lokal dipertahankan; tidak ada autoresume lokal sesudah Preview.
- UI mempertahankan panel Preview setelah sumber berhasil di-resolve tanpa mengirim signed source ke ViewModel. Terdapat status Resolving/Connecting/Buffering dan Retry/error yang aman.
- Controller reconnect dengan backoff, mengabaikan hasil command lama, dan tidak mengulang START yang sudah dikirim hanya karena reconnect. Pelepasan controller/komposisi bukan perintah stop.
- Signed URL/header hanya RAM sementara pada jalur service/repository. Timeline/notifikasi/session extras memakai metadata publik, bukan URL stream. Tidak mencatat exception native yang dapat mengandung URL/header.

## Pengukuran startup dan batas klaim

Laporan awal pengguna: kira-kira 15 detik dari tap Preview sampai audio, dan audio berhenti saat Home. **Belum ada angka pembanding dari HP untuk 0.4.1; jangan menganggap latensi sudah membaik.** Buffer tidak dituning agresif berdasarkan dugaan.

Log tag `SwarariaTiming` hanya berisi nama tahap, angka durasi dan cache-hit yang memang tersedia:
- `preview_dispatch_to_service_ms`: tap pada remote sampai service menerima request.
- `metadata_engine_init_ms`: inisialisasi engine metadata.
- `metadata_execute_ms`: eksekusi yt-dlp metadata; bukan pengukuran network-first-byte.
- `preview_resolve_ms` dan `cache_hit`: resolver/cache total.
- `playback_player_init_ms`: inisialisasi player service.
- `preview_prepare_to_ready_ms`, `preview_prepare_to_playing_ms`: tahap player setelah sumber siap.
- `preview_start_ms`: request service sampai player melaporkan playing.
- `preview_tap_to_playing_ms`: tap remote sampai player melaporkan playing, memakai clock elapsed-realtime yang sama. Ini bukan pengukuran suara fisik keluar speaker.
- `download_engine_init_ms`, `download_execute_to_first_progress_ms`: inisialisasi download dan callback progres yt-dlp pertama yang valid, **bukan** byte pertama jaringan.

Default startup buffer pada Media3 1.8.0 yang dipakai adalah 1 detik media terbuffer; ini bukan jaminan 1 detik wall-clock dan tidak menunggu seluruh lagu. Cache URL RAM tetap singkat/terbatas; tidak menambahkan persistent extractor cache, cookies, bypass anti-bot, atau prefetch.

## Pemasangan dan acceptance HP

Pasang ARM64 sebagai **update 0.4.0**, jangan uninstall atau clear data. Catat beberapa track/rename history sebelum update. Lolos signer/schema/APK checks bukan bukti proses update di HP berhasil.

1. Cek koleksi, riwayat rename, artwork dan playback file lokal tetap ada. Coba offline seperti sebelumnya.
2. Putar lokal, pilih Preview di Search: lokal harus berhenti/jeda sebelum Preview, tanpa overlap. Setelah Preview berhenti, queue/posisi/mode lokal kembali paused.
3. Tap Preview lalu segera tekan Home **ketika masih Resolving**, bukan hanya sesudah suara mulai. Resolusi harus dilanjutkan dan audio dapat mulai di background. Ulangi dengan layar dikunci.
4. Saat Preview berbunyi, Home dan lock layar lagi. Uji Pause/Play pada notifikasi; audio tidak boleh berhenti hanya karena UI background. Lagu panjang harus selesai paling lambat di akhir interval media 120 detik.
5. Saat Resolving, Cancel Preview dari notifikasi lalu coba lagi. Cancel/action request lama tidak boleh menghentikan request pengganti. Coba Stop saat Connecting/Buffering.
6. Putar layar saat Resolving dan saat Playing; kembali ke aplikasi. Jangan ada resolusi/start duplikat, dua player, panel hilang atau progress tertahan.
7. Uji error jaringan/offline, Retry, lagu lain, pindah jaringan dan mulai lokal/download/search baru. Error tidak boleh memuat signed URL/header; queue lokal tidak otomatis berbunyi setelah error.
8. Uji Delete dan rename setelah queue kembali, termasuk rotasi ketika operasi masih berjalan. Track yang dihapus tidak boleh muncul kembali lewat saved queue.
9. Catat perkiraan tap→audio pada percobaan pertama dan ulangi lagu yang sama segera setelah stop. Catat apakah jeda terutama terlihat pada Resolving, Connecting atau Buffering. Jangan mengirim raw log/credential/signed URL; ADB bukan prasyarat untuk laporan awal ini.

Navigasi eksplisit Back/pindah halaman di dalam aplikasi masih dapat menghentikan Preview; itu berbeda dari Home/lock/rotasi. **Swipe-dismiss task dari Recents**, force-stop, reboot, OS process-kill dan pembatasan baterai vendor bukan jaminan background continuity. Media3 dapat menghentikan service ketika task dibuang saat resolving/buffering; itu tidak disamakan dengan Home. Signed stream/permintaan yang sedang berjalan tidak dipersist untuk autoresume setelah proses mati.

Catatan review non-blocking: kegagalan koneksi controller Activity yang pertama masih dapat memerlukan Activity dibuka ulang; retry otomatis pada jalur awal Activity itu belum ditambahkan (perilaku lama). PreviewRemote dan koneksi Activity yang sudah sempat tersambung mempunyai jalur reconnect masing-masing. Jangan menyebut seluruh kemungkinan koneksi sudah ditangani otomatis.

## Pemeriksaan dan rollback

Jalankan `python verify_upgrade_041.py` untuk gate offline lengkap. Unit/host/API/source checks, compile, lint, kedua ABI APK, signature/zip alignment, identitas update, schema/resources/dependency dan payload native dibandingkan terpisah. Packaging menolak review gagal, fingerprint sumber berbeda atau APK yang tidak sama dengan bukti pemeriksaan.

Rollback source memakai base commit/checkpoint dengan terlebih dahulu menyimpan perubahan yang lebih baru; jangan destructive reset secara sembarangan. Snapshot parsial hanya untuk mengembalikan state saat resume, bukan kandidat terverifikasi. Downgrade APK versionCode lebih rendah mungkin ditolak installer; jangan uninstall/clear data sebagai jalan pintas. Schema sama tidak otomatis membuktikan downgrade Android aman. Checkpoint lokal bukan backup terenkripsi/off-host.

Lima nested ELF ARM64 FFmpeg masih alignment 4096 dan audit GPL Corresponding Source/provenance belum tuntas. APK hanya kandidat privat, bukan siap publikasi/distribusi komersial.
