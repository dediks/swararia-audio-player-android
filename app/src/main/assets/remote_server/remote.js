'use strict';

(function () {
  const MAX_UPLOAD_BYTES = 150 * 1024 * 1024;
  const ALLOWED_EXTENSIONS = new Set(['mp3', 'm4a', 'mp4', 'opus', 'ogg', 'webm', 'wav', 'flac', 'aac']);

  let csrfToken = null;
  let sessionState = null;
  let stateData = null;
  let catalogData = null;
  let manifestData = null;
  let selectedGroupId = 'g_all';
  let syncGroupId = 'g_all';
  let activeTab = 'foh';
  let pendingSwitchTrack = null;
  let pendingUploadPlan = [];
  let syncInProgress = false;
  let pollTimer = null;

  const el = (id) => document.getElementById(id);

  function setVisible(node, show) {
    node.classList.toggle('hidden', !show);
  }

  function showNotice(message, isError = false) {
    const box = el('globalNotice');
    if (!message) {
      box.textContent = '';
      setVisible(box, false);
      box.classList.remove('error');
      return;
    }
    box.textContent = message;
    box.classList.toggle('error', Boolean(isError));
    setVisible(box, true);
  }

  function formatSeconds(sec) {
    if (typeof sec !== 'number' || !Number.isFinite(sec) || sec < 0) return '--:--';
    const total = Math.floor(sec);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    const pad = (n) => String(n).padStart(2, '0');
    return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
  }

  function formatBytes(bytes) {
    if (typeof bytes !== 'number' || !Number.isFinite(bytes) || bytes < 0) return '0 B';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
  }

  function fileExtension(name) {
    const idx = name.lastIndexOf('.');
    return idx > 0 ? name.slice(idx + 1).toLowerCase() : '';
  }

  function newIdempotencyKey() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID();
    }
    const bytes = new Uint8Array(16);
    window.crypto.getRandomValues(bytes);
    return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  }

  async function apiGet(path) {
    const res = await fetch(path, {
      method: 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
    });
    const data = await res.json().catch(() => ({}));
    return { ok: res.ok, status: res.status, data };
  }

  async function apiPostJson(path, body, includeCsrf = true) {
    const headers = { 'Content-Type': 'application/json' };
    if (includeCsrf && csrfToken) {
      headers['X-Swararia-Csrf'] = csrfToken;
    }
    const res = await fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers,
      body: JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    return { ok: res.ok, status: res.status, data };
  }

  async function computeSha256Hex(file) {
    if (!window.crypto || !window.crypto.subtle) {
      throw new Error('WebCrypto SHA-256 butuh koneksi HTTPS yang sudah disetujui sertifikatnya.');
    }
    const buffer = await file.arrayBuffer();
    const hashBuf = await window.crypto.subtle.digest('SHA-256', buffer);
    const bytes = new Uint8Array(hashBuf);
    let hex = '';
    for (let i = 0; i < bytes.length; i++) {
      hex += bytes[i].toString(16).padStart(2, '0');
    }
    return hex;
  }

  function switchView(mode) {
    setVisible(el('viewPair'), mode === 'pair');
    setVisible(el('viewPending'), mode === 'pending');
    setVisible(el('viewActive'), mode === 'active');
    setVisible(el('btnDisconnect'), mode === 'active' || mode === 'pending');
  }

  function setTab(tab) {
    activeTab = tab;
    el('tabBtnFoh').classList.toggle('active', tab === 'foh');
    el('tabBtnSync').classList.toggle('active', tab === 'sync');
    setVisible(el('panelFoh'), tab === 'foh');
    setVisible(el('panelSync'), tab === 'sync');
    if (tab === 'sync') {
      loadManifestIfPermitted();
    }
  }

  function renderHeader() {
    const pill = el('rolePill');
    const tPill = el('transferPill');
    if (!sessionState || sessionState.status !== 'active') {
      setVisible(pill, false);
      setVisible(tPill, false);
      el('subStatus').textContent = sessionState?.status === 'pending'
        ? 'Menunggu persetujuan di HP Server'
        : 'Siap disambungkan ke HP Swararia';
      return;
    }
    const isControl = sessionState.role === 'control';
    pill.textContent = isControl ? 'Control FOH' : 'View-Only';
    pill.className = `pill ${isControl ? 'pill-primary' : 'pill-warn'}`;
    setVisible(pill, true);

    const canTransfer = Boolean(sessionState.transfer_allowed);
    tPill.textContent = canTransfer ? 'Sync & Transfer Aktif' : 'Transfer Terkunci';
    tPill.className = `pill ${canTransfer ? 'pill-primary' : 'pill-muted'}`;
    setVisible(tPill, true);

    const masterOnline = stateData?.master_online === true;
    el('subStatus').textContent = masterOnline
      ? 'Terhubung ke HP Server • Pemutar siap'
      : 'Pemutar HP sedang sibuk / preview aktif';
  }

  function renderDeck() {
    const st = stateData?.state;
    const online = stateData?.master_online === true;
    const isControl = sessionState?.status === 'active' && sessionState?.role === 'control';
    const transport = st?.transport_state || 'STOPPED';
    const isPlaying = st?.is_playing === true || transport === 'PLAYING';
    const isFading = transport === 'FADING';

    const badge = el('transportBadge');
    badge.textContent = online ? transport : 'UNAVAILABLE';
    badge.className = `pill ${isFading ? 'pill-warn' : isPlaying ? 'pill-primary' : 'pill-muted'}`;

    el('nowTitle').textContent = st?.track_title || 'Belum ada lagu diputar';
    const cur = st?.current_time;
    const dur = st?.duration;
    el('timeLabel').textContent = `${formatSeconds(cur)} / ${formatSeconds(dur)}`;

    const pct = typeof cur === 'number' && typeof dur === 'number' && dur > 0
      ? Math.max(0, Math.min(100, (cur / dur) * 100))
      : 0;
    el('progressBar').style.width = `${pct}%`;

    const canAct = Boolean(online && isControl);
    const playPauseBtn = el('btnPlayPause');
    playPauseBtn.textContent = isPlaying && !isFading ? 'Pause' : 'Play';
    playPauseBtn.disabled = !canAct;
    el('btnPrev').disabled = !canAct;
    el('btnNext').disabled = !canAct;
    el('btnStop').disabled = !canAct;
    el('btnFadeOut').disabled = !(canAct && isPlaying && !isFading);
  }

  function renderChips(container, groups, activeId, onPick) {
    container.replaceChildren();
    for (const grp of groups) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = `chip ${grp.id === activeId ? 'active' : ''}`;
      btn.textContent = `${grp.name} (${grp.count})`;
      btn.addEventListener('click', () => onPick(grp.id));
      container.appendChild(btn);
    }
  }

  function matchesGroup(track, groupId) {
    if (!groupId || groupId === 'g_all') return true;
    if (Array.isArray(track.playlist_ids) && track.playlist_ids.includes(groupId)) return true;
    return track.group_id === groupId;
  }

  function renderTrackCatalog() {
    const tracks = Array.isArray(catalogData?.tracks) ? catalogData.tracks : [];
    const groups = Array.isArray(catalogData?.groups) ? catalogData.groups : [];
    if (!groups.some((g) => g.id === selectedGroupId)) selectedGroupId = groups[0]?.id || 'g_all';

    renderChips(el('groupChips'), groups, selectedGroupId, (gid) => {
      selectedGroupId = gid;
      sendCommand('select_group', { group_id: gid });
      renderTrackCatalog();
    });

    const query = el('trackSearch').value.trim().toLowerCase();
    const filtered = tracks.filter((t) => {
      if (!matchesGroup(t, selectedGroupId)) return false;
      if (!query) return true;
      return (t.title || '').toLowerCase().includes(query) ||
        (t.artist || '').toLowerCase().includes(query);
    });

    el('catalogSummary').textContent = `${filtered.length} dari ${catalogData?.total_tracks ?? tracks.length} lagu`;

    const list = el('trackList');
    list.replaceChildren();
    if (filtered.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'muted';
      empty.textContent = 'Tidak ada lagu yang cocok pada grup ini.';
      list.appendChild(empty);
      return;
    }

    const canControl = sessionState?.role === 'control' && stateData?.master_online === true;
    const curId = catalogData?.current_track_id || null;

    for (const track of filtered) {
      const row = document.createElement('div');
      row.className = `track-row ${track.id === curId ? 'current' : ''}`;

      const meta = document.createElement('div');
      meta.className = 'track-meta';

      const title = document.createElement('div');
      title.className = 'track-title';
      title.textContent = track.title || track.id;

      const subParts = [];
      if (track.artist) subParts.push(track.artist);
      if (typeof track.duration_ms === 'number') subParts.push(formatSeconds(track.duration_ms / 1000));
      if (typeof track.size_bytes === 'number') subParts.push(formatBytes(track.size_bytes));
      const sub = document.createElement('div');
      sub.className = 'track-sub';
      sub.textContent = subParts.join(' · ') || 'Audio';

      meta.appendChild(title);
      meta.appendChild(sub);

      const actions = document.createElement('div');
      actions.className = 'track-actions';

      const cueBtn = document.createElement('button');
      cueBtn.type = 'button';
      cueBtn.className = 'btn btn-secondary btn-sm';
      cueBtn.textContent = 'Cue';
      cueBtn.disabled = !canControl;
      cueBtn.addEventListener('click', () => handleSelectTrack(track, false));

      const playBtn = document.createElement('button');
      playBtn.type = 'button';
      playBtn.className = 'btn btn-primary btn-sm';
      playBtn.textContent = track.id === curId ? 'Aktif' : 'Play';
      playBtn.disabled = !canControl;
      playBtn.addEventListener('click', () => handleSelectTrack(track, true));

      actions.appendChild(cueBtn);
      actions.appendChild(playBtn);
      row.appendChild(meta);
      row.appendChild(actions);
      list.appendChild(row);
    }
  }

  function handleSelectTrack(track, autoPlay) {
    const st = stateData?.state;
    const isPlaying = st?.is_playing === true || st?.transport_state === 'FADING';
    if (track.id === catalogData?.current_track_id) {
      if (autoPlay) sendCommand(st?.is_playing ? 'pause' : 'play');
      return;
    }
    if (isPlaying) {
      pendingSwitchTrack = track;
      el('switchGuardText').textContent = `Audio sedang aktif diputar. Ganti ke "${track.title}" (${autoPlay ? 'Putar sekarang' : 'Cue'})?`;
      setVisible(el('switchGuardCard'), true);
      return;
    }
    sendCommand('select_track', { track_id: track.id, auto_play: autoPlay });
  }

  function renderSyncTab() {
    const allowed = Boolean(sessionState?.transfer_allowed);
    setVisible(el('syncBlockedBanner'), !allowed);
    setVisible(el('syncEnabledContent'), allowed);
    if (!allowed) return;

    const groups = Array.isArray(catalogData?.groups) ? catalogData.groups : [];
    if (!groups.some((g) => g.id === syncGroupId)) syncGroupId = groups[0]?.id || 'g_all';
    renderChips(el('syncGroupChips'), groups, syncGroupId, (gid) => {
      syncGroupId = gid;
      renderSyncTab();
    });

    const datalist = el('existingPlaylistsDatalist');
    datalist.replaceChildren();
    for (const grp of groups) {
      if (grp.id === 'g_all') continue;
      const opt = document.createElement('option');
      opt.value = grp.name;
      datalist.appendChild(opt);
    }

    const tracks = Array.isArray(manifestData?.tracks) ? manifestData.tracks : [];
    const filtered = tracks.filter((t) => {
      if (syncGroupId === 'g_all') return true;
      return Array.isArray(t.playlist_ids) && t.playlist_ids.includes(syncGroupId);
    });

    const dlList = el('downloadTrackList');
    dlList.replaceChildren();
    if (filtered.length === 0) {
      const p = document.createElement('p');
      p.className = 'muted';
      p.textContent = 'Belum ada lagu di grup ini.';
      dlList.appendChild(p);
    } else {
      for (const track of filtered) {
        const row = document.createElement('div');
        row.className = 'track-row';

        const meta = document.createElement('div');
        meta.className = 'track-meta';
        const t = document.createElement('div');
        t.className = 'track-title';
        t.textContent = track.display_name || track.title;
        const s = document.createElement('div');
        s.className = 'track-sub';
        s.textContent = `${formatBytes(track.size_bytes)} · ${track.sha256 ? 'SHA-256 terverifikasi' : 'Menunggu hash'}`;
        meta.appendChild(t);
        meta.appendChild(s);

        const dlBtn = document.createElement('button');
        dlBtn.type = 'button';
        dlBtn.className = 'btn btn-secondary btn-sm';
        dlBtn.textContent = 'Unduh';
        dlBtn.addEventListener('click', () => downloadSingleTrack(track));

        row.appendChild(meta);
        row.appendChild(dlBtn);
        dlList.appendChild(row);
      }
    }
    el('btnDownloadGroup').disabled = syncInProgress || filtered.length === 0;
  }

  async function downloadSingleTrack(track) {
    try {
      const res = await fetch(`/web/media/${encodeURIComponent(track.id)}`, {
        method: 'GET',
        credentials: 'same-origin',
        cache: 'no-store',
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        showNotice(`Gagal mengunduh ${track.title}: ${err.code || res.status}`, true);
        return false;
      }
      const blob = await res.blob();
      const blobUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = blobUrl;
      anchor.download = track.display_name || `${track.title || track.id}.audio`;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      setTimeout(() => URL.revokeObjectURL(blobUrl), 4000);
      return true;
    } catch (_) {
      showNotice(`Koneksi terputus saat mengunduh ${track.title}.`, true);
      return false;
    }
  }

  async function downloadSelectedGroup() {
    if (syncInProgress) return;
    const tracks = Array.isArray(manifestData?.tracks) ? manifestData.tracks : [];
    const queue = tracks.filter((t) => syncGroupId === 'g_all' || (Array.isArray(t.playlist_ids) && t.playlist_ids.includes(syncGroupId)));
    if (queue.length === 0) return;
    syncInProgress = true;
    el('btnDownloadGroup').disabled = true;
    try {
      for (let i = 0; i < queue.length; i++) {
        el('downloadProgressLabel').textContent = `Mengunduh ${i + 1}/${queue.length}: ${queue[i].title}`;
        const ok = await downloadSingleTrack(queue[i]);
        if (!ok) break;
      }
      el('downloadProgressLabel').textContent = `Selesai memeriksa ${queue.length} unduhan.`;
    } finally {
      syncInProgress = false;
      renderSyncTab();
    }
  }

  async function analyzeUploadSelection() {
    const files = Array.from(el('uploadFileInput').files || []);
    if (files.length === 0) {
      showNotice('Pilih minimal 1 file audio dari komputer terlebih dahulu.', true);
      return;
    }
    showNotice('');
    await loadManifestIfPermitted();
    if (!manifestData || manifestData.transfer_enabled === false) {
      showNotice('Transfer file di HP Server belum diizinkan.', true);
      return;
    }

    const byDigestAndSize = new Map();
    const byNameAndSize = new Map();
    for (const remote of manifestData.tracks || []) {
      if (remote.sha256 && typeof remote.size_bytes === 'number') {
        byDigestAndSize.set(`${remote.sha256.toLowerCase()}|${remote.size_bytes}`, remote);
      }
      if (remote.display_name && typeof remote.size_bytes === 'number') {
        byNameAndSize.set(`${remote.display_name.toLowerCase()}|${remote.size_bytes}`, remote);
      }
    }

    pendingUploadPlan = [];
    const summaryBox = el('syncAnalysisSummary');
    setVisible(summaryBox, true);
    summaryBox.className = 'notice';
    summaryBox.textContent = `Menghitung SHA-256 untuk ${files.length} file…`;

    let newCount = 0;
    let skipCount = 0;
    let invalidCount = 0;

    for (let i = 0; i < files.length; i++) {
      const f = files[i];
      const ext = fileExtension(f.name);
      if (!ALLOWED_EXTENSIONS.has(ext)) {
        pendingUploadPlan.push({ file: f, action: 'invalid', reason: `Format .${ext || '?'} belum didukung`, sha256: null });
        invalidCount++;
        continue;
      }
      if (f.size <= 0 || f.size > MAX_UPLOAD_BYTES) {
        pendingUploadPlan.push({ file: f, action: 'invalid', reason: 'Ukuran melebihi batas 150 MiB atau kosong', sha256: null });
        invalidCount++;
        continue;
      }
      summaryBox.textContent = `Menghitung SHA-256 (${i + 1}/${files.length}): ${f.name}`;
      let sha;
      try {
        sha = await computeSha256Hex(f);
      } catch (err) {
        showNotice(err.message, true);
        return;
      }

      const existing = byDigestAndSize.get(`${sha}|${f.size}`) ||
        byNameAndSize.get(`${f.name.toLowerCase()}|${f.size}`);
      const targetPlaylist = el('uploadPlaylistInput').value.trim();

      if (existing && !targetPlaylist) {
        pendingUploadPlan.push({ file: f, action: 'skip', reason: 'Sudah identik di HP (dilewati)', sha256: sha });
        skipCount++;
      } else if (existing && targetPlaylist) {
        pendingUploadPlan.push({ file: f, action: 'link', reason: `Identik — tautkan ke playlist "${targetPlaylist}"`, sha256: sha });
        newCount++;
      } else {
        pendingUploadPlan.push({ file: f, action: 'upload', reason: 'Lagu baru — siap dikirim', sha256: sha });
        newCount++;
      }
    }

    summaryBox.textContent = `Hasil analisa: ${newCount} file akan diproses, ${skipCount} sudah ada di HP (dilewati), ${invalidCount} ditolak.`;
    el('btnStartSync').disabled = newCount === 0 || syncInProgress;
    renderUploadPlan();
  }

  function renderUploadPlan() {
    const list = el('uploadPlanList');
    list.replaceChildren();
    for (const item of pendingUploadPlan) {
      const row = document.createElement('div');
      row.className = 'track-row';

      const meta = document.createElement('div');
      meta.className = 'track-meta';
      const title = document.createElement('div');
      title.className = 'track-title';
      title.textContent = `${item.file.name} (${formatBytes(item.file.size)})`;
      const sub = document.createElement('div');
      sub.className = 'track-sub';
      sub.textContent = item.reason;
      meta.appendChild(title);
      meta.appendChild(sub);

      const badge = document.createElement('span');
      badge.className = `pill ${item.action === 'done' || item.action === 'upload' || item.action === 'link' ? 'pill-primary' : item.action === 'skip' ? 'pill-muted' : 'pill-warn'}`;
      badge.textContent = item.statusBadge || item.action.toUpperCase();

      row.appendChild(meta);
      row.appendChild(badge);
      list.appendChild(row);
    }
  }

  async function executeUploadSync() {
    if (syncInProgress || pendingUploadPlan.length === 0) return;
    const queue = pendingUploadPlan.filter((x) => x.action === 'upload' || x.action === 'link');
    if (queue.length === 0) return;

    syncInProgress = true;
    el('btnStartSync').disabled = true;
    el('btnAnalyzeSync').disabled = true;
    const playlistName = el('uploadPlaylistInput').value.trim();

    try {
      for (let i = 0; i < queue.length; i++) {
        const item = queue[i];
        item.statusBadge = `MENGIRIM ${i + 1}/${queue.length}`;
        renderUploadPlan();

        const headers = {
          'Content-Type': 'application/octet-stream',
          'X-Swararia-Csrf': csrfToken || '',
          'X-Swararia-Filename': encodeURIComponent(item.file.name),
          'X-Swararia-Sha256': item.sha256,
        };
        if (playlistName) {
          headers['X-Swararia-Playlist'] = encodeURIComponent(playlistName);
        }

        const res = await fetch('/web/library/upload', {
          method: 'POST',
          credentials: 'same-origin',
          cache: 'no-store',
          headers,
          body: item.file,
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok) {
          item.statusBadge = 'GAGAL';
          item.reason = body.code === 'transfer_paused_during_playback'
            ? 'Ditahan karena audio FOH sedang diputar di HP Server'
            : `Ditolak server (${body.code || res.status})`;
          renderUploadPlan();
          showNotice(item.reason, true);
          if (body.code === 'transfer_paused_during_playback' || res.status === 401 || res.status === 403) {
            break;
          }
          continue;
        }
        item.action = 'done';
        item.statusBadge = body.deduplicated ? 'TERTAUT (ANTI-DOBEL)' : 'TERKIRIM';
        item.reason = body.deduplicated
          ? 'File identik sudah ada, tidak digandakan'
          : 'Berhasil ditambahkan ke Music/SwarariaAudioLab/';
        renderUploadPlan();
      }
      await loadManifestIfPermitted();
      await pollAll();
    } finally {
      syncInProgress = false;
      el('btnAnalyzeSync').disabled = false;
      el('btnStartSync').disabled = pendingUploadPlan.every((x) => x.action !== 'upload' && x.action !== 'link');
    }
  }

  async function loadManifestIfPermitted() {
    if (!sessionState?.transfer_allowed) {
      manifestData = null;
      renderSyncTab();
      return;
    }
    const res = await apiGet('/web/library/manifest');
    if (res.ok) {
      manifestData = res.data;
    } else {
      manifestData = null;
    }
    renderSyncTab();
  }

  async function sendCommand(action, extra = {}) {
    const res = await apiPostJson('/web/commands', {
      action,
      idempotency_key: newIdempotencyKey(),
      ...extra,
    });
    if (!res.ok) {
      showNotice(`Perintah ditolak: ${res.data?.code || res.status}`, true);
    } else {
      showNotice('');
    }
    await pollAll();
  }

  function handleClientReset(message = '') {
    csrfToken = null;
    sessionState = null;
    stateData = null;
    catalogData = null;
    manifestData = null;
    pendingUploadPlan = [];
    if (message) showNotice(message, true);
    switchView('pair');
    renderHeader();
  }

  async function pollAll() {
    const s = await apiGet('/web/session');
    if (!s.ok) {
      if (sessionState) {
        window.location.reload();
        return;
      }
      handleClientReset('');
      return;
    }
    sessionState = s.data;
    if (typeof s.data.csrf_token === 'string') {
      csrfToken = s.data.csrf_token;
    }

    if (sessionState.status === 'pending') {
      switchView('pending');
      renderHeader();
      return;
    }
    if (sessionState.status !== 'active') {
      window.location.reload();
      return;
    }

    switchView('active');
    const [stRes, trRes] = await Promise.all([apiGet('/web/state'), apiGet('/web/tracks')]);
    if (stRes.ok) stateData = stRes.data;
    if (trRes.ok) catalogData = trRes.data;
    renderHeader();
    renderDeck();
    renderTrackCatalog();
    if (activeTab === 'sync') {
      await loadManifestIfPermitted();
    }
  }

  async function submitPair(event) {
    event.preventDefault();
    const label = el('pairLabel').value.trim();
    const pin = el('pairPin').value.trim();
    if (!label || !pin) return;
    el('btnPair').disabled = true;
    showNotice('');
    try {
      const res = await apiPostJson('/web/pair', { label, pin }, false);
      if (!res.ok) {
        showNotice(`Pairing gagal (${res.data?.code || res.status}). Periksa PIN & coba lagi.`, true);
        return;
      }
      csrfToken = res.data.csrf_token || null;
      sessionState = { device_id: res.data.device_id, status: res.data.status };
      el('pairPin').value = '';
      switchView('pending');
      renderHeader();
    } finally {
      el('btnPair').disabled = false;
    }
  }

  async function logoutSession() {
    await apiPostJson('/web/logout', {}, true).catch(() => {});
    csrfToken = null;
    sessionState = null;
    window.location.replace('/');
  }

  function wireEvents() {
    el('pairForm').addEventListener('submit', submitPair);
    el('btnRefreshPending').addEventListener('click', pollAll);
    el('btnCancelPending').addEventListener('click', logoutSession);
    el('btnDisconnect').addEventListener('click', logoutSession);

    el('tabBtnFoh').addEventListener('click', () => setTab('foh'));
    el('tabBtnSync').addEventListener('click', () => setTab('sync'));

    el('btnPlayPause').addEventListener('click', () => {
      const isPlaying = stateData?.state?.is_playing === true && stateData?.state?.transport_state !== 'FADING';
      sendCommand(isPlaying ? 'pause' : 'play');
    });
    el('btnPrev').addEventListener('click', () => sendCommand('prev'));
    el('btnNext').addEventListener('click', () => sendCommand('next'));
    el('btnFadeOut').addEventListener('click', () => sendCommand('fade_out'));
    el('btnStop').addEventListener('click', () => sendCommand('stop'));

    el('btnConfirmCue').addEventListener('click', () => {
      const target = pendingSwitchTrack;
      pendingSwitchTrack = null;
      setVisible(el('switchGuardCard'), false);
      if (target) sendCommand('select_track', { track_id: target.id, auto_play: false });
    });
    el('btnConfirmPlay').addEventListener('click', () => {
      const target = pendingSwitchTrack;
      pendingSwitchTrack = null;
      setVisible(el('switchGuardCard'), false);
      if (target) sendCommand('select_track', { track_id: target.id, auto_play: true });
    });
    el('btnConfirmCancel').addEventListener('click', () => {
      pendingSwitchTrack = null;
      setVisible(el('switchGuardCard'), false);
    });

    el('trackSearch').addEventListener('input', renderTrackCatalog);
    el('btnRefreshManifest').addEventListener('click', loadManifestIfPermitted);
    el('btnDownloadGroup').addEventListener('click', downloadSelectedGroup);
    el('btnAnalyzeSync').addEventListener('click', analyzeUploadSelection);
    el('btnStartSync').addEventListener('click', executeUploadSync);
  }

  document.addEventListener('DOMContentLoaded', () => {
    wireEvents();
    pollAll();
    pollTimer = setInterval(pollAll, 1500);
    window.addEventListener('beforeunload', () => {
      if (pollTimer) clearInterval(pollTimer);
    });
  });
})();

