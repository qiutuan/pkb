/* ===== 知识图谱视图 ===== */
const GraphViewPage = {
  el: null,
  kbs: [],
  kbIds: [],
  graph: null,
  pollTimer: null,
  extracting: false,

  async init(el) {
    this.el = el;
    try {
      this.kbs = await Api.get('/kbs') || [];
    } catch (e) { toast(e.message, 'error'); }
    if (this.kbs.length) this.kbIds = [this.kbs[0].kb.id];
    this.render();
    this.bind();
    if (this.kbIds.length) await this.loadGraph();
  },

  render() {
    this.el.innerHTML = `
      <div class="page graph-page">
        <div class="graph-head">
          <div class="page-title">知识图谱</div>
          <div class="page-sub">从知识库文档中自动抽取实体与关系，支持 GraphRAG 混合检索</div>
        </div>
        <div class="graph-toolbar-row card">
          <div class="graph-kb-pick">
            <label class="graph-kb-all"><input type="checkbox" id="graphKbAll"> 全选</label>
            <div class="graph-kb-tags" id="graphKbTags"></div>
            <span class="graph-kb-open" id="graphKbOpenBtn" title="选择知识库">▾ 选择</span>
            <div class="graph-kb-drop" id="graphKbDrop" style="display:none">
              ${this.kbs.map(k => `
                <label class="graph-kb-item" data-id="${k.kb.id}">
                  <input type="checkbox" ${this.kbIds.includes(k.kb.id) ? 'checked' : ''}> ${Util.esc(k.kb.name)}
                </label>`).join('')}
              ${this.kbs.length ? '' : '<div class="hint">请先创建知识库并上传文档</div>'}
            </div>
          </div>
          <div class="graph-stats" id="graphStats"></div>
          <div class="graph-toolbar-actions">
            <button class="btn btn-primary" id="graphExtractBtn">开始抽取</button>
            <button class="btn btn-danger" id="graphClearBtn">清空图谱</button>
          </div>
        </div>
        <div class="graph-layout">
          <aside class="graph-side">
            <div class="graph-side-block">
              <div class="graph-side-title">选择知识库</div>
              <div class="graph-kb-multi" id="graphKbList">
                ${this.kbs.map(k => `
                  <label class="graph-kb-item" data-id="${k.kb.id}">
                    <input type="checkbox" ${this.kbIds.includes(k.kb.id) ? 'checked' : ''}> ${Util.esc(k.kb.name)}
                    ${k.kb.graphEnabled ? '<span class="badge badge-purple">图谱</span>' : ''}
                  </label>`).join('')}
                ${this.kbs.length ? '' : '<div class="hint">请先创建知识库并上传文档</div>'}
              </div>
            </div>
            <div class="graph-side-block">
              <div class="graph-side-title">聚焦实体</div>
              <input class="input" id="graphQuery" placeholder="输入实体名，如：知识图谱">
              <button class="btn" id="graphFocusBtn" style="width:100%;margin-top:8px">聚焦（图谱召回高亮）</button>
              <div id="graphFocusInfo" class="hint"></div>
            </div>
            <div class="graph-side-block" id="graphEntityDetail">
              <div class="graph-side-title">实体详情</div>
              <div class="hint">点击图谱中的节点查看详情与溯源片段</div>
            </div>
            <div id="graphProgress" style="display:none"></div>
          </aside>
          <div class="graph-main">
            <div class="graph-canvas-wrap" id="graphCanvasWrap">
              <div class="graph-tip">滚轮缩放 · 拖拽平移 · 点击节点查看溯源 · 金色描边 = 命中查询</div>
              <canvas id="graphCanvas"></canvas>
              <div class="graph-empty" id="graphEmpty" style="display:none">
                <svg width="96" height="80" viewBox="0 0 96 80" fill="none"><circle cx="26" cy="26" r="10" stroke="#F97316" stroke-width="2.5"/><circle cx="70" cy="18" r="8" stroke="#FB923C" stroke-width="2.5"/><circle cx="74" cy="58" r="9" stroke="#FDBA74" stroke-width="2.5"/><circle cx="24" cy="60" r="7" stroke="#F97316" stroke-width="2.5"/><path d="M33 30L63 22M69 25L66 51M71 50L31 56M30 53L28 33" stroke="#FCA5A5" stroke-width="2" stroke-linecap="round"/></svg>
                <div class="empty-title">暂无图谱数据</div>
                <div class="empty-sub">先建库上传文档 → 点击「开始抽取」，由 LLM 自动抽取实体与关系</div>
              </div>
              <div class="graph-legend" id="graphLegend" style="display:none"></div>
            </div>
          </div>
        </div>
      </div>`;
  },

  bind() {
    const openBtn = this.el.querySelector('#graphKbOpenBtn');
    const drop = this.el.querySelector('#graphKbDrop');
    if (openBtn) openBtn.onclick = e => { e.stopPropagation(); drop.style.display = drop.style.display === 'none' ? 'block' : 'none'; };
    document.addEventListener('click', e => { if (drop && !e.target.closest('.graph-kb-pick')) drop.style.display = 'none'; });
    const list = this.el.querySelector('#graphKbList');
    list.addEventListener('change', e => {
      const item = e.target.closest('.graph-kb-item');
      if (item) {
        const id = Number(item.dataset.id);
        const on = item.querySelector('input').checked;
        this.kbIds = on ? [...new Set([...this.kbIds, id])] : this.kbIds.filter(x => x !== id);
      }
      const all = this.el.querySelector('#graphKbAll');
      if (all) all.checked = this.kbIds.length === this.kbs.length && this.kbs.length > 0;
      this.loadGraph();
    });
    const all = this.el.querySelector('#graphKbAll');
    if (all) all.onchange = e => {
      this.kbIds = e.target.checked ? this.kbs.map(k => k.kb.id) : [];
      this.el.querySelectorAll('.graph-kb-item input').forEach(cb => { cb.checked = e.target.checked; });
      this.loadGraph();
    };
    this.el.querySelector('#graphExtractBtn').onclick = () => this.extract();
    this.el.querySelector('#graphClearBtn').onclick = () => this.clear();
    this.el.querySelector('#graphFocusBtn').onclick = () => this.focus();
    const inp = this.el.querySelector('#graphQuery');
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') this.focus(); });
  },

  async loadGraph() {
    const statsEl = this.el.querySelector('#graphStats');
    const side = this.el.querySelector('#graphEntityDetail');
    const legendEl = this.el.querySelector('#graphLegend');
    const emptyEl = this.el.querySelector('#graphEmpty');
    if (!this.kbIds.length) {
      statsEl.innerHTML = '<span class="hint">请选择至少一个知识库</span>';
      emptyEl.style.display = 'block';
      legendEl.style.display = 'none';
      return;
    }
    try {
      const [stats, data] = await Promise.all([
        Api.post('/graph/multi/stats', { kbIds: this.kbIds }),
        Api.post('/graph/multi/data', { kbIds: this.kbIds, query: this.el.querySelector('#graphQuery').value.trim() || null })
      ]);
      statsEl.innerHTML = `
        <span class="stat-pill"><b>${stats.entities ?? 0}</b> 实体</span>
        <span class="stat-pill"><b>${stats.relations ?? 0}</b> 关系</span>
        <span class="stat-pill"><b>${this.kbIds.length}</b> 覆盖库</span>`;
      const tagBox = this.el.querySelector('#graphKbTags');
      if (tagBox) {
        const names = this.kbs.filter(k => this.kbIds.includes(k.kb.id)).map(k => k.kb.name);
        tagBox.innerHTML = names.map(n => `<span class="kb-tag">${Util.esc(n)}</span>`).join('') || '<span class="hint">未选择</span>';
      }
      const wrap = this.el.querySelector('#graphCanvasWrap');
      const canvas = this.el.querySelector('#graphCanvas');
      if (this.graph) this.graph.stop();
      this.graph = new GraphView(canvas, { onNodeClick: n => this.showEntity(n) });
      this.graph.setData(data.nodes || [], data.links || []);
      const nodes = data.nodes || [];
      emptyEl.style.display = nodes.length ? 'none' : 'block';
      legendEl.style.display = nodes.length ? '' : 'none';
      if (nodes.length) {
        legendEl.innerHTML = Object.entries(this.graph.typeColors)
          .map(([t, c]) => `<span><i style="background:${c}"></i>${t}</span>`).join('');
      }
      if (!nodes.length) {
        side.innerHTML = `<div class="hint">该知识库暂无图谱，点击「开始抽取」生成</div>`;
      } else {
        side.innerHTML = `<div class="hint">点击图谱中的节点查看详情与溯源片段</div>`;
      }
      const info = this.el.querySelector('#graphFocusInfo');
      if (data.matched && data.matched.length) {
        info.innerHTML = `命中 ${data.matched.length} 个实体：${data.matched.join('、')}`;
      }
    } catch (e) {
      statsEl.innerHTML = `<span class="hint" style="color:var(--danger)">加载失败：${Util.esc(e.message)}</span>`;
    }
  },

  async focus() {
    const q = this.el.querySelector('#graphQuery').value.trim();
    if (!q) { toast('请输入实体名', 'warn'); return; }
    if (!this.kbIds.length) { toast('请先选择知识库', 'warn'); return; }
    await this.loadGraph();
  },

  async extract() {
    if (!this.kbIds.length) { toast('请先选择知识库', 'warn'); return; }
    if (this.extracting) return;
    this.extracting = true;
    const btn = this.el.querySelector('#graphExtractBtn');
    btn.disabled = true;
    btn.textContent = '抽取中…';
    const prog = this.el.querySelector('#graphProgress');
    prog.style.display = 'block';
    prog.innerHTML = '<div class="hint">已提交抽取任务…</div>';
    const done = {};
    for (const kbId of this.kbIds) {
      try {
        await Api.post(`/graph/${kbId}/extract`);
        done[kbId] = true;
      } catch (e) {
        done[kbId] = false;
        prog.innerHTML = `<div class="hint" style="color:var(--danger)">知识库 ${kbId} 提交失败：${Util.esc(e.message)}</div>`;
      }
    }
    this.pollStatus(done);
  },

  pollStatus(done) {
    if (this.pollTimer) clearInterval(this.pollTimer);
    const prog = this.el.querySelector('#graphProgress');
    const kbNames = Object.fromEntries(this.kbs.map(k => [k.kb.id, k.kb.name]));
    this.pollTimer = setInterval(async () => {
      let allIdle = true;
      let lines = '';
      for (const kbId of this.kbIds) {
        try {
          const st = await Api.get(`/graph/${kbId}/extract-status`);
          const running = st.running;
          const processed = st.processed ?? 0;
          const total = st.total ?? 0;
          const pct = total ? Math.round(processed / total * 100) : 0;
          lines += `<div class="batch-line"><span>${Util.esc(kbNames[kbId] || 'KB' + kbId)}</span><b>${running ? pct + '%' : (done[kbId] ? '完成' : '空闲')}</b></div>`;
          if (running) allIdle = false;
        } catch (e) {
          lines += `<div class="batch-line"><span>${Util.esc(kbNames[kbId] || 'KB' + kbId)}</span><b style="color:var(--danger)">状态查询失败</b></div>`;
        }
      }
      if (prog) prog.innerHTML = `<div class="batch-progress">${lines}</div>`;
      if (allIdle) {
        clearInterval(this.pollTimer);
        this.pollTimer = null;
        this.extracting = false;
        const btn = this.el.querySelector('#graphExtractBtn');
        btn.disabled = false;
        btn.textContent = '开始抽取';
        if (prog) { prog.style.display = 'none'; prog.innerHTML = ''; }
        toast('图谱抽取完成', 'success');
        await this.loadGraph();
      }
    }, 3000);
  },

  async clear() {
    if (!this.kbIds.length) { toast('请先选择知识库', 'warn'); return; }
    confirmBox('确定清空所选知识库的全部图谱实体与关系？此操作不可恢复。').then(async ok => {
      if (!ok) return;
      try {
        for (const kbId of this.kbIds) {
          await Api.post(`/graph/${kbId}/clear`);
        }
        toast('已清空', 'success');
        await this.loadGraph();
      } catch (e) { toast(e.message, 'error'); }
    });
  },

  async showEntity(node) {
    const side = this.el.querySelector('#graphEntityDetail');
    side.innerHTML = `
      <div class="entity-name" style="color:${this.graph.color(node)}">${Util.esc(node.name)}</div>
      <div class="entity-meta">
        <span class="badge badge-gray">${Util.esc(node.type || '未分类')}</span>
        <span class="badge badge-blue">度 ${node.degree}</span>
      </div>
      <div class="entity-desc">${Util.esc(node.description || '暂无描述')}</div>
      <div class="entity-src-title">溯源片段</div>
      <div id="entityChunks" class="hint">加载中…</div>`;
    try {
      const chunks = await Api.get(`/graph/entities/${node._eid}/chunks`) || [];
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
