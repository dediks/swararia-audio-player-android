# Swararia Audio Player — Android

Aplikasi Android native untuk menyiapkan, mengelola, dan memutar audio lokal. Target penggunaan: soundman dan rental sound system hajatan. Aplikasi tetap dapat memutar file lokal tanpa laptop; fitur transfer koleksi dari Swararia Desktop **belum diimplementasikan**.

## Status pengembangan

Baseline source adalah **0.2.0-spike**. Branch pengembangan saat ini menyiapkan **0.3.0-spike**, tetap build debug/pengujian, bukan rilis produksi.

- Android 10+ (minSdk 29), targetSdk 36; Kotlin/Compose, Media3, Room dan MediaStore/SAF.
- Pustaka: pencarian lokal, urutan terbaru/A–Z, rename file dan riwayat undo.
- Mini-player, pemutar lengkap, antrean dan background media session.
- Tambah audio melalui tautan HTTPS berizin, pencarian metadata YouTube/YouTube Music, atau impor salinan file lokal.
- Logo Swararia dan font Inter lokal; artwork abstrak merupakan dekorasi fallback, bukan sampul album yang terverifikasi.
- Tidak memerlukan server, akun, Python atau Termux di HP untuk fungsi yang sudah tersedia. Pencarian/unduhan online memerlukan internet.

0.3.0 menambahkan default YouTube Music, filter lagu/playlist, urutan hasil termuat, metadata kanal/durasi, pemuatan hasil tambahan, preview online hingga 30 detik, mode Loop/Continue/Once dan Fade 3s. Prioritas kanal memakai sinyal terverifikasi/indikasi artis, bukan bukti label asli atau hak penggunaan. Playlist dibuka untuk memilih lagu; bukan unduh massal.

Branch `refactor/architecture-foundation` merapikan dependency/state dan memisahkan UI tanpa menerapkan mockup baru. Pada repository ini, build memakai toolchain/cache yang sudah ada: 33 unit test, kompilasi, kedua APK ABI, kontrak APK dan signature lulus. Lint: 15 warning, tidak ada Error/Fatal. Batas dan alur pengembangan dijelaskan di `ARCHITECTURE.md` serta `CONTRIBUTING.md`. Review versi 0.3.0 lulus sesudah perbaikan temuan awal; review refactor juga lulus dan dicatat terpisah pada `verification/architecture-review.json`.

Pengguna melaporkan 0.1.0, 0.2.0, dan fungsi 0.3.0 bekerja pada Samsung M15 Android 16, tetapi UX 0.3.0 perlu diperbaiki. Laporan itu bukan verifikasi independen setiap fitur/kasus. **Build refactor belum diuji pada HP.** Pengambilan stream preview pada jaringan build dibatasi anti-bot YouTube; penanganan error tersedia. Lihat `DEVICE-TEST.md`.

## Batasan penting

- Online ingest membutuhkan halaman memori perangkat 4KB. Lima library ARM64 FFmpeg bawaan belum kompatibel 16KB; import/player lokal masih dapat dicoba. ZIP alignment bukan bukti kompatibilitas ELF 16KB.
- Tidak ada cookies/login, DRM bypass, sinkronisasi desktop, QC/stem splitting atau jaminan resume sesudah force-stop.
- Gunakan hanya audio dan sumber yang penggunaannya diizinkan.
- **Audit lisensi/public-distribution belum tuntas.** Source JAR dependency bukan bukti kelengkapan corresponding source native. Baca `THIRD-PARTY-NOTICES.md`; jangan menganggap proyek otomatis berlisensi MIT atau seluruhnya proprietary. Publikasi source bukan sertifikasi bahwa APK siap didistribusikan secara komersial.

## Build pada Linux x86_64

Butuh Python 3.12+. Pelajari dan setujui persyaratan SDK sebelum menjalankan setup. Script setup mengunduh JDK 21, Gradle 8.13, SDK platform 36 dan Build Tools 36.0.0 yang dipin/checksum ke direktori project-local. Tidak memerlukan root, Android Studio, emulator atau NDK. Tidak ada toolchain di dalam Git.

```sh
python setup_toolchain.py
bash build.sh compileDebugKotlin
bash build.sh testDebugUnitTest
bash build.sh assembleDebug
bash build.sh -Pandroid.experimental.lint.heapSize=384m lintDebug
python -m unittest test_engine_payload test_apk_contract -v
python verify_artifacts.py
```

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
