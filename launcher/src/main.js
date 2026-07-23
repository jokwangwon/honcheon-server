// 혼천 런처 — main 프로세스 (공식 런처 재활용 방식 · 2026-07-23 사용자 확정)
//
// Prism 을 걷어냈다. 대신 **공식 마인크래프트에 혼천을 프로필 하나로 얹는다**:
//   ① 공식 .minecraft 를 찾는다 (이미 로그인돼 있다 — 재로그인 없음)
//   ② Fabric 로더 프로필을 versions/ 에 설치 (공식 런처가 라이브러리를 알아서 받는다)
//   ③ 별도 게임폴더 .minecraft/honcheon/ 로 격리 — 기존 모드는 손도 안 댄다
//   ④ options.txt(설정)를 이어받는다 · 혼천 모드·셰이더를 예의 동기화
//   ⑤ launcher_profiles.json 에 「혼천」 프로필 추가 → 공식 런처로 바로 플레이
//
// 핀은 launcher_manifest.json(릴리스) 이 정본. 로그인·자바는 공식 런처가 맡는다 (자작 없음).

const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const https = require('https');
const { spawn } = require('child_process');

const REL = 'https://github.com/jokwangwon/honcheon-pack/releases/download/pack';
const FABRIC_META = 'https://meta.fabricmc.net/v2';
const APPDATA = process.env.APPDATA || path.join(process.env.USERPROFILE || '', 'AppData', 'Roaming');
const DOTMC = path.join(APPDATA, '.minecraft');
const GAMEDIR = path.join(DOTMC, 'honcheon');
const MANIFEST_TXT = path.join(GAMEDIR, 'honcheon_manifest.txt');

let win;

function createWindow() {
  win = new BrowserWindow({
    width: 900, height: 560, resizable: false, frame: false,
    backgroundColor: '#16161a',
    webPreferences: { preload: path.join(__dirname, 'preload.js') },
  });
  win.loadFile(path.join(__dirname, 'index.html'));
}

function download(url, dest, onProgress) {
  return new Promise((resolve, reject) => {
    const file = fs.createWriteStream(dest);
    const get = (u) => https.get(u, { headers: { 'User-Agent': 'honcheon-launcher' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.destroy(); return get(res.headers.location);
      }
      if (res.statusCode !== 200) { reject(new Error(`HTTP ${res.statusCode} — ${url}`)); return; }
      const total = parseInt(res.headers['content-length'] || '0', 10);
      let got = 0;
      res.on('data', (c) => { got += c.length; if (total && onProgress) onProgress(got / total); });
      res.pipe(file);
      file.on('finish', () => file.close(resolve));
    }).on('error', reject);
    get(url);
  });
}

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'User-Agent': 'honcheon-launcher' } }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.destroy(); return fetchJson(res.headers.location).then(resolve, reject);
      }
      let body = '';
      res.on('data', (d) => (body += d));
      res.on('end', () => { try { resolve(JSON.parse(body)); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
}

function say(phase, text, pct) {
  if (win && !win.isDestroyed()) win.webContents.send('progress', { phase, text, pct });
}

// ① 공식 마인크래프트를 찾는다
function findDotMinecraft() {
  if (fs.existsSync(DOTMC)) return DOTMC;
  return null;   // 없으면 설치 단계에서 안내한다 (공식 마크를 먼저 깔아야 로그인이 산다)
}

// ② Fabric 프로필 설치 — 공식 런처가 읽을 versions/<id>/<id>.json
async function ensureFabric(manifest) {
  say('fabric', 'Fabric 준비 중...', 0);
  const loader = manifest.fabric_loader; // 매니페스트가 핀한다
  const prof = await fetchJson(`${FABRIC_META}/versions/loader/${manifest.minecraft}/${loader}/profile/json`);
  const id = prof.id; // fabric-loader-0.19.3-1.21.11
  const dir = path.join(DOTMC, 'versions', id);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `${id}.json`), JSON.stringify(prof));
  return id;
}

// ③④ 게임폴더 격리 + 설정 이어받기 + 예의 동기화
async function syncGame(manifest) {
  fs.mkdirSync(path.join(GAMEDIR, 'mods'), { recursive: true });
  fs.mkdirSync(path.join(GAMEDIR, 'shaderpacks'), { recursive: true });
  fs.mkdirSync(path.join(GAMEDIR, 'config'), { recursive: true });

  // 설정 이어받기 — 기존 .minecraft/options.txt 를 첫 설치 때 한 번 복사 (키설정·화면)
  const srcOpt = path.join(DOTMC, 'options.txt');
  const dstOpt = path.join(GAMEDIR, 'options.txt');
  if (fs.existsSync(srcOpt) && !fs.existsSync(dstOpt)) {
    fs.copyFileSync(srcOpt, dstOpt);
    say('settings', '기존 설정을 이어받았습니다', 1);
  }

  // 기존 모드 탐지 — 손대지 않는다 (게임폴더 격리라 서로 안 섞인다). 봤다는 것만 알린다
  const foreignMods = path.join(DOTMC, 'mods');
  if (fs.existsSync(foreignMods)) {
    const n = fs.readdirSync(foreignMods).filter((f) => f.endsWith('.jar')).length;
    if (n > 0) say('detect', `기존 모드 ${n}개를 확인 — 건드리지 않고 혼천만 따로 설치합니다`, 0);
  }

  // ★예의 동기화 — 우리 장부에 있던 것 중 핀에서 빠진 것만 지운다
  const keep = new Set([...manifest.mods.map((m) => m.file), ...manifest.shaders.map((s) => s.file)]);
  const ours = fs.existsSync(MANIFEST_TXT)
    ? fs.readFileSync(MANIFEST_TXT, 'utf8').split('\n').map((s) => s.trim()).filter(Boolean) : [];
  for (const sub of ['mods', 'shaderpacks']) {
    for (const f of fs.readdirSync(path.join(GAMEDIR, sub))) {
      if (ours.includes(f) && !keep.has(f)) fs.rmSync(path.join(GAMEDIR, sub, f), { force: true });
    }
  }
  const all = [...manifest.mods.map((m) => ['mods', m]), ...manifest.shaders.map((s) => ['shaderpacks', s])];
  let i = 0;
  for (const [sub, item] of all) {
    i++;
    const dest = path.join(GAMEDIR, sub, item.file);
    if (!fs.existsSync(dest)) {
      say('mods', `내려받는 중 — ${item.file}`, 0);
      await download(item.url, dest, (p) => say('mods', `내려받는 중 — ${item.file}`, p));
    }
    say('mods', `모드 맞추는 중 (${i}/${all.length})`, i / all.length);
  }
  fs.writeFileSync(MANIFEST_TXT, [...keep].join('\n'));
  if (manifest.shaders[0]) {
    fs.writeFileSync(path.join(GAMEDIR, 'config', 'iris.properties'),
      `enableShaders=true\nshaderPack=${manifest.shaders[0].file}\n`);
  }
}

// ⑤ 공식 런처에 「혼천」 프로필 추가 (기존 프로필·설정은 보존)
function writeProfile(versionId) {
  const pf = path.join(DOTMC, 'launcher_profiles.json');
  let root = { profiles: {}, settings: {}, version: 3 };
  if (fs.existsSync(pf)) {
    try { root = JSON.parse(fs.readFileSync(pf, 'utf8')); } catch (e) { /* 깨졌으면 새로 */ }
  }
  if (!root.profiles) root.profiles = {};
  const now = new Date().toISOString();
  const seal = loadSealDataUri();
  root.profiles.honcheon = {
    name: '혼천',
    type: 'custom',
    created: (root.profiles.honcheon && root.profiles.honcheon.created) || now,
    lastUsed: now,
    lastVersionId: versionId,
    gameDir: GAMEDIR,
    icon: seal || 'Furnace',
    javaArgs: '-Xmx4G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC',
  };
  fs.writeFileSync(pf, JSON.stringify(root, null, 2));
}

function loadSealDataUri() {
  try {
    const p = path.join(__dirname, 'honcheon.ico');
    if (fs.existsSync(p)) return 'data:image/x-icon;base64,' + fs.readFileSync(p).toString('base64');
  } catch (e) { /* 아이콘 없으면 기본 */ }
  return null;
}

async function runInstall() {
  try {
    say('start', '준비하는 중...', 0);
    if (!findDotMinecraft()) {
      say('error', '공식 마인크래프트를 찾지 못했습니다 — 먼저 설치·로그인한 뒤 다시 시도해 주세요.', 0);
      return { ok: false };
    }
    const manifest = await fetchJson(`${REL}/launcher_manifest.json`);
    const versionId = await ensureFabric(manifest);
    await syncGame(manifest);
    writeProfile(versionId);
    say('done', '준비 완료 — 「시작」을 누르면 공식 런처에서 혼천을 플레이합니다', 1);
    return { ok: true };
  } catch (e) {
    say('error', `문제가 생겼습니다: ${e.message}`, 0);
    return { ok: false, error: e.message };
  }
}

// 공식 런처를 연다 — 사용자는 「혼천」을 골라 Play (이미 로그인돼 있다)
function launchOfficial() {
  const pf = process.env['ProgramFiles(x86)'] || 'C:\\Program Files (x86)';
  const candidates = [
    path.join(pf, 'Minecraft Launcher', 'MinecraftLauncher.exe'),
    path.join(pf, 'Minecraft', 'MinecraftLauncher.exe'),
    path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Minecraft Launcher', 'MinecraftLauncher.exe'),
  ];
  for (const exe of candidates) {
    if (fs.existsSync(exe)) { spawn(exe, [], { detached: true, stdio: 'ignore' }).unref(); return true; }
  }
  // 스탠드얼론이 없으면 MS 스토어판을 프로토콜로 (실패해도 사람이 직접 열 수 있다)
  shell.openExternal('minecraft://').catch(() => {});
  return false;
}

app.whenReady().then(() => {
  createWindow();
  ipcMain.handle('install', runInstall);
  ipcMain.handle('launch', () => { const ok = launchOfficial(); return { ok }; });
  ipcMain.handle('close', () => app.quit());
  ipcMain.handle('minimize', () => win.minimize());
  ipcMain.handle('openFolder', () => shell.openPath(GAMEDIR));
});

app.on('window-all-closed', () => app.quit());
