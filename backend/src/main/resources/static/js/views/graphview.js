/* ===== 知识图谱视图 ===== */
const GraphViewPage = {
  el: null,
  kbs: [],
  current: null,
  graph: null,
  pollTimer: null,

  async init(el) {
    this.el = el;
    try {
      this.kbs = await Api.get('/kbs') || [];
    } catch (e) { toast(e.message, 'error'); }
    this.current = this.kbs.find(k => k.kb.graphEnabled)?.kb.id || this.kbs[0]?.kb.id || null;
    this.render();
    this.bind();
    if (this.current) await this.loadGraph();
  },

  render() {
    this.el.innerHTML = `
      <div class="graph-page">
        <div class="graph-head card">
          <label class="label" style="margin:0">选择知识库</label>
          <select class="select" id="graphKbSel" style="max-width:280px">
            ${this.kbs.map(k => `<option value="${k.kb.id}" ${k.kb.id === this.current ? 'selected' : ''}>${Util.esc(k.kb.name)}${k.kb.graphEnabled ? '（图谱）' : ''}</option>`).join('')}
          </select>
          <div class="graph-stats" id="graphStats"></div>
          <div style="flex:1"></div>
          <button class="btn" id="graphExtractBtn">开始抽取</button>
          <button class="btn btn-danger" id="graphClearBtn">清空图谱</button>
        </div>
        <div class="graph-body">
          <div class="graph-canvas card" id="graphCanvasWrap">
            <div class="graph-tip">滚轮缩放 · 拖拽平移 · 点击节点查看溯源 · 金色描边=命中查询</div>
            <canvas id="graphCanvas"></canvas>
          </div>
          <aside class="graph-side card" id="graphSide">
            <div class="graph-search">
              <label class="label">聚焦实体（混合检索命中高亮）</label>
              <div class="row">
                <input class="input" id="graphQuery" placeholder="输入实体名，如：知识图谱">
                <button class="btn btn-primary" id="graphFocusBtn">聚焦</button>
              </div>
              <div id="graphFocusInfo" class="hint"></div>
            </div>
            <div id="graphEntityDetail"></div>
          </aside>
        </div>
      </div>`;
  },

  bind() {
    this.el.querySelector('#graphKbSel').onchange = async e => {
      this.current = Number(e.target.value);
      await this.loadGraph();
    };
    this.el.querySelector('#graphExtractBtn').onclick = () => this.extract();
    this.el.querySelector('#graphClearBtn').onclick = () => this.clear();
    this.el.querySelector('#graphFocusBtn').onclick = () => this.focus();
    const inp = this.el.querySelector('#graphQuery');
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') this.focus(); });
  },

  async loadGraph() {
    if (!this.current) {
      this.el.querySelector('#graphStats').innerHTML = '<span class="hint">请先创建知识库</span>';
      this.el.querySelector('#graphEntityDetail').innerHTML = '<div class="empty"><div class="empty-icon">🕸</div><div class="empty-title">暂无图谱</div><div>先创建并上传文档到知识库</div></div>';
      return;
    }
    const statsEl = this.el.querySelector('#graphStats');
    const side = this.el.querySelector('#graphEntityDetail');
    try {
      const [stats, data] = await Promise.all([
        Api.get(`/graph/${this.current}/stats`),
        Api.get(`/graph/${this.current}/data`)
      ]);
      statsEl.innerHTML = `
        <span class="badge badge-blue">实体 ${stats.entities ?? 0}</span>
        <span class="badge badge-purple">关系 ${stats.relations ?? 0}</span>
        <span class="badge badge-gray">状态：${Util.esc(stats.status || 'idle')}</span>`;
      const wrap = this.el.querySelector('#graphCanvasWrap');
      const canvas = this.el.querySelector('#graphCanvas');
      if (this.graph) this.graph.stop();
      this.graph = new GraphView(canvas, { onNodeClick: n => this.showEntity(n) });
      this.graph.setData(data.nodes || [], data.links || []);
      if (!(data.nodes || []).length) {
        side.innerHTML = `<div class="empty"><div class="empty-icon">🕸</div><div class="empty-title">该知识库暂无图谱</div><div>上传文档后点击「开始抽取」，由 LLM 自动抽取实体与关系</div></div>`;
      } else {
        side.innerHTML = `<div class="graph-legend">
          ${Object.entries(this.graph.typeColors).map(([t, c]) => `<span><i style="background:${c}"></i>${t}</span>`).join('')}
        </div><div class="hint" style="margin-top:8px">点击图谱中的节点查看详情与溯源</div>`;
      }
    } catch (e) {
      statsEl.innerHTML = `<span class="hint">加载失败：${Util.esc(e.message)}</span>`;
    }
  },

  async focus() {
    const q = this.el.querySelector('#graphQuery').value.trim();
    const info = this.el.querySelector('#graphFocusInfo');
    if (!q) { toast('请输入实体名', 'warn'); return; }
    try {
      const data = await Api.get(`/graph/${this.current}/data?query=${encodeURIComponent(q)}`);
      this.graph.setData(data.nodes || [], data.links || []);
      const matched = (data.matched || []);
      info.innerHTML = matched.length ? `命中 ${matched.length} 个实体：${matched.join('、')}` : '未命中实体，可尝试图谱抽取或换关键词';
    } catch (e) { toast(e.message, 'error'); }
  },

  async extract() {
    if (!this.current) return;
    const btn = this.el.querySelector('#graphExtractBtn');
    btn.disabled = true;
    btn.textContent = '抽取中…';
    try {
      await Api.post(`/graph/${this.current}/extract`);
      toast('已提交抽取任务，正在处理…', 'success');
      this.pollStatus();
    } catch (e) {
      btn.disabled = false;
      btn.textContent = '开始抽取';
      toast(e.message, 'error');
    }
  },

  pollStatus() {
    if (this.pollTimer) clearInterval(this.pollTimer);
    this.pollTimer = setInterval(async () => {
      try {
        const st = await Api.get(`/graph/${this.current}/extract-status`);
        const badge = this.el.querySelector('#graphStats .badge-gray');
        if (badge) badge.textContent = '状态：' + (st.status || '');
        if (st.status === 'idle') {
          clearInterval(this.pollTimer);
          this.pollTimer = null;
          const btn = this.el.querySelector('#graphExtractBtn');
          btn.disabled = false;
          btn.textContent = '开始抽取';
          toast('图谱抽取完成', 'success');
          await this.loadGraph();
        }
      } catch (e) { /* 继续轮询 */ }
    }, 3000);
  },

  async clear() {
    if (!this.current) return;
    confirmBox('清空该知识库的全部图谱实体与关系？').then(async ok => {
      if (!ok) return;
      try {
        await Api.post(`/graph/${this.current}/clear`);
        toast('已清空', 'success');
        await this.loadGraph();
      } catch (e) { toast(e.message, 'error'); }
    });
  },

  async showEntity(node) {
    const side = this.el.querySelector('#graphEntityDetail');
    side.innerHTML = `
      <div class="entity-detail">
        <div class="entity-name" style="color:${this.graph.color(node)}">${Util.esc(node.name)}</div>
        <div class="entity-meta"><span class="badge badge-gray">${Util.esc(node.type || '未分类')}</span><span class="badge badge-blue">度 ${node.degree}</span></div>
        <div class="entity-desc">${Util.esc(node.description || '暂无描述')}</div>
        <div class="entity-src-title">溯源片段</div>
        <div id="entityChunks" class="hint">加载中…</div>
      </div>`;
    try {
      const chunks = await Api.get(`/graph/entities/${node.id}/chunks`) || [];
      const box = side.querySelector('#entityChunks');
      if (!chunks.length) { box.innerHTML = '该实体暂无溯源片段'; return; }
      box.className = '';
      box.innerHTML = chunks.map(c => `
        <div class="chunk-item">
          <div class="chunk-meta">${Util.esc(c.docName)} · 第 ${(c.position ?? 0) + 1} 段</div>
          <div class="chunk-content">${Util.esc((c.content || '').slice(0, 200))}${(c.content || '').length > 200 ? '…' : ''}</div>
        </div>`).join('');
    } catch (e) {
      side.querySelector('#entityChunks').textContent = '加载失败';
    }
  }
};
