// バスロケ監視 Windows デスクトップ版。
// Web 版（Base44）を専用ウィンドウで開く。ログイン状態は端末に保存され、外部リンクは既定のブラウザで開く。
const { app, BrowserWindow, Menu, shell, session, dialog } = require("electron");
const path = require("path");

const APP_URL = process.env.NARA_BUS_URL || "https://nara-bus-pulse.base44.app/";
const APP_HOST = new URL(APP_URL).host;
// アプリ内で開いてよい接続先（本体と Base44 のログイン画面）。それ以外は既定のブラウザへ渡す。
const isInternal = (url) => {
  try {
    const host = new URL(url).host;
    return host === APP_HOST || host.endsWith(".base44.app") || host.endsWith(".base44.com") || host === "accounts.google.com";
  } catch {
    return false;
  }
};

let win = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1360,
    height: 880,
    minWidth: 420,
    minHeight: 560,
    title: "バスロケ監視",
    backgroundColor: "#f1f3f4",
    autoHideMenuBar: false,
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, partition: "persist:nara-bus-pulse" },
  });

  const ses = win.webContents.session;
  // 現在地・通知・カメラ（露出計）・USB（カメラ接続）は本体のページにだけ許可する
  const allowed = new Set(["geolocation", "notifications", "media", "clipboard-sanitized-write", "fullscreen", "usb"]);
  ses.setPermissionRequestHandler((wc, permission, callback, details) => callback(isInternal(details.requestingUrl || wc.getURL()) && allowed.has(permission)));
  ses.setPermissionCheckHandler((wc, permission, origin) => isInternal(origin || "") && allowed.has(permission));
  // WebUSB：カメラ統合コンソールの「カメラに接続」で、つながっている静止画機器（PTP）を選ぶ
  ses.on("select-usb-device", (event, details, callback) => {
    event.preventDefault();
    const list = details.deviceList || [];
    if (!list.length) return callback();
    if (list.length === 1) return callback(list[0].deviceId);
    const index = dialog.showMessageBoxSync(win, {
      type: "question",
      title: "USB機器の選択",
      message: "接続するカメラを選んでください",
      buttons: [...list.map((d) => d.productName || `USB ${d.vendorId}:${d.productId}`), "キャンセル"],
      cancelId: list.length,
    });
    callback(index < list.length ? list[index].deviceId : undefined);
  });
  ses.setUSBProtectedClassesHandler(() => []);

  win.webContents.setWindowOpenHandler(({ url }) => {
    if (isInternal(url)) return { action: "allow" };
    shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("will-navigate", (event, url) => {
    if (!isInternal(url)) {
      event.preventDefault();
      shell.openExternal(url);
    }
  });
  // 写真・CSV・RAW などの保存は「名前を付けて保存」で場所を選べるようにする
  ses.on("will-download", (event, item) => item.setSaveDialogOptions({ defaultPath: path.join(app.getPath("downloads"), item.getFilename()) }));
  win.webContents.on("did-fail-load", (event, code, description, url, isMainFrame) => {
    if (isMainFrame && code !== -3) win.loadFile(path.join(__dirname, "offline.html"), { query: { reason: description || String(code) } });
  });
  win.on("page-title-updated", (event) => event.preventDefault());
  win.loadURL(APP_URL);
}

const menu = Menu.buildFromTemplate([
  {
    label: "ファイル",
    submenu: [
      { label: "ホームへ戻る", accelerator: "Alt+Home", click: () => win && win.loadURL(APP_URL) },
      { label: "再読み込み", accelerator: "F5", click: () => win && win.webContents.reload() },
      { label: "印刷…", accelerator: "Ctrl+P", click: () => win && win.webContents.print() },
      { type: "separator" },
      { label: "終了", role: "quit" },
    ],
  },
  { label: "編集", submenu: [{ label: "元に戻す", role: "undo" }, { label: "やり直す", role: "redo" }, { type: "separator" }, { label: "切り取り", role: "cut" }, { label: "コピー", role: "copy" }, { label: "貼り付け", role: "paste" }, { label: "すべて選択", role: "selectAll" }] },
  {
    label: "表示",
    submenu: [
      { label: "戻る", accelerator: "Alt+Left", click: () => win && win.webContents.navigationHistory.canGoBack() && win.webContents.navigationHistory.goBack() },
      { label: "進む", accelerator: "Alt+Right", click: () => win && win.webContents.navigationHistory.canGoForward() && win.webContents.navigationHistory.goForward() },
      { type: "separator" },
      { label: "拡大", role: "zoomIn" },
      { label: "縮小", role: "zoomOut" },
      { label: "実際のサイズ", role: "resetZoom" },
      { label: "全画面表示", role: "togglefullscreen" },
    ],
  },
  {
    label: "ヘルプ",
    submenu: [
      { label: "ブラウザで開く", click: () => shell.openExternal(APP_URL) },
      { label: "保存データを消去して再起動（ログアウト）", click: async () => { await session.fromPartition("persist:nara-bus-pulse").clearStorageData(); app.relaunch(); app.exit(0); } },
      { label: `バージョン ${app.getVersion()}`, enabled: false },
    ],
  },
]);

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (!win) return;
    if (win.isMinimized()) win.restore();
    win.focus();
  });
  app.whenReady().then(() => {
    Menu.setApplicationMenu(menu);
    createWindow();
  });
  app.on("window-all-closed", () => app.quit());
}
