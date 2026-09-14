/* ===== 对话视图 ===== */
const ChatView = {
  el: null,
  sessions: [],
  current: null,
  messages: [],
  streaming: false,
  kbs: [],
  providers: [],
  pendingImage: null,
  popoverOpen: false,
  state: { kbIds: [], topK: 8, minScore: 0.1, rerank: 'hybrid', graphRag: true, modelProviderId: null },

  async init(el) {
    this.el = el;
    el.innerHTML = `
      <div class="chat-layout">
        <aside class="chat-sessions card">
          <div class="chat-sessions-head">
            <button class="btn btn-primary" id="chatNewBtn">+ 新对话</button>
          </div>
          <div class="chat-session-list" id="chatSessionList"></div>
        </aside>
        <section class="chat-main card">
          <div class="chat-messages" id="chatMessages"></div>
          <div class="chat-input-wrap">
            <div class="chat-toolbar">
              <select class="select chat-model-select" id="chatModelSelect" title="选择本会话使用的聊天模型"></select>
              <div class="chat-kb-tags" id="chatKbTags">
                <span class="hint" id="chatKbHint">尚未选择知识库</span>
              </div>
              <div class="chat-actions">
                <div class="popover-wrap">
                  <button class="icon-btn ${this.popoverOpen ? 'active' : ''}" id="chatGearBtn" title="检索设置">⚙</button>
                  <div class="popover" id="chatPopover" style="display:none">
                    <div class="popover-title">检索设置</div>
                    <div class="form-item">
                      <label class="label">TopK（1–50）</label>
                      <input class="input" type="number" id="chatTopK" value="${this.state.topK}" min="1" max="50">
                    </div>
                    <div class="form-item">
                      <label class="label">相似度阈值（0–1）</label>
                      <input class="input" type="number" id="chatMinScore" value="${this.state.minScore}" min="0" max="1" step="0.05">
                    </div>
                    <div class="form-item">
                      <label class="label">重排策略</label>
                      <select class="select" id="chatRerank">
                        <option value="hybrid" ${this.state.rerank === 'hybrid' ? 'selected' : ''}>混合（向量+关键词）</option>
                        <option value="llm" ${this.state.rerank === 'llm' ? 'selected' : ''}>LLM 重排</option>
                        <option value="none" ${this.state.rerank === 'none' ? 'selected' : ''}>不重排</option>
                      </select>
                    </div>
                    <div class="form-item">
                      <label class="switch-label"><span class="switch"><input type="checkbox" id="chatGraphRag" ${this.state.graphRag ? 'checked' : ''}><span class="slider"></span></span> 图谱混合检索（GraphRAG）</label>
                    </div>
                    <div class="popover-foot">
                      <button class="btn" id="chatRetrieveTest">试检索</button>
                      <span class="hint">即时生效</span>
                    </div>
                  </div>
                </div>
                <button class="icon-btn" id="chatAttachBtn" title="附加图片" style="display:none">🖼</button>
              </div>
            </div>
            <div id="chatRetrievalPanel" style="display:none"></div>
            <div class="chat-input-row">
              <textarea class="chat-input" id="chatInput" placeholder="输入问题，Enter 发送，Shift+Enter 换行…"></textarea>
              <button class="btn btn-primary chat-send" id="chatSendBtn">发送</button>
            </div>
            <input type="file" id="chatImageInput" accept="image/*" style="display:none">
          </div>
        </section>
      </div>`;
    await Promise.all([this.loadSessions(), this.loadDefaults(), this.loadProviders(), this.loadKbs()]);
    this.renderSessions();
    this.renderKbTags();
    this.renderModelSelect();
    this.bind();
  },

  async loadDefaults() {
    try {
      const s = await Api.get('/settings');
      this.state.topK = Number(s.ragTopK ?? 8);
      this.state.minScore = Number(s.ragMinScore ?? 0.1);
      this.state.rerank = s.ragRerank || 'hybrid';
    } catch (e) { /* 使用默认 */ }
  },

  async loadSessions() {
    try {
      this.sessions = await Api.get('/chat/sessions') || [];
    } catch (e) {
      this.sessions = [];
      toast(e.message, 'error');
    }
  },

  async loadProviders() {
    try {
      const list = await Api.get('/providers') || [];
      // 仅已启用的聊天模型 Provider
      this.providers = list.filter(p => p.enabled && p.chatModel);
      this.visionAvailable = list.some(p => p.enabled && p.chatModel && (p.capabilities || '').includes('vision'));
    } catch (e) { this.providers = []; this.visionAvailable = false; }
  },

  async loadKbs() {
    try {
      this.kbs = await Api.get('/kbs') || [];
      if (!this.state.kbIds.length) this.state.kbIds = this.kbs.map(k => k.kb.id);
    } catch (e) { this.kbs = []; }
  },

  renderSessions() {
    const box = this.q('#chatSessionList');
    if (!box) return;
    if (!this.sessions.length) {
      box.innerHTML = `<div class="chat-session-empty">暂无会话，点击「新对话」开始</div>`;
      return;
    }
    box.innerHTML = this.sessions.map(s => `
      <div class="chat-session-item ${s.id === this.current ? 'active' : ''}" data-id="${s.id}">
        <span class="s-title">${Util.esc(s.title || '新对话')}</span>
        <span class="s-ops">
          <button class="rename" data-rename="${s.id}" title="重命名">✎</button>
          <button class="export" data-export="${s.id}" title="导出 Markdown">⤓</button>
          <button class="danger" data-del="${s.id}" title="删除会话">✕</button>
        </span>
      </div>`).join('');
  },

  renderKbTags() {
    const box = this.q('#chatKbTags');
    if (!box) return;
    const selected = this.kbs.filter(k => this.state.kbIds.includes(k.kb.id));
    box.innerHTML = selected.map(k => `
      <span class="kb-tag">${Util.esc(k.kb.name)}<button data-untag="${k.kb.id}" title="移除">✕</button></span>`).join('')
      + (selected.length ? '' : '<span class="hint">尚未选择知识库，点击下方添加</span>');
    this.updatePlaceholder();
  },

  renderModelSelect() {
    const sel = this.q('#chatModelSelect');
    if (!sel) return;
    const cur = this.providers.find(p => p.id === this.state.modelProviderId);
    sel.innerHTML = `<option value="">默认模型${cur ? '' : '（跟随系统默认）'}</option>`
      + this.providers.map(p => `<option value="${p.id}" ${p.id === this.state.modelProviderId ? 'selected' : ''}>${Util.esc(p.name)}（${Util.esc(p.chatModel)}）</option>`).join('');
    // 无可用聊天模型时给出提示
    if (!this.providers.length) {
      sel.innerHTML = `<option value="">未配置聊天模型</option>`;
      sel.disabled = true;
      sel.title = '请到「模型管理」添加并启用聊天模型';
    }
    // 图片按钮仅在多模态能力可用时出现
    const attach = this.q('#chatAttachBtn');
    if (attach) attach.style.display = this.visionAvailable ? '' : 'none';
  },

  async openSession(id) {
    this.current = id;
    this.renderSessions();
    try {
      this.messages = await Api.get(`/chat/sessions/${id}/messages`) || [];
    } catch (e) {
      this.messages = [];
    }
    this.renderMessages();
  },

  async newSession() {
    try {
      const s = await Api.post('/chat/sessions', { title: '' });
      this.sessions.unshift(s);
      this.current = s.id;
      this.messages = [];
      this.renderSessions();
      this.renderMessages();
    } catch (e) {
      toast(e.message, 'error');
    }
  },

  /** 导出会话为 Markdown（含来源引用） */
  async exportSession(id) {
    try {
      const resp = await fetch(`/api/chat/sessions/${id}/export`, { headers: Api.jsonHeaders() });
      if (!resp.ok) throw new Error('导出失败（' + resp.status + '）');
      const text = await resp.text();
      const s = this.sessions.find(x => x.id === id) || {};
      const name = (s.title || '会话').replace(/[\\/:*?"<>|\s]+/g, '_');
      const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = name + '.md';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      toast('已导出 Markdown', 'success');
    } catch (e) {
      toast(e.message, 'error');
    }
  },

  async renameSession(id) {
    const s = this.sessions.find(x => x.id === id);
    const mask = openModal(`
      <div class="modal-title">重命名会话</div>
      <div class="form-item"><input class="input" id="renameInput" value="${Util.esc(s?.title || '')}" placeholder="会话名称"></div>
      <div class="modal-foot">
        <button class="btn" onclick="closeModal()">取消</button>
        <button class="btn btn-primary" id="renameSave">保存</button>
      </div>`);
    const input = mask.querySelector('#renameInput');
    input.focus();
    input.select();
    mask.querySelector('#renameSave').onclick = async () => {
      const title = input.value.trim() || '新对话';
      try {
        await Api.put(`/chat/sessions/${id}`, { title });
        closeModal();
        await this.loadSessions();
        this.renderSessions();
      } catch (e) { toast(e.message, 'error'); }
    };
  },

  renderMessages() {
    const box = this.q('#chatMessages');
    if (!box) return;
    if (!this.messages.length) {
      box.innerHTML = `<div class="empty">
        <svg width="88" height="72" viewBox="0 0 88 72" fill="none"><rect x="6" y="8" width="76" height="50" rx="10" fill="#FFF7ED" stroke="#F97316" stroke-width="2"/><path d="M20 52L36 34l10 10 12-14 12 12" stroke="#F97316" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/><circle cx="24" cy="22" r="4" fill="#F97316"/><circle cx="40" cy="18" r="4" fill="#FB923C"/><circle cx="60" cy="22" r="4" fill="#FDBA74"/></svg>
        <div class="empty-title">开始你的知识探索</div>
        <div class="empty-sub">基于 RAG + 知识图谱，让 AI 基于你自己的知识库回答问题</div>
        <div class="guide-cards">
          <div class="guide-card" data-goto="models">
            <div class="guide-num">1</div>
            <div class="guide-title">配置模型</div>
            <div class="guide-sub">添加聊天模型（OpenAI 兼容 / Ollama）并设为默认</div>
          </div>
          <div class="guide-card" data-goto="kbs">
            <div class="guide-num">2</div>
            <div class="guide-title">建库传文档</div>
            <div class="guide-sub">创建知识库并上传文档，自动解析分块入库</div>
          </div>
          <div class="guide-card" data-goto="chat">
            <div class="guide-num">3</div>
            <div class="guide-title">开始提问</div>
            <div class="guide-sub">勾选知识库，输入问题即可获得带引用的回答</div>
          </div>
        </div>
      </div>`;
      this.q('#chatMessages').querySelectorAll('[data-goto]').forEach(c => {
        c.onclick = () => location.hash = '#/' + c.dataset.goto;
      });
      return;
    }
    box.innerHTML = this.messages.map(m => this.messageHtml(m)).join('');
    this.scrollBottom();
  },

  messageHtml(m) {
    if (m.role === 'user') {
      return `<div class="msg user"><div class="msg-avatar">我</div><div class="msg-bubble">${this.renderUserContent(m.content)}</div></div>`;
    }
    let sourcesHtml = '';
    if (m.sources) {
      try {
        const arr = JSON.parse(m.sources);
        if (Array.isArray(arr) && arr.length) {
          sourcesHtml = `<div class="msg-sources">` + arr.map(c => `
            <span class="source-chip" title="${Util.esc(c.content || '')}">[${c.index}] ${Util.esc(c.docName)} · 第${c.position}段 ${c.source === 'graph' ? '· 图谱' : c.source === 'both' ? '· 向量+图谱' : ''}</span>`).join('') + `</div>`;
        }
      } catch (e) { /* ignore */ }
    }
    return `<div class="msg assistant"><div class="msg-avatar">PKB</div><div class="msg-bubble md-body">${Md.render(m.content || '')}</div>${sourcesHtml}</div>`;
  },

  renderUserContent(content) {
    if (content.startsWith('[图片]')) {
      return `<span>🖼 ${Util.esc(content)}</span>`;
    }
    return Util.esc(content).replace(/\n/g, '<br>');
  },

  updatePlaceholder() {
    const inp = this.q('#chatInput');
    if (inp) inp.placeholder = `输入问题，Enter 发送，Shift+Enter 换行…（已选中 ${this.state.kbIds.length} 个知识库）`;
  },

  togglePopover(force) {
    const pv = this.q('#chatPopover');
    const btn = this.q('#chatGearBtn');
    if (!pv) return;
    const open = force !== undefined ? force : !this.popoverOpen;
    this.popoverOpen = open;
    pv.style.display = open ? '' : 'none';
    if (btn) btn.classList.toggle('active', open);
    if (open) {
      const tk = this.q('#chatTopK'); if (tk) tk.value = this.state.topK;
      const ms = this.q('#chatMinScore'); if (ms) ms.value = this.state.minScore;
      const rr = this.q('#chatRerank'); if (rr) rr.value = this.state.rerank;
      const gr = this.q('#chatGraphRag'); if (gr) gr.checked = this.state.graphRag;
    }
  },

  bind() {
    this.q('#chatNewBtn').onclick = () => this.newSession();
    this.q('#chatSendBtn').onclick = () => this.send();
    this.q('#chatGearBtn').onclick = (e) => { e.stopPropagation(); this.togglePopover(); };
    document.addEventListener('click', e => {
      if (this.popoverOpen && !e.target.closest('.popover-wrap')) this.togglePopover(false);
    });
    this.q('#chatPopover').addEventListener('click', e => e.stopPropagation());
    this.q('#chatTopK').onchange = e => { this.state.topK = Math.max(1, Math.min(50, Number(e.target.value) || 8)); };
    this.q('#chatMinScore').onchange = e => { this.state.minScore = Math.max(0, Math.min(1, Number(e.target.value) || 0.1)); };
    this.q('#chatRerank').onchange = e => { this.state.rerank = e.target.value; };
    this.q('#chatGraphRag').onchange = e => { this.state.graphRag = e.target.checked; };
    this.q('#chatRetrieveTest').onclick = () => this.testRetrieve();

    // 会话列表：打开 / 重命名 / 导出 / 删除
    this.q('#chatSessionList').addEventListener('click', e => {
      const rename = e.target.closest('[data-rename]');
      const del = e.target.closest('[data-del]');
      const exp = e.target.closest('[data-export]');
      const item = e.target.closest('.chat-session-item');
      if (del) {
        e.stopPropagation();
        confirmBox('确定删除该会话？删除后不可恢复。').then(async ok => {
          if (!ok) return;
          try {
            await Api.del(`/chat/sessions/${del.dataset.del}`);
            if (this.current === Number(del.dataset.del)) { this.current = null; this.messages = []; }
            await this.loadSessions();
            this.renderSessions();
            this.renderMessages();
          } catch (err) { toast(err.message, 'error'); }
        });
        return;
      }
      if (exp) {
        e.stopPropagation();
        this.exportSession(Number(exp.dataset.export));
        return;
      }
      if (rename) {
        e.stopPropagation();
        this.renameSession(Number(rename.dataset.rename));
        return;
      }
      if (item) this.openSession(Number(item.dataset.id));
    });

    // 知识库 Tag 移除
    this.q('#chatKbTags').addEventListener('click', e => {
      const untag = e.target.closest('[data-untag]');
      if (!untag) return;
      const id = Number(untag.dataset.untag);
      this.state.kbIds = this.state.kbIds.filter(x => x !== id);
      this.renderKbTags();
    });
    // 知识库 Tag 区域点击弹出知识库多选
    this.q('#chatKbTags').addEventListener('click', e => {
      const untag = e.target.closest('[data-untag]');
      if (!untag) this.kbPicker();
    });

    // 模型选择：按会话记忆
    this.q('#chatModelSelect').onchange = e => {
      const v = e.target.value;
      this.state.modelProviderId = v ? Number(v) : null;
      if (this.current) this.saveSessionModel(this.current, this.state.modelProviderId);
    };

    const input = this.q('#chatInput');
    input.addEventListener('keydown', e => {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this.send(); }
    });

    this.q('#chatAttachBtn').onclick = () => this.q('#chatImageInput').click();
    this.q('#chatImageInput').onchange = e => {
      const f = e.target.files[0];
      if (!f) return;
      const reader = new FileReader();
      reader.onload = () => {
        const base64 = String(reader.result).split(',')[1];
        this.pendingImage = { base64, mime: f.type || 'image/png', name: f.name };
        toast(`已附加图片：${f.name}`, 'success');
        this.q('#chatAttachBtn').style.color = '#EA580C';
        this.q('#chatAttachBtn').style.borderColor = '#F97316';
      };
      reader.readAsDataURL(f);
    };
  },

  /** 知识库多选浮层（橙色 Tag 展示 + 可单选移除） */
  kbPicker() {
    const tags = this.q('#chatKbTags');
    const rect = tags.getBoundingClientRect();
    const mask = document.createElement('div');
    mask.style.cssText = `position:fixed;inset:0;z-index:40`;
    mask.innerHTML = `
      <div style="position:absolute;left:${Math.min(rect.left, window.innerWidth - 260)}px;top:${rect.bottom + 6}px;width:260px;max-height:300px;overflow:auto;background:#fff;border:1px solid var(--border);border-radius:12px;box-shadow:var(--shadow-lg);padding:10px">
        <div style="font-size:12px;font-weight:600;color:var(--text-1);padding:4px 8px 8px">选择知识库（可多选）</div>
        ${this.kbs.length ? this.kbs.map(k => `
          <label style="display:flex;align-items:center;gap:8px;padding:6px 8px;border-radius:6px;cursor:pointer;font-size:13px;color:var(--text-2)" data-id="${k.kb.id}">
            <input type="checkbox" style="accent-color:#F97316" ${this.state.kbIds.includes(k.kb.id) ? 'checked' : ''}> ${Util.esc(k.kb.name)}
          </label>`).join('')
        : '<div style="padding:8px;font-size:12px;color:var(--text-3)">尚无知识库，请先创建</div>'}
      </div>`;
    mask.addEventListener('click', e => {
      const label = e.target.closest('label[data-id]');
      if (label) {
        const id = Number(label.dataset.id);
        const cb = label.querySelector('input');
        if (cb.checked) {
          if (!this.state.kbIds.includes(id)) this.state.kbIds.push(id);
        } else {
          this.state.kbIds = this.state.kbIds.filter(x => x !== id);
        }
        this.renderKbTags();
        e.stopPropagation();
      } else if (e.target === mask) {
        mask.remove();
      }
    });
    document.body.appendChild(mask);
    const close = ev => {
      if (ev.target.closest('#chatKbTags')) return;
      mask.remove();
      document.removeEventListener('click', close);
    };
    setTimeout(() => document.addEventListener('click', close), 0);
  },

  async saveSessionModel(sessionId, providerId) {
    try {
      await Api.post(`/chat/sessions/${sessionId}/model`, { providerId });
    } catch (e) { /* 静默：会话级记忆尽力而为 */ }
  },

  q(sel) { return this.el.querySelector(sel); },

  async testRetrieve() {
    const panel = this.q('#chatRetrievalPanel');
    if (!this.state.kbIds.length) { toast('请先选择知识库', 'warn'); return; }
    const query = this.q('#chatInput').value.trim();
    if (!query) { toast('请先输入检索问题', 'warn'); return; }
    panel.style.display = 'block';
    panel.innerHTML = '<div class="hint" style="padding:4px 0">检索中…</div>';
    try {
      const res = await Api.post('/retrieval', {
        kbIds: this.state.kbIds, query,
        topK: this.state.topK, minScore: this.state.minScore,
        rerank: this.state.rerank, graphRag: this.state.graphRag
      });
      if (!res.length) {
        panel.innerHTML = `<div class="retrieval-panel"><div class="retrieval-panel-title">检索结果</div><div class="hint">未命中任何内容，可调低阈值或增大 TopK</div></div>`;
        return;
      }
      panel.innerHTML = `<div class="retrieval-panel"><div class="retrieval-panel-title">命中 ${res.length} 条（文档 · 相似度）</div>` + res.map(c => `
        <div class="retrieval-item"><b>${Util.esc(c.docName)}</b> · 第${c.position + 1}段 · ${String(c.score).slice(0, 5)}${c.source === 'graph' ? ' · 图谱' : c.source === 'both' ? ' · 向量+图谱' : ''}<br>${Util.esc((c.content || '').slice(0, 110))}${c.content && c.content.length > 110 ? '…' : ''}</div>`).join('') + `</div>`;
    } catch (e) {
      panel.innerHTML = `<div class="retrieval-panel"><div class="retrieval-panel-title">检索失败</div><div class="hint" style="color:var(--danger)">${Util.esc(e.message)}</div></div>`;
    }
  },

  async send() {
    if (this.streaming) return;
    const input = this.q('#chatInput');
    const query = input.value.trim();
    if (!query) { toast('请输入问题', 'warn'); return; }
    if (!this.state.kbIds.length) { toast('请至少选择一个知识库', 'warn'); return; }
    if (!this.current) {
      try {
        const s = await Api.post('/chat/sessions', { title: query.slice(0, 30) });
        this.sessions.unshift(s);
        this.current = s.id;
        this.renderSessions();
        if (this.state.modelProviderId) this.saveSessionModel(this.current, this.state.modelProviderId);
      } catch (e) { toast(e.message, 'error'); return; }
    }
    // 用户消息上屏
    let userContent = query;
    const img = this.pendingImage;
    if (img) userContent = `[图片] ${img.name}\n` + query;
    this.messages.push({ role: 'user', content: userContent });
    this.renderMessages();
    input.value = '';
    this.pendingImage = null;
    const attach = this.q('#chatAttachBtn');
    if (attach) { attach.style.color = ''; attach.style.borderColor = ''; }
    this.q('#chatRetrievalPanel').style.display = 'none';

    const aiMsg = { role: 'assistant', content: '', sources: null };
    this.messages.push(aiMsg);
    const box = this.q('#chatMessages');
    const aiDiv = Util.el(`<div class="msg assistant"><div class="msg-avatar">PKB</div><div class="msg-bubble md-body typing"></div></div>`);
    box.appendChild(aiDiv);
    this.streaming = true;
    this.q('#chatSendBtn').disabled = true;
    this.scrollBottom();

    const body = {
      sessionId: this.current, query,
      kbIds: this.state.kbIds,
      topK: this.state.topK, minScore: this.state.minScore,
      rerank: this.state.rerank, graphRag: this.state.graphRag
    };
    if (this.state.modelProviderId) body.modelProviderId = this.state.modelProviderId;
    if (img) { body.imageBase64 = img.base64; body.imageMime = img.mime; }

    await Api.streamChat(body, {
      onDelta: text => {
        aiMsg.content += text;
        const md = aiDiv.querySelector('.md-body');
        md.innerHTML = Md.render(aiMsg.content);
        this.scrollBottom();
      },
      onDone: evt => {
        this.streaming = false;
        this.q('#chatSendBtn').disabled = false;
        aiMsg.content = evt.content || aiMsg.content;
        aiMsg.sources = evt.sources || null;
        const md = aiDiv.querySelector('.md-body');
        md.innerHTML = Md.render(aiMsg.content);
        md.classList.remove('typing');
        if (aiMsg.sources && aiMsg.sources.length) {
          aiDiv.insertAdjacentHTML('beforeend', `<div class="msg-sources">` +
            aiMsg.sources.map(c => `
              <span class="source-chip" title="${Util.esc(c.content || '')}">[${c.index}] ${Util.esc(c.docName)} · 第${c.position}段 ${c.source === 'graph' ? '· 图谱' : c.source === 'both' ? '· 向量+图谱' : ''}</span>`).join('') + `</div>`);
        }
        this.scrollBottom();
        this.loadSessions().then(() => this.renderSessions());
      },
      onError: msg => {
        this.streaming = false;
        this.q('#chatSendBtn').disabled = false;
        aiMsg.content = '**生成失败**：' + msg;
        const md = aiDiv.querySelector('.md-body');
        md.innerHTML = Md.render(aiMsg.content);
        md.classList.remove('typing');
        this.scrollBottom();
      }
    });
  },

  scrollBottom() {
    const box = this.q('#chatMessages');
    if (box) box.scrollTop = box.scrollHeight;
  }
};
