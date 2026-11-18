let currentStatus = null;
let isDragging = false;
let dragTimeout = null;
let debounceTimer = null;

function copyAdbCmd() {
  const code = document.getElementById('adb-code').innerText;
  navigator.clipboard.writeText(code).then(() => {
    const btn = document.querySelector('.btn-copy');
    const orig = btn.innerText;
    btn.innerText = 'Copied!';
    btn.style.background = '#00e676';
    setTimeout(() => { btn.innerText = orig; btn.style.background = ''; }, 1500);
  });
}

function debouncedPostSettings(settings) {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    postSettings(settings);
  }, 50);
}

function postSettings(settings) {
  fetch('/api/settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings)
  }).catch(console.error);
}

function postAction(act, extra = {}) {
  fetch('/api/action', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(Object.assign({ action: act }, extra))
  }).then(() => fetchStatus()).catch(console.error);
}

function renderResolutionList() {
  const data = window.lastResData;
  if (!data || !data.availableResolutions) return;
  const filter = document.getElementById('sel-aspect-filter').value;
  const container = document.getElementById('resolution-items-list');
  const selSet = new Set(data.selectedResolutions || []);
  const defRes = data.defaultResolution || '';

  const filtered = data.availableResolutions.filter(item => {
    if (filter === 'All') return true;
    if (filter === '16:9') return item.aspectRatio === '16:9';
    if (filter === '9:16') return item.aspectRatio === '9:16';
    if (filter === '4:3') return item.aspectRatio === '4:3' || item.aspectRatio === '3:4';
    if (filter === '1:1') return item.aspectRatio === '1:1';
    return !['16:9', '9:16', '4:3', '3:4', '1:1'].includes(item.aspectRatio);
  });

  container.innerHTML = filtered.map(item => {
    const checked = selSet.has(item.key) ? 'checked' : '';
    const isDefault = defRes === item.key;
    const starColor = isDefault ? 'var(--accent)' : 'var(--text-muted)';
    const starIcon = isDefault ? '★' : '☆';
    const defaultBadge = isDefault ? '<span style="font-size: 10px; background: var(--accent); color: #000; padding: 1px 4px; border-radius: 3px; font-weight: bold;">Default</span>' : '';
    const deleteBtn = item.isCustom
      ? '<button onclick="removeCustomRes(\'' + item.key + '\')" style="background:none; border:none; color:var(--danger); cursor:pointer; font-size:14px; padding:0 4px;">✕</button>'
      : '';

    return '<div style="display: flex; align-items: center; justify-content: space-between; padding: 4px 6px; background: rgba(0,0,0,0.15); border-radius: 4px;">' +
      '<label style="display: flex; align-items: center; gap: 8px; cursor: pointer; flex: 1;">' +
        '<input type="checkbox" ' + checked + ' onchange="toggleRes(\'' + item.key + '\', this.checked)">' +
        '<span style="font-weight: 500;">' + item.width + '×' + item.height + '</span>' +
        '<span style="font-size: 11px; color: var(--accent);">' + item.aspectRatio + '</span>' +
        defaultBadge +
      '</label>' +
      '<div style="display: flex; align-items: center; gap: 6px;">' +
        '<button title="Set as default startup resolution" onclick="setDefaultRes(\'' + item.key + '\')" style="background:none; border:none; color:' + starColor + '; cursor:pointer; font-size:16px; padding:0 4px;">' + starIcon + '</button>' +
        deleteBtn +
      '</div>' +
    '</div>';
  }).join('');
}

function toggleRes(key, isSel) {
  postAction('toggleResolution', { resolution: key, selected: isSel });
  setTimeout(fetchStatus, 150);
}

function setDefaultRes(key) {
  postAction('setDefaultResolution', { resolution: key });
  setTimeout(fetchStatus, 150);
}

function addCustomRes() {
  const w = parseInt(document.getElementById('inp-custom-w').value);
  const h = parseInt(document.getElementById('inp-custom-h').value);
  if (w > 0 && h > 0) {
    postAction('addCustomResolution', { width: w, height: h });
    document.getElementById('inp-custom-w').value = '';
    document.getElementById('inp-custom-h').value = '';
    setTimeout(fetchStatus, 200);
  }
}

const RES_TIERS = ['max_240p', 'max_360p', 'max_480p', 'max_720p', 'max_1080p', 'max_1440p', 'max_4k'];
const RES_LABELS = ['240p', '360p', '480p', '720p', '1080p', '1440p', '2160p (4K)'];

function onMaxResPreview(idx) {
  const i = Math.max(0, Math.min(RES_TIERS.length - 1, parseInt(idx) || 0));
  document.getElementById('lbl-res-profile').innerText = 'Max Resolution: ' + RES_LABELS[i];
}

function onMaxResSlider(idx) {
  const i = Math.max(0, Math.min(RES_TIERS.length - 1, parseInt(idx) || 0));
  onMaxResPreview(i);
  setResProfile(RES_TIERS[i]);
}

function getFilteredResolutionItems() {
  const data = window.lastResData;
  if (!data || !data.availableResolutions) return [];
  const filter = document.getElementById('sel-aspect-filter').value;
  return data.availableResolutions.filter(item => {
    if (filter === 'All') return true;
    if (filter === '16:9') return item.aspectRatio === '16:9';
    if (filter === '9:16') return item.aspectRatio === '9:16';
    if (filter === '4:3') return item.aspectRatio === '4:3' || item.aspectRatio === '3:4';
    if (filter === '1:1') return item.aspectRatio === '1:1';
    return !['16:9', '9:16', '4:3', '3:4', '1:1'].includes(item.aspectRatio);
  });
}

function selectAllFilteredRes() {
  const keys = getFilteredResolutionItems().map(i => i.key);
  postAction('selectAllResolutions', { resolutions: keys });
  setTimeout(fetchStatus, 150);
}

function deselectAllFilteredRes() {
  const keys = getFilteredResolutionItems().map(i => i.key);
  postAction('deselectAllResolutions', { resolutions: keys });
  setTimeout(fetchStatus, 150);
}

function removeCustomRes(key) {
  postAction('removeCustomResolution', { resolution: key });
  setTimeout(fetchStatus, 150);
}

function toggleWebcam() {
  if (currentStatus && currentStatus.isUvcLoopRunning) {
    postAction('stop');
  } else {
    postAction('start');
  }
}

function recenterPan() {
  document.getElementById('rng-panx').value = 0.0;
  document.getElementById('rng-pany').value = 0.0;
  document.getElementById('lbl-panx').innerText = '0%';
  document.getElementById('lbl-pany').innerText = '0%';
  postAction('recenter');
}

function resetIsp() {
  postAction('resetIsp');
}

function setResProfile(p) {
  postSettings({ resolutionProfile: p });
  setTimeout(fetchStatus, 150);
}

function onZoomInput(val) {
  markInteracting();
  document.getElementById('lbl-zoom').innerText = parseFloat(val).toFixed(1) + 'x';
  debouncedPostSettings({ zoomRatio: parseFloat(val) });
}

function setZoomVal(val) {
  markInteracting();
  document.getElementById('rng-zoom').value = val;
  document.getElementById('lbl-zoom').innerText = parseFloat(val).toFixed(1) + 'x';
  postSettings({ zoomRatio: parseFloat(val) });
}

function onPanXInput(val) {
  markInteracting();
  const pct = Math.round(val * 100);
  document.getElementById('lbl-panx').innerText = (pct > 0 ? '+' : '') + pct + '%';
  debouncedPostSettings({ panX: parseFloat(val) });
}

function onPanYInput(val) {
  markInteracting();
  const pct = Math.round(val * 100);
  document.getElementById('lbl-pany').innerText = (pct > 0 ? '+' : '') + pct + '%';
  debouncedPostSettings({ panY: parseFloat(val) });
}

function stepPan(dx, dy) {
  markInteracting();
  let curX = parseFloat(document.getElementById('rng-panx').value) || 0;
  let curY = parseFloat(document.getElementById('rng-pany').value) || 0;
  curX = Math.max(-1.0, Math.min(1.0, curX + dx));
  curY = Math.max(-1.0, Math.min(1.0, curY + dy));
  document.getElementById('rng-panx').value = curX;
  document.getElementById('rng-pany').value = curY;
  onPanXInput(curX);
  onPanYInput(curY);
}

function onQualityInput(val) {
  markInteracting();
  document.getElementById('lbl-quality').innerText = val + '%';
  debouncedPostSettings({ jpegQuality: parseInt(val) });
}

function onEvInput(val) {
  markInteracting();
  const f = parseFloat(val);
  document.getElementById('lbl-ev').innerText = (f > 0 ? '+' : '') + f.toFixed(1) + ' EV';
  debouncedPostSettings({ exposureEv: f });
}

function setRotation(deg) {
  postSettings({ rotation: deg });
}

function setFpsCap(cap) {
  postSettings({ targetFpsCap: cap });
}

function setBufDepth(d) {
  postSettings({ maxInFlightBuffers: d });
}

function markInteracting() {
  isDragging = true;
  clearTimeout(dragTimeout);
  dragTimeout = setTimeout(() => { isDragging = false; }, 1200);
}

function fetchStatus() {
  fetch('/api/status')
    .then(r => r.json())
    .then(data => {
      currentStatus = data;
      updateUi(data);
    })
    .catch(console.error);
}

function updateUi(data) {
  // Top status badge & button
  const badge = document.getElementById('badge-status');
  const toggleBtn = document.getElementById('btn-toggle-webcam');
  if (data.isHostStreaming) {
    badge.className = 'badge streaming';
    badge.innerText = 'Streaming';
    toggleBtn.className = 'btn-danger';
    toggleBtn.innerText = 'Stop Webcam';
  } else if (data.isUvcLoopRunning) {
    badge.className = 'badge ready';
    badge.innerText = 'Gadget Ready';
    toggleBtn.className = 'btn-danger';
    toggleBtn.innerText = 'Stop Webcam';
  } else {
    badge.className = 'badge idle';
    badge.innerText = 'Idle';
    toggleBtn.className = 'btn-primary';
    toggleBtn.innerText = 'Start Webcam';
  }

  const restartBtn = document.getElementById('btn-restart-webcam');
  if (restartBtn) {
    restartBtn.style.display = data.isUvcLoopRunning ? 'inline-block' : 'none';
  }

  // Resolution profile slider
  const tierIdx = RES_TIERS.indexOf(data.resolutionProfile);
  const rngMaxRes = document.getElementById('rng-max-res');
  const lblResProfile = document.getElementById('lbl-res-profile');
  if (tierIdx >= 0) {
    if (rngMaxRes && !isDragging) rngMaxRes.value = tierIdx;
    if (lblResProfile) lblResProfile.innerText = 'Max Resolution: ' + RES_LABELS[tierIdx];
  } else {
    if (lblResProfile) lblResProfile.innerText = 'Exposed Resolutions: Custom Set';
  }

  // Update resolution set count and default
  if (data.selectedResolutions) {
    const countEl = document.getElementById('res-active-count');
    const defEl = document.getElementById('res-active-default');
    if (countEl) countEl.innerText = data.selectedResolutions.length;
    if (defEl) defEl.innerText = data.defaultResolution || '-';
    window.lastResData = data;
    renderResolutionList();
  }

  // Telemetry
  document.getElementById('txt-fps-pill').innerText = data.measuredFps.toFixed(1) + ' FPS';
  document.getElementById('stat-mode').innerText = data.format;
  document.getElementById('stat-res').innerText = data.width + 'x' + data.height + ' @ ' + data.fps + 'fps';
  document.getElementById('stat-fps').innerText = data.measuredFps.toFixed(1) + ' fps';
  document.getElementById('stat-lat').innerText = data.avgEncodeTimeMs.toFixed(1) + ' ms';
  document.getElementById('stat-size').innerText = data.avgFrameSizeKb.toFixed(1) + ' KB';
  document.getElementById('stat-pkt').innerText = data.boundMaxPacket + ' B';
  document.getElementById('stat-node').innerText = data.videoNodePath || 'None';

  if (isDragging) return; // Do not overwrite sliders while user is manipulating them

  // Sliders & Zoom
  const rngZoom = document.getElementById('rng-zoom');
  rngZoom.max = Math.max(4.0, data.maxZoom || 4.0);
  rngZoom.value = data.zoomRatio;
  document.getElementById('lbl-zoom').innerText = data.zoomRatio.toFixed(1) + 'x';

  document.getElementById('rng-panx').value = data.panX;
  const pctX = Math.round(data.panX * 100);
  document.getElementById('lbl-panx').innerText = (pctX > 0 ? '+' : '') + pctX + '%';

  document.getElementById('rng-pany').value = data.panY;
  const pctY = Math.round(data.panY * 100);
  document.getElementById('lbl-pany').innerText = (pctY > 0 ? '+' : '') + pctY + '%';

  // Lens buttons
  const lensesContainer = document.getElementById('cam-lenses-container');
  lensesContainer.innerHTML = '';
  let activeLensHasFlash = false;
  (data.availableCameras || []).forEach(cam => {
    const btn = document.createElement('button');
    btn.className = 'pill-btn' + (cam.id === data.selectedCameraId ? ' active' : '');
    btn.innerText = cam.label;
    btn.onclick = () => postSettings({ cameraId: cam.id });
    lensesContainer.appendChild(btn);
    if (cam.id === data.selectedCameraId && cam.hasFlash) {
      activeLensHasFlash = true;
    }
  });

  // Rotation pills
  [0, 90, 180, 270].forEach(deg => {
    const btn = document.getElementById('rot-' + deg);
    if (btn) btn.className = 'pill-btn' + (data.rotation === deg ? ' active' : '');
  });

  document.getElementById('chk-mirror').checked = !!data.mirror;

  document.getElementById('rng-quality').value = data.jpegQuality;
  document.getElementById('lbl-quality').innerText = data.jpegQuality + '%';

  // FPS cap pills
  [0, 60, 30, 24, 15, 10, 5].forEach(cap => {
    const btn = document.getElementById('fpscap-' + cap);
    if (btn) btn.className = 'pill-btn' + (data.targetFpsCap === cap ? ' active' : '');
  });

  // V4L2 queue depth pills
  [1, 2, 3].forEach(d => {
    const btn = document.getElementById('buf-' + d);
    if (btn) btn.className = 'pill-btn' + (data.maxInFlightBuffers === d ? ' active' : '');
  });

  // ISP toggles
  document.getElementById('chk-af').checked = !!data.autoFocus;
  document.getElementById('chk-constfps').checked = !!data.lockConstantFps;
  document.getElementById('chk-eis').checked = !!data.videoStabilization;
  document.getElementById('chk-aelock').checked = !!data.aeLock;
  document.getElementById('chk-studio').checked = !!data.studioQuality;
  document.getElementById('chk-awblock').checked = !!data.awbLock;
  const chkPtz = document.getElementById('chk-native-ptz');
  if (chkPtz) chkPtz.checked = !!data.useNativeCamera2Ptz;

  document.getElementById('rng-ev').value = data.exposureEv;
  const evVal = data.exposureEv;
  document.getElementById('lbl-ev').innerText = (evVal > 0 ? '+' : '') + evVal.toFixed(1) + ' EV';

  document.getElementById('sel-awb').value = data.awbMode;
  document.getElementById('sel-ab').value = data.antiBandingMode;

  const torchRow = document.getElementById('row-torch');
  if (activeLensHasFlash) {
    torchRow.style.display = 'flex';
    document.getElementById('chk-torch').checked = !!data.torch;
  } else {
    torchRow.style.display = 'none';
  }
}

// Auto refresh every 750ms
fetchStatus();
setInterval(fetchStatus, 750);
