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
