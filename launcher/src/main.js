// 혼천 런처 — main 프로세스
//
// 얼굴은 수묵(renderer), 엔진은 Prism Launcher(포터블)다. 런처가 하는 일:
//   ① Prism 포터블 + Java 자동 확보  ② 모드·셰이더 예의 동기화(장부 대조)
//   ③ 「시작」 → Prism 이 인증·게임 실행을 맡는다 (MC 인증 자작을 피하는 현실적 경로)
//
// 핀은 config/modpack.yml 에서 굽는 manifest.json 이 정본이다 (tools/build_launcher_manifest.py).
// 런처는 그 manifest 를 릴리스에서 받아 읽는다 — 코드가 버전을 지어내지 않는다.

const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const https = require('https');
const { spawn } = require('child_process');
const { execFile } = require('child_process');

const REL = 'https://github.com/jokwangwon/honcheon-pack/releases/download/pack';
const ROOT = path.join(process.env.LOCALAPPDATA || app.getPath('userData'), 'Honcheon');
const PRISM = path.join(ROOT, 'prism');
const INST = path.join(PRISM, 'instances', 'honcheon');
const MC = path.join(INST, '.minecraft');
const MANIFEST = path.join(INST, 'honcheon_manifest.txt');

let win;

function createWindow() {
  win = new BrowserWindow({
    width: 900, height: 560, resizable: false, frame: false, transparent: false,
    backgroundColor: '#16161a',
    webPreferences: { preload: path.join(__dirname, 'preload.js') },
  });
  win.loadFile(path.join(__dirname, 'index.html'));
}

// ── 유틸: 다운로드(진행 보고) ──
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

// ── 설치·동기화 파이프라인 ──
async function ensurePrism() {
  fs.mkdirSync(PRISM, { recursive: true });
  const exe = path.join(PRISM, 'prismlauncher.exe');
  if (!fs.existsSync(exe)) {
    say('prism', 'Prism Launcher 내려받는 중...', 0);
    const rel = await fetchJson('https://api.github.com/repos/PrismLauncher/PrismLauncher/releases/latest');
    const asset = rel.assets.find((a) => a.name.includes('Windows-MSVC-Portable')
      && a.name.endsWith('.zip') && !a.name.toLowerCase().includes('arm64'));
    const zip = path.join(ROOT, 'prism.zip');
    await download(asset.browser_download_url, zip, (p) => say('prism', 'Prism Launcher 내려받는 중...', p));
    say('prism', 'Prism 푸는 중...', 1);
    await extractZip(zip, PRISM);
    fs.rmSync(zip, { force: true });
  }
  fs.writeFileSync(path.join(PRISM, 'portable.txt'), '');
  fs.writeFileSync(path.join(PRISM, 'prismlauncher.cfg'),
    '[General]\nLanguage=ko_KR\nAutomaticJavaDownload=true\nAutomaticJavaSwitch=true\n');
}

function extractZip(zip, dest) {
  // 윈도우 내장 tar 로 푼다 (외부 의존 없음 · Win10+ 기본 탑재)
  return new Promise((resolve, reject) => {
    execFile('tar', ['-xf', zip, '-C', dest], (err) => (err ? reject(err) : resolve()));
  });
}

async function syncMods(manifest) {
  fs.mkdirSync(path.join(MC, 'mods'), { recursive: true });
  fs.mkdirSync(path.join(MC, 'shaderpacks'), { recursive: true });
  fs.mkdirSync(path.join(MC, 'config'), { recursive: true });
  // 인스턴스 정의
  fs.writeFileSync(path.join(INST, 'instance.cfg'),
    '[General]\nConfigVersion=1.2\nInstanceType=OneSix\nname=혼천\niconKey=honcheon\n');
  fs.writeFileSync(path.join(INST, 'mmc-pack.json'), JSON.stringify(manifest.mmc));

  // ★예의 동기화 — 우리 장부에 있던 것 중 핀에서 빠진 것만 지운다 (사용자 모드는 보존)
  const keep = new Set([...manifest.mods.map((m) => m.file), ...manifest.shaders.map((s) => s.file)]);
  const ours = fs.existsSync(MANIFEST)
    ? fs.readFileSync(MANIFEST, 'utf8').split('\n').map((s) => s.trim()).filter(Boolean) : [];
  for (const dir of ['mods', 'shaderpacks']) {
    for (const f of fs.readdirSync(path.join(MC, dir))) {
      if (ours.includes(f) && !keep.has(f)) fs.rmSync(path.join(MC, dir, f), { force: true });
    }
  }
  // 없는 것만 받는다
  const all = [...manifest.mods.map((m) => ['mods', m]), ...manifest.shaders.map((s) => ['shaderpacks', s])];
  let i = 0;
  for (const [dir, item] of all) {
    i++;
    const dest = path.join(MC, dir, item.file);
    if (!fs.existsSync(dest)) {
      say('mods', `내려받는 중 — ${item.file}`, 0);
      await download(item.url, dest, (p) => say('mods', `내려받는 중 — ${item.file}`, p));
    }
    say('mods', `맞추는 중 (${i}/${all.length})`, i / all.length);
  }
  fs.writeFileSync(MANIFEST, [...keep].join('\n'));
  if (manifest.shaders[0]) {
    fs.writeFileSync(path.join(MC, 'config', 'iris.properties'),
      `enableShaders=true\nshaderPack=${manifest.shaders[0].file}\n`);
  }
}

async function runInstall() {
  try {
    say('start', '준비하는 중...', 0);
    fs.mkdirSync(ROOT, { recursive: true });
    // 창의 옷 — 배경 그림을 renderer 가 이미 그린다 (자산 동봉). manifest 받기
    const manifest = await fetchJson(`${REL}/launcher_manifest.json`);
    await ensurePrism();
    await syncMods(manifest);
    say('done', '준비 완료 — 시작할 수 있습니다', 1);
    return { ok: true };
  } catch (e) {
    say('error', `문제가 생겼습니다: ${e.message}`, 0);
    return { ok: false, error: e.message };
  }
}

function launchGame() {
  const exe = path.join(PRISM, 'prismlauncher.exe');
  const child = spawn(exe, ['-l', 'honcheon'], { cwd: PRISM, detached: true, stdio: 'ignore' });
  child.unref();
}

app.whenReady().then(() => {
  createWindow();
  ipcMain.handle('install', runInstall);
  ipcMain.handle('launch', () => { launchGame(); return true; });
  ipcMain.handle('close', () => app.quit());
  ipcMain.handle('minimize', () => win.minimize());
  ipcMain.handle('openFolder', () => shell.openPath(ROOT));
});

app.on('window-all-closed', () => app.quit());
