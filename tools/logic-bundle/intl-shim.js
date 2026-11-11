/*
 * QuickJS には Intl が無いため、logic.js が使う範囲だけを補う最小の実装。
 * タイムゾーンは Asia/Tokyo（UTC+9・夏時間なし）と UTC のみ対応。
 * 本物の Intl がある環境では何もしない。
 */
(function () {
  if (typeof Intl !== "undefined" && Intl.DateTimeFormat) return;
  var WD_JA = ["日", "月", "火", "水", "木", "金", "土"];
  var WD_EN = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  var pad = function (n) { return (n < 10 ? "0" : "") + n; };
  var offsetOf = function (tz) { return tz === "UTC" || tz === "Etc/UTC" ? 0 : 9 * 60; };

  function fields(date, tz) {
    var t = (date instanceof Date ? date.getTime() : new Date(date === undefined ? Date.now() : date).getTime());
    var d = new Date(t + offsetOf(tz) * 60000);
    return { y: d.getUTCFullYear(), mo: d.getUTCMonth() + 1, d: d.getUTCDate(), h: d.getUTCHours(), mi: d.getUTCMinutes(), s: d.getUTCSeconds(), wd: d.getUTCDay() };
  }

  function DateTimeFormat(locale, options) {
    if (!(this instanceof DateTimeFormat)) return new DateTimeFormat(locale, options);
    this.locale = String(locale || "ja-JP");
    this.o = options || {};
    this.tz = this.o.timeZone || "Asia/Tokyo";
  }

  DateTimeFormat.prototype.formatToParts = function (date) {
    var f = fields(date, this.tz), o = this.o, ja = /^ja/.test(this.locale), parts = [];
    var push = function (type, value) { parts.push({ type: type, value: String(value) }); };
    var lit = function (v) { parts.push({ type: "literal", value: v }); };
    var two = function (key, v) { return o[key] === "2-digit" ? pad(v) : String(v); };
    var hasDate = o.year || o.month || o.day, hasTime = o.hour || o.minute || o.second;
    if (o.weekday && !hasDate && !hasTime) { push("weekday", ja ? WD_JA[f.wd] : WD_EN[f.wd]); return parts; }
    if (hasDate) {
      if (/^en-CA/.test(this.locale)) {
        if (o.year) { push("year", f.y); lit("-"); }
        if (o.month) { push("month", two("month", f.mo)); if (o.day) lit("-"); }
        if (o.day) push("day", two("day", f.d));
      } else {
        if (o.year) { push("year", f.y); lit("/"); }
        if (o.month) { push("month", two("month", f.mo)); if (o.day) lit("/"); }
        if (o.day) push("day", two("day", f.d));
      }
      if (hasTime) lit(" ");
    }
    if (hasTime) {
      if (o.hour) push("hour", o.hour === "2-digit" ? pad(f.h) : f.h);
      if (o.minute) { lit(":"); push("minute", pad(f.mi)); }
      if (o.second) { lit(":"); push("second", pad(f.s)); }
    }
    return parts;
  };

  DateTimeFormat.prototype.format = function (date) {
    return this.formatToParts(date).map(function (p) { return p.value; }).join("");
  };

  DateTimeFormat.prototype.resolvedOptions = function () {
    return { locale: this.locale, timeZone: this.tz };
  };

  function NumberFormat(locale, options) {
    if (!(this instanceof NumberFormat)) return new NumberFormat(locale, options);
    this.o = options || {};
  }
  NumberFormat.prototype.format = function (n) { return groupNumber(n, this.o); };

  function groupNumber(n, o) {
    if (typeof n !== "number" || !isFinite(n)) return String(n);
    o = o || {};
    var max = o.maximumFractionDigits != null ? o.maximumFractionDigits : 3;
    var min = o.minimumFractionDigits != null ? o.minimumFractionDigits : 0;
    var s = n.toFixed(max);
    if (s.indexOf(".") >= 0) { s = s.replace(/0+$/, ""); if (s.slice(-1) === ".") s = s.slice(0, -1); }
    var dot = s.indexOf("."), frac = dot >= 0 ? s.slice(dot + 1) : "";
    while (frac.length < min) frac += "0";
    var int = (dot >= 0 ? s.slice(0, dot) : s), neg = int[0] === "-";
    if (neg) int = int.slice(1);
    int = int.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
    return (neg ? "-" : "") + int + (frac ? "." + frac : "");
  }

  globalThis.Intl = { DateTimeFormat: DateTimeFormat, NumberFormat: NumberFormat };

  var dateToLocale = function (defaults) {
    return function (locale, options) {
      var given = options || {};
      var hasField = ["year", "month", "day", "hour", "minute", "second", "weekday"].some(function (k) { return given[k]; });
      var o = hasField ? Object.assign({}, given) : Object.assign({}, defaults, given);
      return new DateTimeFormat(locale || "ja-JP", o).format(this);
    };
  };
  Date.prototype.toLocaleString = dateToLocale({ year: "numeric", month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit", second: "2-digit" });
  Date.prototype.toLocaleDateString = dateToLocale({ year: "numeric", month: "numeric", day: "numeric" });
  Date.prototype.toLocaleTimeString = dateToLocale({ hour: "numeric", minute: "2-digit", second: "2-digit" });
  Number.prototype.toLocaleString = function (locale, options) { return groupNumber(Number(this), options); };
})();

/*
 * QuickJS に無いブラウザの基本機能（logic.js が使う範囲だけ）。
 * 本物がある環境では何もしない。
 */
(function () {
  var g = globalThis;
  if (typeof g.console === "undefined") g.console = { log: function () {}, warn: function () {}, error: function () {}, info: function () {}, debug: function () {} };
  if (typeof g.performance === "undefined") g.performance = { now: function () { return Date.now(); } };
  if (typeof g.setTimeout === "undefined") {
    // 計算処理の中で遅延実行は使わないため、マイクロタスクで即時実行する
    var seq = 0, cancelled = {};
    g.setTimeout = function (fn) { var id = ++seq; var args = [].slice.call(arguments, 2); Promise.resolve().then(function () { if (!cancelled[id]) fn.apply(null, args); }); return id; };
    g.clearTimeout = function (id) { cancelled[id] = true; };
    g.setInterval = function () { return 0; };
    g.clearInterval = function () {};
  }
  if (typeof g.crypto === "undefined") {
    g.crypto = { getRandomValues: function (a) { for (var i = 0; i < a.length; i++) a[i] = Math.floor(Math.random() * 256); return a; },
      randomUUID: function () { return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) { var r = Math.random() * 16 | 0; return (c === "x" ? r : (r & 3 | 8)).toString(16); }); } };
  }
  if (typeof g.TextEncoder === "undefined") {
    g.TextEncoder = function () {};
    g.TextEncoder.prototype.encoding = "utf-8";
    g.TextEncoder.prototype.encode = function (s) {
      s = String(s === undefined ? "" : s);
      var out = [];
      for (var i = 0; i < s.length; i++) {
        var c = s.charCodeAt(i);
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) { var d = s.charCodeAt(i + 1); if (d >= 0xdc00 && d <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00); i++; } }
        if (c < 0x80) out.push(c);
        else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
        else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
        else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      }
      return new Uint8Array(out);
    };
  }
  if (typeof g.TextDecoder === "undefined") {
    g.TextDecoder = function () {};
    g.TextDecoder.prototype.decode = function (buf) {
      var b = buf instanceof Uint8Array ? buf : new Uint8Array(buf ? (buf.buffer || buf) : []), s = "", i = 0;
      while (i < b.length) {
        var c = b[i++], cp;
        if (c < 0x80) cp = c;
        else if (c < 0xe0) cp = ((c & 31) << 6) | (b[i++] & 63);
        else if (c < 0xf0) cp = ((c & 15) << 12) | ((b[i++] & 63) << 6) | (b[i++] & 63);
        else cp = ((c & 7) << 18) | ((b[i++] & 63) << 12) | ((b[i++] & 63) << 6) | (b[i++] & 63);
        s += String.fromCodePoint(cp);
      }
      return s;
    };
  }
  if (typeof g.URLSearchParams === "undefined") {
    var enc = function (s) { return encodeURIComponent(s).replace(/%20/g, "+"); };
    var dec = function (s) { return decodeURIComponent(String(s).replace(/\+/g, " ")); };
    g.URLSearchParams = function (init) {
      this._p = [];
      if (typeof init === "string") {
        init.replace(/^\?/, "").split("&").forEach(function (kv) { if (!kv) return; var i = kv.indexOf("="); this._p.push(i < 0 ? [dec(kv), ""] : [dec(kv.slice(0, i)), dec(kv.slice(i + 1))]); }, this);
      } else if (init && typeof init === "object") {
        (Array.isArray(init) ? init : Object.keys(init).map(function (k) { return [k, init[k]]; })).forEach(function (kv) { this._p.push([String(kv[0]), String(kv[1])]); }, this);
      }
    };
    var P = g.URLSearchParams.prototype;
    P.append = function (k, v) { this._p.push([String(k), String(v)]); };
    P.set = function (k, v) { this.delete(k); this.append(k, v); };
    P.get = function (k) { for (var i = 0; i < this._p.length; i++) if (this._p[i][0] === k) return this._p[i][1]; return null; };
    P.getAll = function (k) { return this._p.filter(function (p) { return p[0] === k; }).map(function (p) { return p[1]; }); };
    P.has = function (k) { return this.get(k) !== null; };
    P.delete = function (k) { this._p = this._p.filter(function (p) { return p[0] !== k; }); };
    P.forEach = function (fn) { this._p.forEach(function (p) { fn(p[1], p[0]); }); };
    P.entries = function () { return this._p.slice()[Symbol.iterator](); };
    P[Symbol.iterator] = P.entries;
    P.toString = function () { return this._p.map(function (p) { return enc(p[0]) + "=" + enc(p[1]); }).join("&"); };
  }
  if (typeof g.URL === "undefined") {
    g.URL = function (url, base) {
      url = String(url);
      if (base && !/^[a-z][a-z0-9+.-]*:/i.test(url)) {
        var b = new g.URL(base);
        url = url.charAt(0) === "/" ? b.origin + url : b.origin + b.pathname.replace(/[^/]*$/, "") + url;
      }
      var m = url.match(/^([a-z][a-z0-9+.-]*:)\/\/([^/?#:]*)(?::(\d+))?([^?#]*)(\?[^#]*)?(#.*)?$/i);
      if (!m) throw new TypeError("Invalid URL: " + url);
      this.protocol = m[1].toLowerCase(); this.hostname = m[2]; this.port = m[3] || "";
      this.host = this.hostname + (this.port ? ":" + this.port : ""); this.origin = this.protocol + "//" + this.host;
      this.pathname = m[4] || "/"; this.hash = m[6] || "";
      this.searchParams = new g.URLSearchParams(m[5] || "");
    };
    Object.defineProperty(g.URL.prototype, "search", {
      get: function () { var s = this.searchParams.toString(); return s ? "?" + s : ""; },
      set: function (v) { this.searchParams = new g.URLSearchParams(v); },
    });
    Object.defineProperty(g.URL.prototype, "href", { get: function () { return this.origin + this.pathname + this.search + this.hash; } });
    g.URL.prototype.toString = function () { return this.href; };
    g.URL.prototype.toJSON = function () { return this.href; };
  }
})();
