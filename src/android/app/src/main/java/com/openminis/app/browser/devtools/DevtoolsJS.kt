package com.openminis.app.browser.devtools

import com.openminis.app.browser.BrowserUseJS
import org.json.JSONArray
import org.json.JSONObject

/**
 * [M5-browser-devtools] Builders for every script the devtools actions inject.
 * Pure string building - the generated JavaScript is syntax-checked and run
 * against a fake DOM under Node in DevtoolsJSTest.
 *
 * Escaping rule: a caller-supplied value reaches a script only through
 * [q] (a JSON string literal), a number, a boolean, or a ref that
 * [DevtoolsLimits.normalizeRef] already reduced to `e<digits>`. The single
 * exception is [evaluate], whose whole point is to run the given code.
 *
 * Page-side state lives in `window.__minisDT` (non-enumerable). The page can
 * see and tamper with it, exactly as it can tamper with its own DOM; nothing
 * it reports is trusted beyond being shown to the model, and everything is
 * clipped again in Kotlin.
 */
object DevtoolsJS {

    /** Name of the native bridge object (see BrowserDevtools). */
    const val BRIDGE = "__minisDevtools__"

    /** Stand-in selector used to retarget an existing BrowserUseJS script at a ref. */
    internal const val REF_SENTINEL = "__MINIS_REF_TARGET__"

    /** A JSON string literal: safe to embed anywhere a JS expression is expected. */
    fun q(s: String): String =
        JSONObject.quote(s).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029").replace("</", "<\\/")

    // ── The hook ─────────────────────────────────────────────────────────

    /**
     * Installed at document start (and re-run, idempotently, before the
     * actions that need it).
     *
     * Passive part, always on - nothing a page can observe changes:
     *  - `error` / `unhandledrejection` listeners (message, URL:line:col, stack);
     *  - PerformanceObserver for resource + navigation timing, LCP and
     *    layout shift;
     *  - the ref table used by snapshot.
     *
     * Deep part, only when [deep] - it replaces `window.fetch` and patches
     * `XMLHttpRequest.prototype`, which a page (or an anti-bot script) can
     * detect, so it is off until the agent first asks for the network log:
     *  - method, status, request/response headers and a text/JSON body
     *    preview for fetch and XHR, plus the in-flight counter used by the
     *    network-idle wait.
     */
    fun hook(deep: Boolean): String = """
(function(){
  try {
    var W = window, DT = W.__minisDT;
    if (!DT) {
      DT = {v: 1, q: [], t: 0, inflight: 0, last: Date.now(), lcp: -1, cls: 0, refs: {}, refSeq: 0, refOf: (typeof WeakMap === 'function' ? new WeakMap() : null), deep: false};
      try { Object.defineProperty(W, '__minisDT', {value: DT, enumerable: false, configurable: true, writable: true}); } catch (e) { W.__minisDT = DT; }
      var clip = function(s, n){ s = String(s == null ? '' : s); return s.length > n ? s.slice(0, n) : s; };
      DT.clip = clip;
      DT.flush = function(){
        DT.t = 0;
        if (!DT.q.length) return;
        var b = DT.q; DT.q = [];
        try { var br = W.$BRIDGE; if (br && br.events) br.events(JSON.stringify(b)); } catch (e) {}
      };
      DT.push = function(ev){
        DT.q.push(ev);
        if (DT.q.length > 400) DT.q.shift();
        if (DT.q.length >= 50) { if (DT.t) { clearTimeout(DT.t); } DT.flush(); }
        else if (!DT.t) DT.t = setTimeout(DT.flush, 250);
      };
      DT.el = function(ref){ var e = DT.refs[ref]; return (e && e.isConnected) ? e : null; };
      DT.ref = function(el){
        var r = DT.refOf ? DT.refOf.get(el) : null;
        if (!r) { r = 'e' + (++DT.refSeq); if (DT.refOf) DT.refOf.set(el, r); }
        DT.refs[r] = el;
        return r;
      };
      W.addEventListener('error', function(e){
        try {
          if (!e || (e.target && e.target !== W)) return;
          var err = e.error;
          DT.push({k: 'c', lv: 'error', kind: 'exception', m: clip(e.message || (err && err.message) || 'Script error', 500), u: clip(e.filename || '', 300), l: e.lineno || 0, c: e.colno || 0, st: clip((err && err.stack) || '', 1500)});
        } catch (x) {}
      }, true);
      W.addEventListener('unhandledrejection', function(e){
        try {
          var r = e.reason, msg;
          try { msg = (r && r.message) ? ((r.name || 'Error') + ': ' + r.message) : (r && typeof r === 'object' ? JSON.stringify(r) : String(r)); } catch (x) { msg = String(r); }
          DT.push({k: 'c', lv: 'error', kind: 'rejection', m: clip('Unhandled promise rejection: ' + msg, 500), st: clip((r && r.stack) || '', 1500)});
        } catch (x) {}
      });
      W.addEventListener('pagehide', function(){ try { DT.flush(); } catch (x) {} });
      try {
        var po = new PerformanceObserver(function(list){
          var es = list.getEntries();
          for (var i = 0; i < es.length; i++) {
            var e = es[i];
            DT.last = Date.now();
            DT.push({k: 'n', src: 'rt', u: clip(e.name, 600), ty: e.entryType === 'navigation' ? 'document' : (e.initiatorType || ''), s: e.responseStatus || 0, d: Math.round(e.duration), ts: e.transferSize || 0, es: e.encodedBodySize || 0, ds: e.decodedBodySize || 0});
          }
        });
        po.observe({type: 'resource', buffered: true});
        try { po.observe({type: 'navigation', buffered: true}); } catch (x) {}
      } catch (x) {}
      try { new PerformanceObserver(function(l){ var es = l.getEntries(), e = es[es.length - 1]; if (e) DT.lcp = Math.round(e.renderTime || e.loadTime || e.startTime); }).observe({type: 'largest-contentful-paint', buffered: true}); } catch (x) {}
      try { new PerformanceObserver(function(l){ var es = l.getEntries(); for (var i = 0; i < es.length; i++) { if (!es[i].hadRecentInput) DT.cls += es[i].value; } }).observe({type: 'layout-shift', buffered: true}); } catch (x) {}
    }
    if (${if (deep) "true" else "false"} && !DT.deep) {
      DT.deep = true;
      var clip2 = DT.clip;
      var abs = function(u){ try { return new URL(u, document.baseURI).href; } catch (e) { return String(u); } };
      var textual = function(mt){ return /^(text\/|application\/(json|[a-z0-9.+-]*\+json|xml|[a-z0-9.+-]*\+xml|javascript|x-www-form-urlencoded))/i.test(mt) && !/event-stream/i.test(mt); };
      var done = function(){ DT.inflight = Math.max(0, DT.inflight - 1); DT.last = Date.now(); };
      var hdrs = function(h){
        var o = {}, n = 0;
        try {
          if (!h) return o;
          if (typeof h.forEach === 'function' && !Array.isArray(h)) { h.forEach(function(v, k){ if (n++ < 25) o[String(k).toLowerCase()] = clip2(v, 200); }); }
          else if (Array.isArray(h)) { for (var i = 0; i < h.length && n < 25; i++, n++) o[String(h[i][0]).toLowerCase()] = clip2(h[i][1], 200); }
          else { for (var k in h) { if (n++ >= 25) break; o[String(k).toLowerCase()] = clip2(h[k], 200); } }
        } catch (e) {}
        return o;
      };
      var readBody = function(resp, o){
        try {
          if (!textual(o.mt) || !resp.body || !resp.clone || typeof TextDecoder !== 'function') { DT.push(o); return; }
          var rd = resp.clone().body.getReader(), dec = new TextDecoder(), out = '', fin = false;
          var end = function(){
            if (fin) return; fin = true;
            o.b = out.slice(0, 2048); if (out.length > 2048) o.bt = 1;
            DT.push(o);
            try { rd.cancel(); } catch (e) {}
          };
          var step = function(){
            rd.read().then(function(r){
              if (r.done) { end(); return; }
              out += dec.decode(r.value, {stream: true});
              if (out.length >= 2048) end(); else step();
            }, end);
          };
          setTimeout(end, 3000);
          step();
        } catch (e) { DT.push(o); }
      };
      var nativeFetch = W.fetch;
      if (typeof nativeFetch === 'function') {
        var wrapped = function(input, init){
          var t0 = Date.now(), url = '', method = 'GET', rq = {};
          try {
            if (typeof input === 'string') url = input;
            else if (input && typeof input.url === 'string') { url = input.url; method = input.method || method; rq = hdrs(input.headers); }
            else url = String(input);
            if (init && init.method) method = String(init.method);
            if (init && init.headers) rq = hdrs(init.headers);
            url = abs(url); method = String(method).toUpperCase();
          } catch (e) {}
          DT.inflight++; DT.last = t0;
          var p = nativeFetch.apply(this, arguments);
          try {
            p.then(function(resp){
              done();
              var o = {k: 'n', src: 'fetch', u: clip2(url, 600), me: method, s: resp.status, ty: 'fetch', d: Date.now() - t0, rq: rq, mt: ''};
              try { o.rs = hdrs(resp.headers); o.mt = String(resp.headers.get('content-type') || '').split(';')[0]; } catch (e) {}
              if (resp.type === 'opaque') o.note = 'opaque response (no-cors): status and body are hidden from scripts';
              readBody(resp, o);
            }, function(err){
              done();
              DT.push({k: 'n', src: 'fetch', u: clip2(url, 600), me: method, s: 0, ty: 'fetch', d: Date.now() - t0, rq: rq, f: 1, er: clip2((err && err.message) || String(err), 200)});
            });
          } catch (e) { done(); }
          return p;
        };
        try { wrapped.toString = function(){ return nativeFetch.toString(); }; } catch (e) {}
        W.fetch = wrapped;
      }
      var X = W.XMLHttpRequest;
      if (X && X.prototype && X.prototype.open) {
        var xo = X.prototype.open, xs = X.prototype.send, xh = X.prototype.setRequestHeader;
        X.prototype.open = function(m, u){
          try { this.__mdt = {me: String(m || 'GET').toUpperCase(), u: abs(String(u)), rq: {}, n: 0}; } catch (e) {}
          return xo.apply(this, arguments);
        };
        X.prototype.setRequestHeader = function(k, v){
          try { var s = this.__mdt; if (s && s.n++ < 25) s.rq[String(k).toLowerCase()] = clip2(v, 200); } catch (e) {}
          return xh.apply(this, arguments);
        };
        X.prototype.send = function(){
          var x = this, s = x.__mdt, t0 = Date.now();
          if (s) {
            try {
              DT.inflight++; DT.last = t0;
              x.addEventListener('loadend', function(){
                done();
                try {
                  var o = {k: 'n', src: 'xhr', u: clip2(s.u, 600), me: s.me, s: x.status || 0, ty: 'xhr', d: Date.now() - t0, rq: s.rq, mt: ''};
                  if (!x.status) { o.f = 1; o.er = 'no response (network error, CORS, abort or timeout)'; }
                  try { o.mt = String(x.getResponseHeader('content-type') || '').split(';')[0]; } catch (e) {}
                  try {
                    var raw = x.getAllResponseHeaders() || '', rs = {}, lines = raw.split(/\r?\n/), n = 0;
                    for (var i = 0; i < lines.length && n < 25; i++) { var ix = lines[i].indexOf(':'); if (ix > 0) { rs[lines[i].slice(0, ix).trim().toLowerCase()] = clip2(lines[i].slice(ix + 1).trim(), 200); n++; } }
                    o.rs = rs;
                  } catch (e) {}
                  try {
                    if (textual(o.mt)) {
                      var b = (x.responseType === '' || x.responseType === 'text') ? x.responseText : (x.responseType === 'json' ? JSON.stringify(x.response) : '');
                      if (b) { o.b = b.slice(0, 2048); if (b.length > 2048) o.bt = 1; }
                    }
                  } catch (e) {}
                  DT.push(o);
                } catch (e) {}
              });
            } catch (e) {}
          }
          return xs.apply(this, arguments);
        };
      }
    }
  } catch (outer) {}
})();
""".trim()

    /** Pushes any queued hook events to Kotlin right now. Evaluates to 1. */
    const val FLUSH: String = "(function(){ try { var d = window.__minisDT; if (d && d.flush) d.flush(); } catch (e) {} return 1; })()"

    // ── Shared snippets ──────────────────────────────────────────────────

    /** `var el = …` for a ref or a selector, returning a JSON error when it cannot be resolved. */
    private fun resolveOne(selector: String?, ref: String?): String = when {
        ref != null -> """
            var DT = window.__minisDT;
            var el = (DT && DT.el) ? DT.el('$ref') : null;
            if (!el) return JSON.stringify({error: ${q(refError(ref))}});
        """.trimIndent()
        else -> """
            var el = null;
            try { el = document.querySelector(${q(selector.orEmpty())}); } catch (e) { return JSON.stringify({error: 'Invalid selector: ' + ${q(selector.orEmpty())}}); }
            if (!el) return JSON.stringify({error: 'Element not found: ' + ${q(selector.orEmpty())}});
        """.trimIndent()
    }

    fun refError(ref: String): String =
        "Element not found: ref $ref is unknown or stale (refs do not survive a navigation, and die when the element is removed). Take a new snapshot."

    private const val VISIBLE_FN = """
            var vis = function(el){
                if (!el || !el.isConnected) return false;
                var r = el.getBoundingClientRect();
                if (r.width < 1 || r.height < 1) return false;
                var st = getComputedStyle(el);
                return st.display !== 'none' && st.visibility !== 'hidden' && st.visibility !== 'collapse';
            };"""

    // ── Ref targeting for the existing actions ───────────────────────────

    /**
     * Retargets a BrowserUseJS script that was built for the selector
     * [REF_SENTINEL] at the element behind [ref], so click / type / hover /
     * get_text behave exactly as they do with a selector (same events, same
     * result shape) without a second copy of that logic. Returns null when the
     * script does not have the expected `document.querySelector('<selector>')`
     * shape - the caller then reports that the action does not take a ref.
     */
    fun retargetToRef(script: String, ref: String): String? {
        // Only `e` + digits is ever embedded, whatever the caller passed.
        val safeRef = DevtoolsLimits.normalizeRef(ref) ?: return null
        val lookup = "document.querySelector('$REF_SENTINEL')"
        if (!script.contains(lookup)) return null
        val resolver = "((window.__minisDT && window.__minisDT.el) ? window.__minisDT.el('$safeRef') : null)"
        return script
            .replace(lookup, resolver)
            .replace(REF_SENTINEL, "ref $safeRef")
    }

    /** Appended to a retargeted script's "Element not found: ref eN" error. */
    const val REF_NOT_FOUND_HINT: String =
        " - the ref is unknown or stale (refs do not survive a navigation and die when the element is removed). Take a new snapshot."

    /** Adds [REF_NOT_FOUND_HINT] to a ref-targeted action's not-found error; other text passes through. */
    fun explainRefError(text: String, ref: String): String =
        if (text.contains("Element not found: ref $ref") && !text.contains(REF_NOT_FOUND_HINT)) {
            text.replace("Element not found: ref $ref", "Element not found: ref $ref$REF_NOT_FOUND_HINT")
        } else text

    fun clickRef(ref: String): String? = retargetToRef(BrowserUseJS.click(REF_SENTINEL), ref)
    fun typeRef(ref: String, text: String): String? = retargetToRef(BrowserUseJS.type(REF_SENTINEL, text), ref)
    fun hoverRef(ref: String): String? = retargetToRef(BrowserUseJS.hover(REF_SENTINEL), ref)
    fun getTextRef(ref: String): String? = retargetToRef(BrowserUseJS.getText(REF_SENTINEL), ref)

    // ── snapshot ─────────────────────────────────────────────────────────

    /**
     * Accessibility-style outline. Returns JSON
     * `{url,title,total,interactive,nodes:[{r,ro,n,d,…}]}`; formatted by
     * [DevtoolsFormat.snapshot]. A password field's value never leaves the
     * page (reported as "[redacted]"), like get_backbone.
     */
    fun snapshot(scopeSelector: String?, interactiveOnly: Boolean, maxNodes: Int, maxDepth: Int, includeUrls: Boolean): String {
        val max = maxNodes.coerceIn(1, DevtoolsLimits.SNAPSHOT_MAX_NODES)
        val depth = maxDepth.coerceIn(0, DevtoolsLimits.SNAPSHOT_MAX_DEPTH)
        val scope = if (scopeSelector.isNullOrBlank()) "null" else q(scopeSelector)
        return hook(false) + "\n" + BrowserUseJS.guardedRead("""
        (function() {
            var DT = window.__minisDT;
            if (!DT || !DT.ref) return JSON.stringify({error: 'Devtools hook is not available on this page'});
            var ONLY_I = $interactiveOnly, MAX = $max, MAXD = $depth, URLS = $includeUrls, SCOPE = $scope;
            var roots;
            if (SCOPE) {
                try { roots = Array.prototype.slice.call(document.querySelectorAll(SCOPE)); } catch (e) { return JSON.stringify({error: 'Invalid selector: ' + SCOPE}); }
                if (!roots.length) return JSON.stringify({error: 'Element not found: ' + SCOPE});
            } else {
                roots = [document.body || document.documentElement];
            }
            var keys = Object.keys(DT.refs);
            if (keys.length > 3000) DT.refs = {};
            else for (var ki = 0; ki < keys.length; ki++) { var old = DT.refs[keys[ki]]; if (!old || !old.isConnected) delete DT.refs[keys[ki]]; }
            var IROLE = {button:1, link:1, checkbox:1, radio:1, tab:1, menuitem:1, menuitemcheckbox:1, menuitemradio:1, option:1, 'switch':1, textbox:1, searchbox:1, combobox:1, listbox:1, slider:1, spinbutton:1, treeitem:1, clickable:1, file:1, iframe:1, video:1, audio:1};
            var CROLE = {dialog:1, alertdialog:1, form:1, navigation:1, main:1, menu:1, tablist:1, toolbar:1, search:1};
            var SROLE = {heading:1, img:1, alert:1, status:1};
            var SKIP = {SCRIPT:1, STYLE:1, NOSCRIPT:1, TEMPLATE:1, HEAD:1, META:1, LINK:1, BR:1, HR:1};
            var txt = function(s, n){ s = String(s == null ? '' : s).replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
            var roleOf = function(el){
                var r = el.getAttribute('role');
                if (r) { r = r.trim().split(/\s+/)[0].toLowerCase(); if (r && r !== 'presentation' && r !== 'none') return r; }
                var t = el.tagName;
                if (t === 'A') return el.hasAttribute('href') ? 'link' : '';
                if (t === 'BUTTON' || t === 'SUMMARY') return 'button';
                if (t === 'INPUT') {
                    var ty = (el.getAttribute('type') || 'text').toLowerCase();
                    if (ty === 'hidden') return '';
                    if (ty === 'checkbox' || ty === 'radio') return ty;
                    if (ty === 'button' || ty === 'submit' || ty === 'reset' || ty === 'image') return 'button';
                    if (ty === 'range') return 'slider';
                    if (ty === 'search') return 'searchbox';
                    if (ty === 'number') return 'spinbutton';
                    if (ty === 'file') return 'file';
                    return 'textbox';
                }
                if (t === 'TEXTAREA') return 'textbox';
                if (t === 'SELECT') return (el.multiple || el.size > 1) ? 'listbox' : 'combobox';
                if (t.length === 2 && t.charAt(0) === 'H' && t.charAt(1) >= '1' && t.charAt(1) <= '6') return 'heading';
                if (t === 'IMG') return 'img';
                if (t === 'NAV') return 'navigation';
                if (t === 'MAIN') return 'main';
                if (t === 'FORM') return 'form';
                if (t === 'DIALOG') return 'dialog';
                if (t === 'IFRAME') return 'iframe';
                if (t === 'VIDEO') return 'video';
                if (t === 'AUDIO') return 'audio';
                if (el.isContentEditable && !(el.parentElement && el.parentElement.isContentEditable)) return 'textbox';
                if (el.hasAttribute('onclick') || (el.hasAttribute('tabindex') && el.tabIndex >= 0)) return 'clickable';
                return '';
            };
            var nameOf = function(el, labelOnly){
                var v = el.getAttribute('aria-label');
                if (v && v.trim()) return txt(v, 80);
                var lb = el.getAttribute('aria-labelledby');
                if (lb) {
                    var ids = lb.trim().split(/\s+/), parts = [];
                    for (var i = 0; i < ids.length; i++) { var x = el.ownerDocument.getElementById(ids[i]); if (x) parts.push(x.textContent || ''); }
                    var joined = txt(parts.join(' '), 80);
                    if (joined) return joined;
                }
                // A container is named only by an explicit label: its text is its children.
                if (labelOnly) return txt(el.getAttribute('title') || '', 80);
                var t = el.tagName;
                if (t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT') {
                    if (el.labels && el.labels.length) { var l = txt(el.labels[0].textContent, 80); if (l) return l; }
                    var ity = (el.getAttribute('type') || '').toLowerCase();
                    if (t === 'INPUT' && (ity === 'button' || ity === 'submit' || ity === 'reset')) return txt(el.getAttribute('value') || ity, 80);
                    return txt(el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('name') || '', 80);
                }
                if (t === 'IMG') return txt(el.getAttribute('alt') || el.getAttribute('title') || '', 80);
                if (t === 'IFRAME') return txt(el.getAttribute('title') || el.getAttribute('name') || el.getAttribute('src') || '', 80);
                var s = txt(el.innerText || el.textContent || '', 80);
                if (s) return s;
                var im = el.querySelector ? el.querySelector('img[alt]') : null;
                if (im && im.getAttribute('alt')) return txt(im.getAttribute('alt'), 80);
                return txt(el.getAttribute('title') || '', 80);
            };
            var vis = function(el){
                var r = el.getBoundingClientRect();
                if (r.width < 1 || r.height < 1) return false;
                var st = getComputedStyle(el);
                return st.display !== 'none' && st.visibility !== 'hidden' && st.visibility !== 'collapse';
            };
            var ownText = function(el){
                var s = '';
                for (var n = el.firstChild; n; n = n.nextSibling) if (n.nodeType === 3) s += n.nodeValue;
                return txt(s, 120);
            };
            var out = [], total = 0;
            var describe = function(el, role, depth, isContainer){
                var o = {ro: role, n: nameOf(el, isContainer), d: Math.min(depth, MAXD)};
                var t = el.tagName, st = [];
                if (t === 'INPUT') {
                    var ty = (el.getAttribute('type') || 'text').toLowerCase();
                    if (role === 'textbox' && ty !== 'text') o.ty = ty;
                    if (role === 'checkbox' || role === 'radio') { if (el.checked) st.push('checked'); }
                    else if (role !== 'button' && role !== 'file') {
                        var val = el.value;
                        if (val) o.v = (ty === 'password') ? '[redacted]' : txt(val, 60);
                    }
                } else if (t === 'TEXTAREA') {
                    if (el.value) o.v = txt(el.value, 60);
                } else if (t === 'SELECT') {
                    var so = el.selectedOptions && el.selectedOptions[0];
                    if (so) o.v = txt(so.textContent, 60);
                    o.o = el.options ? el.options.length : 0;
                } else if (role === 'heading') {
                    o.lv = t.charAt(0) === 'H' && t.length === 2 ? parseInt(t.charAt(1), 10) : (parseInt(el.getAttribute('aria-level') || '0', 10) || 0);
                } else if (role === 'link' && URLS) {
                    var href = el.getAttribute('href') || '';
                    try { var u = new URL(href, el.ownerDocument.baseURI); href = (u.origin === location.origin) ? (u.pathname + u.search) : u.href; } catch (e) {}
                    o.h = txt(href, 100);
                }
                if (el.disabled || el.getAttribute('aria-disabled') === 'true') st.push('disabled');
                if (el.getAttribute('aria-checked') === 'true' && st.indexOf('checked') < 0) st.push('checked');
                if (el.getAttribute('aria-selected') === 'true') st.push('selected');
                if (el.getAttribute('aria-pressed') === 'true') st.push('pressed');
                var ex = el.getAttribute('aria-expanded');
                if (ex === 'true') st.push('expanded'); else if (ex === 'false') st.push('collapsed');
                if (el.required || el.getAttribute('aria-required') === 'true') st.push('required');
                if (el.readOnly) st.push('readonly');
                if (el.ownerDocument.activeElement === el) st.push('focused');
                if (st.length) o.s = st;
                if (!o.n) {
                    var hint = el.getAttribute('data-testid') || el.getAttribute('name') || el.id || '';
                    if (!hint) { var cn = (typeof el.className === 'string') ? el.className.trim().split(/\s+/)[0] : ''; if (cn) hint = '.' + cn; }
                    if (hint) o.hi = txt(hint, 40);
                }
                return o;
            };
            var walk = function(parent, depth, insideInteractive){
                var kids = parent.children;
                if (!kids) return;
                for (var i = 0; i < kids.length; i++) {
                    var c = kids[i];
                    if (SKIP[c.tagName]) continue;
                    if (c.getAttribute('aria-hidden') === 'true') continue;
                    var role = roleOf(c), nextDepth = depth, nextInside = insideInteractive, listed = false, kind = '';
                    if (role) {
                        if (IROLE[role]) kind = 'i';
                        else if (CROLE[role]) kind = 'c';
                        else if (!ONLY_I && SROLE[role]) kind = 's';
                    }
                    if (!kind && !ONLY_I && !insideInteractive) {
                        var own = ownText(c);
                        if (own.length > 1) { kind = 't'; role = 'text'; }
                    }
                    if (kind && vis(c)) {
                        if (kind === 's' && role === 'img' && !nameOf(c)) kind = '';
                        if (kind) {
                            total++;
                            listed = true;
                            if (out.length < MAX) {
                                var o = describe(c, role, depth, kind === 'c');
                                if (kind === 't') o.n = ownText(c);
                                if (kind === 'c') o.c = 1;
                                o.r = DT.ref(c);
                                out.push(o);
                            }
                            if (kind === 'c' || kind === 'i') nextDepth = depth + 1;
                            if (kind === 'i') nextInside = true;
                        }
                    }
                    if (role === 'iframe') {
                        try { var doc = c.contentDocument; if (doc && doc.body) walk(doc.body, nextDepth, false); } catch (e) {}
                        continue;
                    }
                    if (role === 'combobox' && c.tagName === 'SELECT') continue;
                    walk(c, nextDepth, nextInside);
                    if (c.shadowRoot) walk(c.shadowRoot, nextDepth, nextInside);
                }
            };
            for (var ri = 0; ri < roots.length; ri++) walk(roots[ri], 0, false);
            var kept = [];
            for (var j = 0; j < out.length; j++) {
                var node = out[j];
                if (node.c && !node.n) {
                    var next = out[j + 1];
                    if (!next || next.d <= node.d) { if (node.d < MAXD) continue; }
                }
                kept.push(node);
            }
            return JSON.stringify({url: location.href, title: document.title, total: total, interactive: ONLY_I, nodes: kept});
        })()
        """.trimIndent(), requiresBody = false)
    }

    // ── inspect ──────────────────────────────────────────────────────────

    val DEFAULT_STYLE_PROPERTIES: List<String> = listOf(
        "display", "position", "visibility", "opacity", "width", "height", "margin", "padding",
        "color", "background-color", "font-size", "font-family", "font-weight", "line-height",
        "border", "z-index", "overflow", "cursor", "pointer-events", "box-sizing",
        "flex-direction", "justify-content", "align-items", "gap", "transform",
    )

    /** CSS property names only: letters, digits, `-`; custom properties (`--x`) allowed. */
    fun sanitizeProperties(raw: List<String>?): List<String> =
        raw.orEmpty().map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it.length <= 60 && Regex("^-{0,2}[a-z][a-z0-9-]*$").matches(it) }
            .distinct()
            .take(DevtoolsLimits.INSPECT_MAX_PROPERTIES)

    /** DOM inspection of a selector (first [limit] matches) or one ref. */
    fun inspect(selector: String?, ref: String?, properties: List<String>?, limit: Int): String {
        val props = sanitizeProperties(properties).ifEmpty { DEFAULT_STYLE_PROPERTIES }
        val n = limit.coerceIn(1, DevtoolsLimits.INSPECT_MAX_ELEMENTS)
        val htmlCap = DevtoolsLimits.INSPECT_HTML_CHARS / n
        val find = if (ref != null) {
            """
            var DT = window.__minisDT;
            var one = (DT && DT.el) ? DT.el('$ref') : null;
            if (!one) return JSON.stringify({error: ${q(refError(ref))}});
            var els = [one];
            """.trimIndent()
        } else {
            """
            var els;
            try { els = document.querySelectorAll(${q(selector.orEmpty())}); } catch (e) { return JSON.stringify({error: 'Invalid selector: ' + ${q(selector.orEmpty())}}); }
            """.trimIndent()
        }
        return BrowserUseJS.guardedRead("""
        (function() {
            $find
            var PROPS = ${JSONArray(props)}, LIMIT = $n, HTML_CAP = $htmlCap;
            var clip = function(s, n){ s = String(s == null ? '' : s); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
            var short = function(el){ var s = '<' + el.tagName.toLowerCase(); if (el.id) s += '#' + el.id; return s + '>'; };
            var one2 = function(el){
                var r = el.getBoundingClientRect(), cs = getComputedStyle(el);
                var isPw = el.tagName === 'INPUT' && String(el.getAttribute('type') || '').toLowerCase() === 'password';
                var o = {tag: el.tagName.toLowerCase(), children: el.childElementCount || 0};
                var attrs = {}, na = Math.min(el.attributes ? el.attributes.length : 0, 30);
                for (var i = 0; i < na; i++) { var a = el.attributes[i]; attrs[a.name] = (isPw && a.name === 'value') ? '[redacted]' : clip(a.value, 200); }
                o.attrs = attrs;
                o.text = clip(String(el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim(), 500);
                var html = el.outerHTML || '';
                o.htmlLen = html.length;
                if (/type\s*=\s*["']?password/i.test(html)) {
                    try {
                        var cl = el.cloneNode(true);
                        var pws = cl.querySelectorAll ? Array.prototype.slice.call(cl.querySelectorAll('input[type=password]')) : [];
                        if (isPw) pws.push(cl);
                        for (var p = 0; p < pws.length; p++) if (pws[p].hasAttribute('value')) pws[p].setAttribute('value', '[redacted]');
                        html = cl.outerHTML || '';
                    } catch (e) { html = short(el) + ' [outerHTML withheld: contains a password field]'; }
                }
                o.html = html.length > HTML_CAP ? html.slice(0, HTML_CAP) + '…' : html;
                o.rect = {x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height), pageX: Math.round(r.left + (window.scrollX || 0)), pageY: Math.round(r.top + (window.scrollY || 0))};
                var why = [];
                if (!el.isConnected) why.push('detached from the document');
                if (cs.display === 'none') why.push('display:none');
                else if (r.width < 1 || r.height < 1) why.push('zero size (or inside a display:none ancestor)');
                if (cs.visibility === 'hidden' || cs.visibility === 'collapse') why.push('visibility:' + cs.visibility);
                if (parseFloat(cs.opacity) === 0) why.push('opacity:0');
                o.visible = why.length === 0;
                if (why.length) o.why = why.join(', ');
                var vw = window.innerWidth || 0, vh = window.innerHeight || 0;
                o.inViewport = r.bottom > 0 && r.right > 0 && r.top < vh && r.left < vw;
                if (o.visible && o.inViewport && document.elementFromPoint) {
                    var cx = Math.min(Math.max(r.left + r.width / 2, 0), vw - 1), cy = Math.min(Math.max(r.top + r.height / 2, 0), vh - 1);
                    var top = document.elementFromPoint(cx, cy);
                    if (top && top !== el && !el.contains(top) && !top.contains(el)) o.coveredBy = short(top);
                }
                if (cs.pointerEvents === 'none') o.pointerEvents = 'none';
                if (el.disabled) o.disabled = true;
                if ('value' in el && typeof el.value === 'string' && el.tagName !== 'BUTTON' && el.tagName !== 'LI') o.value = isPw ? (el.value ? '[redacted]' : '') : clip(el.value, 200);
                if (el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) o.checked = !!el.checked;
                var styles = {};
                for (var k = 0; k < PROPS.length; k++) { var v = cs.getPropertyValue(PROPS[k]); if (v !== '' && v != null) styles[PROPS[k]] = clip(String(v).trim(), 120); }
                o.styles = styles;
                return o;
            };
            var res = [], shown = Math.min(els.length, LIMIT);
            for (var i = 0; i < shown; i++) res.push(one2(els[i]));
            return JSON.stringify({count: els.length, shown: shown, inspected: res});
        })()
        """.trimIndent(), requiresBody = false)
    }

    // ── storage ──────────────────────────────────────────────────────────

    /** document.cookie + localStorage + sessionStorage of the CURRENT document only. */
    fun storage(): String = BrowserUseJS.guardedRead("""
        (function() {
            var VCAP = ${DevtoolsLimits.STORAGE_VALUE_CHARS}, KCAP = ${DevtoolsLimits.STORAGE_KEYS_PER_STORE};
            var o = {url: location.href, origin: location.origin, cookie: '', ls: [], ss: [], lsN: 0, ssN: 0};
            try { o.cookie = String(document.cookie || '').slice(0, 20000); } catch (e) { o.cookieErr = String((e && e.message) || e); }
            var dump = function(name, key){
                try {
                    var s = window[name];
                    o[key + 'N'] = s.length;
                    for (var i = 0; i < s.length && i < KCAP; i++) {
                        var k = s.key(i), v = s.getItem(k);
                        v = v == null ? '' : String(v);
                        o[key].push([String(k).slice(0, 120), v.slice(0, VCAP), v.length]);
                    }
                } catch (e) { o[key + 'Err'] = String((e && e.message) || e); }
            };
            dump('localStorage', 'ls');
            dump('sessionStorage', 'ss');
            return JSON.stringify(o);
        })()
    """.trimIndent(), requiresBody = false)

    // ── performance ──────────────────────────────────────────────────────

    fun performance(): String = hook(false) + "\n" + BrowserUseJS.guardedRead("""
        (function() {
            var DT = window.__minisDT || {};
            var P = window.performance, o = {url: location.href};
            if (!P || !P.getEntriesByType) return JSON.stringify({error: 'The Performance API is not available on this page'});
            var R = function(x){ return Math.round(x || 0); };
            var nav = P.getEntriesByType('navigation')[0];
            if (nav) {
                o.nav = {type: nav.type || '', redirects: nav.redirectCount || 0, ttfb: R(nav.responseStart), responseEnd: R(nav.responseEnd), domInteractive: R(nav.domInteractive), dcl: R(nav.domContentLoadedEventEnd), load: R(nav.loadEventEnd), transfer: nav.transferSize || 0, decoded: nav.decodedBodySize || 0, status: nav.responseStatus || 0};
            }
            var paints = P.getEntriesByType('paint');
            for (var i = 0; i < paints.length; i++) {
                if (paints[i].name === 'first-paint') o.fp = R(paints[i].startTime);
                if (paints[i].name === 'first-contentful-paint') o.fcp = R(paints[i].startTime);
            }
            if (typeof DT.lcp === 'number' && DT.lcp >= 0) o.lcp = DT.lcp;
            if (typeof DT.cls === 'number') o.cls = Math.round(DT.cls * 1000) / 1000;
            var res = P.getEntriesByType('resource'), byType = {}, total = 0, slow = [];
            for (var j = 0; j < res.length; j++) {
                var e = res[j], t = e.initiatorType || 'other';
                if (!byType[t]) byType[t] = [0, 0];
                byType[t][0]++; byType[t][1] += (e.transferSize || 0);
                total += (e.transferSize || 0);
                slow.push([R(e.duration), String(e.name).slice(0, 120)]);
            }
            slow.sort(function(a, b){ return b[0] - a[0]; });
            o.res = {count: res.length, transfer: total, byType: byType, slowest: slow.slice(0, 5)};
            if (P.memory) o.mem = {used: P.memory.usedJSHeapSize || 0, total: P.memory.totalJSHeapSize || 0, limit: P.memory.jsHeapSizeLimit || 0};
            try { o.domNodes = document.getElementsByTagName('*').length; } catch (x) {}
            o.now = R(P.now());
            return JSON.stringify(o);
        })()
    """.trimIndent(), requiresBody = false)

    // ── evaluate ─────────────────────────────────────────────────────────

    /**
     * Wraps [script] so its value - awaited when it is a promise - is
     * serialised safely (undefined, functions, DOM nodes, errors, Map/Set,
     * cycles, depth and size limits) and handed to Kotlin through
     * `__minisDevtools__.result(token, json)`.
     *
     * [asExpression] = true compiles the script as `return ( <script> );`, the
     * way a devtools console evaluates an expression; false runs it as a
     * function body (statements, explicit `return`). The caller tries the
     * expression form first and falls back when it does not parse - a script
     * that does not parse never runs at all, so nothing executes twice.
     *
     * [script] is embedded verbatim: this action exists to run it. It has
     * exactly the powers of any script on the page (DOM, cookies visible to
     * JS, storage, same-origin requests) and no more. [token] must be
     * [isSafeToken].
     */
    fun evaluate(script: String, token: String, asExpression: Boolean): String {
        require(isSafeToken(token)) { "bad token" }
        val body = if (asExpression) "return (\n$script\n);" else script
        return """
(async function(){
  var __mdtSend = function(o){
    var s;
    try { s = JSON.stringify(o); } catch (e) { s = JSON.stringify({error: 'Result could not be serialised: ' + ((e && e.message) || e)}); }
    if (s.length > 60000) s = JSON.stringify({type: o.type || '', truncated: true, text: s.slice(0, 12000)});
    window.$BRIDGE.result('$token', s);
  };
  var __mdtSer = $SERIALIZER;
  try {
    var __mdtV = await (async function(){
$body
    })();
    __mdtSend({type: (__mdtV === null ? 'null' : (Array.isArray(__mdtV) ? 'array' : typeof __mdtV)), value: __mdtSer(__mdtV)});
  } catch (e) {
    __mdtSend({error: ((e && e.name) || 'Error') + ': ' + ((e && e.message) || String(e)), stack: String((e && e.stack) || '').slice(0, 2000)});
  }
})()
""".trim()
    }

    /**
     * The script as tried in expression form: trimmed, without trailing
     * semicolons, so `document.title;` still evaluates to the title instead of
     * falling back to a statement that returns nothing.
     */
    fun expressionCandidate(script: String): String = script.trim().trimEnd(';', ' ', '\n', '\r', '\t')

    fun isSafeToken(token: String): Boolean = Regex("^[A-Za-z0-9]{8,64}$").matches(token)

    /** A JS function expression: value → JSON-safe value. */
    private const val SERIALIZER = """function(root){
    var seen = [], budget = 3000;
    var desc = function(el){
      var s = '<' + String(el.tagName || el.nodeName).toLowerCase();
      if (el.id) s += '#' + el.id;
      if (typeof el.className === 'string' && el.className.trim()) s += '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.');
      s += '>';
      var t = String(el.textContent || '').replace(/\s+/g, ' ').trim();
      if (t) s += ' "' + t.slice(0, 60) + (t.length > 60 ? '…' : '') + '"';
      return s;
    };
    var is = function(v, name){ try { var C = window[name]; return typeof C === 'function' && v instanceof C; } catch (e) { return false; } };
    var go = function(v, depth){
      if (v === undefined) return '[undefined]';
      if (v === null) return null;
      var t = typeof v;
      if (t === 'string') return v.length > 4000 ? v.slice(0, 4000) + '…[' + v.length + ' chars]' : v;
      if (t === 'number') return isFinite(v) ? v : String(v);
      if (t === 'boolean') return v;
      if (t === 'bigint') return String(v) + 'n';
      if (t === 'symbol') return String(v);
      if (t === 'function') return '[Function ' + (v.name || 'anonymous') + ']';
      if (--budget < 0) return '[…]';
      if (is(v, 'Node')) {
        if (v.nodeType === 1) return desc(v);
        if (v.nodeType === 9) return '[Document ' + String(v.URL || '') + ']';
        return '[' + v.nodeName + ' ' + String(v.textContent || '').slice(0, 60) + ']';
      }
      if (is(v, 'Window')) return '[Window]';
      if (v instanceof Error) return {name: v.name, message: v.message, stack: String(v.stack || '').slice(0, 1500)};
      if (v instanceof Date) return isNaN(v.getTime()) ? 'Invalid Date' : v.toISOString();
      if (v instanceof RegExp) return String(v);
      if (typeof Promise === 'function' && v instanceof Promise) return '[Promise]';
      if (seen.indexOf(v) >= 0) return '[Circular]';
      if (depth >= 6) return Array.isArray(v) ? '[Array(' + v.length + ')]' : '[Object]';
      seen.push(v);
      var out;
      try {
        if (typeof Map === 'function' && v instanceof Map) {
          out = {'[Map]': go(Array.from(v.entries()).slice(0, 100), depth + 1)};
        } else if (typeof Set === 'function' && v instanceof Set) {
          out = {'[Set]': go(Array.from(v.values()).slice(0, 100), depth + 1)};
        } else if (Array.isArray(v) || is(v, 'NodeList') || is(v, 'HTMLCollection') || (typeof ArrayBuffer === 'function' && ArrayBuffer.isView(v) && typeof v.length === 'number')) {
          out = [];
          var n = Math.min(v.length, 200);
          for (var i = 0; i < n; i++) out.push(go(v[i], depth + 1));
          if (v.length > n) out.push('[… ' + (v.length - n) + ' more]');
        } else {
          out = {};
          var c = 0;
          for (var k in v) {
            if (c++ >= 100) { out['[…]'] = 'more keys omitted'; break; }
            try { out[k] = go(v[k], depth + 1); } catch (e) { out[k] = '[Throws: ' + ((e && e.message) || e) + ']'; }
          }
        }
      } catch (e) { out = '[Unserialisable: ' + ((e && e.message) || e) + ']'; }
      seen.pop();
      return out;
    };
    return go(root, 0);
  }"""

    // ── wait ─────────────────────────────────────────────────────────────

    /**
     * One poll of the page for the wait action. Returns JSON
     * `{rs,url,inflight,quiet,deep,attached?,visible?,text?,err?}`; the
     * decision is taken in Kotlin by [WaitSpec.check].
     */
    fun waitProbe(selector: String?, ref: String?, text: String?): String {
        val target = when {
            ref != null -> "try { el = (DT.el ? DT.el('$ref') : null); } catch (e) { o.err = 'ref lookup failed'; }"
            selector != null -> "try { el = document.querySelector(${q(selector)}); } catch (e) { o.err = 'Invalid selector: ' + ${q(selector)}; }"
            else -> ""
        }
        val hasTarget = ref != null || selector != null
        val textPart = if (text != null) {
            "try { o.text = String((document.body && (document.body.innerText || document.body.textContent)) || '').indexOf(${q(text)}) >= 0; } catch (e) { o.text = false; }"
        } else ""
        return """
(function(){
  try {
    var DT = window.__minisDT || {};
    var o = {rs: document.readyState, url: String(location.href), inflight: DT.inflight || 0, quiet: (typeof DT.last === 'number') ? (Date.now() - DT.last) : -1, deep: !!DT.deep};
    $VISIBLE_FN
    var el = null;
    $target
    ${if (hasTarget) "o.attached = !!(el && el.isConnected); o.visible = vis(el);" else ""}
    $textPart
    return JSON.stringify(o);
  } catch (e) {
    return JSON.stringify({error: 'wait probe failed: ' + ((e && e.message) || e)});
  }
})()
""".trim()
    }

    // ── select_option ────────────────────────────────────────────────────

    /** Chooses an option of a native `<select>` by value, then exact label, then label substring. */
    fun selectOption(selector: String?, ref: String?, option: String): String = BrowserUseJS.guardedRead("""
        (function() {
            ${resolveOne(selector, ref)}
            if (el.tagName !== 'SELECT') return JSON.stringify({error: 'select_option needs a <select> element, got <' + el.tagName.toLowerCase() + '>. For a custom dropdown, click it, take a snapshot and click the option.'});
            if (el.disabled) return JSON.stringify({error: 'Element is disabled', tag: el.tagName});
            var want = ${q(option)}, norm = function(s){ return String(s == null ? '' : s).replace(/\s+/g, ' ').trim(); };
            var opts = el.options, hit = -1, i;
            for (i = 0; i < opts.length && hit < 0; i++) if (opts[i].value === want) hit = i;
            for (i = 0; i < opts.length && hit < 0; i++) if (norm(opts[i].textContent) === norm(want)) hit = i;
            for (i = 0; i < opts.length && hit < 0; i++) if (norm(opts[i].textContent).toLowerCase().indexOf(norm(want).toLowerCase()) >= 0) hit = i;
            if (hit < 0) {
                var avail = [];
                for (i = 0; i < opts.length && i < 30; i++) avail.push(norm(opts[i].textContent).slice(0, 40));
                return JSON.stringify({error: 'No option matches ' + JSON.stringify(want) + '. Options: ' + avail.join(' | ') + (opts.length > 30 ? ' | …' : '')});
            }
            if (opts[hit].disabled) return JSON.stringify({error: 'That option is disabled: ' + norm(opts[hit].textContent)});
            el.focus();
            if (el.multiple) opts[hit].selected = true; else el.selectedIndex = hit;
            el.dispatchEvent(new Event('input', {bubbles: true}));
            el.dispatchEvent(new Event('change', {bubbles: true}));
            return JSON.stringify({selectedOption: true, label: norm(opts[hit].textContent).slice(0, 100), optionValue: String(opts[hit].value).slice(0, 100), index: hit});
        })()
    """.trimIndent(), requiresBody = false)
}
