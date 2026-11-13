# Uji pada HP — QA fitur interaktif belum selesai

APK debug (`versionCode=6`, `0.5.0-spike` + Pembaruan 0.6.0 Server Mode) sudah dibangun ulang; cek status pemasangan terbaru di bawah. Lolos kompilasi, unit test, tanda tangan APK, dan pemeriksaan native library bukan bukti alur berikut sudah berhasil pada HP.

## Pembaruan 0.6.0 — prioritas uji Mode Server HP, Browser FOH & Sync SHA-256
- Pasang sebagai **update** (`adb install -r`) di atas APK `com.swararia.audiolab.spike` dari keystore yang sama; verifikasi koleksi & playlist dari v3 tetap utuh sesudah migrasi aditif Room `v3 → v4` dan kolom SHA-256 terisi bertahap tanpa gangguan pemutaran.
- Masuk tab **Remote**, pilih switcher **`Jadikan HP Ini Server`**, hubungkan HP ke Hotspot/Wi-Fi lokal (atau nyalakan Hotspot HP ini), ketuk **Segarkan IP**, pilih IP Hotspot/Wi-Fi dan port (`8443`), lalu tekan **Nyalakan Server HP**. Pastikan notifikasi foreground pemutar audio aktif dan HP tetap melayani meski layar dimatikan.
- **Uji Klien Android lain (`QR Native`):** pada HP Swararia Android ke-2, buka **Remote → `Kendalikan PC` → `Scan QR dari PC`** untuk memindai QR pada layar Server HP, lalu setujui sebagai `Control` / `View-Only` pada Server HP. Uji `Play`/`Pause`/`Prev`/`Next`/`Fade 3s`/`Stop`, ganti grup playlist, serta pastikan preview online lokal di Server HP mengunci remote control untuk sementara.
- **Uji Web Browser FOH (Chrome Android / Safari iPhone / Laptop):** buka `https://<IP>:<port>/` dari browser, lanjutkan peringatan sertifikat lokal (cocokkan fingerprint SHA-256), masukkan **PIN 8 karakter**, setujui perangkat pada Server HP:
  - Cek rate-limit/lockout 5x PIN salah, cek tombol refresh/PIN Baru, dan cek tombol **`Putuskan`** langsung mengakhiri sesi browser.
  - Uji role `View-Only` (tombol kontrol tetap nonaktif & API transfer menolak akses).
- **Uji Download & Sinkronisasi Lagu (Anti-Duplikat SHA-256):**
  - Pastikan toggle **"Izinkan Transfer & Sync File"** awalnya **MATI** (unduh/unggah harus ditolak). Nyalakan toggle global tersebut dan beri izin sync pada perangkat `Control`.
  - Uji toggle **"Tahan Transfer Saat Audio Diputar"** saat audio `PLAYING` (upload/download ditolak rapi dengan pesan status tanpa gangguan audio) lalu jeda lagu untuk melanjutkan transfer.
  - Unduh 1 lagu & batch beberapa lagu dari grup playlist; lalu unggah kembali lagu yang sama dari browser ke Server HP (harus terdeteksi `Sudah ada / Deduplicated SHA-256`, tidak menggandakan file di `Music/SwarariaAudioLab`, namun playlist tujuan tetap tertaut), serta unggah 1 lagu MP3/WAV baru dan pastikan muncul di Library Server HP.
- Tekan **Hentikan Server** dan pastikan endpoint segera menutup seluruh koneksi dan membatalkan PIN/invite.

## Persiapan
- APK ditujukan untuk Android 10+; pilih ARM64 untuk HP 64-bit, atau ARMv7 bila perangkat menjalankan Android 32-bit.
- Unduh online hanya didukung pada halaman memori 4KB. Pada perangkat 16KB, build ini menolak ingest dengan pesan yang jelas; impor/player/organizer lokal masih dapat dicoba.
- APK debug ini memiliki applicationId `com.swararia.audiolab.spike`, terpisah dari aplikasi produksi.
- Aktifkan izin memasang APK hanya pada aplikasi yang dipakai membuka file. Izin sumber tidak dikenal dapat dimatikan kembali sesudah pemasangan.
- Izin notifikasi membantu menampilkan unduhan dan pemutar. Tidak ada permintaan izin mengelola semua file.
- Gunakan sumber audio yang dimiliki atau secara jelas mengizinkan pengunduhan. Tidak ada cookies/login, pembobolan DRM, atau bypass paywall.

## Pembaruan 0.5.0 — prioritas uji Remote HP–PC
- Ikuti `UPDATE-0.5.0.md`; pasang sebagai update (`adb.exe install -r`) bila memakai debug keystore yang sama. Jika sebelumnya terpasang APK dari keystore berbeda dan ditolak (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), `uninstall` sekali saja lalu `install` ulang (file di `Music/SwarariaAudioLab` tetap ada, sedangkan database Room privat ter-reset).
- Desktop harus menjalankan Native Remote v1 terbaru (`audio-library-starter`, termasuk dukungan endpoint `/v1/tracks`, `fade_out`, `prev`, `next`, `select_track`, `select_group`, dan bridge WSL bila dijalankan di WSL2); **restart `web_ui.py` dan refresh browser PC** sebelum menguji. Aktifkan HTTPS dari PC, buat invite, scan QR lewat tombol **`Scan QR dari PC`** atau tempel teks pairing ke tab Remote, lalu approve pada PC. Jangan mengirim kode/QR/token melalui Telegram.
- Uji grup folder & daftar lagu PC di tab Remote: pastikan `FilterChip` grup folder tampil, aktifkan/nonaktifkan mode `Kelompokkan per folder`, pilih lagu langsung dari HP, dan tekan **Tombol `Fade 3s`, `Prev`, `Next`, `Play`/`Pause`, `Stop`**. Pastikan suara keluar di PC, bukan HP, dan ganti grup di HP (`select_group` / `Terapkan grup di PC`) ikut mengganti filter/daftar aktif di PC. Jangan menandai sukses hanya karena Accepted/Pending; tunggu hasil dan amati player/audio PC.
- Uji Library Lokal HP setelah update (`v2 → v3`): pastikan koleksi lagu dan riwayat rename lama tetap utuh. Cek chip **`Semua` / `Playlist` / `Artis` / `Album` / `Format`**:
  - Buat, rename, dan hapus Playlist kustom (memastikan file asli di `Music/SwarariaAudioLab` tidak terhapus).
  - Masukkan beberapa lagu dari menu ⋮ (`Masukkan ke playlist…`) maupun dari dalam detail playlist (`Tambah lagu`), lalu keluarkan lagu dan pastikan urutannya tetap rapi.
  - Uji pengelompokan **Artis / Album / Format**, lipat/buka header grup, tekan tombol play grup (`▶ Putar grup`), dan cek tombol Next/Previous di MiniPlayer/FocusedPlayer tetap berjalan di dalam grup yang dipilih.
  - Impor file lokal ber-tag ID3 dan unduh audio baru, lalu pastikan tag `Artis` muncul di subtitle lagu dan terisi ke grup `Artis`/`Album` (lagu tanpa tag masuk ke `Artis Tidak Diketahui` / `Album Tidak Diketahui`).
- View-only harus menonaktifkan kontrol; revoke/expiry/sertifikat baru harus menolak sesi lama dan langsung menghapus kredensial tersimpan. Tidak boleh mengulang POST otomatis ketika respons hilang atau jaringan tersambung kembali.
- Uji **auto-reconnect setelah aplikasi ditutup penuh**: setelah pairing & approve, tutup aplikasi Android sepenuhnya (swipe dari Recents / kill process) lalu buka kembali dan masuk ke tab **Remote**. Pastikan status langsung mencoba menyambung ulang tanpa scan QR/approve lagi, tombol tetap terkunci hingga konfirmasi PC berhasil, menekan **`Putuskan`** menghapus sesi secara permanen, sedangkan revoke atau restart PC dengan sertifikat baru tetap memaksa pairing ulang (fail closed).
- Uji rotasi, Remote→tab lain, Back, Home/lock, Wi-Fi terputus dan restart PC. Disconnect/Home bukan Stop otomatis. Identitas output HP/Desktop harus jelas; Preview HP yang sedang aktif tidak berhenti hanya karena membuka/menutup Remote.
- Uji font besar, landscape, keyboard, tombol **`Scan QR dari PC`** (Google Code Scanner / Play Services `barcode_ui`, tanpa izin `CAMERA`), dan paste kode manual `swararia1|...`. Catat bila Play Services meminta internet sekali saat unduh pertama `barcode_ui` pada APK sideload.
- Ulangi Library offline, rename/undo, queue, Download/Delete dan Preview background. Host/JVM/compile/signature checks bukan bukti acceptance perangkat ini.

## Pembaruan 0.4.1 — matriks historis
- Checklist lengkap: `UPDATE-0.4.1.md`. Pasang sebagai update, tanpa uninstall/clear data; schema tetap v2.
- Uji Home dan lock segera saat Resolving, kemudian saat Preview sudah berbunyi. Uji Cancel notifikasi, Pause/Play, rotasi, reconnect, cap 120 detik, single audio dan queue lokal kembali paused.
- Catat tap→audio pertama dan pengulangan segera, serta label tahap yang paling lama. Log timing baru bukan bukti latensi sudah membaik; tidak perlu mengirim raw log/signed URL/credential.
- Force-stop/OS kill bukan jaminan continuity. Home/lock berbeda dari navigasi eksplisit Back/pindah halaman yang masih dapat menghentikan Preview.

## Pembaruan 0.4.0 — matriks historis
- Gunakan acceptance checklist lengkap di `UPDATE-0.4.0.md`. Bagian 0.3/0.2 di bawah adalah riwayat, bukan batas fitur 0.4.
- Update tanpa uninstall/clear data; Room1→2 harus menjaga koleksi dan riwayat rename. Downgrade APK bukan rollback database.
- Preview kini maksimal dua menit; satu playback surface, seek aktual, error/Retry, cache RAM singkat dan stop saat background. Hasil tes host bukan bukti audio/latensi di HP.
- Batch hanya lagu yang dipilih dari hasil termuat, dengan consent baru. Coba cancel saat transfer dan saat finalisasi, retry failed, serta membuka ulang setelah proses dihentikan. Salinan yang sudah selesai tidak boleh diunduh lagi akibat recovery.
- Uji penyimpanan pending→visible di MediaStore dan recovery journal, termasuk ruang penuh/error provider. Pemeriksaan memakai ukuran descriptor sebenarnya, bukan ukuran metadata yang bisa terlambat.
- Putar layar saat Delete masih berjalan, lalu cek player queue dan Library. Sumber import asli tetap ada. File salinan yang benar-benar dihapus tidak memiliki Undo.
- Download link mendukung video publik YouTube/YT Music, bukan direct stream, Playlist atau link bertoken. Jangan kirim signed URL/header untuk diagnosis.
- Pada 0.4, Cancel batch tetap dapat diminta ketika item saat ini berada di fase terlindungi; item itu bisa selesai dahulu, sedangkan item menunggu dibatalkan. Tidak ada transfer pause/resume otomatis.

## Pembaruan 0.3.0 — matriks historis
- 0.2.0 telah dilaporkan pengguna bekerja pada Samsung M15 Android 16. Itu bukan bukti fitur 0.3.0 sudah diuji. Pasang 0.3.0 sebagai update; **jangan uninstall**. Periksa koleksi dan riwayat tetap ada.
- Tambah audio membuka pencarian dengan YouTube Music. Coba Lagu dan Playlist pada kedua sumber, lalu buka playlist dan pilih satu lagu. Tidak ada unduh massal otomatis.
- Semua hasil lagu yang tampil harus memiliki kanal nyata dan durasi; playlist menampilkan pembuat serta jumlah lagu bila tersedia. Hasil tidak lengkap tidak dikarang dan diberi catatan. Artis bukan pengganti nama kanal.
- Coba relevansi/A–Z/durasi pendek-panjang; urutan hanya untuk hasil yang telah dimuat. Indikator kanal terverifikasi/kanal artis bukan bukti kepemilikan label atau izin unduh.
- Muat lebih banyak beberapa kali, pastikan hasil lama tetap ada dan tidak dobel. Batas 50 hasil per pencarian. Batalkan saat memuat; coba ulang dan query baru.
- Preview membutuhkan internet, maksimal 30 detik dan tidak menambah pustaka. Coba berhenti, pindah layar, tekan Home, matikan layar, mulai player lokal dan ulangi pada lagu lain. Tidak boleh ada dua audio berjalan bersamaan atau player lokal otomatis lanjut setelah preview.
- Jaringan build terkena pembatasan anti-bot YouTube saat mengambil stream preview. Error ini ditangani; preview sukses di HP **belum terbukti**. Jangan kirim cookie/login atau URL stream bertanda tangan untuk diagnosis.
- Pada pemutar lengkap: Loop track mengulang track yang sama; Continue melanjutkan antrean; Once menjeda sesudah satu track. Coba berganti mode, track pendek, next/previous, dan tutup/buka aplikasi. Antrean tidak boleh direset saat ganti mode.
- Saat audio sedang diputar tekan Fade 3s: volume aplikasi turun, player dijeda, lalu volume awal kembali untuk pemutaran berikutnya. Uji juga pause/Stop/seek/next, kehilangan fokus audio dan pindah background di tengah fade. Jangan sampai volume tersangkut kecil atau audio mendadak lanjut sendiri.
- Coba font besar dan landscape. APK/source compilation serta kontrak statis tidak membuktikan tata letak ini di HP.

## Pembaruan 0.2.0 — prioritas uji Samsung M15 Android 16
- Pasang sebagai **update**, bukan uninstall. Periksa bahwa pustaka dan riwayat rename dari 0.1.0 masih ada. Identitas paket, signer, schema Room v1 dan byte payload engine/native sudah dibandingkan pada mesin build; pemasangan langsung di HP belum diuji.
- Periksa logo pada ikon launcher, splash dan header. Pustaka memakai UI gelap/mint dan font Inter lokal. Artwork abstrak adalah dekorasi fallback, bukan sampul album atau gelombang audio yang diukur.
- Pustaka: cari judul, urutkan terbaru/A–Z, ubah nama melalui menu audio, lalu urungkan dari Riwayat. Periksa nama file fisik dan ekstensi tetap benar.
- Putar audio, pindah halaman, gunakan mini-player, buka pemutar lengkap, seek/previous/next/Stop, lalu lanjutkan tanpa antrean ter-reset. Periksa urutan antrean, notifikasi, pause saat buffering/selesai, serta kembali dari background dan layar mati.
- Tambah audio → Tautan: URL HTTPS berizin, konfirmasi hak penggunaan harus awalnya tidak dicentang. Mengubah/menempel URL harus menghapus centang sebelumnya.
- Tambah audio → Cari: coba **YouTube** dan **YouTube Music**, hasil kosong, tanpa internet, galat, pembatalan dan query baru. Hasil pencarian sendiri tidak boleh memulai unduhan. Memilih hasil harus membuka konfirmasi dengan centang baru yang kosong.
- Saat pencarian aktif, unduh/impor/pembaruan mesin harus ditahan; saat proses audio aktif, pencarian harus ditahan. Coba juga kembali dari picker atau dialog izin ketika status berubah.
- Sesudah Batalkan pencarian, coba mencari lagi serta mengunduh dari hasil yang berizin. Bersihkan proses lama/timeout adalah uji native yang belum terbukti hanya dari unit test.
- Coba impor lokal terpisah. File asli harus tetap ada dan salinan muncul di Pustaka.
- Periksa mode landscape, keyboard terbuka, nama panjang dan ukuran font HP lebih besar: tombol/form/pemutar tetap dapat digulir dan dipakai.
- Laporkan layar/langkah dan pesan error persis. Jangan kirim kredensial atau URL privat.

## Jalur utama (fitur dasar)
1. Buka Tambah, tempel URL HTTPS yang berizin, centang konfirmasi, lalu Unduh ke HP.
2. Catat apakah inisialisasi engine berhasil dan apakah progres/galat terlihat. Jangan menandai berhasil hanya karena progress mencapai 100%; file masih harus selesai disimpan.
3. Pastikan audio muncul di library dan folder Music/SwarariaAudioLab/.
4. Di Player, putar audio, pause/resume, coba seek, lalu matikan layar. Periksa notifikasi dan tombol headset bila tersedia.
5. Di Organizer, ubah nama. Pastikan nama fisik file berubah, ekstensi tidak berganti, lalu putar kembali.
6. Coba Batalkan perubahan nama bila tersedia; periksa nama fisik kembali.
7. Tutup dan buka lagi aplikasi: library harus tetap ada. Aktifkan mode pesawat lalu putar dan ubah nama file lokal.

## Isolasi masalah
- Bila unduh gagal, simpan pesan galat persis, versi Android, model HP, dan URL yang boleh dibagikan. Jangan mengirim URL privat atau kredensial.
- Coba Perbarui yt-dlp dari tombol eksplisit, lalu ulangi. Situs sumber dapat berubah atau membatasi akses; pembaruan tidak menjamin setiap situs bekerja.
- Impor satu audio dari HP untuk memisahkan masalah engine online dari player/organizer lokal. File asli impor tidak diubah; aplikasi bekerja pada salinannya.
- Pembatalan unduhan bersifat best-effort; penghentian native subprocess belum diuji pada perangkat. Tombol dibatasi saat finishing/publikasi file.
- Penghentian paksa, reboot, dan pengulangan unduhan/resume bukan bagian yang sudah tervalidasi pada spike ini.

## Hapus / rollback pada HP
- Uninstall menghapus APK, database dan cache privat aplikasi.
- File hasil unduh/impor di folder Music/SwarariaAudioLab dapat tetap ada; hapus secara terpisah bila tidak diperlukan.
- Menghapus folder proyek build pada komputer tidak menghapus aplikasi atau file audio pada HP.
