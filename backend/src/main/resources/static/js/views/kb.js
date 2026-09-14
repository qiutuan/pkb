/* ===== 知识库视图 ===== */
const KbView = {
  el: null,
  categories: [],
  kbs: [],
  currentKb: null,
  pollTimer: null,

  async init(el) {
    this.el = el;
    await this.reload();
    this.render();
    this.bind();
  },

  async reload() {
    try {
      const [cats, kbs] = await Promise.all([Api.get('/categories'), Api.get('/kbs')]);
      this.categories = cats || [];
      this.kbs = kbs || [];
    } catch (e) {
      toast(e.message, 'error');
    }
  },

  catTree() {
    const roots = this.categories.filter(c => !c.parentId);
    const build = (p, depth) => {
      const kids = this.categories.filter(c => c.parentId === p.id);
      return `<div class="cat-node" style="padding-left:${depth * 16}px" data-id="${p.id}">
        <span class="cat-name">${'📁 '.repeat(Math.min(depth, 3))}${Util.esc(p.name)}</span>
        <span class="cat-count">${this.kbs.filter(k => k.kb.categoryId === p.id).length}</span>
        <span class="cat-ops">
          <button class="btn btn-sm btn-ghost" data-cat-edit="${p.id}">编辑</button>
          <button class="btn btn-sm btn-ghost btn-danger" data-cat-del="${p.id}">删</button>
        </span>
      </div>` + (kids.map(k => build(k, depth + 1)).join(''));
    };
    return roots.map(r => build(r, 0)).join('') +
      (this.categories.length ? '' : '<div class="hint" style="padding:10px">暂无分类，点击右上角「新建分类」</div>');
  },

  render() {
    this.el.innerHTML = `
      <div class="kb-layout">
        <aside class="kb-side card">
          <div class="kb-side-head">
            <span style="font-weight:600">分类</span>
            <button class="btn btn-sm btn-primary" id="kbNewCat">新建分类</button>
          </div>
          <div class="kb-cat-tree" id="kbCatTree">${this.catTree()}</div>
        </aside>
        <section class="kb-main">
          <div class="kb-head">
            <div>
              <div class="page-title">知识库</div>
              <div class="page-sub">共 ${this.kbs.length} 个知识库 · 多级分类 · 每库独立索引与配置</div>
            </div>
            <button class="btn btn-primary" id="kbNew">+ 新建知识库</button>
          </div>
          <div id="kbList" class="kb-grid">${this.renderKbs()}</div>
        </section>
      </div>`;
  },

  renderKbs() {
    if (!this.kbs.length) {
      return `<div class="empty"><div class="empty-icon">🗂</div><div class="empty-title">还没有知识库</div><div>创建知识库后上传文档，即可开始 RAG 问答与图谱构建</div></div>`;
    }
    return this.kbs.map(v => {
      const k = v.kb;
      return `<div class="kb-card" data-id="${k.id}">
        <div class="kb-card-top">
          <span class="kb-card-name">${Util.esc(k.name)}</span>
          ${k.multimodal ? '<span class="badge badge-purple">多模态</span>' : '<span class="badge badge-gray">纯文本</span>'}
          ${k.graphEnabled ? '<span class="badge badge-teal">图谱</span>' : ''}
        </div>
        <div class="kb-card-desc">${Util.esc(k.description || '暂无描述')}</div>
        <div class="kb-card-meta">
          <span>分类：${Util.esc(v.categoryName || '未分类')}</span>
          <span>向量：${Util.esc(v.providerName || '默认')}</span>
        </div>
        <div class="kb-card-meta">
          <span>文档 ${v.docCount}</span><span>分块 ${v.chunkCount}</span><span>向量 ${v.vectorCount}</span>
        </div>
        <div class="kb-card-actions">
          <button class="btn btn-sm btn-primary" data-open="${k.id}">管理文档</button>
          <button class="btn btn-sm" data-edit="${k.id}">编辑</button>
          <button class="btn btn-sm btn-danger" data-del="${k.id}">删除</button>
        </div>
      </div>`;
    }).join('');
  },

  bind() {
    this.el.querySelector('#kbNewCat').onclick = () => this.catModal();
    this.el.querySelector('#kbNew').onclick = () => this.kbModal();
    this.el.querySelector('#kbCatTree').addEventListener('click', e => {
      const ed = e.target.closest('[data-cat-edit]');
      const del = e.target.closest('[data-cat-del]');
      if (ed) { const c = this.categories.find(x => x.id === Number(ed.dataset.catEdit)); this.catModal(c); }
      if (del) {
        const c = this.categories.find(x => x.id === Number(del.dataset.catDel));
        confirmBox(`删除分类「${c.name}」？其下知识库将变为未分类。`).then(async ok => {
          if (!ok) return;
          try { await Api.del(`/categories/${c.id}`); toast('已删除', 'success'); await this.reload(); this.render(); this.bind(); }
          catch (err) { toast(err.message, 'error'); }
        });
      }
    });
    this.el.querySelector('#kbList').addEventListener('click', e => {
      const open = e.target.closest('[data-open]');
      const edit = e.target.closest('[data-edit]');
      const del = e.target.closest('[data-del]');
      if (open) this.openKb(Number(open.dataset.open));
      if (edit) {
        const v = this.kbs.find(x => x.kb.id === Number(edit.dataset.edit));
        this.kbModal(v.kb);
      }
      if (del) {
        const v = this.kbs.find(x => x.kb.id === Number(del.dataset.del));
        confirmBox(`删除知识库「${v.kb.name}」？文档与向量索引将一并删除，不可恢复。`).then(async ok => {
          if (!ok) return;
          try { await Api.del(`/kbs/${v.kb.id}`); toast('已删除', 'success'); await this.reload(); this.render(); this.bind(); }
          catch (err) { toast(err.message, 'error'); }
        });
      }
    });
  },

  catModal(cat) {
    const isEdit = !!cat;
    const mask = openModal(`
      <div class="modal-title">${isEdit ? '编辑分类' : '新建分类'}</div>
      <div class="form-item">
        <label class="label">分类名称</label>
        <input class="input" id="catName" value="${Util.esc(cat?.name || '')}" placeholder="如：技术 / 生活">
      </div>
      <div class="form-item">
        <label class="label">上级分类（可选，最多两级）</label>
        <select class="select" id="catParent">
          <option value="">无（一级分类）</option>
          ${this.categories.filter(c => !c.parentId && c.id !== cat?.id).map(c => `<option value="${c.id}" ${cat?.parentId === c.id ? 'selected' : ''}>${Util.esc(c.name)}</option>`).join('')}
        </select>
      </div>
      <div class="modal-foot">
        <button class="btn" onclick="closeModal()">取消</button>
        <button class="btn btn-primary" id="catSave">保存</button>
      </div>`);
    mask.querySelector('#catSave').onclick = async () => {
      const name = mask.querySelector('#catName').value.trim();
      if (!name) { toast('请填写分类名称', 'warn'); return; }
      const parentId = mask.querySelector('#catParent').value;
      try {
        if (isEdit) await Api.put(`/categories/${cat.id}`, { id: cat.id, name, parentId: parentId ? Number(parentId) : null });
        else await Api.post('/categories', { name, parentId: parentId ? Number(parentId) : null });
        closeModal(); toast('已保存', 'success');
        await this.reload(); this.render(); this.bind();
      } catch (e) { toast(e.message, 'error'); }
    };
  },

  kbModal(kb) {
    const isEdit = !!kb;
    const providers = [];
    Api.get('/providers').then(list => {
      list.forEach(p => {
        if (p.providerType !== 'local' && p.embeddingModel) providers.push(p);
      });
      this._kbForm(kb, providers);
    }).catch(() => this._kbForm(kb, providers));
  },

  _kbForm(kb, providers) {
    const isEdit = !!kb;
    const mask = openModal(`
      <div class="modal-title">${isEdit ? '编辑知识库' : '新建知识库'}</div>
      <div class="form-grid">
        <div class="form-item">
          <label class="label">名称 *</label>
          <input class="input" id="kbName" value="${Util.esc(kb?.name || '')}">
        </div>
        <div class="form-item">
          <label class="label">分类</label>
          <select class="select" id="kbCategory">
            <option value="">未分类</option>
            ${this.categories.map(c => `<option value="${c.id}" ${kb?.categoryId === c.id ? 'selected' : ''}>${Util.esc(c.name)}</option>`).join('')}
          </select>
        </div>
      </div>
      <div class="form-item">
        <label class="label">描述</label>
        <input class="input" id="kbDesc" value="${Util.esc(kb?.description || '')}" placeholder="一句话说明这个知识库的内容">
      </div>
      <div class="form-grid">
        <div class="form-item">
          <label class="label">Embedding 模型</label>
          <select class="select" id="kbEmbedding">
            <option value="">跟随默认</option>
            ${providers.map(p => `<option value="${p.id}" ${kb?.embeddingProviderId === p.id ? 'selected' : ''}>${Util.esc(p.name)}（${Util.esc(p.embeddingModel)}）</option>`).join('')}
          </select>
        </div>
        <div class="form-item">
          <label class="label">分块策略</label>
          <select class="select" id="kbStrategy">
            <option value="fixed" ${kb?.chunkStrategy !== 'paragraph' ? 'selected' : ''}>固定长度（按字符）</option>
            <option value="paragraph" ${kb?.chunkStrategy === 'paragraph' ? 'selected' : ''}>按段落</option>
          </select>
        </div>
      </div>
      <div class="form-grid">
        <div class="form-item">
          <label class="label">分块大小</label>
          <input class="input" type="number" id="kbChunkSize" value="${kb?.chunkSize ?? 600}" min="50" max="4000">
        </div>
        <div class="form-item">
          <label class="label">重叠</label>
          <input class="input" type="number" id="kbChunkOverlap" value="${kb?.chunkOverlap ?? 100}" min="0" max="1000">
        </div>
      </div>
      <div class="form-item" style="display:flex;gap:28px">
        <label class="switch-label"><span class="switch"><input type="checkbox" id="kbMultimodal" ${kb?.multimodal ? 'checked' : ''}><span class="slider"></span></span> 多模态模式（接受图片/视频，需多模态模型）</label>
        <label class="switch-label"><span class="switch"><input type="checkbox" id="kbGraph" ${kb?.graphEnabled === false ? '' : 'checked'}><span class="slider"></span></span> 启用知识图谱</label>
      </div>
      <div class="hint">纯文本模式仅接受 txt / md / pdf / docx / doc，图片视频将被拒绝并提示。</div>
      <div class="modal-foot">
        <button class="btn" onclick="closeModal()">取消</button>
        <button class="btn btn-primary" id="kbSave">保存</button>
      </div>`);
    mask.querySelector('#kbSave').onclick = async () => {
      const name = mask.querySelector('#kbName').value.trim();
      if (!name) { toast('请填写知识库名称', 'warn'); return; }
      const body = {
        name,
        description: mask.querySelector('#kbDesc').value.trim(),
        categoryId: mask.querySelector('#kbCategory').value ? Number(mask.querySelector('#kbCategory').value) : null,
        embeddingProviderId: mask.querySelector('#kbEmbedding').value ? Number(mask.querySelector('#kbEmbedding').value) : null,
        chunkStrategy: mask.querySelector('#kbStrategy').value,
        chunkSize: Number(mask.querySelector('#kbChunkSize').value) || 600,
        chunkOverlap: Number(mask.querySelector('#kbChunkOverlap').value) || 100,
        multimodal: mask.querySelector('#kbMultimodal').checked,
        graphEnabled: mask.querySelector('#kbGraph').checked
      };
      try {
        if (isEdit) await Api.put(`/kbs/${kb.id}`, body);
        else await Api.post('/kbs', body);
        closeModal(); toast('已保存', 'success');
        await this.reload(); this.render(); this.bind();
      } catch (e) { toast(e.message, 'error'); }
    };
  },

  /* ===== 知识库详情 ===== */
  async openKb(id) {
    this.currentKb = this.kbs.find(x => x.kb.id === id) || { kb: { id } };
    const v = this.kbs.find(x => x.kb.id === id);
    this.el.innerHTML = `
      <div class="kb-detail">
        <div class="kb-detail-head card">
          <button class="btn btn-sm" id="kbBack">← 返回</button>
          <div style="flex:1">
            <div style="font-weight:700;font-size:16px">${Util.esc(v?.kb.name || '')}
              ${v?.kb.multimodal ? '<span class="badge badge-purple" style="margin-left:8px">多模态</span>' : '<span class="badge badge-gray" style="margin-left:8px">纯文本</span>'}
              ${v?.kb.graphEnabled ? '<span class="badge badge-teal">图谱</span>' : ''}
            </div>
            <div class="hint">${Util.esc(v?.kb.description || '')} · ${Util.esc(v?.categoryName || '未分类')} · 向量模型：${Util.esc(v?.providerName || '默认')} · 分块：${v?.kb.chunkStrategy === 'paragraph' ? '按段落' : '固定'} ${v?.kb.chunkSize}/${v?.kb.chunkOverlap}</div>
          </div>
          <button class="btn btn-primary" id="kbUploadBtn">上传文档</button>
          <input type="file" id="kbFileInput" multiple hidden
            accept="${v?.kb.multimodal ? '.txt,.md,.pdf,.docx,.doc,.png,.jpg,.jpeg,.gif,.bmp,.webp,.mp4,.mov,.avi' : '.txt,.md,.pdf,.docx,.doc'}">
        </div>
        <div class="kb-detail-docs card">
          <div class="kb-docs-head"><span>文档（${v?.docCount ?? 0}）</span><span class="hint">上传后自动解析 → 分块 → 向量化 → 入库</span></div>
          <div id="kbDocTable"></div>
        </div>
      </div>`;
    this.bindDetail();
    await this.loadDocs();
    this.startPolling();
  },

  async loadDocs() {
    if (!this.currentKb) return;
    const table = this.q('#kbDocTable');
    if (!table) return;
    try {
      const docs = await Api.get(`/kbs/${this.currentKb.kb.id}/documents`) || [];
      if (!docs.length) {
        table.innerHTML = `<div class="empty"><div class="empty-icon">📄</div><div class="empty-title">暂无文档</div><div>支持 txt / md / pdf / docx / doc${this.currentKb.kb.multimodal ? ' / 图片 / 视频' : ''}，点击右上角上传</div></div>`;
        return;
      }
      table.innerHTML = `<table class="table">
        <thead><tr><th>文件名</th><th>类型</th><th>大小</th><th>状态</th><th>进度</th><th>分块</th><th>上传时间</th><th>操作</th></tr></thead>
        <tbody>${docs.map(d => `<tr>
          <td class="ellipsis" style="max-width:260px" title="${Util.esc(d.fileName)}">${Util.esc(d.fileName)}</td>
          <td><span class="badge badge-gray">${Util.esc((d.fileType || '').replace('application/', '').replace('text/', '').slice(0, 8))}</span></td>
          <td>${Util.fmtBytes(d.fileSize)}</td>
          <td>${Util.statusBadge(d.status)}${d.errorMessage ? `<div class="hint" style="color:var(--danger)" title="${Util.esc(d.errorMessage)}">${Util.esc((d.errorMessage || '').slice(0, 40))}</div>` : ''}</td>
          <td>${d.status === 'INDEXED' ? '100%' : Math.round((d.progress || 0) * 100) + '%'}</td>
          <td>${d.chunkCount ?? 0}</td>
          <td>${Util.fmtTime(d.createdAt)}</td>
          <td><div class="actions">
            <button class="btn btn-sm" data-chunks="${d.id}" title="查看分块">分块</button>
            ${d.status === 'FAILED' ? `<button class="btn btn-sm" data-retry="${d.id}">重试</button>` : ''}
            ${d.status === 'INDEXED' ? `<button class="btn btn-sm" data-reindex="${d.id}">重建</button>` : ''}
            <button class="btn btn-sm btn-danger" data-deldoc="${d.id}">删除</button>
          </div></td>
        </tr>`).join('')}</tbody></table>`;
    } catch (e) {
      table.innerHTML = `<div class="hint" style="padding:16px;color:var(--danger)">${Util.esc(e.message)}</div>`;
    }
  },

  startPolling() {
    this.stopPolling();
    this.pollTimer = setInterval(() => {
      const hasRunning = this.el.querySelector('.badge-blue');
      if (hasRunning || this.el.querySelector('.badge-gray')) this.loadDocs();
    }, 2500);
  },
  stopPolling() {
    if (this.pollTimer) { clearInterval(this.pollTimer); this.pollTimer = null; }
  },

  bindDetail() {
    const self = this;
    this.q('#kbBack').onclick = async () => { self.stopPolling(); await self.reload(); self.render(); self.bind(); };
    this.q('#kbUploadBtn').onclick = () => this.q('#kbFileInput').click();
    this.q('#kbFileInput').onchange = async e => {
      const files = Array.from(e.target.files);
      if (!files.length) return;
      try {
        const uploaded = await Api.upload(this.currentKb.kb.id, files);
        toast(`已上传 ${uploaded.length} 个文档，开始处理`, 'success');
        e.target.value = '';
        await this.loadDocs();
      } catch (err) {
        toast(err.message, 'error');
      }
    };
    this.q('#kbDocTable').addEventListener('click', async ev => {
      const chunks = ev.target.closest('[data-chunks]');
      const retry = ev.target.closest('[data-retry]');
      const reindex = ev.target.closest('[data-reindex]');
      const del = ev.target.closest('[data-deldoc]');
      if (chunks) this.chunksModal(Number(chunks.dataset.chunks));
      if (retry) { try { await Api.post(`/documents/${retry.dataset.retry}/retry`); toast('已提交重试', 'success'); this.loadDocs(); } catch (err) { toast(err.message, 'error'); } }
      if (reindex) { try { await Api.post(`/documents/${reindex.dataset.reindex}/reindex`); toast('已提交重建', 'success'); this.loadDocs(); } catch (err) { toast(err.message, 'error'); } }
      if (del) {
        confirmBox('删除该文档及其分块与向量？').then(async ok => {
          if (!ok) return;
          try { await Api.del(`/documents/${del.dataset.deldoc}`); toast('已删除', 'success'); this.loadDocs(); }
          catch (err) { toast(err.message, 'error'); }
        });
      }
    });
  },

  async chunksModal(docId) {
    const mask = openModal(`
      <div class="modal-title">文档分块 <button class="btn btn-sm btn-ghost" onclick="closeModal()">✕</button></div>
      <div id="chunkList" style="max-height:60vh;overflow:auto"><div class="hint">加载中…</div></div>`);
    try {
      const chunks = await Api.get(`/documents/${docId}/chunks`) || [];
      if (!chunks.length) { mask.querySelector('#chunkList').innerHTML = '<div class="hint">该文档尚无分块</div>'; return; }
      mask.querySelector('#chunkList').innerHTML = chunks.map(c => `
        <div class="chunk-item">
          <div class="chunk-meta">第 ${c.position + 1} 段 · ${c.content.length} 字</div>
          <div class="chunk-content">${Util.esc(c.content.slice(0, 300))}${c.content.length > 300 ? '…' : ''}</div>
        </div>`).join('');
    } catch (e) {
      mask.querySelector('#chunkList').innerHTML = `<div class="hint" style="color:var(--danger)">${Util.esc(e.message)}</div>`;
    }
  },

  q(sel) { return this.el.querySelector(sel); }
};
