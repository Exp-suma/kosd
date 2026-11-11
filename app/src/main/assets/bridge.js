// WebView では blob:/data: URL のダウンロード（CSV・JSON・PDF の書き出し）が動かないため、
// ダウンロード用リンクのクリックを横取りして中身を Android 側 (NBPAndroid) に渡す。
(function () {
  if (window.__nbpBridge || !window.NBPAndroid) return;
  window.__nbpBridge = true;

  // サイト側はクリック直後に revokeObjectURL するため、読み出し終わるまで解放を遅らせる
  var origRevoke = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = function (u) { setTimeout(function () { origRevoke(u); }, 60000); };

  function save(href, name) {
    fetch(href).then(function (r) { return r.blob(); }).then(function (b) {
      var fr = new FileReader();
      fr.onloadend = function () {
        var s = String(fr.result), i = s.indexOf(',');
        NBPAndroid.saveFile(s.slice(i + 1), name || 'download', b.type || 'application/octet-stream');
      };
      fr.readAsDataURL(b);
    }).catch(function (e) { NBPAndroid.saveFailed(String(e)); });
  }

  function handle(a) {
    if (!a || !a.hasAttribute || !a.hasAttribute('download')) return false;
    var h = a.href || '';
    if (!/^(blob:|data:)/.test(h)) return false;
    save(h, a.getAttribute('download'));
    return true;
  }

  // a.click()（DOM に追加しないリンクでも呼ばれる）
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (handle(this)) return;
    return origClick.call(this);
  };
  // a.dispatchEvent(new MouseEvent('click'))（jsPDF などの保存処理）
  var origDispatch = HTMLAnchorElement.prototype.dispatchEvent;
  HTMLAnchorElement.prototype.dispatchEvent = function (ev) {
    if (ev && ev.type === 'click' && handle(this)) return false;
    return origDispatch.call(this, ev);
  };
  // 利用者が直接タップしたダウンロードリンク
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (a && handle(a)) { e.preventDefault(); e.stopPropagation(); }
  }, true);
})();
