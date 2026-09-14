/* ===== 应用入口与路由 ===== */
const App = {
  views: { chat: ChatView, kbs: KbView, graph: GraphViewPage, models: ModelsView, settings: SettingsView },
  current: null,

  async boot() {
    this.bindNav();
    this.loadInfo();
    window.addEventListener('hashchange', () => this.route());
    if (!location.hash) location.hash = '#/chat';
    else this.route();
  },

  bindNav() {
    document.querySelectorAll('.nav-item').forEach(a => {
      a.addEventListener('click', () => {
        document.querySelectorAll('.nav-item').forEach(x => x.classList.remove('active'));
        a.classList.add('active');
      });
    });
  },

  async route() {
    const hash = location.hash.replace('#/', '') || 'chat';
    const name = hash.split('?')[0];
    const view = document.getElementById('view');
    const active = this.current;
    if (active && active.stop) {
      try { active.stop(); } catch (e) { /* ignore */ }
    }
    document.querySelectorAll('.nav-item').forEach(x => x.classList.toggle('active', x.dataset.nav === name));
    view.innerHTML = '<div class="page"><div class="hint" style="padding:40px;text-align:center">加载中…</div></div>';
    const v = this.views[name];
    if (!v) { view.innerHTML = '<div class="empty"><div class="empty-icon">404</div><div>页面不存在</div></div>'; return; }
    try {
      await v.init(view);
      this.current = v;
    } catch (e) {
      view.innerHTML = `<div class="empty"><div class="empty-icon">⚠️</div><div class="empty-title">页面加载失败</div><div>${Util.esc(e.message || '未知错误')}</div></div>`;
    }
  },

  async loadInfo() {
    try {
      const info = await Api.get('/system/info');
      const badge = document.getElementById('storageBadge');
      badge.textContent = '向量库：' + (info.vectorMode === 'pgvector' ? 'PostgreSQL pgvector' : '内置 SQLite + HNSW');
      badge.title = '数据目录：' + (info.dataDir || '');
    } catch (e) {
      const badge = document.getElementById('storageBadge');
      badge.textContent = '服务未连接';
      badge.classList.add('badge-red');
    }
  }
};

document.addEventListener('DOMContentLoaded', () => App.boot());
