(function(){
  var data = window.SKTRACE_DATA;
  if (!data) return;
  var total = data.tickTotal || [];
  var duration = data.tickDuration || [];
  var triggers = data.triggers || [];
  var functions = data.functions || [];
  // Skript's periodic full rewrites of variables.csv during the window (see VariableFlushTracker).
  // Drawn as bands on the tick chart and listed in the Variables section. Empty when none occurred.
  var varFlush = data.varFlush || { ok:false };
  var flushes = (varFlush.ok && varFlush.events) || [];
  var ns = total.length;
  var overheadNs = data.overheadNs || 0;
  var windowMs = data.windowMs || (ns * 50);

  // Sort state per table. String cols default asc on first click, num cols default desc.
  var defaultSort = {
    trig: { col:'totalNs', dir:'desc' },
    brk:  { col:'totalNs', dir:'desc' },
    fn:   { col:'totalNs', dir:'desc' }
  };
  var sortState = {
    trig: { col:'totalNs', dir:'desc' },
    brk:  { col:'totalNs', dir:'desc' },
    fn:   { col:'totalNs', dir:'desc' }
  };
  var STRING_COLS = { script:1, name:1 };

  // Top-N row collapsing for the long tables. Show the first TOP_N rows, hide the rest behind a
  // "Show N more" toggle. Re-applied on every render so the visible rows track the current sort;
  // the expanded/collapsed choice persists per table across re-sorts.
  var TOP_N = 5;
  var rowsExpanded = { trig:false, fn:false, events:false, brk:false };

  function isDefaultSort(key){
    return sortState[key].col === defaultSort[key].col
        && sortState[key].dir === defaultSort[key].dir;
  }
  function sortRows(rows, state, getValue){
    var dir = state.dir === 'asc' ? 1 : -1;
    rows.sort(function(a, b){
      var av = getValue(a, state.col);
      var bv = getValue(b, state.col);
      if (typeof av === 'string'){
        return String(av).localeCompare(String(bv)) * dir;
      }
      return ((av || 0) - (bv || 0)) * dir;
    });
  }
  function updateHeaderState(tableSelector, key){
    var table = typeof tableSelector === 'string' ? document.querySelector(tableSelector) : tableSelector;
    if (!table) return;
    var s = sortState[key];
    var ths = table.querySelectorAll('th');
    for (var i = 0; i < ths.length; i++){
      ths[i].classList.remove('sort-asc', 'sort-desc');
      if (ths[i].getAttribute('data-sort') === s.col){
        ths[i].classList.add(s.dir === 'asc' ? 'sort-asc' : 'sort-desc');
      }
    }
  }
  function updateResetButton(key){
    var btn = document.querySelector('.sort-reset[data-target="' + key + '"]');
    if (btn) btn.hidden = isDefaultSort(key);
  }

  // Collapse a freshly-rendered table to its top TOP_N rows, inserting (once) a "Show N more"
  // toggle after the table. Called after each (re)render so the kept rows always reflect the
  // current sort order; rowsExpanded[key] remembers the user's choice across re-renders.
  function applyRowLimit(key, tbody){
    if (!tbody) return;
    var dataRows = [];
    for (var i = 0; i < tbody.children.length; i++){
      var tr = tbody.children[i];
      if (tr.querySelector && tr.querySelector('td.empty')) continue;   // skip placeholder rows
      dataRows.push(tr);
    }
    var toggle = document.getElementById(key + '-rows-toggle');
    if (dataRows.length <= TOP_N){
      if (toggle) toggle.hidden = true;
      return;
    }
    if (!toggle){
      var wrap = tbody.closest('.table-wrap');
      toggle = document.createElement('button');
      toggle.id = key + '-rows-toggle';
      toggle.className = 'rows-toggle';
      toggle.setAttribute('type', 'button');
      if (wrap && wrap.parentNode) wrap.parentNode.insertBefore(toggle, wrap.nextSibling);
    }
    toggle.hidden = false;
    var hiddenCount = dataRows.length - TOP_N;
    function paint(){
      var open = rowsExpanded[key];
      for (var i = TOP_N; i < dataRows.length; i++) dataRows[i].style.display = open ? '' : 'none';
      toggle.innerHTML = (open ? 'Show fewer' : 'Show ' + fmtInt(hiddenCount) + ' more') + ' <span class="chev">▾</span>';
      toggle.setAttribute('aria-expanded', String(open));
    }
    paint();
    toggle.onclick = function(){ rowsExpanded[key] = !rowsExpanded[key]; paint(); };
  }

  var SVG_NS = 'http://www.w3.org/2000/svg';
  function el(name, attrs){
    var e = document.createElementNS(SVG_NS, name);
    if (attrs) for (var k in attrs) e.setAttribute(k, attrs[k]);
    return e;
  }
  function esc(s){
    return String(s).replace(/[&<>"']/g, function(c){
      return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c];
    });
  }
  function fmtMs(n){ return (n / 1e6).toFixed(2) }
  function fmtMs1(n){ return (n / 1e6).toFixed(1) }
  function fmtInt(n){ return n.toLocaleString('en-US') }
  function fmtBytes(n){
    if (n == null || n < 0) return '—';
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
    return (n / 1073741824).toFixed(2) + ' GB';
  }
  function fmtDur(ms){
    if (ms == null) return 'duration n/a';
    return ms >= 1000 ? '~' + (ms / 1000).toFixed(2) + ' s' : '~' + Math.round(ms) + ' ms';
  }

  // Floating tooltip shared by donut + histogram hover.
  var tipEl = document.getElementById('tip');
  function showTip(e, html){ if (!tipEl) return; tipEl.innerHTML = html; tipEl.classList.add('show'); moveTip(e); }
  function moveTip(e){ if (!tipEl) return; tipEl.style.left = e.clientX + 'px'; tipEl.style.top = e.clientY + 'px'; }
  function hideTip(){ if (tipEl) tipEl.classList.remove('show'); }
  // Read a CSS custom property so canvas/SVG colors track the active theme.
  function cssVar(n){ return getComputedStyle(document.documentElement).getPropertyValue(n).trim(); }

  // ===========================================================
  // DONUT: Time by script
  // ===========================================================
  function renderDonut(){
    var donutEl = document.getElementById('donut');
    var legend = document.getElementById('donut-legend');
    if (!donutEl) return;
    var byScript = {};
    var grandTotal = 0;
    for (var i = 0; i < triggers.length; i++){
      var t = triggers[i];
      byScript[t.script] = (byScript[t.script] || 0) + t.totalNs;
      grandTotal += t.totalNs;
    }
    var entries = Object.keys(byScript).map(function(k){ return { name:k, ns:byScript[k] } });
    entries.sort(function(a,b){ return b.ns - a.ns });

    if (entries.length === 0 || grandTotal === 0){
      donutEl.innerHTML = '<div style="color:var(--text-3);font-size:12px;text-align:center;padding-top:60px">No trigger time</div>';
      if (legend) legend.innerHTML = '';
      return;
    }

    var top = entries.slice(0, 5);
    var rest = entries.slice(5);
    var otherSum = 0;
    for (var i = 0; i < rest.length; i++) otherSum += rest[i].ns;
    if (otherSum > 0) top.push({ name:'other ('+rest.length+' skripts)', ns:otherSum, isOther:true });

    // Orange-family palette via CSS vars so it tracks light/dark theme.
    var segVars = ['--seg-1','--seg-2','--seg-3','--seg-4','--seg-5','--seg-6'];

    var size = 150, cx = size/2, cy = size/2, R = 64, rIn = 46;
    var svg = el('svg', { viewBox:'0 0 '+size+' '+size });
    function ptOn(r, a){ return [cx + r*Math.cos(a), cy + r*Math.sin(a)]; }
    var a0 = -Math.PI/2;
    var segPaths = [];
    function markLegend(idx, on){
      if (!legend) return;
      var li = legend.querySelectorAll('li')[idx];
      if (li) li.classList.toggle('on', on);
    }
    for (var i = 0; i < top.length; i++){
      var seg = top[i];
      var frac = seg.ns / grandTotal;
      var a1 = a0 + frac * Math.PI * 2;
      var mid = (a0 + a1) / 2;
      var d, dx, dy;
      if (frac >= 0.999){
        // One script (or one holding ~all the time) spans the whole circle. An SVG
        // arc whose start and end points coincide draws nothing, so a single-segment
        // donut would silently vanish — render it as a full ring instead: two 180°
        // arcs for the outer edge, two for the inner hole. Outer winds one way and
        // inner the other so the nonzero fill rule still punches out the centre,
        // matching the wedge winding (outer sweep 1, inner sweep 0).
        var ot = ptOn(R, -Math.PI/2), ob = ptOn(R, Math.PI/2);
        var it = ptOn(rIn, -Math.PI/2), ib = ptOn(rIn, Math.PI/2);
        d = 'M' + ot[0].toFixed(2) + ' ' + ot[1].toFixed(2)
          + ' A' + R + ' ' + R + ' 0 1 1 ' + ob[0].toFixed(2) + ' ' + ob[1].toFixed(2)
          + ' A' + R + ' ' + R + ' 0 1 1 ' + ot[0].toFixed(2) + ' ' + ot[1].toFixed(2) + ' Z'
          + ' M' + it[0].toFixed(2) + ' ' + it[1].toFixed(2)
          + ' A' + rIn + ' ' + rIn + ' 0 1 0 ' + ib[0].toFixed(2) + ' ' + ib[1].toFixed(2)
          + ' A' + rIn + ' ' + rIn + ' 0 1 0 ' + it[0].toFixed(2) + ' ' + it[1].toFixed(2) + ' Z';
        dx = '0.00'; dy = '0.00'; // a centred ring has no direction to pop toward
      } else {
        var large = (a1 - a0) > Math.PI ? 1 : 0;
        var o0 = ptOn(R, a0), o1 = ptOn(R, a1), i1 = ptOn(rIn, a1), i0 = ptOn(rIn, a0);
        d = 'M' + o0[0].toFixed(2) + ' ' + o0[1].toFixed(2)
          + ' A' + R + ' ' + R + ' 0 ' + large + ' 1 ' + o1[0].toFixed(2) + ' ' + o1[1].toFixed(2)
          + ' L' + i1[0].toFixed(2) + ' ' + i1[1].toFixed(2)
          + ' A' + rIn + ' ' + rIn + ' 0 ' + large + ' 0 ' + i0[0].toFixed(2) + ' ' + i0[1].toFixed(2) + ' Z';
        dx = (Math.cos(mid) * 5).toFixed(2); dy = (Math.sin(mid) * 5).toFixed(2);
      }
      var p = el('path', { d:d, fill:'var(' + segVars[i % segVars.length] + ')', 'class':'donut-seg' });
      var pct = (100 * frac).toFixed(1);
      (function(p, seg, pct, dx, dy, idx){
        p.addEventListener('mouseenter', function(e){
          p.style.transform = 'translate(' + dx + 'px,' + dy + 'px)';
          markLegend(idx, true);
          showTip(e, esc(seg.name) + ' \u00b7 <b>' + fmtMs1(seg.ns) + 'ms</b> (' + pct + '%)');
        });
        p.addEventListener('mousemove', moveTip);
        p.addEventListener('mouseleave', function(){ p.style.transform = ''; markLegend(idx, false); hideTip(); });
        p._dx = dx; p._dy = dy; p._tip = esc(seg.name) + ' \u00b7 <b>' + fmtMs1(seg.ns) + 'ms</b> (' + pct + '%)';
      })(p, seg, pct, dx, dy, i);
      svg.appendChild(p);
      segPaths.push(p);
      a0 = a1;
    }
    donutEl.innerHTML = '';
    donutEl.appendChild(svg);
    var center = document.createElement('div');
    center.className = 'donut-center';
    var winSec = (windowMs / 1000).toFixed(1);
    // Shrink the center number for big totals (laggy servers, large ms values) so
    // it stays inside the donut hole instead of spilling over the ring. Normal
    // small totals keep the full size.
    var valStr = fmtMs1(grandTotal);
    var vSize = valStr.length >= 8 ? 14 : valStr.length >= 7 ? 16 : valStr.length >= 6 ? 18 : 21;
    center.innerHTML = '<div class="v" style="font-size:' + vSize + 'px">' + valStr + '<span class="u" style="font-size:11px;color:var(--text-3);margin-left:2px">ms</span></div><div class="u">over ' + winSec + 's</div>';
    donutEl.appendChild(center);

    if (legend){
      var html = '<ul>';
      for (var i = 0; i < top.length; i++){
        var seg = top[i];
        var pct = (100 * seg.ns / grandTotal).toFixed(1);
        html += '<li data-idx="' + i + '">'
          + '<div class="swatch" style="background:var(' + segVars[i % segVars.length] + ')"></div>'
          + '<div class="lname" title="' + esc(seg.name) + ' \u2014 ' + fmtMs1(seg.ns) + ' ms">' + esc(seg.name) + '</div>'
          + '<div class="lpct">' + pct + '%</div>'
          + '</li>';
      }
      html += '</ul>';
      legend.innerHTML = html;
      // Hovering a legend row pops its matching segment and vice versa.
      var lis = legend.querySelectorAll('li');
      for (var i = 0; i < lis.length; i++){
        (function(li, p){
          if (!p) return;
          li.addEventListener('mouseenter', function(e){ p.style.transform = 'translate(' + p._dx + 'px,' + p._dy + 'px)'; li.classList.add('on'); showTip(e, p._tip); });
          li.addEventListener('mousemove', moveTip);
          li.addEventListener('mouseleave', function(){ p.style.transform = ''; li.classList.remove('on'); hideTip(); });
        })(lis[i], segPaths[i]);
      }
    }
  }

  // ===========================================================
  // HISTOGRAM: Tick duration distribution (Skript time per tick)
  // ===========================================================
  function renderHistogram(){
    var hist = document.getElementById('hist');
    if (!hist) return;
    var buckets = [
      { lo:0,    hi:10,  count:0 },
      { lo:10,   hi:20,  count:0 },
      { lo:20,   hi:30,  count:0 },
      { lo:30,   hi:40,  count:0 },
      { lo:40,   hi:50,  count:0 },
      { lo:50,   hi:Infinity, count:0, over:true }
    ];
    var sorted = [];
    for (var i = 0; i < total.length; i++){
      var ms = total[i] / 1e6;
      sorted.push(ms);
      for (var b = 0; b < buckets.length; b++){
        if (ms >= buckets[b].lo && ms < buckets[b].hi){ buckets[b].count++; break }
      }
    }
    sorted.sort(function(a,b){ return a-b });
    var n = sorted.length;
    var p50 = n > 0 ? sorted[Math.floor(n*0.5)] : 0;
    var p95 = n > 0 ? sorted[Math.floor(n*0.95)] : 0;
    var p99 = n > 0 ? sorted[Math.floor(n*0.99)] : 0;

    var maxCount = 0;
    for (var i = 0; i < buckets.length; i++) if (buckets[i].count > maxCount) maxCount = buckets[i].count;

    var html = '';
    for (var i = 0; i < buckets.length; i++){
      var b = buckets[i];
      var h = maxCount > 0 ? (b.count / maxCount) * 100 : 0;
      var barH = h * 0.85;
      html += '<div class="hist-col">'
        + '<div class="hist-count">' + (b.count > 0 ? b.count : '') + '</div>'
        + '<div class="hist-bar' + (b.over ? ' over' : '') + (b.count === 0 ? ' empty' : '') + '" style="height:' + barH + '%"></div>'
        + '</div>';
    }
    var labels = ['0\u201310', '10\u201320', '20\u201330', '30\u201340', '40\u201350', '50+'];
    hist.innerHTML = html;

    // Hover each column for an exact count + range tooltip.
    var cols = hist.querySelectorAll('.hist-col');
    for (var ci = 0; ci < cols.length; ci++){
      (function(col, b, label){
        col.addEventListener('mouseenter', function(e){ showTip(e, '<b>' + b.count + '</b> tick' + (b.count === 1 ? '' : 's') + ' \u00b7 ' + label + ' ms'); });
        col.addEventListener('mousemove', moveTip);
        col.addEventListener('mouseleave', hideTip);
      })(cols[ci], buckets[ci], labels[ci]);
    }

    var labelsEl = document.getElementById('hist-labels');
    if (labelsEl) labelsEl.innerHTML = labels.map(function(l){return '<span>'+l+'</span>'}).join('');

    var sumEl = document.getElementById('hist-summary');
    if (sumEl) sumEl.innerHTML =
      '<span><b>p50</b><span class="v">' + p50.toFixed(1) + ' ms</span></span>' +
      '<span><b>p95</b><span class="v">' + p95.toFixed(1) + ' ms</span></span>' +
      '<span><b>p99</b><span class="v">' + p99.toFixed(1) + ' ms</span></span>';
  }

  // ===========================================================
  // SCRIPT MODAL
  // ===========================================================
  var modalEl = document.getElementById('script-modal');
  var modalScript = document.getElementById('modal-script');
  var modalTrigger = document.getElementById('modal-trigger');
  var modalHotlines = document.getElementById('modal-hotlines');
  var modalBody = document.getElementById('modal-body');
  var modalFoot = document.getElementById('modal-foot');

  function highlight(line){
    var safe = line.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    var commentMatch = safe.match(/^(\s*)(#.*)$/);
    if (commentMatch){
      return commentMatch[1] + '<span class="tok-com">' + commentMatch[2] + '</span>';
    }
    safe = safe.replace(/"([^"]*)"/g, '<span class="tok-str">&quot;$1&quot;</span>');
    safe = safe.replace(/\{([^}]+)\}/g, '<span class="tok-var">{$1}</span>');
    safe = safe.replace(/%([^%]+)%/g, '<span class="tok-var">%$1%</span>');
    safe = safe.replace(/\b(\d+(?:\.\d+)?)\b/g, '<span class="tok-num">$1</span>');
    safe = safe.replace(/(\s|^)(\/[a-zA-Z][\w-]*)/g, '$1<span class="tok-cmd">$2</span>');
    var kwList = ['on','if','else','set','send','cancel','broadcast','command','trigger','every','wait','stop','loop','add','subtract','give','teleport','to','of','is','not','or','and','in','true','false','then','return','permission','execute','console','apply','update','play','open','close','wipe','at','for','from','with','as','due','parsed','contains'];
    var kwRe = new RegExp('\\b(' + kwList.join('|') + ')\\b','g');
    safe = safe.replace(kwRe, '<span class="tok-kw">$1</span>');
    return safe;
  }

  // Linkify calls to profiled functions inside already-highlighted source HTML, so
  // a call like `lb_update_bar_fill({_progress})` can be clicked to drill into that
  // function's own line-by-line breakdown. Built lazily from the functions list:
  // base name (sans params) -> index, plus a regex matching `name(` at a word
  // boundary. Function names are [A-Za-z0-9_], so they never appear inside the
  // highlight()'s tag/attribute markup — only as plain text — keeping this safe.
  var fnCallIndex = null, fnCallRe = null;
  function buildFnCallLookup(){
    if (fnCallIndex !== null) return;
    fnCallIndex = {};
    var names = [];
    for (var i = 0; i < functions.length; i++){
      var base = String(functions[i].name || '').replace(/\(.*$/, '').trim();
      if (!base) continue;
      if (!(base in fnCallIndex)){ fnCallIndex[base] = i; names.push(base); }
    }
    fnCallRe = names.length
      ? new RegExp('\\b(' + names.map(function(n){ return n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }).join('|') + ')\\b(?=\\s*\\()', 'g')
      : null;
  }
  function linkifyCalls(html){
    buildFnCallLookup();
    if (!fnCallRe) return html;
    return html.replace(fnCallRe, function(m, name){
      var idx = fnCallIndex[name];
      if (idx === undefined) return m;
      return '<span class="fn-call linkable" data-fnidx="' + idx + '" title="View ' + esc(name) + '() line-by-line">' + name + '</span>';
    });
  }

  function costTier(trigger, peers){
    peers = peers || triggers;
    var maxTotal = 0;
    for (var i = 0; i < peers.length; i++){
      if (peers[i].totalNs > maxTotal) maxTotal = peers[i].totalNs;
    }
    if (maxTotal === 0) return { tier:'', label:'' };
    var pct = trigger.totalNs / maxTotal;
    if (pct >= 0.6) return { tier:'high', label:'High cost' };
    if (pct >= 0.3) return { tier:'med',  label:'Medium cost' };
    if (pct >= 0.1) return { tier:'low',  label:'Low cost' };
    return { tier:'', label:'Minimal' };
  }

  function fmtNs(ns){
    if (ns < 1000) return ns.toFixed(0) + 'ns';
    if (ns < 1e6)  return (ns/1e3).toFixed(0) + '\u03bcs';
    return (ns/1e6).toFixed(2) + 'ms';
  }
  function renderHotLines(trigger, hasSource){
    var lines = trigger.lines || [];
    if (lines.length === 0) return '';
    var sorted = lines.slice().sort(function(a,b){ return b.totalNs - a.totalNs });
    var maxTotal = sorted[0].totalNs || 1;
    var top = sorted.slice(0, 5);
    var scopeNote = data.clipMode
      ? sorted.length + ' tracked \u00b7 cumulative since rolling started (not range-scoped)'
      : sorted.length + ' tracked \u00b7 sorted by total time';
    var html = '<div class="hotlines"><div class="hotlines-head">'
      + 'Hottest top-level statements'
      + '<span class="hotlines-sub">' + scopeNote + '</span>'
      + '</div><ul>';
    for (var i = 0; i < top.length; i++){
      var ln = top[i];
      var pct = (100 * ln.totalNs / maxTotal).toFixed(0);
      var lineCell;
      if (ln.line && ln.line > 0){
        // Source loaded → clickable to scroll the modal-body to that line.
        // No source → still show the line number, just not linkable.
        var cls = hasSource ? 'hl-line linkable' : 'hl-line';
        lineCell = '<div class="' + cls + '" data-line="' + ln.line + '" title="line ' + ln.line + '">' + ln.line + '</div>';
      } else {
        lineCell = '<div class="hl-line unknown" title="line not resolved">\u2014</div>';
      }
      html += '<li>'
        + lineCell
        + '<div class="hl-bar"><div class="hl-bar-fill" style="width:' + pct + '%"></div></div>'
        + '<div class="hl-label" title="' + esc(ln.label) + '">' + linkifyCalls(esc(ln.label)) + '</div>'
        + '<div class="hl-stat">'
        +   '<span class="hl-total">' + (ln.totalNs/1e6).toFixed(2) + ' ms</span>'
        +   '<span class="hl-meta">\u00d7' + fmtInt(ln.calls) + ' \u00b7 avg ' + fmtNs(ln.avgNs) + '</span>'
        + '</div>'
        + '</li>';
    }
    html += '</ul></div>';
    return html;
  }

  // For a trigger's tracked items, return a {lineNumber -> heatClass} map.
  // Top item gets heat-1, then by descending share of the hottest: 33-66% = heat-2,
  // 10-33% = heat-3, below = none. Items with line == -1 are skipped.
  function buildHeatMap(trigger){
    var lines = trigger.lines || [];
    var heat = {};
    if (lines.length === 0) return heat;
    var maxTotal = 0;
    for (var i = 0; i < lines.length; i++) if (lines[i].totalNs > maxTotal) maxTotal = lines[i].totalNs;
    if (maxTotal === 0) return heat;
    for (var i = 0; i < lines.length; i++){
      var ln = lines[i];
      if (!ln.line || ln.line < 1) continue;
      var r = ln.totalNs / maxTotal;
      var cls = '';
      if (r >= 0.66) cls = 'heat-1';
      else if (r >= 0.33) cls = 'heat-2';
      else if (r >= 0.10) cls = 'heat-3';
      if (cls) heat[ln.line] = cls;
    }
    return heat;
  }
  // Scroll the modal code view so a line sits ~a third down from the top — clearly
  // visible with context above and below. Uses getBoundingClientRect deltas against
  // the current scrollTop, which is correct regardless of offsetParent (the code
  // line's offsetParent is NOT the scroll container, so el.offsetTop was wrong and
  // landed the target just out of view).
  function scrollLineIntoView(el){
    if (!el) return;
    var bodyRect = modalBody.getBoundingClientRect();
    var elRect = el.getBoundingClientRect();
    var delta = (elRect.top - bodyRect.top) - modalBody.clientHeight * 0.32;
    modalBody.scrollTop = Math.max(0, modalBody.scrollTop + delta);
  }

  // Floating preview popover for hovering a function call (drill without clicking).
  var fnPreviewEl = document.getElementById('fn-preview');
  function showFnPreview(anchor, f){
    if (!fnPreviewEl || !f) return;
    var head = '<div class="fnp-head"><span class="fn-badge">fn</span> ' + esc(f.name)
      + '<span class="fnp-stat">' + (f.totalNs/1e6).toFixed(1) + ' ms · ×' + fmtInt(f.calls || 0) + '</span></div>';
    var body;
    var fsrc = f.source || (data.sources && data.sources[f.script]) || null;
    if (fsrc){
      var sl = fsrc.split('\n');
      var start = (f.line && f.line > 0) ? Math.max(0, f.line - (f.startLine || 1)) : 0;
      var slice = sl.slice(start, start + 7);
      body = '<div class="fnp-code">' + slice.map(function(l){ return highlight(l) || ' '; }).join('\n') + '</div>';
    } else {
      body = '<div class="fnp-note">Source not in this report. Click to open its timing breakdown.</div>';
    }
    fnPreviewEl.innerHTML = head + body + '<div class="fnp-foot">click to open · line-by-line</div>';
    fnPreviewEl.classList.add('show');
    var r = anchor.getBoundingClientRect();
    var pw = fnPreviewEl.offsetWidth, ph = fnPreviewEl.offsetHeight;
    var left = Math.max(8, Math.min(r.left, window.innerWidth - pw - 12));
    var top = r.bottom + 8;
    if (top + ph > window.innerHeight - 8) top = r.top - ph - 8; // flip above if no room below
    if (top < 8) top = 8;
    fnPreviewEl.style.left = left + 'px';
    fnPreviewEl.style.top = top + 'px';
  }
  function hideFnPreview(){ if (fnPreviewEl) fnPreviewEl.classList.remove('show'); }
  document.addEventListener('mouseover', function(e){
    var c = e.target.closest && e.target.closest('.fn-call.linkable');
    if (!c) return;
    showFnPreview(c, functions[parseInt(c.getAttribute('data-fnidx'), 10)]);
  });
  document.addEventListener('mouseout', function(e){
    var c = e.target.closest && e.target.closest('.fn-call.linkable');
    if (!c || (e.relatedTarget && c.contains(e.relatedTarget))) return;
    hideFnPreview();
  });

  function openScriptModal(trigger, peers){
    if (!modalEl || !trigger) return;
    hideFnPreview();
    var isFn = (peers === functions);
    var cost = costTier(trigger, peers || triggers);
    modalBody.classList.remove('cost-low','cost-med','cost-high');
    // Source is stored once in a shared map (data.sources[script]); fall back to an
    // inline trigger.source for reports produced before that change.
    var src = trigger.source || (data.sources && data.sources[trigger.script]) || null;
    var hasSource = !!src;
    // For functions, lead with a banner that spells out the cost being attributed
    // to this function, so it's obvious "this function is what's eating the time".
    var fnNote = isFn
      ? '<div class="fn-modal-note">This function ran <b>×' + fmtInt(trigger.calls || 0)
        + '</b> for <b>' + (trigger.totalNs/1e6).toFixed(1) + ' ms</b> total'
        + (trigger.maxNs ? ' · worst call <b>' + (trigger.maxNs/1e6).toFixed(2) + ' ms</b>' : '')
        + '. The lines below show where that time went.</div>'
      : '';
    modalHotlines.innerHTML = fnNote + renderHotLines(trigger, hasSource);

    if (!hasSource){
      modalScript.textContent = trigger.script;
      var why = data.sourcesIncluded
        ? 'Source file not found in plugins/Skript/scripts/.'
        : 'Skript source was not included in this report. Run /sktrace report --include-files to include it.';
      modalTrigger.innerHTML = (isFn ? '<span class="fn-badge">fn</span> ' : '')
        + '<span class="dim">source not available</span>';
      modalBody.innerHTML = '<div style="padding:24px;color:var(--text-3);text-align:center">' + why + '</div>';
      modalFoot.innerHTML = '';
    } else {
      modalScript.textContent = trigger.script;
      var badge = cost.tier
        ? '<span class="cost-badge ' + cost.tier + '">' + cost.label + '</span>'
        : '';
      var lineLabel = trigger.line >= 0 ? ' <span class="dim">@ line ' + trigger.line + '</span>' : '';
      var fnTag = isFn ? '<span class="fn-badge">fn</span> ' : '';
      modalTrigger.innerHTML = fnTag + '<span class="dim">\u00b7</span> ' + esc(trigger.name) + lineLabel + badge;

      if (cost.tier) modalBody.classList.add('cost-' + cost.tier);

      var startLine = trigger.startLine || 1;
      var srcLines = src.split('\n');
      var entryIdx = trigger.line >= 0
        ? Math.max(0, Math.min(srcLines.length - 1, trigger.line - startLine))
        : -1;
      var heatMap = buildHeatMap(trigger);
      // Index tracked items by source line so each line can show its own timing
      // in a right-hand gutter (total ms + call count, details on hover).
      var lineStats = {};
      var tracked = trigger.lines || [];
      for (var s = 0; s < tracked.length; s++){
        var ls = tracked[s];
        if (ls.line && ls.line > 0) lineStats[ls.line] = ls;
      }
      var html = '';
      for (var i = 0; i < srcLines.length; i++){
        var lineNum = startLine + i;
        var classes = 'code-line';
        if (i === entryIdx){
          classes += cost.tier ? ' hl cost-' + cost.tier : ' hl';
        }
        var heatCls = heatMap[lineNum];
        if (heatCls) classes += ' ' + heatCls;
        var st = lineStats[lineNum];
        var timeCell;
        if (st){
          timeCell = '<div class="ltime" title="' + fmtInt(st.calls) + ' call' + (st.calls === 1 ? '' : 's')
            + ' \u00b7 avg ' + fmtNs(st.avgNs) + ' \u00b7 max ' + fmtNs(st.maxNs) + '">'
            + '<span class="lt-ms">' + (st.totalNs / 1e6).toFixed(2) + ' ms</span>'
            + '<span class="lt-calls">\u00d7' + fmtInt(st.calls) + '</span>'
            + '</div>';
        } else {
          timeCell = '<div class="ltime empty"></div>';
        }
        html += '<div class="' + classes + '" data-line="' + lineNum + '" data-idx="' + i + '">'
          + '<div class="lnum">' + lineNum + '</div>'
          + '<div class="lcode">' + (linkifyCalls(highlight(srcLines[i])) || '&nbsp;') + '</div>'
          + timeCell
          + '</div>';
      }
      // Wrap in a max-content sizer so every row stretches to the WIDEST line's
      // width. Without this, short rows only span the viewport, so on horizontal
      // scroll their highlight backgrounds and the sticky timing gutter cut off.
      modalBody.innerHTML = '<div class="code-wrap">' + html + '</div>';

      requestAnimationFrame(function(){
        var entryEl = modalBody.querySelector('.code-line.hl');
        if (entryEl) scrollLineIntoView(entryEl);
      });

      modalFoot.innerHTML =
        '<span><span class="mono">' + (trigger.totalNs/1e6).toFixed(1) + ' ms</span> total \u00b7 '
        + '<span class="mono">' + (trigger.calls || 0).toLocaleString() + '</span> calls \u00b7 '
        + 'max <span class="mono">' + (trigger.maxNs/1e6).toFixed(2) + ' ms</span></span>'
        + '<span><span class="hint-key">Esc</span> to close</span>';
    }
    modalEl.hidden = false;
    document.body.style.overflow = 'hidden';
  }
  function closeModal(){
    if (!modalEl) return;
    modalEl.hidden = true;
    document.body.style.overflow = '';
  }
  var closeBtn = document.getElementById('modal-close');
  if (closeBtn) closeBtn.addEventListener('click', closeModal);
  if (modalEl) modalEl.addEventListener('click', function(e){ if (e.target === modalEl) closeModal(); });
  document.addEventListener('keydown', function(e){ if (e.key === 'Escape' && modalEl && !modalEl.hidden) closeModal(); });

  // Theme toggle: flip light/dark, persist, and redraw the chart so its
  // JS-set colors (which can't use CSS vars) track the new theme.
  var themeToggle = document.getElementById('theme-toggle');
  if (themeToggle) themeToggle.addEventListener('click', function(){
    var h = document.documentElement;
    var next = h.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
    h.setAttribute('data-theme', next);
    try { localStorage.setItem('sktrace-theme', next); } catch(e){}
    if (ns > 0) drawChart();
  });

  // Share button: copies the real report URL (captured before the address bar was
  // masked). Only meaningful on the hosted view, so it stays hidden for file://.
  var toastEl = document.getElementById('toast');
  var toastTimer = null;
  function showToast(msg){
    if (!toastEl) return;
    toastEl.textContent = msg;
    toastEl.classList.add('show');
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function(){ toastEl.classList.remove('show'); }, 1800);
  }
  var shareBtn = document.getElementById('share-btn');
  var shareUrl = window.__sktraceUrl || (location.protocol.indexOf('http') === 0 ? location.href : '');
  if (shareBtn && shareUrl){
    shareBtn.hidden = false;
    shareBtn.addEventListener('click', function(){
      function ok(){
        shareBtn.classList.add('copied');
        setTimeout(function(){ shareBtn.classList.remove('copied'); }, 1200);
        showToast('Share link copied to clipboard');
      }
      function fallback(){
        // Clipboard API needs a secure context; fall back to a hidden textarea.
        try {
          var ta = document.createElement('textarea');
          ta.value = shareUrl; ta.style.position = 'fixed'; ta.style.opacity = '0';
          document.body.appendChild(ta); ta.select();
          document.execCommand('copy'); document.body.removeChild(ta);
          ok();
        } catch(e){ showToast('Copy failed — link is in the address bar'); }
      }
      if (navigator.clipboard && navigator.clipboard.writeText){
        navigator.clipboard.writeText(shareUrl).then(ok, fallback);
      } else {
        fallback();
      }
    });
  }

  function findTriggerByScript(scriptName, triggerName){
    for (var i = 0; i < triggers.length; i++){
      if (triggers[i].script === scriptName){
        if (!triggerName || triggers[i].name === triggerName) return triggers[i];
      }
    }
    return null;
  }

  document.body.addEventListener('click', function(e){
    // Sortable header click
    var th = e.target.closest('th[data-sort]');
    if (th){
      var table = th.closest('table');
      var key = table && table.getAttribute('data-table');
      if (key && sortState[key]){
        var col = th.getAttribute('data-sort');
        var s = sortState[key];
        if (s.col === col){
          s.dir = s.dir === 'asc' ? 'desc' : 'asc';
        } else {
          s.col = col;
          s.dir = STRING_COLS[col] ? 'asc' : 'desc';
        }
        if (key === 'trig') renderTriggersTable();
        else if (key === 'brk') renderBreakdown();
        else if (key === 'fn') renderFunctionsTable();
        return;
      }
    }
    // Reset-sort button click
    var reset = e.target.closest('.sort-reset[data-target]');
    if (reset){
      var rKey = reset.getAttribute('data-target');
      if (sortState[rKey]){
        sortState[rKey] = { col: defaultSort[rKey].col, dir: defaultSort[rKey].dir };
        if (rKey === 'trig') renderTriggersTable();
        else if (rKey === 'brk') renderBreakdown();
        else if (rKey === 'fn') renderFunctionsTable();
      }
      return;
    }
    // Hot-line click → scroll the source code view to that line.
    var hlLine = e.target.closest('.hl-line.linkable');
    if (hlLine){
      var n = parseInt(hlLine.getAttribute('data-line'), 10);
      if (!isNaN(n)){
        var target = modalBody.querySelector('.code-line[data-line="' + n + '"]');
        if (target){
          scrollLineIntoView(target);
          // Brief pulse so the user sees what got selected.
          target.classList.add('jump-flash');
          setTimeout(function(){ target.classList.remove('jump-flash'); }, 800);
        }
      }
      return;
    }
    // Open-script-modal click. Includes function call-sites inside the source
    // viewer / hot-lines (.fn-call), which carry a function index too.
    var el2 = e.target.closest('.script.linkable, .worst-script.linkable, .fn-call.linkable');
    if (!el2) return;
    // Function rows + call-sites carry an index into the functions array; route
    // those to the function source view (ranked against other functions).
    var fnIdx = el2.getAttribute('data-fnidx');
    if (fnIdx !== null){
      var fobj = functions[parseInt(fnIdx, 10)];
      if (fobj) openScriptModal(fobj, functions);
      return;
    }
    var scriptName = el2.getAttribute('data-script');
    var triggerName = el2.getAttribute('data-trigger');
    var t = findTriggerByScript(scriptName, triggerName);
    if (t) openScriptModal(t);
  });

  // ===========================================================
  // WORST TICK detail
  // ===========================================================
  function renderWorstTick(){
    var body = document.getElementById('worst-body');
    if (!body) return;
    if (ns === 0){
      body.innerHTML = '<div class="empty" style="padding:8px 0">No tick samples.</div>';
      return;
    }
    var worstIdx = 0, worstNs = 0;
    for (var i = 0; i < total.length; i++){
      if (total[i] > worstNs){ worstNs = total[i]; worstIdx = i }
    }
    var worstMs = worstNs / 1e6;
    var pctBudget = (worstMs / 50 * 100).toFixed(0);
    var atSec = (worstIdx / 20).toFixed(2);
    // Severity colour for the headline number + budget pill: green under 40ms,
    // amber 40-50, red at/over the 50ms server budget.
    var wtTier = worstMs < 40 ? 'wt-good' : (worstMs < 50 ? 'wt-warn' : 'wt-bad');

    var contribs = [];
    for (var k = 0; k < triggers.length; k++){
      var t = triggers[k];
      var n = (t.nanos && t.nanos[worstIdx]) || 0;
      if (n > 0) contribs.push({ script:t.script, line:t.line, name:t.name, ns:n });
    }
    contribs.sort(function(a,b){ return b.ns - a.ns });

    var realMs = (duration[worstIdx] || 0) / 1e6;
    var realSuffix = realMs > 0 ? ' \u00b7 real MSPT ' + realMs.toFixed(1) + 'ms' : '';

    var html = '<div class="worst-hero ' + wtTier + '">'
      + '<span class="worst-ms">' + worstMs.toFixed(1) + '<span class="unit" style="color:var(--text-3);font-size:14px;margin-left:2px">ms</span></span>'
      + '<span class="worst-budget">' + pctBudget + '% of budget</span>'
      + '</div>'
      + '<div class="worst-when">at ' + atSec + 's into capture \u00b7 tick #' + worstIdx + realSuffix + '</div>';

    if (contribs.length === 0){
      html += '<div class="worst-foot">No trigger activity tracked in this tick.</div>';
    } else {
      html += '<div class="worst-list">';
      var top = contribs.slice(0, 4);
      for (var i = 0; i < top.length; i++){
        var c = top[i];
        var lno = c.line >= 0 ? '<span class="lno">:' + c.line + '</span>' : '';
        html += '<div class="worst-row">'
          + '<span class="worst-rank">' + (i + 1) + '</span>'
          + '<span class="worst-script linkable" data-script="' + esc(c.script) + '" data-trigger="' + esc(c.name) + '" title="' + esc(c.script) + '">' + esc(c.script) + lno + '</span>'
          + '<span class="worst-val">' + (c.ns / 1e6).toFixed(2) + '<span class="unit"> ms</span></span>'
          + '</div>';
      }
      html += '</div>';
      if (contribs.length > 4){
        html += '<div class="worst-foot">+ ' + (contribs.length - 4) + ' other triggers fired this tick</div>';
      }
    }

    body.innerHTML = html;
  }

  // ===========================================================
  // TRIGGERS TABLE
  // ===========================================================
  function trigValue(t, col){
    if (col === 'script')  return t.script || '';
    if (col === 'name')    return t.name || '';
    if (col === 'calls')   return t.calls;
    if (col === 'totalNs') return t.totalNs;
    if (col === 'avgNs')   return t.avgNs;
    if (col === 'maxNs')   return t.maxNs;
    return 0;
  }
  function renderTriggersTable(){
    var tbody = document.getElementById('trig-tbody');
    if (!tbody) return;
    if (triggers.length === 0){
      tbody.innerHTML = '<tr><td colspan="8" class="empty">No trigger calls recorded.</td></tr>';
      updateHeaderState('table[data-table="trig"]', 'trig');
      updateResetButton('trig');
      return;
    }
    var maxTotal = 0;
    for (var i = 0; i < triggers.length; i++) if (triggers[i].totalNs > maxTotal) maxTotal = triggers[i].totalNs;

    var sorted = triggers.slice();
    sortRows(sorted, sortState.trig, trigValue);
    var isDefault = isDefaultSort('trig');
    var html = '';
    for (var i = 0; i < sorted.length; i++){
      var t = sorted[i];
      var pct = maxTotal > 0 ? (100 * t.totalNs / maxTotal).toFixed(1) : 0;
      // "ms-hot" tinting is only meaningful for total-desc ordering — when the user
      // re-sorts by another column, top-3 isn't necessarily highest-cost anymore.
      var msCls = (isDefault && i < 3) ? 'num ms-hot' : 'num ms';
      var maxMs = t.maxNs / 1e6;
      var maxCls = maxMs >= 5 ? 'num ms-bad' : 'num';
      var lno = t.line >= 0 ? '<span class="lno">:' + t.line + '</span>' : '';
      html += '<tr>'
        + '<td class="heat-cell"><div class="heat-track"><div class="heat-bar" style="width:' + pct + '%"></div></div></td>'
        + '<td><span class="script linkable" data-script="' + esc(t.script) + '" data-trigger="' + esc(t.name) + '" title="' + esc(t.script) + '">' + esc(t.script) + lno + '</span></td>'
        + '<td class="trig-name"><span class="trig-clip" title="' + esc(t.name) + '">' + esc(t.name) + '</span></td>'
        + '<td class="spark-cell">' + makeSparkline(t.nanos) + '</td>'
        + '<td class="num dim">' + fmtInt(t.calls) + '</td>'
        + '<td class="' + msCls + '">' + (t.totalNs/1e6).toFixed(1) + '<span class="unit">ms</span></td>'
        + '<td class="num dim">' + formatTime(t.avgNs) + '</td>'
        + '<td class="' + maxCls + '">' + maxMs.toFixed(2) + '<span class="unit">ms</span></td>'
        + '</tr>';
    }
    tbody.innerHTML = html;
    updateHeaderState('table[data-table="trig"]', 'trig');
    updateResetButton('trig');
    applyRowLimit('trig', tbody);
  }

  // ===========================================================
  // FUNCTIONS TABLE — a separate layer from triggers (function
  // time nests inside its caller's trigger time). Click a row to
  // open the same source viewer used for triggers, scrolled to the
  // function body with per-line timing in the gutter.
  // ===========================================================
  function fnValue(f, col){
    if (col === 'script')  return f.script || '';
    if (col === 'name')    return f.name || '';
    if (col === 'calls')   return f.calls;
    if (col === 'totalNs') return f.totalNs;
    if (col === 'avgNs')   return f.avgNs;
    if (col === 'maxNs')   return f.maxNs;
    return 0;
  }
  function renderFunctionsTable(){
    var tbody = document.getElementById('fn-tbody');
    var section = document.getElementById('functions-section');
    if (!tbody) return;
    if (functions.length === 0){
      if (section) section.hidden = true;
      return;
    }
    if (section) section.hidden = false;
    var maxTotal = 0;
    for (var i = 0; i < functions.length; i++) if (functions[i].totalNs > maxTotal) maxTotal = functions[i].totalNs;

    var sorted = functions.slice();
    sortRows(sorted, sortState.fn, fnValue);
    var isDefault = isDefaultSort('fn');
    var html = '';
    for (var i = 0; i < sorted.length; i++){
      var f = sorted[i];
      var idx = functions.indexOf(f);
      var pct = maxTotal > 0 ? (100 * f.totalNs / maxTotal).toFixed(1) : 0;
      var msCls = (isDefault && i < 3) ? 'num ms-hot' : 'num ms';
      var maxMs = f.maxNs / 1e6;
      var maxCls = maxMs >= 5 ? 'num ms-bad' : 'num';
      var lno = f.line >= 0 ? '<span class="lno">:' + f.line + '</span>' : '';
      html += '<tr>'
        + '<td class="heat-cell"><div class="heat-track"><div class="heat-bar" style="width:' + pct + '%"></div></div></td>'
        + '<td><span class="script linkable" data-fnidx="' + idx + '" title="' + esc(f.script) + '">' + esc(f.script) + lno + '</span></td>'
        + '<td class="trig-name"><span class="fn-badge">fn</span> <span class="trig-clip" title="' + esc(f.name) + '">' + esc(f.name) + '</span></td>'
        + '<td class="spark-cell">' + makeSparkline(f.nanos) + '</td>'
        + '<td class="num dim">' + fmtInt(f.calls) + '</td>'
        + '<td class="' + msCls + '">' + (f.totalNs/1e6).toFixed(1) + '<span class="unit">ms</span></td>'
        + '<td class="num dim">' + formatTime(f.avgNs) + '</td>'
        + '<td class="' + maxCls + '">' + maxMs.toFixed(2) + '<span class="unit">ms</span></td>'
        + '</tr>';
    }
    tbody.innerHTML = html;
    updateHeaderState('table[data-table="fn"]', 'fn');
    updateResetButton('fn');
    applyRowLimit('fn', tbody);
  }
  function formatTime(nsv){
    if (nsv < 1000) return nsv.toFixed(0) + '<span class="unit">ns</span>';
    if (nsv < 1e6) return (nsv/1e3).toFixed(0) + '<span class="unit">\u03bcs</span>';
    return (nsv/1e6).toFixed(2) + '<span class="unit">ms</span>';
  }
  function makeSparkline(arr){
    if (!arr || arr.length === 0) return '';
    var w = 80, h = 24;
    var bins = 40;
    var binSize = Math.max(1, Math.ceil(arr.length / bins));
    var binned = [];
    var maxBin = 0;
    for (var b = 0; b < bins; b++){
      var s = 0;
      for (var k = 0; k < binSize; k++){
        var idx = b * binSize + k;
        if (idx < arr.length) s += arr[idx];
      }
      binned.push(s);
      if (s > maxBin) maxBin = s;
    }
    if (maxBin === 0) maxBin = 1;
    var fillPts = '0,' + h;
    var linePts = '';
    for (var i = 0; i < binned.length; i++){
      var x = binned.length === 1 ? w/2 : (i / (binned.length - 1)) * w;
      var y = h - (binned[i] / maxBin) * (h - 2) - 1;
      fillPts += ' ' + x.toFixed(1) + ',' + y.toFixed(1);
      linePts += (i === 0 ? '' : ' ') + x.toFixed(1) + ',' + y.toFixed(1);
    }
    fillPts += ' ' + w + ',' + h;
    return '<svg class="spark" viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none">'
      + '<polygon class="spark-fill" points="' + fillPts + '"/>'
      + '<polyline class="spark-line" points="' + linePts + '"/>'
      + '</svg>';
  }

  // ===========================================================
  // TICK CHART
  // ===========================================================
  var svg = document.querySelector('.tickgraph');
  var statsEl = document.querySelector('.tick-stats');
  var breakdownEl = document.getElementById('selection-breakdown');
  var resetBtn = document.getElementById('reset-zoom');

  var padX = 50, padTop = 18, padBottom = 28;
  var viewW = 1200, viewH = 280;
  var chartW = viewW - padX * 2;
  var chartH = viewH - padTop - padBottom;

  // Render the chart in real device pixels. The SVG uses preserveAspectRatio="none",
  // which stretches a fixed 1200x280 viewBox to fit any width — on a phone that
  // squashes every axis label horizontally to ~28% width. Matching the viewBox to
  // the element's actual pixel box keeps 1 user unit = 1 px, so text and the curve
  // stay crisp at any screen size. Recomputed on each draw (and on resize).
  function measureChart(){
    if (!svg) return;
    var rect = svg.getBoundingClientRect();
    viewW = Math.max(320, Math.round(rect.width) || 1200);
    viewH = Math.round(rect.height) || 280;
    chartW = viewW - padX * 2;
    chartH = viewH - padTop - padBottom;
    svg.setAttribute('viewBox', '0 0 ' + viewW + ' ' + viewH);
  }

  var viewLo = 0, viewHi = Math.max(0, ns - 1);
  var dragStart = null, dragEnd = null;
  var selRect = null;

  function clearSvg(){
    if (!svg) return;
    while (svg.firstChild) svg.removeChild(svg.firstChild);
    selRect = null;
  }

  function drawChart(){
    if (!svg) return;
    measureChart();
    clearSvg();
    if (ns === 0) return;

    // Pull theme colors so the chart recolors on light/dark toggle (SVG
    // presentation attributes can't reference CSS vars, so read them here).
    var cAccent = cssVar('--accent') || '#f7901e';
    var cAccent2 = cssVar('--accent-2') || '#e8740f';
    var cCrit = cssVar('--crit') || '#cf3636';
    var cGrid = cssVar('--grid') || 'rgba(0,0,0,.06)';
    var cGridStrong = cssVar('--grid-strong') || 'rgba(0,0,0,.16)';
    var cAxis = cssVar('--text-3') || '#5d666c';

    var defs = el('defs');
    var grad = el('linearGradient', { id:'fill', x1:0, y1:0, x2:0, y2:1 });
    grad.appendChild(el('stop', { offset:'0%', 'stop-color':cAccent, 'stop-opacity':'0.32' }));
    grad.appendChild(el('stop', { offset:'100%', 'stop-color':cAccent, 'stop-opacity':'0' }));
    defs.appendChild(grad);
    svg.appendChild(defs);

    var span = viewHi - viewLo + 1;
    var maxNs = 0;
    for (var i = viewLo; i <= viewHi; i++){
      var v = total[i] || 0;
      if (v > maxNs) maxNs = v;
    }
    var yMaxNs = Math.max(6e7, maxNs * 1.18);
    // When a big lag spike pushes the scale into the thousands of ms, switch the whole axis to
    // seconds so labels stay short (and readable) instead of overflowing the left margin.
    var axisInSeconds = (yMaxNs / 1e6) >= 1000;

    for (var g = 0; g <= 4; g++){
      var gy = padTop + chartH * g / 4;
      var labelMs = (yMaxNs / 1e6) * (4 - g) / 4;
      if (g > 0 && g < 4) {
        svg.appendChild(el('line', {
          x1: padX, y1: gy.toFixed(1), x2: padX + chartW, y2: gy.toFixed(1),
          stroke: cGrid, 'stroke-width': 1
        }));
      }
      if (g === 4) {
        svg.appendChild(el('line', {
          x1: padX, y1: gy.toFixed(1), x2: padX + chartW, y2: gy.toFixed(1),
          stroke: cGridStrong, 'stroke-width': 1
        }));
      }
      var lt = el('text', {
        x: padX - 8, y: (gy + 3).toFixed(1),
        fill: cAxis, 'font-size': 11, 'text-anchor': 'end',
        'font-family': '"IBM Plex Mono", ui-monospace, monospace'
      });
      lt.textContent = axisInSeconds
        ? (labelMs / 1000).toFixed(1) + 's'
        : (labelMs >= 10 ? labelMs.toFixed(0) : labelMs.toFixed(1)) + 'ms';
      svg.appendChild(lt);
    }

    var startSec = viewLo / 20, endSec = viewHi / 20;
    function xLbl(x, anchor, sec){
      var t = el('text', {
        x: x, y: padTop + chartH + 18,
        fill: cAxis, 'font-size': 11,
        'font-family': '"IBM Plex Mono", ui-monospace, monospace'
      });
      if (anchor) t.setAttribute('text-anchor', anchor);
      t.textContent = sec.toFixed(2) + 's';
      svg.appendChild(t);
    }
    xLbl(padX, 'start', startSec);
    xLbl(padX + chartW / 2, 'middle', (startSec + endSec) / 2);
    xLbl(padX + chartW, 'end', endSec);

    // Downsample to one point per chart pixel using max-per-bin. For long captures
    // this turns 30000+ point strings into ~1100, which keeps SVG render time flat.
    var pixelCount = Math.min(span, Math.max(2, Math.ceil(chartW)));
    var ptsArr = [padX + ',' + (padTop + chartH)];
    var lineArr = [];
    for (var p = 0; p < pixelCount; p++){
      var lo = viewLo + ((p * span / pixelCount) | 0);
      var hi = p === pixelCount - 1 ? viewHi : viewLo + (((p + 1) * span / pixelCount) | 0) - 1;
      if (hi < lo) hi = lo;
      var v = 0;
      for (var i = lo; i <= hi; i++){
        var tv = total[i] || 0;
        if (tv > v) v = tv;
      }
      var x = padX + (pixelCount === 1 ? chartW / 2 : (p / (pixelCount - 1)) * chartW);
      var y = padTop + chartH - v / yMaxNs * chartH;
      if (y < padTop) y = padTop;
      var xs = x.toFixed(1), ys = y.toFixed(1);
      ptsArr.push(xs + ',' + ys);
      lineArr.push(xs + ',' + ys);
    }
    ptsArr.push((padX + chartW) + ',' + (padTop + chartH));
    svg.appendChild(el('polygon', { points: ptsArr.join(' '), fill: 'url(#fill)' }));
    svg.appendChild(el('polyline', {
      points: lineArr.join(' '), fill: 'none', stroke: cAccent2, 'stroke-width': 1.5,
      'stroke-linejoin': 'round', 'stroke-linecap': 'round'
    }));

    var thresholdY = padTop + chartH - 5e7 / yMaxNs * chartH;
    if (thresholdY >= padTop && thresholdY <= padTop + chartH){
      svg.appendChild(el('line', {
        x1: padX, y1: thresholdY.toFixed(1),
        x2: padX + chartW, y2: thresholdY.toFixed(1),
        stroke: cCrit, 'stroke-width': 4, opacity: '0.12',
        'stroke-linecap': 'round'
      }));
      svg.appendChild(el('line', {
        x1: padX, y1: thresholdY.toFixed(1),
        x2: padX + chartW, y2: thresholdY.toFixed(1),
        stroke: cCrit, 'stroke-width': 1, opacity: '0.6'
      }));
    }

    drawFlushBands();
  }

  // variables.csv full-save band(s). Skript's periodic rewrite holds the variable read-lock the
  // whole time, so scripts setting variables stall until it finishes — a save that overlaps a
  // tick-time spike is the likely cause. Drawn on top at low opacity, clipped to the current zoom.
  function drawFlushBands(){
    if (!flushes.length) return;
    var cSave = cssVar('--save-band') || '#7b6cf2';
    var bspan = viewHi - viewLo;
    for (var fi = 0; fi < flushes.length; fi++){
      var f = flushes[fi];
      if (f.endIdx < viewLo || f.startIdx > viewHi) continue;
      var a = Math.max(viewLo, f.startIdx), b = Math.min(viewHi, f.endIdx);
      var bx1 = padX + (bspan <= 0 ? 0 : ((a - viewLo) / bspan) * chartW);
      var bx2 = padX + (bspan <= 0 ? chartW : ((b - viewLo) / bspan) * chartW);
      var bw = Math.max(3, bx2 - bx1);
      svg.appendChild(el('rect', {
        x: bx1.toFixed(1), y: padTop, width: bw.toFixed(1), height: chartH,
        fill: cSave, 'fill-opacity': '0.14', 'pointer-events': 'none'
      }));
      svg.appendChild(el('line', {
        x1: bx1.toFixed(1), y1: padTop, x2: bx1.toFixed(1), y2: padTop + chartH,
        stroke: cSave, 'stroke-width': 1, 'stroke-opacity': '0.7', 'pointer-events': 'none'
      }));
      var lbl = el('text', {
        x: (bx1 + 3).toFixed(1), y: padTop + 11, fill: cSave, 'font-size': 10,
        'font-family': '"IBM Plex Mono", ui-monospace, monospace', 'pointer-events': 'none'
      });
      lbl.textContent = 'save';
      svg.appendChild(lbl);
    }
  }

  function updateStats(){
    if (!statsEl) return;
    var span = viewHi - viewLo + 1;
    var max = 0, sum = 0, over = 0;
    var dSum = 0, dMax = 0, dCount = 0;
    for (var i = viewLo; i <= viewHi; i++){
      var v = total[i] || 0;
      sum += v;
      if (v > max) max = v;
      if (v > 5e7) over++;
      var d = duration[i] || 0;
      if (d > 0){ dSum += d; if (d > dMax) dMax = d; dCount++ }
    }
    var avg = span > 0 ? sum / span : 0;
    var overheadPerTick = ns > 0 ? overheadNs / ns : 0;
    var overheadPct = (overheadPerTick / 5e7 * 100).toFixed(2);
    var zoomLabel = (viewLo === 0 && viewHi === ns - 1) ? 'Full window' : 'Ticks ' + viewLo + '\u2013' + viewHi;

    var realHtml = '';
    if (dCount > 0){
      var avgDur = dSum / dCount;
      var tps = Math.min(20, 1e9 / avgDur);
      var share = avgDur > 0 ? (100 * avg / avgDur).toFixed(1) : '0';
      realHtml =
        '<span><b>Avg MSPT</b><div>' + fmtMs(avgDur) + ' ms</div></span>' +
        '<span><b>Worst MSPT</b><div>' + fmtMs1(dMax) + ' ms</div></span>' +
        '<span><b>Avg TPS</b><div>' + tps.toFixed(2) + ' / 20</div></span>' +
        '<span><b>Skript share</b><div>' + share + '% of tick</div></span>';
    }

    statsEl.innerHTML =
      '<span><b>View</b><div>' + zoomLabel + '</div></span>' +
      '<span><b>Worst Skript tick</b><div>' + fmtMs1(max) + ' ms</div></span>' +
      '<span><b>Avg Skript tick</b><div>' + fmtMs(avg) + ' ms</div></span>' +
      '<span><b>Skript over 50ms</b><div>' + over + ' / ' + span +
        (over > 0 ? ' (' + (100 * over / span).toFixed(1) + '%)' : '') + '</div></span>' +
      realHtml +
      '<span><b>Sktrace overhead</b><div>' + (overheadPerTick / 1e3).toFixed(2) + ' \u03bcs/tick (' + overheadPct + '%)</div></span>';
  }

  function ensureRect(){
    if (!selRect){
      selRect = el('rect', {
        fill: cssVar('--accent-soft') || 'rgba(247,144,30,0.13)',
        stroke: cssVar('--accent-2') || '#e8740f', 'stroke-width': 1,
        y: padTop, height: chartH, 'pointer-events': 'none'
      });
      svg.appendChild(selRect);
    }
    return selRect;
  }
  function setRect(a, b){
    var span = viewHi - viewLo;
    if (span === 0) return;
    var r = ensureRect();
    var x1 = padX + ((Math.min(a, b) - viewLo) / span) * chartW;
    var x2 = padX + ((Math.max(a, b) - viewLo) / span) * chartW;
    r.setAttribute('x', x1);
    r.setAttribute('width', Math.max(2, x2 - x1));
  }
  function xToTick(clientX){
    var pt = svg.createSVGPoint();
    pt.x = clientX; pt.y = 0;
    var p = pt.matrixTransform(svg.getScreenCTM().inverse());
    var frac = (p.x - padX) / chartW;
    frac = Math.max(0, Math.min(1, frac));
    var span = viewHi - viewLo;
    return Math.round(viewLo + frac * span);
  }

  function brkValue(r, col){
    if (col === 'script')  return r.script || '';
    if (col === 'name')    return r.name || '';
    if (col === 'calls')   return r.callsInRange;
    if (col === 'totalNs') return r.sum;
    if (col === 'avgNs')   return r.avgInRange;
    if (col === 'maxNs')   return r.maxNs;
    if (col === 'pct')     return r.pct;
    return 0;
  }
  function renderBreakdown(){
    if (!breakdownEl) return;
    var span = viewHi - viewLo + 1;
    var rangeSec = (span / 20).toFixed(2);
    var rangeTotal = 0;
    for (var i = viewLo; i <= viewHi; i++) rangeTotal += total[i] || 0;

    var rows = [];
    for (var k = 0; k < triggers.length; k++){
      var t = triggers[k];
      var arr = t.nanos || [];
      var callsArr = t.tickCalls || [];
      var sum = 0, hits = 0, callsInRange = 0;
      for (var i = viewLo; i <= viewHi; i++){
        var v = i < arr.length ? arr[i] : 0;
        if (v > 0){ sum += v; hits++; }
        if (i < callsArr.length) callsInRange += callsArr[i];
      }
      if (sum > 0){
        rows.push({
          script: t.script,
          line:   t.line,
          name:   t.name,
          nanos:  arr,
          sum:    sum,
          hits:   hits,
          callsInRange: callsInRange,
          avgInRange:   callsInRange > 0 ? sum / callsInRange : 0,
          maxNs:  t.maxNs,
          pct:    rangeTotal > 0 ? (100 * sum / rangeTotal) : 0
        });
      }
    }

    sortRows(rows, sortState.brk, brkValue);
    var isDefault = isDefaultSort('brk');

    var isFull = (viewLo === 0 && viewHi === ns - 1);
    var rangeLabel = isFull
      ? 'Full window'
      : (viewLo/20).toFixed(2) + 's \u2013 ' + (viewHi/20).toFixed(2) + 's';

    var html = '<div class="breakdown-head">' +
      '<div class="breakdown-title">' + (isFull ? 'Trigger breakdown' : 'Selected range') +
        ' <span class="range">' + rangeLabel + '</span></div>' +
      '<div class="breakdown-meta">' +
        '<span><b>Duration</b><span class="mono">' + rangeSec + 's</span></span>' +
        '<span><b>Skript time</b><span class="mono">' + fmtMs1(rangeTotal) + ' ms</span></span>' +
        '<span><b>Triggers</b><span class="mono">' + rows.length + '</span></span>' +
        '<button class="sort-reset" data-target="brk" hidden>Reset sort</button>' +
      '</div></div>';

    if (rows.length === 0){
      html += '<div class="empty">No trigger activity in this range.</div>';
    } else {
      var maxSum = 0;
      for (var i = 0; i < rows.length; i++) if (rows[i].sum > maxSum) maxSum = rows[i].sum;
      html += '<div class="table-wrap"><table data-table="brk"><thead><tr>'
        + '<th class="heat-th"></th>'
        + '<th class="sortable" data-sort="script">Skript</th>'
        + '<th class="sortable" data-sort="name">Trigger</th>'
        + '<th class="spark-th">Activity</th>'
        + '<th class="num sortable" data-sort="calls">Calls</th>'
        + '<th class="num sortable" data-sort="totalNs">Total</th>'
        + '<th class="num sortable" data-sort="avgNs">Avg</th>'
        + '<th class="num sortable" data-sort="maxNs" title="Max single-call duration over the full window — Skript doesn\'t expose per-call samples, so this is not range-scoped.">Max</th>'
        + '<th class="num sortable" data-sort="pct">% of range</th>'
        + '</tr></thead><tbody>';
      // Render every row; the top-5 dropdown (applyRowLimit below) collapses the rest, the
      // same way the Triggers/Functions/Events tables do, instead of a hard 40-row cutoff.
      for (var i = 0; i < rows.length; i++){
        var r = rows[i];
        var heatW = maxSum > 0 ? (100 * r.sum / maxSum).toFixed(1) : 0;
        var msCls = (isDefault && i < 3) ? 'num ms-hot' : 'num ms';
        var maxMs = r.maxNs / 1e6;
        var maxCls = maxMs >= 5 ? 'num ms-bad' : 'num dim';
        var lno = r.line >= 0 ? '<span class="lno">:' + r.line + '</span>' : '';
        var rangeSlice = isFull ? r.nanos : r.nanos.slice(viewLo, viewHi + 1);
        html += '<tr>'
          + '<td class="heat-cell"><div class="heat-track"><div class="heat-bar" style="width:' + heatW + '%"></div></div></td>'
          + '<td><span class="script linkable" data-script="' + esc(r.script) + '" data-trigger="' + esc(r.name) + '" title="' + esc(r.script) + '">' + esc(r.script) + lno + '</span></td>'
          + '<td class="trig-name"><span class="trig-clip" title="' + esc(r.name) + '">' + esc(r.name) + '</span></td>'
          + '<td class="spark-cell">' + makeSparkline(rangeSlice) + '</td>'
          + '<td class="num dim">' + fmtInt(r.callsInRange) + '</td>'
          + '<td class="' + msCls + '">' + fmtMs(r.sum) + '<span class="unit">ms</span></td>'
          + '<td class="num dim">' + formatTime(r.avgInRange) + '</td>'
          + '<td class="' + maxCls + '">' + maxMs.toFixed(2) + '<span class="unit">ms</span></td>'
          + '<td class="num dim">' + r.pct.toFixed(1) + '<span class="unit">%</span></td>'
          + '</tr>';
      }
      html += '</tbody></table></div>';
    }
    breakdownEl.innerHTML = html;
    updateHeaderState('table[data-table="brk"]', 'brk');
    updateResetButton('brk');
    applyRowLimit('brk', breakdownEl.querySelector('table[data-table="brk"] tbody'));
  }

  function applyView(lo, hi){
    viewLo = lo; viewHi = hi;
    drawChart();
    updateStats();
    renderBreakdown();
    if (resetBtn) resetBtn.style.display = (lo === 0 && hi === ns - 1) ? 'none' : 'inline-block';
  }

  if (svg){
    svg.addEventListener('mousedown', function(e){
      if (e.button !== 0 || ns === 0) return;
      dragStart = xToTick(e.clientX); dragEnd = dragStart;
      setRect(dragStart, dragEnd);
      e.preventDefault();
    });
    window.addEventListener('mousemove', function(e){
      if (dragStart === null) return;
      dragEnd = xToTick(e.clientX);
      setRect(dragStart, dragEnd);
    });
    window.addEventListener('mouseup', function(){
      if (dragStart === null) return;
      var lo = Math.min(dragStart, dragEnd);
      var hi = Math.max(dragStart, dragEnd);
      dragStart = null;
      if (hi - lo >= 2) applyView(lo, hi);
      else if (selRect){ svg.removeChild(selRect); selRect = null; }
    });

    // Touch: same drag-to-select gesture for phones/tablets. touch-action:none on
    // the SVG stops the browser from scrolling/zooming so these handlers own the
    // gesture; preventDefault is the belt-and-braces for older mobile browsers.
    function endTouch(){
      if (dragStart === null) return;
      var lo = Math.min(dragStart, dragEnd);
      var hi = Math.max(dragStart, dragEnd);
      dragStart = null;
      if (hi - lo >= 2) applyView(lo, hi);
      else if (selRect){ svg.removeChild(selRect); selRect = null; }
    }
    svg.addEventListener('touchstart', function(e){
      if (ns === 0 || !e.touches.length) return;
      dragStart = xToTick(e.touches[0].clientX); dragEnd = dragStart;
      setRect(dragStart, dragEnd);
      e.preventDefault();
    }, { passive: false });
    svg.addEventListener('touchmove', function(e){
      if (dragStart === null || !e.touches.length) return;
      dragEnd = xToTick(e.touches[0].clientX);
      setRect(dragStart, dragEnd);
      e.preventDefault();
    }, { passive: false });
    svg.addEventListener('touchend', endTouch);
    svg.addEventListener('touchcancel', endTouch);

    // Re-measure + redraw when the viewport changes (rotate, resize) so the
    // pixel-perfect viewBox tracks the new width. rAF-throttled to one redraw
    // per frame regardless of how many resize events fire.
    var resizeRAF = null;
    window.addEventListener('resize', function(){
      if (resizeRAF) return;
      resizeRAF = requestAnimationFrame(function(){
        resizeRAF = null;
        if (ns > 0) drawChart();
      });
    });
  }
  if (resetBtn) resetBtn.addEventListener('click', function(){ applyView(0, ns - 1) });

  // ===========================================================
  // CHROME (header, warn, glance, events) — rendered from data
  // so the page is produced entirely from SKTRACE_DATA and a
  // static shell. No server-rendered, user-influenced HTML.
  // ===========================================================
  function setText(id, s){ var e = document.getElementById(id); if (e) e.textContent = s; }
  function setHtml(id, s){ var e = document.getElementById(id); if (e) e.innerHTML = s; }

  function renderChrome(){
    var clip = !!data.clipMode;
    var winSec = (windowMs / 1000);

    setText('hdr-kind', clip ? 'Clip' : 'Profile');
    setText('hdr-sub', clip
      ? ('Snapshot of the last ' + winSec.toFixed(1) + 's from the rolling buffer')
      : 'Skript performance over the captured window');
    setText('hdr-ts', data.generatedAt || '');
    setText('donut-window', 'total over ' + winSec.toFixed(1) + 's window');
    document.title = (clip ? 'Sktrace Clip' : 'Sktrace Profile')
      + (data.generatedAt ? ' \u00b7 ' + data.generatedAt : '');

    if (!data.hooksOk){
      setHtml('warn-slot', '<div class="warn">Per-trigger timing unavailable on this '
        + 'Skript version (' + esc(data.hookReason || 'unknown') + '). Only event-level data shown.</div>');
    }

    // ----- Glance grid -----
    var totalTicks = total.length;
    var sumDur = 0, counted = 0;
    for (var i = 0; i < duration.length; i++){
      var d = duration[i]; if (d <= 0) continue; sumDur += d; counted++;
    }
    var avgTps = counted > 0 ? Math.min(20, 1e9 / (sumDur / counted)) : 0;
    var ticksOver = 0, worstNs = 0, worstIdx = -1;
    for (var i = 0; i < total.length; i++){
      var v = total[i];
      if (v > 5e7) ticksOver++;
      if (v > worstNs){ worstNs = v; worstIdx = i; }
    }
    var worstMs = worstNs / 1e6;
    var worstScript = '', worstLine = -1;
    // Same green/amber/red severity as the Worst tick card, so the headline number
    // reads consistently in both places.
    var wtTier = worstMs < 40 ? 'wt-good' : (worstMs < 50 ? 'wt-warn' : 'wt-bad');
    if (worstIdx >= 0){
      var best = 0;
      for (var k = 0; k < triggers.length; k++){
        var nn = (triggers[k].nanos && triggers[k].nanos[worstIdx]) || 0;
        if (nn > best){ best = nn; worstScript = triggers[k].script; worstLine = triggers[k].line; }
      }
    }
    var windowFoot = totalTicks + ' ticks' + (counted > 0 ? ' @ ' + avgTps.toFixed(1) + ' TPS' : '');
    var activeFoot = (data.silentCount || 0) + ' hooked but silent';
    var overFoot = totalTicks > 0 ? (100 * ticksOver / totalTicks).toFixed(1) + '% of the window' : 'no ticks captured';
    var worstFoot;
    if (worstIdx >= 0){
      var at = 'at ' + (worstIdx / 20).toFixed(1) + 's';
      var where = worstScript ? ' \u00b7 ' + esc(worstScript) + (worstLine > 0 ? ':' + worstLine : '') : '';
      var mspt = (counted > 0 && (duration[worstIdx] || 0) > 0) ? ' \u00b7 MSPT ' + (duration[worstIdx] / 1e6).toFixed(1) + 'ms' : '';
      worstFoot = at + where + mspt;
    } else {
      worstFoot = 'no ticks captured';
    }
    setHtml('glance',
      '<div class="cell"><div class="cell-label">Window</div>'
        + '<div class="cell-val">' + winSec.toFixed(2) + '<span class="u">s</span></div>'
        + '<div class="cell-foot">' + windowFoot + '</div></div>'
      + '<div class="cell"><div class="cell-label">Active triggers</div>'
        + '<div class="cell-val">' + triggers.length + '<span class="sub"> / ' + (data.totalTriggers != null ? data.totalTriggers : triggers.length) + '</span></div>'
        + '<div class="cell-foot">' + activeFoot + '</div></div>'
      + '<div class="cell' + (ticksOver > 0 ? ' crit' : '') + '"><div class="cell-label">Ticks over 50ms budget</div>'
        + '<div class="cell-val">' + ticksOver + '</div>'
        + '<div class="cell-foot">' + overFoot + '</div></div>'
      + '<div class="cell ' + wtTier + '"><div class="cell-label">Worst Skript tick</div>'
        + '<div class="cell-val">' + worstMs.toFixed(1) + '<span class="u">ms</span></div>'
        + '<div class="cell-foot">' + worstFoot + '</div></div>');

    setText('silent-note', (data.silentCount || 0) + ' hooked triggers stayed silent and are hidden.');

    if (ns === 0){
      setHtml('tick-empty', '<div class="empty">No tick samples captured. (Did you /sktrace start before activity?)</div>');
    }

    // ----- Events table -----
    var events = data.events || [];
    var tbody = document.getElementById('events-tbody');
    if (tbody){
      if (events.length === 0){
        tbody.innerHTML = '<tr><td colspan="6" class="empty">No events fired during the window.</td></tr>';
      } else {
        var maxEv = events[0].totalNs || 0;
        var rows = '';
        for (var i = 0; i < events.length; i++){
          var e = events[i];
          var pct = maxEv === 0 ? 0 : (100 * e.totalNs / maxEv);
          rows += '<tr>'
            + '<td class="heat-cell"><div class="heat-track"><div class="heat-bar dim" style="width:' + pct.toFixed(1) + '%"></div></div></td>'
            + '<td class="event-name">' + esc(e.name) + '</td>'
            + '<td class="num dim">' + fmtInt(e.calls) + '</td>'
            + '<td class="num ms">' + (e.totalNs / 1e6).toFixed(1) + '<span class="unit">ms</span></td>'
            + '<td class="num dim">' + formatTime(e.avgNs) + '</td>'
            + '<td class="num">' + (e.maxNs / 1e6).toFixed(2) + '<span class="unit">ms</span></td>'
            + '</tr>';
        }
        tbody.innerHTML = rows;
      }
      applyRowLimit('events', tbody);
    }
  }

  // ===========================================================
  // LOOPS — loop/while sections seen running during the window.
  // Stays hidden when nothing was observed (or watching is off).
  // ===========================================================
  function renderLoops(){
    var L = data.loops || {};
    var section = document.getElementById('loops-section');
    if (!section) return;
    var items = (L.ok && L.items) ? L.items : [];
    if (items.length === 0) return;   // section stays hidden
    section.hidden = false;
    var tbody = document.getElementById('loops-tbody');
    if (!tbody) return;
    var maxIter = 0;
    for (var i = 0; i < items.length; i++){
      if ((items[i].peakIter || 0) > maxIter) maxIter = items[i].peakIter;
    }
    var html = '';
    for (var i = 0; i < items.length; i++){
      var r = items[i];
      var pct = maxIter > 0 ? (100 * (r.peakIter || 0) / maxIter).toFixed(1) : 0;
      var loc = r.line > 0 ? esc(r.script) + ':' + r.line : esc(r.script);
      var status = r.running
        ? '<span class="loop-run">running</span> <span class="dim">#' + fmtInt(r.currentIter || 0)
            + (r.itersPerSec > 0 ? ' · ↑' + fmtInt(r.itersPerSec) + '/s' : '') + '</span>'
        : '<span class="dim">finished</span>';
      html += '<tr>'
        + '<td class="heat-cell"><div class="heat-track"><div class="heat-bar' + (r.running ? '' : ' dim') + '" style="width:' + pct + '%"></div></div></td>'
        + '<td class="loop-loc">' + loc + '</td>'
        + '<td><span class="var-name">' + esc(r.name) + '</span></td>'
        + '<td class="dim">' + (r.isWhile ? 'while' : 'loop') + '</td>'
        + '<td class="num">' + fmtInt(r.peakIter || 0) + '</td>'
        + '<td class="num dim">' + fmtInt(r.peakConcurrent || 0) + '</td>'
        + '<td>' + status + '</td>'
        + '</tr>';
    }
    tbody.innerHTML = html;
    applyRowLimit('loops', tbody);
  }

  // ===========================================================
  // VARIABLES — global variable writes observed during the
  // window (the variables.csv / database ones). Stays hidden
  // when tracking is off or unavailable on this Skript version.
  // ===========================================================
  function renderVariables(){
    var v = data.variables || {};
    var section = document.getElementById('variables-section');
    if (!section) return;
    var haveStats = !!v.ok;
    var haveFlush = flushes.length > 0;
    if (!haveStats && !haveFlush) return;   // section stays hidden
    section.hidden = false;

    renderFlushList(haveStats);

    var note = document.getElementById('vars-note');
    var top = document.getElementById('vars-top');
    var tbody = document.getElementById('vars-tbody');
    var toggle = document.getElementById('vars-toggle');
    var wrap = document.getElementById('vars-tablewrap');

    if (!haveStats){
      // Flush-only: in-memory write tracking is unavailable, but we still observed disk saves.
      if (note) note.textContent = '';
      if (top) top.innerHTML = '';
      if (toggle) toggle.hidden = true;
      if (wrap) wrap.hidden = true;
      return;
    }

    var list = v.top || [];

    if (note){
      if (v.capped) note.textContent = 'A distinct-name cap was reached, so more names changed than are listed.';
      else if (v.distinct && list.length < v.distinct) note.textContent = 'Showing the ' + fmtInt(list.length) + ' most-written of ' + fmtInt(v.distinct) + ' variables.';
      else note.textContent = '';
    }

    if (top){
      var spark = (v.perTick && v.perTick.length) ? makeSparkline(v.perTick) : '';
      top.innerHTML =
        '<div class="vars-stat"><b>Total writes</b><span class="v">' + fmtInt((v.creates||0)+(v.updates||0)+(v.deletes||0)) + '</span></div>' +
        '<div class="vars-stat"><b>Created</b><span class="v">' + fmtInt(v.creates||0) + '</span></div>' +
        '<div class="vars-stat"><b>Updated</b><span class="v">' + fmtInt(v.updates||0) + '</span></div>' +
        '<div class="vars-stat"><b>Deleted</b><span class="v">' + fmtInt(v.deletes||0) + '</span></div>' +
        '<div class="vars-stat"><b>Distinct names</b><span class="v">' + fmtInt(v.distinct||0) + (v.capped ? '+' : '') + '</span></div>' +
        (spark ? '<div class="vars-spark" title="variable writes per tick">' + spark + '</div>' : '');
    }

    if (list.length === 0){
      if (tbody) tbody.innerHTML = '<tr><td colspan="6" class="empty">No global variable writes recorded during the window.</td></tr>';
      if (wrap) wrap.hidden = false;   // nothing to collapse — just show the note
      if (toggle) toggle.hidden = true;
      return;
    }

    if (tbody){
      var maxW = 0;
      for (var i = 0; i < list.length; i++){
        var w = (list[i].sets||0) + (list[i].deletes||0);
        if (w > maxW) maxW = w;
      }
      var html = '';
      for (var i = 0; i < list.length; i++){
        var r = list[i];
        var rw = (r.sets||0) + (r.deletes||0);
        var pct = maxW > 0 ? (100 * rw / maxW).toFixed(1) : 0;
        var net = (r.sets||0) - (r.deletes||0);
        var badge = r.created ? '<span class="vars-badge">new</span>' : '';
        // Stored names have no braces; wrap for the familiar {name} / {list::key} look.
        html += '<tr>'
          + '<td class="heat-cell"><div class="heat-track"><div class="heat-bar" style="width:' + pct + '%"></div></div></td>'
          + '<td><span class="var-name">{' + esc(r.name) + '}</span>' + badge + '</td>'
          + '<td class="num dim">' + fmtInt(r.sets||0) + '</td>'
          + '<td class="num dim">' + fmtInt(r.deletes||0) + '</td>'
          + '<td class="num">' + (net >= 0 ? '+' : '') + fmtInt(net) + '</td>'
          + '<td class="dim">' + (r.type ? esc(r.type) : '—') + '</td>'
          + '</tr>';
      }
      tbody.innerHTML = html;
    }

    // Collapsed-by-default disclosure so a long variable list never dominates the report
    // (servers can churn thousands of variables). Summary + sparkline stay visible above.
    if (toggle && wrap){
      var shown = list.length;
      var label = function(open){
        return (open ? 'Hide variables' : 'Show variables (' + fmtInt(shown) + ')')
          + ' <span class="chev">▾</span>';
      };
      toggle.hidden = false;
      wrap.hidden = true;
      toggle.setAttribute('aria-expanded', 'false');
      toggle.innerHTML = label(false);
      toggle.onclick = function(){
        var willOpen = wrap.hidden;
        wrap.hidden = !willOpen;
        toggle.setAttribute('aria-expanded', String(willOpen));
        toggle.innerHTML = label(willOpen);
      };
    }
  }

  // Disk saves observed during the window: each variables.csv full rewrite, with when it happened
  // (seconds into the window), ~how long it took, the file size, and how many queued changes it
  // compacted. When variable stats are present but no save occurred, says so.
  function renderFlushList(haveStats){
    var box = document.getElementById('vars-flush');
    if (!box) return;
    if (!flushes.length){
      box.innerHTML = (haveStats && varFlush.ok)
        ? '<div class="vars-flush-empty">No full variables.csv save occurred during this capture. Skript rewrites the whole file about every 5 minutes, once enough changes pile up.</div>'
        : '';
      return;
    }
    var rows = '';
    for (var i = 0; i < flushes.length; i++){
      var f = flushes[i];
      var at = (f.startIdx / 20).toFixed(1);
      var chg = (f.changes == null) ? '' : ' · compacted ' + fmtInt(f.changes) + ' changes';
      // Per-save variable list (only present when the report was made with --variable-values).
      var varsHtml = '';
      if (f.vars && f.vars.length){
        var items = '';
        for (var j = 0; j < f.vars.length; j++){
          var vv = f.vars[j];
          var val = vv.deleted
            ? '<span class="flush-var-del">deleted</span>'
            : (vv.value != null
                ? '<span class="flush-var-val">= ' + esc(vv.value) + '</span>'
                : (vv.type ? '<span class="flush-var-type">(' + esc(vv.type) + ')</span>' : ''));
          items += '<div class="flush-var"><span class="var-name">{' + esc(vv.name) + '}</span> ' + val
            + ' <span class="dim">' + fmtInt(vv.writes || 0) + '×</span></div>';
        }
        varsHtml = '<details class="flush-vars"><summary>Show ' + fmtInt(f.vars.length)
          + ' variable' + (f.vars.length === 1 ? '' : 's') + '</summary>'
          + '<div class="flush-vars-body">' + items + '</div></details>';
      }
      rows += '<div class="flush-row">'
        + '<span class="flush-dot"></span>'
        + '<div class="flush-text"><div class="flush-main"><b>variables.csv full save</b> at ' + at + 's into the window</div>'
        + '<div class="flush-meta">' + fmtDur(f.durationMs) + ' · ' + fmtBytes(f.bytes) + chg + '</div>'
        + varsHtml
        + '</div>'
        + '</div>';
    }
    box.innerHTML = '<div class="vars-flush-head">Disk saves <span class="dim">— while a save runs, scripts setting variables block, so it can surface as the tick spike marked on the chart above.</span></div>' + rows;
  }

  // Render in priority order so the page feels responsive even with thousands of
  // ticks: chrome + chart + breakdown immediately, then secondary cards next frame,
  // then the (potentially long) triggers table on the frame after.
  renderChrome();
  if (ns > 0) applyView(0, ns - 1);
  requestAnimationFrame(function(){
    renderDonut();
    renderHistogram();
    renderWorstTick();
    requestAnimationFrame(function(){
      renderTriggersTable();
      renderFunctionsTable();
      renderLoops();
      renderVariables();
    });
  });
})();
