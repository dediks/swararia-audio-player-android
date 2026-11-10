# Uji pada HP — BELUM dijalankan di mesin build

Tidak ada perangkat Android atau emulator pada lingkungan yang disetujui. Lolos kompilasi, unit test, tanda tangan APK, dan pemeriksaan native library bukan bukti alur berikut sudah berhasil pada HP.

## Persiapan
- APK ditujukan untuk Android 10+; pilih ARM64 untuk HP 64-bit, atau ARMv7 bila perangkat menjalankan Android 32-bit.
- Unduh online hanya didukung pada halaman memori 4KB. Pada perangkat 16KB, build ini menolak ingest dengan pesan yang jelas; impor/player/organizer lokal masih dapat dicoba.
- APK debug ini memiliki applicationId `com.swararia.audiolab.spike`, terpisah dari aplikasi produksi.
- Aktifkan izin memasang APK hanya pada aplikasi yang dipakai membuka file. Izin sumber tidak dikenal dapat dimatikan kembali sesudah pemasangan.
- Izin notifikasi membantu menampilkan unduhan dan pemutar. Tidak ada permintaan izin mengelola semua file.
- Gunakan sumber audio yang dimiliki atau secara jelas mengizinkan pengunduhan. Tidak ada cookies/login, pembobolan DRM, atau bypass paywall.

## Pembaruan 0.3.0 — matriks uji berikutnya
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
