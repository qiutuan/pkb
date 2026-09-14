/* ===== 通用工具 ===== */
const Util = {
  esc(s) {
    if (s === null || s === undefined) return '';
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  },
  fmtBytes(n) {
    if (n === null || n === undefined) return '-';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB';
    return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  },
  fmtTime(s) {
    if (!s) return '';
    return String(s).replace('T', ' ').substring(5, 16);
  },
  debounce(fn, ms) {
    let t;
    return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
  },
  qs(sel, root) { return (root || document).querySelector(sel); },
  qsa(sel, root) { return Array.from((root || document).querySelectorAll(sel)); },
  el(html) {
    const t = document.createElement('template');
    t.innerHTML = html.trim();
    return t.content.firstChild;
  },
  statusBadge(status) {
    const map = {
      PENDING: ['badge-gray', '排队中'],
      PROCESSING: ['badge-blue', '处理中'],
      INDEXED: ['badge-green', '已入库'],
      FAILED: ['badge-red', '失败']
    };
    const [cls, text] = map[status] || ['badge-gray', status];
    return `<span class="badge ${cls}">${text}</span>`;
  },
  providerTypeName(t) {
    return { openai_compatible: 'OpenAI 兼容', ollama: 'Ollama 本地', anthropic: 'Anthropic', gemini: 'Google Gemini', local: '内置本地' }[t] || t;
  }
};

function toast(msg, type = 'info', ms = 3200) {
  const c = document.getElementById('toast-container');
  const t = Util.el(`<div class="toast ${type}">${Util.esc(msg)}</div>`);
  c.appendChild(t);
  setTimeout(() => { t.style.transition = 'opacity .3s'; t.style.opacity = '0'; setTimeout(() => t.remove(), 320); }, ms);
}

function openModal(html, opts = {}) {
  const root = document.getElementById('modal-root');
  const mask = Util.el(`<div class="modal-mask"><div class="modal">${html}</div></div>`);
  root.innerHTML = '';
  root.appendChild(mask);
  mask.addEventListener('click', e => { if (e.target === mask && !opts.lock) closeModal(); });
  return mask;
}
function closeModal() {
  document.getElementById('modal-root').innerHTML = '';
}
function confirmBox(msg) {
  return new Promise(resolve => {
    const mask = openModal(`
      <div class="modal-title">确认操作</div>
      <div style="color:var(--text-2);font-size:14px;">${Util.esc(msg)}</div>
      <div class="modal-foot">
        <button class="btn" onclick="closeModal();resolveRef(false)">取消</button>
        <button class="btn btn-primary" onclick="closeModal();resolveRef(true)">确认</button>
      </div>`);
    window.resolveRef = resolve;
    mask._r = resolve;
  });
}
