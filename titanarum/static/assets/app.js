// titanarum blastbox web UI — clone-and-adapt of RedTusk's SPA, retargeted at
// titanarum's report.json shape (urls, phones, tables, screenshots, document
// metadata, AI verdict) instead of RedTusk's Tika rmeta extraction tree.

// ── version ───────────────────────────────────────────────────
fetch('/v1/version').then(r=>r.json()).then(d=>{
  document.getElementById('version').textContent = d && d.version ? 'v'+d.version : '';
}).catch(()=>{});

// ── helpers ───────────────────────────────────────────────────
function relativeTime(iso) {
  if (!iso) return '—';
  const d = (Date.now() - new Date(iso)) / 1000;
  if (d < 5)    return 'just now';
  if (d < 60)   return Math.floor(d) + 's ago';
  if (d < 3600) return Math.floor(d/60) + 'm ago';
  if (d < 86400)return Math.floor(d/3600) + 'h ago';
  return Math.floor(d/86400) + 'd ago';
}
function fmtMs(ms) {
  if (ms == null) return '—';
  const s = ms / 1000;
  return s < 60 ? s.toFixed(1)+'s' : Math.floor(s/60)+'m '+Math.floor(s%60)+'s';
}
function duration(job) {
  if (!job) return '—';
  const proc = job.processing_ms != null ? job.processing_ms : null;
  if (proc == null && !job.started_at) return '—';
  const procMs = proc != null ? proc : (() => {
    const end = job.completed_at ? new Date(job.completed_at) : new Date();
    return end - new Date(job.started_at);
  })();
  const qMs = job.queue_ms != null ? job.queue_ms : 0;
  const procStr = fmtMs(procMs);
  const qStr = qMs > 500 ? ' (q:'+fmtMs(qMs)+')' : '';
  return procStr + qStr;
}
function esc(s) {
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
// esc() neutralises HTML metacharacters, but a URL scheme needs none of them:
// "javascript:alert(1)" passes through esc() unchanged and executes on click.
// Every URL in this viewer comes from an analyzed (i.e. hostile) document, so a
// link target is only emitted when its scheme is inert. Browsers ignore control
// characters and whitespace while parsing a scheme ("java\tscript:"), so strip
// those before testing.
const SAFE_URL_SCHEME = /^(?:https?|mailto|ftp):/;
function safeUrl(u) {
  const raw = String(u == null ? '' : u).trim();
  const probe = raw.replace(/[\u0000-\u0020]/g, '').toLowerCase();
  if (SAFE_URL_SCHEME.test(probe)) return raw;
  // Scheme-less values (relative paths, bare "#") carry no scheme to abuse.
  if (probe && !/^[a-z][a-z0-9+.-]*:/.test(probe)) return raw;
  return '';
}
// Renders a link when the URL is safe, and inert text (still fully visible, so
// an analyst never loses the IOC) when it is not.
function extLink(url, label, extraAttr) {
  const shown = esc(label == null ? (url == null ? '' : url) : label);
  const href = safeUrl(url);
  if (!href) {
    return '<span class="blocked-url" title="link suppressed: unsafe URL scheme">' + shown + '</span>';
  }
  return '<a href="' + esc(href) + '" target="_blank" rel="noopener"'
    + (extraAttr || '') + '>' + shown + '</a>';
}
function fmt_bytes(n) {
  if (!n) return '0 B';
  const units = ['B','KB','MB','GB'];
  let i = 0;
  while (n >= 1024 && i < units.length-1) { n/=1024; i++; }
  return n.toFixed(i?1:0)+' '+units[i];
}
function pct(v) {
  if (v == null || isNaN(v)) return null;
  return (v*100).toFixed(0)+'%';
}
function fmtNum(v, digits) {
  if (v == null || isNaN(v)) return null;
  return Number(v).toFixed(digits == null ? 1 : digits);
}

// Append the extraction toggles as host `params` form fields. Keys MUST be
// UPPERCASE env-shaped (the dispatcher drops anything not ^[A-Z][A-Z0-9_]*$
// before the allowlist); they must also be in
// BLASTBOX_ENGINE_TITANARUM_PARAM_KEYS (default: TITANARUM_SKIP_SCREENSHOTS,
// TITANARUM_SKIP_IMAGES, TITANARUM_DPI, TITANARUM_PAGES). Params outside the
// operator's allowlist are silently dropped by the dispatcher, not rejected.
function appendJobParams(fd) {
  const skipShots  = document.getElementById('toggle-skip-screenshots').checked;
  const skipImages = document.getElementById('toggle-skip-images').checked;
  const dpi   = document.getElementById('opt-dpi').value.trim();
  const pages = document.getElementById('opt-pages').value.trim();
  fd.append('params', 'TITANARUM_SKIP_SCREENSHOTS=' + skipShots);
  fd.append('params', 'TITANARUM_SKIP_IMAGES=' + skipImages);
  if (dpi)   fd.append('params', 'TITANARUM_DPI=' + dpi);
  if (pages) fd.append('params', 'TITANARUM_PAGES=' + pages);
}

// ── blastbox.host ⇄ UI adapter ────────────────────────────────
// job.status/timestamps come back epoch-shaped; normalizeJob maps them onto
// the shape the rest of this file speaks (iso timestamps, ms durations).
const _BB_STATE = { done: 'succeeded', failed: 'failed', rejected: 'failed',
                    queued: 'queued', running: 'running' };
const _UI_TO_BB_STATE = { queued: 'queued', running: 'running',
                          succeeded: 'done', failed: 'failed' };
const _epochToIso = (e) => {
  if (e == null) return null;
  const n = Number(e);
  if (Number.isNaN(n)) return null;
  try { return new Date(n * 1000).toISOString(); } catch { return null; }
};

function normalizeJob(j) {
  if (!j || j.id) return j;            // already normalized / not a host record
  const created = j.created_at, started = j.started_at, finished = j.finished_at;
  return {
    id: j.job_id,
    state: _BB_STATE[j.status] || j.status || 'queued',
    filename_hint: j.filename || '—',
    submitted_at: _epochToIso(created),
    started_at: _epochToIso(started),
    completed_at: _epochToIso(finished),
    processing_ms: (finished != null && started != null) ? (finished - started) * 1000 : null,
    queue_ms: (started != null && created != null) ? (started - created) * 1000 : null,
    error_detail: j.error || null,
    worker_runtime: j.worker_runtime || null,
    worker_tier: j.worker_tier || null,
    report: null,
    envelope: null,
    _bb: j,
  };
}

// ── upload ────────────────────────────────────────────────────
async function uploadAsync() {
  const files = document.getElementById('file-input').files;
  if (!files.length) { setStatus('Select file(s) first.'); return; }
  setButtons(true); setStatus('Queuing '+files.length+' file(s)…');
  let ok=0, fail=0;
  for (const f of files) {
    try {
      const fd = new FormData();
      fd.append('file', f);
      fd.append('engine', 'titanarum');
      appendJobParams(fd);
      const r = await fetch('/v1/jobs', { method:'POST', body: fd });
      if (r.ok) ok++; else fail++;
    } catch { fail++; }
  }
  setStatus('Queued: '+ok+(fail?', failed: '+fail:''));
  setButtons(false); fetchJobs();
}

function setButtons(d) {
  const b = document.getElementById('queue-btn');
  if (b) b.disabled = d;
}
function setStatus(m) { document.getElementById('upload-status').textContent=m; }

// ── event delegation ──────────────────────────────────────────
// CSP forbids inline event-handler attributes, so all interactive elements
// declare a `data-act` (and optional `data-arg`) attribute and are dispatched
// from here. Works for any dynamically-inserted markup.
const CLICK_ACTIONS = {
  'nav-list': () => navigateToList(),
  'nav-job': (el) => navigateToJob(el.dataset.arg),
  'upload-async': () => uploadAsync(),
  'clear-search': () => clearSearch(),
  'toggle-text': (el) => toggleText(el),
  'go-page': (el) => goToPage(parseInt(el.dataset.arg, 10)),
  'set-state': (el) => setStateFilter(el.dataset.arg || null),
  'delete-job': (el) => deleteJobAndGoBack(el.dataset.arg),
  'toggle-raw': (el) => toggleRawJson(el),
};

document.addEventListener('click', (ev) => {
  const t = ev.target;
  const actEl = t && t.closest ? t.closest('[data-act]') : null;
  if (actEl) {
    const handler = CLICK_ACTIONS[actEl.dataset.act];
    if (handler) {
      if (actEl.dataset.prevent === '1') ev.preventDefault();
      handler(actEl);
    }
  }
});

document.addEventListener('input', (ev) => {
  const t = ev.target;
  if (t && t.id === 'search-input') onSearch(t.value);
});

// Delegated, capture-phase error handler for <img> tags that fail to load
// (replaces inline onerror). 'error' events do not bubble.
document.addEventListener('error', (ev) => {
  const t = ev.target;
  if (t && t.tagName === 'IMG' && t.dataset.onerror === 'hide') {
    t.style.display = 'none';
  }
}, true);

function toggleRawJson(btn) {
  const pre = document.getElementById('raw-json-view');
  if (!pre) return;
  // Fill on first open (see renderJobDetail): textContent, not innerHTML, so no escaping pass and
  // no injection surface.
  if (pre.dataset.filled !== '1') {
    try { pre.textContent = JSON.stringify(_rawReportForDetail, null, 2); }
    catch { pre.textContent = ''; }
    pre.dataset.filled = '1';
  }
  const show = pre.style.display === 'none';
  pre.style.display = show ? 'block' : 'none';
  btn.textContent = show ? '{ } hide JSON' : '{ } raw JSON';
}

// All text display uses esc() before innerHTML injection or textContent
// assignment. Never use innerHTML with raw document text — malware (JS/XFA/
// form-field) payloads must render as inert text, not execute in the browser.
// Full extracted text lives here rather than in a data-full attribute: these
// payloads reach hundreds of KB, and one copy per block in the DOM (escaped,
// so often larger still) bloats the document for text most blocks never expand.
const _fullTextByUid = new Map();

function toggleText(btn) {
  const uid = btn.getAttribute('data-uid');
  const el = document.getElementById(uid);
  if (!el) return;
  if (el.classList.contains('expanded')) {
    el.classList.remove('expanded');
    const full = _fullTextByUid.get(uid) || '';
    el.textContent = full.slice(0, 500) + '\n…';
    btn.textContent = '▼ show more (' + full.length.toLocaleString() + ' chars)';
  } else {
    el.classList.add('expanded');
    el.textContent = _fullTextByUid.get(uid) || ''; // safe: textContent, not innerHTML
    btn.textContent = '▲ show less';
  }
}

function textBlock(text, opts) {
  opts = opts || {};
  if (!text) return '';
  const uid = 'tb-'+(Math.random().toString(36).slice(2));
  let out = '<div class="text-preview" id="'+uid+'">'+esc(text.slice(0, opts.cap || 3000))+'</div>';
  if (text.length > 500) {
    _fullTextByUid.set(uid, text);
    out += '<span class="text-toggle" data-act="toggle-text" data-uid="'+uid+'">▼ show full ('+text.length.toLocaleString()+' chars)</span>';
  }
  return out;
}

function kv(k, v) {
  if (v == null || v === '') return '';
  return '<span class="kv-key">'+esc(k)+'</span><span class="kv-val">'+esc(String(v))+'</span>';
}
function kvHash(k, v) {
  if (!v) return '';
  return '<span class="kv-key">'+esc(k)+'</span><span class="kv-val hash">'+esc(String(v))+'</span>';
}

// ── artifact resolution ───────────────────────────────────────
// Screenshot/image/crop/decoded-artifact path fields in report.json are
// relative to the JVM worker's report_dir (outdir/titan — see engine.py); the
// host serves artifacts by declared id, not by path, so we resolve via the
// path -> id map built from the envelope's artifacts[] when the detail view
// loads (mirrors RedTusk's _curArtMap / _artUrl pattern).
let _curArtMap = {};   // outdir-relative artifact path -> id, for the job being rendered
let _curJobId = null;

function artUrl(reportRelPath) {
  if (!reportRelPath || !_curJobId) return null;
  const full = 'titan/' + reportRelPath;
  const id = _curArtMap[full];
  return id ? '/v1/jobs/' + _curJobId + '/artifacts/' + encodeURIComponent(id) : null;
}

function reportImg(path, cls, extraAttrs) {
  const url = artUrl(path);
  if (!url) return '';
  return '<img class="'+(cls||'report-img')+'" src="'+esc(url)+'" loading="lazy" data-onerror="hide"'+(extraAttrs||'')+'>';
}

// ── hash helpers (screenshots/images carry phash/colorhash/sha256; the JVM
// worker's blank-hash sentinels are all-0s / all-fs and not worth showing) ──
function isRealHash(h) {
  return h && !/^0+$/.test(h) && !/^f+$/i.test(h);
}

// ── report section builders ────────────────────────────────────

function buildSummarySection(job, report) {
  let html = '<div class="kv-grid">';
  html += kvHash('Job ID', job.id);
  html += kv('Filename', job.filename_hint);
  html += kv('Submitted', job.submitted_at ? new Date(job.submitted_at).toLocaleString() : '—');
  if (job.processing_ms != null) html += kv('Worker time', fmtMs(job.processing_ms));
  if (job.worker_runtime) {
    const rt = job.worker_runtime, tier = job.worker_tier;
    let tierLabel = rt;
    if (rt === 'warm') {
      if (tier === 'firecracker') tierLabel = 'warm · Firecracker microVM';
      else if (tier === 'gvisor') tierLabel = 'warm · gVisor C/R';
      else tierLabel = 'warm';
    }
    html += kv('Worker tier', tierLabel);
  }
  if (report) {
    html += kvHash('SHA-256', report.documentSha256);
    if (report.pdfObjectHash) html += kvHash('Object hash', report.pdfObjectHash);
    if (report.fileMagic) html += kv('File magic', report.fileMagic);
  }
  html += '</div>';
  if (report && report.parseError) {
    html += '<div style="color:#f90;font-size:0.8rem;margin-top:0.5rem">⚠ '+esc(report.parseError)+'</div>';
  }
  if (report && report.timedOut) {
    html += '<div style="color:#f90;font-size:0.8rem;margin-top:0.5rem">⚠ partial results after '+esc(report.timedOutAfterMs)+' ms (timed out)</div>';
  }
  return section('Summary', html);
}

function section(title, bodyHtml, extraHdr) {
  return '<div class="job-section"><div class="job-section-hdr">'+esc(title)+(extraHdr||'')+'</div><div class="job-section-body">'+bodyHtml+'</div></div>';
}

function buildCveBanner(report) {
  const cves = (report.jsIndicators||[]).filter(i => i.type === 'cve_detection');
  if (!cves.length) return '';
  let html = '<div style="margin-bottom:0.75rem;padding:0.75rem 1rem;background:#3a0a0a;border:2px solid #d32f2f;border-radius:6px">';
  for (const ji of cves) {
    html += '<div style="display:flex;align-items:center;gap:0.6rem;margin-bottom:0.4rem">';
    html += '<span style="font-size:1.1rem;color:#ff5252;font-weight:bold">⚠ '+esc(ji.indicator||'')+'</span>';
    html += '<span class="badge badge-bad" style="font-weight:bold">CVE DETECTED</span></div>';
    html += '<div style="color:#ffaaaa;font-size:0.78rem">'+esc(ji.detail||'')+'</div>';
  }
  html += '</div>';
  return html;
}

function buildJsIndicatorsSection(report) {
  const items = report.jsIndicators || [];
  if (!items.length) return '';
  let rows = '';
  for (const ji of items) {
    const bg = ji.type === 'cve_detection' ? 'background:#3a0a0a'
      : ji.type === 'jseff_obfuscation' ? 'background:#2a1a00' : '';
    const badge = ji.type === 'cve_detection' ? '<span class="badge badge-bad">CVE</span>'
      : ji.type === 'jseff_obfuscation' ? '<span class="badge badge-warn">obfuscation</span>'
      : '<span class="badge badge-neutral">'+esc(ji.type||'')+'</span>';
    rows += '<tr style="'+bg+'"><td>'+badge+'</td>'
      + '<td style="font-family:monospace;color:#f0c040;white-space:nowrap">'+esc(ji.indicator||'')+'</td>'
      + '<td>'+esc(ji.detail||'')+'</td>'
      + '<td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="'+esc(ji.context||'')+'">'+esc(ji.context||'')+'</td>'
      + '<td>'+esc(ji.count != null ? ji.count : '')+'</td></tr>';
  }
  const body = '<table><thead><tr><th>Type</th><th>Indicator</th><th>Detail</th><th>Context</th><th>Count</th></tr></thead><tbody>'+rows+'</tbody></table>';
  return section('JavaScript Indicators', body, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

function buildFormFieldsSection(report, jobId) {
  const items = report.formFields || [];
  if (!items.length) return '';
  let rows = '';
  for (const ff of items) {
    const flags = ff.flags || [];
    const bg = flags.includes('base64_payload') ? 'background:#2a1a00' : '';
    let flagHtml = '';
    for (const f of flags) {
      if (f === 'hidden') flagHtml += '<span class="badge badge-neutral">hidden</span>';
      else if (f === 'base64_payload') flagHtml += '<span class="badge badge-bad">base64 payload</span>';
      else if (f === 'base64_name_escaped') flagHtml += '<span class="badge badge-warn">#XX escapes</span>';
      else flagHtml += '<span style="color:#aaa;margin-right:4px">'+esc(f)+'</span>';
    }
    let valueCol = '';
    if (ff.rawValueLength != null) valueCol += ff.rawValueLength+' chars';
    if (ff.decodedLength != null) valueCol += '<br><span style="color:#f90;font-size:0.75rem">→ '+ff.decodedLength+' bytes decoded</span>';
    let artCol = '';
    if (ff.decodedArtifact) {
      const url = artUrl(ff.decodedArtifact);
      artCol = url ? extLink(url, ff.decodedArtifact) : esc(ff.decodedArtifact);
      if (ff.decodedSha256) artCol += '<br><code style="font-size:0.68rem;color:#888">'+esc(ff.decodedSha256)+'</code>';
    }
    rows += '<tr style="'+bg+'"><td style="font-family:monospace;color:#f0c040">'+esc(ff.name||'')+'</td>'
      + '<td>'+esc(ff.fieldType||'')+'</td><td style="font-size:0.75rem">'+flagHtml+'</td>'
      + '<td>'+valueCol+'</td><td style="font-size:0.75rem">'+artCol+'</td></tr>';
  }
  const body = '<table><thead><tr><th>Name</th><th>Type</th><th>Flags</th><th>Value Length</th><th>Decoded</th></tr></thead><tbody>'+rows+'</tbody></table>';
  return section('Suspicious Form Fields', body, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

function buildDocMetadataSection(report) {
  const di = report.documentInfo || {};
  let html = '<div class="kv-grid">';
  html += kv('PDF Version', di.pdfVersion);
  html += kv('Title', di.title);
  html += kv('Author', di.author);
  html += kv('Subject', di.subject);
  html += kv('Keywords', di.keywords);
  html += kv('Creator', di.creator);
  html += kv('Producer', di.producer);
  if (di.creationDate) html += kv('Created', di.creationDate + (di.daysSinceCreated != null ? ' ('+di.daysSinceCreated+' days ago)' : ''));
  if (di.modificationDate) html += kv('Modified', di.modificationDate + (di.daysSinceModified != null ? ' ('+di.daysSinceModified+' days ago)' : ''));
  if (di.daysBetweenCreatedAndModified != null) html += kv('Created → Modified', di.daysBetweenCreatedAndModified+' days');
  if (report.pageCount != null) {
    let pagesVal = report.pageCount+' total';
    if (report.blankPageCount) pagesVal += ' — '+report.blankPageCount+' blank ('+(pct(report.blankRatio)||'')+')';
    html += kv('Pages', pagesVal);
  }
  if (report.pagesProcessed && report.pagesProcessed.length) html += kv('Analysed', 'pages '+report.pagesProcessed.join(', '));
  if (report.revisionCount != null) html += kv('Revisions', report.revisionCount);
  if (report.fonts && report.fonts.length) html += kv('Fonts', report.fonts.join(', '));
  html += '</div>';
  if (!di.pdfVersion && !di.title && !di.author && report.pageCount == null) return '';
  return section('Document Metadata', html);
}

function buildAiSection(report) {
  const ai = report.aiAnalysis;
  if (!ai) return '';
  if (ai.error) return section('AI Threat Analysis', '<p style="color:#f66">Error: '+esc(ai.error)+'</p>');
  const level = (ai.threatLevel || '').toLowerCase();
  const cls = level === 'malicious' || level === 'likely_phishing' ? 'threat-malicious'
    : level === 'suspicious' ? 'threat-suspicious' : 'threat-benign';
  let html = '<div class="ai-header">';
  html += '<span class="threat-level '+cls+'">'+esc((ai.threatLevel||'').toUpperCase())+'</span>';
  if (ai.classification) html += '<span style="color:#aaa">'+esc(ai.classification)+'</span>';
  if (ai.confidence != null) html += '<span style="color:#888">confidence: '+esc(pct(ai.confidence)||ai.confidence)+'</span>';
  for (const b of (ai.brands||[])) html += '<span class="badge badge-info">'+esc(b)+'</span>';
  html += '</div>';
  if (ai.summary) html += '<p class="ai-summary">'+esc(ai.summary)+'</p>';
  if (ai.score != null) {
    const color = ai.score >= 70 ? '#f66' : ai.score >= 40 ? '#f90' : '#6c6';
    html += '<p class="ai-score">Score: <strong style="color:'+color+'">'+esc(ai.score)+'/100</strong></p>';
  }
  if (ai.indicators && ai.indicators.length) {
    html += '<ul style="margin:0 0 0.6rem;padding-left:1.2rem;color:#aaa">';
    for (const ind of ai.indicators) html += '<li>'+esc(ind)+'</li>';
    html += '</ul>';
  }
  if (ai.passwords && ai.passwords.length) {
    html += '<div style="margin-bottom:0.6rem"><span style="color:#888;font-size:0.75rem">Passwords/PINs found: </span>';
    for (const pw of ai.passwords) html += '<span class="badge" style="background:#4a1a1a;color:#ffaaaa;font-family:monospace">'+esc(pw)+'</span>';
    html += '</div>';
  }
  if (ai.translatedText) {
    html += '<details style="color:#aaa;font-size:0.78rem"><summary style="cursor:pointer;color:#888">Translated text</summary>'
      + '<p style="margin:0.4rem 0 0;white-space:pre-wrap">'+esc(ai.translatedText)+'</p></details>';
  }
  return section('AI Threat Analysis', html);
}

function buildScreenshotsSection(report) {
  const shots = report.screenshots || [];
  if (!shots.length) return '';
  let html = '<div class="shot-grid">';
  for (const ss of shots) {
    html += '<div class="shot-item">';
    let meta = esc(ss.path||'');
    if (report.blankPages && report.blankPages.includes(ss.page)) meta += ' <span class="badge badge-neutral">blank</span>';
    const h = ss.hashes || {};
    if (isRealHash(h.phash)) meta += ' • phash: <code>'+esc(h.phash)+'</code>';
    if (isRealHash(h.colorhash)) meta += ' • chash: <code>'+esc(h.colorhash)+'</code>';
    html += '<div class="shot-meta">'+meta+'</div>';
    if (ss.qrCodes && ss.qrCodes.length) {
      html += '<div style="margin:0.25rem 0;font-size:0.75rem">';
      for (const qr of ss.qrCodes) html += '<span class="badge badge-purple">QR: '+esc(qr.text||'')+'</span>';
      html += '</div>';
    }
    html += reportImg(ss.path);
    if (ss.ocrText) html += textBlock(ss.ocrText, {cap: 1000});
    html += '</div>';
  }
  html += '</div>';
  return section('Screenshots', html, ' <span style="color:#666;font-weight:normal">('+shots.length+')</span>');
}

function buildRevisionsSection(report) {
  const revs = report.revisions || [];
  if (!revs.length) return '';
  let html = '';
  revs.forEach((rev, idx) => {
    let hdr = 'Revision '+rev.revision+' of '+rev.totalRevisions;
    if (rev.revision === 1) hdr += ' <span style="color:#888;font-size:0.75rem">(oldest)</span>';
    if (rev.urlsChangedVisuallyHidden) hdr += ' <span class="badge badge-bad" title="URLs differ but page appears visually identical">⚠ hidden URL change</span>';
    if (rev.removedUrls && rev.removedUrls.length) hdr += ' <span class="badge" style="background:#7b3800;color:#ffcc88">'+rev.removedUrls.length+' removed</span>';
    if (rev.addedUrls && rev.addedUrls.length) hdr += ' <span class="badge badge-good">'+rev.addedUrls.length+' added</span>';
    let body = '';
    if (rev.urls && rev.urls.length) {
      let rows = '';
      for (const u of rev.urls) {
        const removed = rev.removedUrls && rev.removedUrls.includes(u.url);
        rows += '<tr'+(removed?' style="background:#2a1a00"':'')+'><td>'+(u.cropPath ? reportImg(u.cropPath) : '')+'</td>'
          + '<td style="font-size:0.75rem;word-break:break-all">'+(removed?'<span style="color:#f90">⚠</span> ':'')+esc(u.url||'')+(removed?' <span style="color:#888;font-size:0.7rem">(removed in later revision)</span>':'')+'</td>'
          + '<td>'+esc(u.page != null ? u.page : '')+'</td><td>'+(pct(u.pageCoverageRatio)||'')+'</td>'
          + '<td style="font-size:0.75rem">'+(u.flags ? esc(u.flags.join(', ')) : '')+'</td></tr>';
      }
      body += '<table><thead><tr><th>Crop</th><th>URL</th><th>Page</th><th>Coverage</th><th>Flags</th></tr></thead><tbody>'+rows+'</tbody></table>';
    }
    if (rev.addedUrls && rev.addedUrls.length) {
      body += '<div style="margin:0.5rem 0;font-size:0.75rem"><span style="color:#888">URLs added after this revision: </span>'
        + rev.addedUrls.map(u=>'<span style="color:#8f8;font-family:monospace">'+esc(u)+'</span>').join(', ')+'</div>';
    }
    if (rev.screenshots && rev.screenshots.length) {
      body += '<div class="shot-grid">';
      for (const ss of rev.screenshots) {
        body += '<div class="shot-item"><div class="shot-meta">page '+esc(ss.page)+'</div>'+reportImg(ss.path)+'</div>';
      }
      body += '</div>';
    }
    html += '<details'+(idx===0?' open':'')+' style="margin-bottom:0.5rem;border:1px solid #333;border-radius:3px">'
      + '<summary style="cursor:pointer;padding:0.4rem 0.6rem;background:#1e1e1e">'+hdr+'</summary>'
      + '<div style="padding:0.5rem 0.6rem">'+body+'</div></details>';
  });
  return section('Previous Revisions', html);
}

function buildUrlsSection(report) {
  const items = report.urls || [];
  if (!items.length) return '';
  let rows = '';
  for (const u of items) {
    const dimmed = u.fromRevision != null ? 'style="background:#1a1200;opacity:0.85"' : '';
    let cropCol = '';
    if (u.cropPath) {
      cropCol = reportImg(u.cropPath, 'thumb-img');
      const ch = u.cropHashes || {};
      if (isRealHash(ch.phash)) cropCol += '<code style="font-size:0.68rem;color:#888">'+esc(ch.phash)+'</code>';
    }
    let flagsCol = '';
    if (u.flags) {
      for (const f of u.flags) {
        flagsCol += f === 'silent_trigger'
          ? '<span class="badge badge-bad">silent</span>'
          : '<span style="color:#aaa;margin-right:4px">'+esc(f)+'</span>';
      }
    }
    rows += '<tr '+dimmed+'><td>'+cropCol+'</td>'
      + '<td style="max-width:400px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'
      + (u.fromRevision != null ? '<span style="color:#f90" title="Only present in revision '+esc(u.fromRevision)+'">⚠</span> ' : '')
      + extLink(u.url||'', u.url||'', ' title="'+esc(u.url||'')+'"'+(u.fromRevision!=null?' style="color:#cc9900"':''))+'</td>'
      + '<td>'+esc(u.source||'')+(u.fromRevision!=null?'<br><span class="badge badge-warn">rev '+esc(u.fromRevision)+'/'+esc(report.revisionCount)+' removed</span>':'')+'</td>'
      + '<td>'+esc(u.page != null ? u.page : '')+'</td><td>'+(pct(u.pageCoverageRatio)||'')+'</td>'
      + '<td>'+flagsCol+'</td></tr>';
  }
  const body = '<table><thead><tr><th>Crop</th><th>URL</th><th>Source</th><th>Page</th><th>Coverage</th><th>Flags</th></tr></thead><tbody>'+rows+'</tbody></table>';
  return section('URLs', body, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

function buildCodeSection(title, items, cols) {
  if (!items || !items.length) return '';
  let rows = '';
  for (const it of items) {
    rows += '<tr><td style="white-space:nowrap;color:#888;vertical-align:top">'+esc(it.context||'')
      + (it.contentType ? '<br>'+esc(it.contentType) : '')+'</td>'
      + '<td><pre style="margin:0;white-space:pre-wrap;word-break:break-all;background:none;padding:0">'+esc(it.code||'')+'</pre></td></tr>';
  }
  const body = '<table><thead><tr><th>Context</th><th>Code</th></tr></thead><tbody>'+rows+'</tbody></table>';
  return section(title, body, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

function buildPhonesSection(report) {
  const items = report.phoneNumbers || [];
  if (!items.length) return '';
  let rows = '';
  for (const p of items) {
    rows += '<tr><td>'+esc(p.e164||'')+'</td><td>'+esc(p.raw||'')+'</td><td>'+esc(p.countryCode||'')+'</td>'
      + '<td>'+esc(p.geocode||'')+'</td><td>'+esc(p.source||'')+'</td><td>'+esc(p.page != null ? p.page : '')+'</td></tr>';
  }
  const body = '<table><thead><tr><th>E.164</th><th>Raw</th><th>Country</th><th>Geocode</th><th>Source</th><th>Page</th></tr></thead><tbody>'+rows+'</tbody></table>';
  return section('Phone Numbers', body, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

function buildEmailsSection(report) {
  const items = report.emails || [];
  if (!items.length) return '';
  let rows = '';
  for (const e of items) {
    rows += '<tr><td><a href="mailto:'+esc(e.email||'')+'">'+esc(e.email||'')+'</a></td>'
      + '<td>'+esc(e.source||'')+'</td><td>'+esc(e.page != null ? e.page : '')+'</td></tr>';
  }
  const body = '<table><thead><tr><th>Address</th><th>Source</th><th>Page</th></tr></thead><tbody>'+rows+'</tbody></table>';
  return section('Email Addresses', body, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

function buildActionsSection(report) {
  const items = report.actions || [];
  if (!items.length) return '';
  let rows = '';
  for (const a of items) {
    let target = '';
    if (a.submitUrl) target += extLink(a.submitUrl);
    if (a.importFile) target += esc(a.importFile);
    if (a.remoteFile) target += esc(a.remoteFile);
    if (a.target) {
      target += (a.type === 'URI' || a.type === 'Rendition')
        ? extLink(a.target)
        : esc(a.target);
    }
    let details = '';
    if (a.fields && a.fields.length) details += 'fields: '+esc(a.fields.join(', '))+'<br>';
    if (a.submitFlags) details += 'flags: '+esc(a.submitFlags)+'<br>';
    if (a.destination) details += 'dest: '+esc(a.destination)+'<br>';
    if (a.embeddedTarget) details += 'target: '+esc(a.embeddedTarget)+'<br>';
    if (a.contentType) details += 'type: '+esc(a.contentType)+'<br>';
    if (a.newWindow != null) details += 'newWindow: '+esc(a.newWindow);
    rows += '<tr><td><span class="badge badge-neutral">'+esc(a.type||'')+'</span></td><td>'+target+'</td>'
      + '<td style="font-size:0.75rem">'+details+'</td><td>'+esc(a.page != null ? a.page : '')+'</td>'
      + '<td style="max-width:250px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="'+esc(a.context||'')+'">'+esc(a.context||'')+'</td></tr>';
  }
  const body = '<table><thead><tr><th>Type</th><th>Target</th><th>Details</th><th>Page</th><th>Context</th></tr></thead><tbody>'+rows+'</tbody></table>';
  return section('Actions', body, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

function buildLaunchActionsSection(report) {
  const items = report.launchActions || [];
  if (!items.length) return '';
  let rows = '';
  for (const la of items) {
    rows += '<tr><td>'+esc(la.file||'')+'</td><td>'+esc(la.parameters||'')+'</td><td>'+esc(la.page != null ? la.page : '')+'</td></tr>';
  }
  const body = '<table><thead><tr><th>File</th><th>Parameters</th><th>Page</th></tr></thead><tbody>'+rows+'</tbody></table>';
  return section('Launch Actions', body, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

function buildImageGallerySection(title, items) {
  if (!items || !items.length) return '';
  let html = '<div class="shot-grid">';
  for (const img of items) {
    const h = img.hashes || {};
    // Blank-sentinel phash images are still shown (titanarum's own UI filters
    // them from the *rendered*-image galleries only when the sentinel matches
    // AND no other hash is present; keep this viewer simple and show all).
    html += '<div class="shot-item"><div class="shot-meta">';
    html += 'p'+esc(img.page != null ? img.page : '?')+' • natural: '+esc(img.width||'?')+'×'+esc(img.height||'?')+'px';
    if (img.bounds) {
      html += ' • rendered: '+fmtNum(img.bounds.right-img.bounds.left)+'×'+fmtNum(img.bounds.top-img.bounds.bottom)+'pt';
    }
    if (img.context) html += ' • '+esc(img.context);
    if (isRealHash(h.phash)) html += ' • phash: <code>'+esc(h.phash)+'</code>';
    if (isRealHash(h.colorhash)) html += ' • chash: <code>'+esc(h.colorhash)+'</code>';
    if (h.sha256) html += ' • sha256: <code style="font-size:0.68rem">'+esc(h.sha256)+'</code>';
    html += '</div>';
    html += reportImg(img.path, 'thumb-img');
    if (img.qrCodes && img.qrCodes.length) {
      for (const qr of img.qrCodes) html += '<span class="badge badge-purple">QR: '+esc(qr.text||'')+'</span>';
    }
    html += '</div>';
  }
  html += '</div>';
  return section(title, html, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

function buildOcgSection(report) {
  const items = report.ocgLayers || [];
  if (!items.length) return '';
  let rows = '';
  for (const layer of items) {
    rows += '<tr'+(layer.suspicious?' style="background:#2a1010"':'')+'><td>'+esc(layer.name||'')+'</td>'
      + '<td><span class="badge '+(layer.defaultState==='OFF'?'badge-bad':'badge-good')+'">'+esc(layer.defaultState||'')+'</span></td>'
      + '<td>'+(layer.visibleInView != null ? esc(layer.visibleInView) : '<span style="color:#555">—</span>')+'</td>'
      + '<td>'+(layer.visibleInPrint != null ? esc(layer.visibleInPrint) : '<span style="color:#555">—</span>')+'</td>'
      + '<td>'+(layer.suspicious ? '<span class="badge badge-bad">suspicious</span>' : '')+'</td></tr>';
  }
  const body = '<table><thead><tr><th>Name</th><th>Default</th><th>View</th><th>Print</th><th></th></tr></thead><tbody>'+rows+'</tbody></table>';
  return section('Optional Content Layers / OCG', body, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

function buildEmbeddedFilesSection(report) {
  const items = report.embeddedFiles || [];
  if (!items.length) return '';
  let rows = '';
  for (const ef of items) {
    let name = esc(ef.originalName||'');
    if (ef.duplicateCount != null) name += ' <span class="badge badge-neutral" title="'+ef.duplicateCount+' identical copies">×'+ef.duplicateCount+'</span>';
    rows += '<tr><td>'+name+'</td><td>'+esc(ef.mimeType||'')+'</td><td>'+esc(ef.size != null ? fmt_bytes(ef.size) : '')+'</td>'
      + '<td style="font-size:0.75rem">'+esc(ef.sha256||'')+'</td></tr>';
  }
  const body = '<table><thead><tr><th>Name</th><th>MIME Type</th><th>Size</th><th>SHA-256</th></tr></thead><tbody>'+rows+'</tbody></table>';
  return section('Embedded Files', body, ' <span style="color:#666;font-weight:normal">('+items.length+')</span>');
}

// ── Tables (the titanarum-specific addition) ─────────────────
function buildTablesSection(report) {
  const tables = report.tables || [];
  if (!tables.length) {
    if (report.tablesTruncated) {
      return section('Tables', '<p style="color:#f90;font-size:0.75rem">⚠ table extraction hit safety caps; no tables could be emitted</p>');
    }
    return '';
  }
  let html = '';
  if (report.tablesTruncated) {
    html += '<p style="color:#f90;font-size:0.75rem;margin-bottom:0.6rem">⚠ extraction hit safety caps; list may be incomplete</p>';
  }
  const shown = tables.slice(0, 50);
  for (const t of shown) {
    html += '<div class="table-block">';
    html += '<div class="table-block-hdr">';
    html += '<span>Table — page '+esc(t.page != null ? t.page : '?')+', '+esc(t.rowCount)+'×'+esc(t.colCount)+', '+esc(t.extractionMethod||'')+'</span>';
    if (t.likelyDuplicateOfTagged) {
      html += '<span class="badge dup-badge" title="This lattice table’s cell footprint is substantially covered by an already-emitted tagged table on the same page">possible duplicate of tagged table</span>';
    }
    html += '</div>';
    html += '<div class="table-block-wrap"><table class="grid-table"><tbody>';
    const rows = t.rows || [];
    for (const row of rows) {
      html += '<tr>';
      for (const cell of row) html += '<td>'+esc(cell != null ? cell : '')+'</td>';
      html += '</tr>';
    }
    html += '</tbody></table></div>';
    html += '</div>';
  }
  if (tables.length > shown.length) {
    html += '<p style="color:#888;font-size:0.75rem">… and '+(tables.length - shown.length)+' more table(s) (see raw JSON)</p>';
  }
  return section('Tables', html, ' <span style="color:#666;font-weight:normal">('+tables.length+')</span>');
}

function buildExtractedTextSection(report) {
  const pages = (report.pageTexts || []).filter(p => p && (p.text || '').trim().length);
  if (!pages.length) return '';
  let html = '';
  for (const p of pages) {
    const chars = (p.text || '').length;
    html += '<details style="margin-bottom:0.4rem;border:1px solid #333;border-radius:3px">'
      + '<summary style="cursor:pointer;padding:0.4rem 0.6rem;background:#1e1e1e">Page ' + esc(p.page)
      + ' <span style="color:#666;font-weight:normal">(' + chars + ' chars)</span></summary>'
      + '<pre style="margin:0;padding:0.6rem;white-space:pre-wrap;word-break:break-word;'
      + 'font-size:0.78rem;color:#ccc;max-height:420px;overflow:auto">' + esc(p.text) + '</pre>'
      + '</details>';
  }
  return section('Extracted Text', html, ' <span style="color:#666;font-weight:normal">(' + pages.length + ' page' + (pages.length === 1 ? '' : 's') + ')</span>');
}

function buildReportView(job, report) {
  let html = buildCveBanner(report);
  html += '<div class="job-sections">';
  html += buildSummarySection(job, report);
  html += buildJsIndicatorsSection(report);
  html += buildFormFieldsSection(report);
  html += buildDocMetadataSection(report);
  html += buildAiSection(report);
  html += buildScreenshotsSection(report);
  html += buildRevisionsSection(report);
  html += buildUrlsSection(report);
  html += buildCodeSection('JavaScript', report.javascript);
  html += buildCodeSection('XFA Scripts', report.xfaScripts);
  html += buildPhonesSection(report);
  html += buildEmailsSection(report);
  html += buildActionsSection(report);
  html += buildLaunchActionsSection(report);
  html += buildImageGallerySection('Drawn Images', report.renderedImages);
  html += buildImageGallerySection('Resource Images', report.resourceImages);
  html += buildOcgSection(report);
  html += buildEmbeddedFilesSection(report);
  html += buildTablesSection(report);
  html += buildExtractedTextSection(report);
  html += '</div>';
  return html;
}

// ── client-side routing ───────────────────────────────────────
//
//   /              → recent-jobs list view
//   /jobs/<uuid>   → dedicated detail view for one job
//
let detailPollTimer = null;
const listPollTimers = [];
let _lastJobDetail = null;

function currentRoute() {
  const m = window.location.pathname.match(/^\/jobs\/([0-9a-fA-F-]+)$/);
  return m ? { kind: 'job', id: m[1] } : { kind: 'list' };
}

function navigateToJob(id) {
  const r = currentRoute();
  if (r.kind === 'job' && r.id === id) return;
  history.pushState({}, '', '/jobs/' + encodeURIComponent(id));
  renderRoute();
}

function navigateToList() {
  if (currentRoute().kind === 'list') return;
  history.pushState({}, '', '/');
  renderRoute();
}

function stopAllPolls() {
  while (listPollTimers.length) clearInterval(listPollTimers.pop());
  if (detailPollTimer) { clearTimeout(detailPollTimer); detailPollTimer = null; }
}

function renderRoute() {
  const route = currentRoute();
  if (route.kind === 'job') {
    showJobDetailView(route.id);
  } else {
    showListView();
  }
}

window.addEventListener('popstate', renderRoute);

function showListView() {
  stopAllPolls();
  document.getElementById('detail-view').style.display = 'none';
  document.getElementById('list-view').style.display = '';
  fetchJobs();
  renderStatePills();
  listPollTimers.push(setInterval(fetchJobs, 3000));
}

async function showJobDetailView(id) {
  stopAllPolls();
  document.getElementById('list-view').style.display = 'none';
  document.getElementById('detail-view').style.display = '';
  const idEl = document.getElementById('detail-id');
  if (idEl) idEl.textContent = id;
  const stateEl = document.getElementById('detail-state');
  if (stateEl) stateEl.innerHTML = '';

  const content = document.getElementById('detail-content');
  content.innerHTML = '<div style="padding:1.5rem;color:#888">Loading…</div>';

  try {
    const r = await fetch('/v1/jobs/' + encodeURIComponent(id));
    if (!r.ok) {
      content.innerHTML = '<div style="padding:1.5rem;color:#ef5350">Job not found (HTTP ' + r.status + ')</div>';
      return;
    }
    const job = normalizeJob(await r.json());
    _curArtMap = {};
    _curJobId = id;
    if (job.state === 'succeeded') {
      try {
        const mr = await fetch('/v1/jobs/' + encodeURIComponent(id) + '/metadata');
        if (mr.ok) {
          const env = await mr.json();
          job.envelope = env;
          for (const a of (env.artifacts || [])) {
            if (a && a.path && a.id) _curArtMap[a.path] = a.id;
          }
        }
      } catch { /* metadata unavailable; artifact links degrade gracefully */ }
      try {
        const rr = await fetch('/v1/jobs/' + encodeURIComponent(id) + '/report');
        if (rr.ok) job.report = await rr.json();
      } catch { /* viewer degrades to summary-only */ }
    }
    renderJobDetail(id, job);

    if (job.state === 'queued' || job.state === 'running') {
      detailPollTimer = setTimeout(() => showJobDetailView(id), 3000);
    }
  } catch (e) {
    content.innerHTML = '<div style="padding:1.5rem;color:#ef5350">Failed: ' + esc(e.message) + '</div>';
  }
}

// Holds the report for the currently rendered detail view so the raw-JSON panel can be
// serialized on first open rather than on every render.
let _rawReportForDetail = null;

function renderJobDetail(id, job) {
  _rawReportForDetail = job ? job.report : null;
  _lastJobDetail = { id, job };
  const stateEl = document.getElementById('detail-state');
  if (stateEl) stateEl.innerHTML = stateCell(job.state);

  const content = document.getElementById('detail-content');
  let html = '<div class="expand-actions">';
  if (job.state === 'succeeded') {
    html += '<a href="/v1/jobs/' + id + '/result" download><button class="dl">⬇ result.zip (pw: infected)</button></a>';
  }
  const isTerminal = job.state === 'succeeded' || job.state === 'failed';
  if (isTerminal) {
    html += '<button class="danger" data-act="delete-job" data-arg="' + esc(id) + '">Delete</button>';
  }
  if (job.report) {
    html += '<button class="dl" data-act="toggle-raw">{ } raw JSON</button>';
  }
  html += '</div>';

  if (job.report) {
    html += '<pre id="raw-json-view" data-filled="0" style="display:none;max-height:420px;overflow:auto;background:#0d0d0d;color:#cfcfcf;padding:0.75rem;border-radius:4px;font:0.72rem/1.45 \'Courier New\',monospace;white-space:pre;margin-bottom:0.75rem"></pre>';
  }

  if (job.state === 'failed') {
    html += '<div style="color:#ef5350;font-size:0.9rem;margin-bottom:0.75rem">' + esc(job.error_detail || 'Worker error') + '</div>';
  }
  if (job.report) {
    html += buildReportView(job, job.report);
  } else if (job.state === 'succeeded') {
    html += buildReportView(job, {}); // succeeded but report.json unavailable — Summary only
  } else if (job.state === 'queued' || job.state === 'running') {
    html += '<span style="color:#888">' + job.state + '… (auto-refreshing every 3 s)</span>';
  }
  content.innerHTML = html;
}

async function deleteJobAndGoBack(id) {
  if (!confirm('Delete job ' + id + '?')) return;
  try {
    const r = await fetch('/v1/jobs/' + id, { method: 'DELETE' });
    if (r.ok) {
      navigateToList();
    } else {
      let detail = 'unknown';
      try { detail = (await r.json()).detail || detail; } catch {}
      alert('Delete failed: ' + detail);
    }
  } catch (e) {
    alert('Delete failed: ' + e.message);
  }
}

// ── jobs table ────────────────────────────────────────────────
function stateCell(s) {
  return '<span class="state state-'+(s||'queued')+'">'+esc(s||'queued')+'</span>';
}
function workerCell(job) {
  if (!job.worker_runtime) return '—';
  if (job.worker_runtime === 'warm' && job.worker_tier) return esc(job.worker_tier);
  return esc(job.worker_runtime);
}

let searchDebounce = null;
let activeQuery = '';

function onSearch(val) {
  const q = val.trim();
  document.getElementById('search-clear').style.opacity = q ? '1' : '0.3';
  clearTimeout(searchDebounce);
  searchDebounce = setTimeout(() => triggerSearch(q), 250);
}

function clearSearch() {
  document.getElementById('search-input').value = '';
  onSearch('');
}

async function triggerSearch(q) {
  activeQuery = q;
  if (q) currentPage = 1;
  await fetchJobs();
}

// ── pagination state ──────────────────────────────────────────
const PAGE_SIZE = 50;
let currentPage = 1;
let _lastPageFull = false;

function goToPage(p) {
  currentPage = Math.max(1, p);
  fetchJobs();
}

function renderPager() {
  const el = document.getElementById('pager');
  if (!el) return;
  if (activeQuery) { el.innerHTML = ''; return; }
  const pg = (label, page, disabled) => disabled
    ? '<span class="pg disabled">'+label+'</span>'
    : '<span class="pg" data-act="go-page" data-arg="'+page+'">'+label+'</span>';
  el.innerHTML = [
    pg('‹ Prev', currentPage - 1, currentPage === 1),
    '<span class="count">page '+currentPage+'</span>',
    pg('Next ›', currentPage + 1, !_lastPageFull),
  ].join('');
}

// ── state-filter pills ────────────────────────────────────────
let activeStateFilter = null;

function setStateFilter(state) {
  activeStateFilter = (activeStateFilter === state) ? null : state;
  renderStatePills();
  const inp = document.getElementById('search-input');
  if (inp.value) { inp.value = ''; activeQuery = ''; }
  currentPage = 1;
  fetchJobs();
}

function renderStatePills() {
  const states = [
    { key: null,        label: 'All' },
    { key: 'queued',    label: 'Queued' },
    { key: 'running',   label: 'Running' },
    { key: 'succeeded', label: 'Succeeded' },
    { key: 'failed',    label: 'Failed' },
  ];
  const el = document.getElementById('state-pills');
  if (!el) return;
  el.innerHTML = states.map(s => {
    const active = (activeStateFilter === s.key);
    const classes = 'pill' + (s.key ? ' pill-' + s.key : '') + (active ? ' active' : '');
    const act = s.key ? 'data-act="set-state" data-arg="'+s.key+'"' : 'data-act="set-state"';
    return '<span class="'+classes+'" '+act+'>'+s.label+'</span>';
  }).join('');
}

async function fetchJobs() {
  const ind = document.getElementById('refresh-indicator');
  ind.textContent='refreshing…';
  try {
    const bbState = activeStateFilter ? (_UI_TO_BB_STATE[activeStateFilter] || activeStateFilter) : '';
    const stateQS = bbState ? '&status=' + bbState : '';
    const offset = (currentPage - 1) * PAGE_SIZE;
    const url = activeQuery
      ? '/v1/jobs?limit=200&q=' + encodeURIComponent(activeQuery) + stateQS
      : '/v1/jobs?limit=' + PAGE_SIZE + '&offset=' + offset + stateQS;
    const resp = await fetch(url);
    if (!resp.ok) throw new Error('HTTP '+resp.status);
    const data = await resp.json();
    const raw = Array.isArray(data) ? data : (data.jobs||[]);
    const jobs = raw.map(normalizeJob);
    _lastPageFull = !activeQuery && raw.length >= PAGE_SIZE;

    renderJobs(jobs);
    const countEl = document.getElementById('search-count');
    countEl.textContent = activeQuery ? jobs.length + ' result' + (jobs.length === 1 ? '' : 's') : '';
    ind.textContent='updated '+new Date().toLocaleTimeString();
    renderPager();
  } catch(e) { ind.textContent='error: '+e.message; }
}

function rowCells(job) {
  const fullId = job.id || '';
  const fullName = job.filename_hint || '—';
  return '<td class="cell-id" title="'+esc(fullId)+'" style="font-size:0.72rem;letter-spacing:-0.01em">'+esc(fullId)+'</td>'+
    '<td class="cell-truncate" title="'+esc(fullName)+'">'+esc(fullName)+'</td>'+
    '<td>'+stateCell(job.state)+'</td>'+
    '<td>'+relativeTime(job.submitted_at)+'</td>'+
    '<td>'+duration(job)+'</td>'+
    '<td>'+workerCell(job)+'</td>';
}

function renderJobs(jobs) {
  const tbody = document.getElementById('jobs-body');
  if (!jobs.length) {
    tbody.innerHTML='<tr><td colspan="6" class="no-jobs">No jobs yet.</td></tr>';
    return;
  }
  tbody.innerHTML = '';
  for (const job of jobs) {
    const tr = document.createElement('tr');
    tr.className='job-row';
    tr.innerHTML = rowCells(job);
    tr.addEventListener('click', ()=>navigateToJob(job.id));
    tbody.appendChild(tr);
  }
}

// ── boot ──────────────────────────────────────────────────────
document.getElementById('search-clear').style.opacity = '0.3';
renderRoute();
