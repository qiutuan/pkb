/* ===== 对话视图 ===== */
const ChatView = {
  el: null,
  sessions: [],
  current: null,
  messages: [],
  streaming: false,
  state: { kbIds: [], topK: 8, minScore: 0.1, rerank: 'hybrid', graphRag: true },

  async init(el) {
    this.el = el;
    el.innerHTML = `
      <div class="chat-layout">
        <aside class="chat-side">
          <div class="chat-side-head">
            <button class="btn btn-primary btn-sm" id="chatNewBtn">+ 新对话</button>
          </div>
          <div class="chat-session-list" id="chatSessionList"></div>
        </aside>
        <section class="chat-main">
          <div class="chat-messages" id="chatMessages"></div>
          <div class="chat-panel">
            <div class="chat-retrieval-bar" id="chatRetrievalBar"></div>
            <div class="chat-composer">
              <textarea id="chatInput" placeholder="输入问题，Enter 发送，Shift+Enter 换行…（已选中 ${this.state.kbIds.length} 个知识库）"></textarea>
              <div class="chat-composer-actions">
                <button class="btn btn-sm" id="chatAttachBtn" title="附加图片（多模态知识库）">🖼 图片</button>
                <button class="btn btn-primary" id="chatSendBtn">发送</button>
              </div>
              <input type="file" id="chatImageInput" accept="image/*" style="display:none">
            </div>
          </div>
        </section>
      </div>`;
    await this.loadSessions();
    await this.loadDefaults();
    this.renderRetrievalBar();
    this.bind();
  },

  async loadDefaults() {
    try {
      const s = await Api.get('/settings');
      this.state.topK = Number(s.ragTopK ?? 8);
      this.state.minScore = Number(s.ragMinScore ?? 0.1);
      this.state.rerank = s.ragRerank || 'hybrid';
      this.state.graphRag = s.ragGraphEntities !== undefined ? true : true;
    } catch (e) { /* 使用默认 */ }
  },

  async loadSessions() {
    try {
      this.sessions = await Api.get('/chat/sessions') || [];
    } catch (e) {
      this.sessions = [];
      toast(e.message, 'error');
    }
    this.renderSessions();
  },

  renderSessions() {
    const box = this.q('#chatSessionList');
    if (!this.sessions.length) {
      box.innerHTML = `<div class="chat-session-empty">暂无会话，点击「新对话」开始</div>`;
      return;
    }
    box.innerHTML = this.sessions.map(s => `
      <div class="chat-session ${s.id === this.current ? 'active' : ''}" data-id="${s.id}">
        <span class="ellipsis">${Util.esc(s.title || '新对话')}</span>
        <button class="chat-session-del" data-del="${s.id}" title="删除会话">✕</button>
      </div>`).join('');
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

  renderMessages() {
    const box = this.q('#chatMessages');
    if (!this.messages.length) {
      box.innerHTML = `<div class="empty"><div class="empty-icon">💬</div><div class="empty-title">开始你的第一个问题</div><div>基于 RAG + 知识图谱回答你的知识库内容</div></div>`;
      return;
    }
    box.innerHTML = this.messages.map(m => this.messageHtml(m)).join('');
    this.scrollBottom();
  },

  messageHtml(m) {
    if (m.role === 'user') {
      return `<div class="msg user"><div class="msg-bubble">${this.renderUserContent(m.content)}</div></div>`;
    }
    let sourcesHtml = '';
    if (m.sources) {
      try {
        const arr = JSON.parse(m.sources);
        if (Array.isArray(arr) && arr.length) {
          sourcesHtml = `<div class="msg-sources"><div class="msg-sources-title">来源引用</div>` + arr.map(c => `
            <a class="msg-source" href="#/kbs" title="${Util.esc(c.content || '')}">
              <span class="badge badge-blue">[${c.index}]</span>
              ${Util.esc(c.docName)} · 第${c.position}段
              <span class="badge ${c.source === 'graph' ? 'badge-purple' : c.source === 'both' ? 'badge-amber' : 'badge-gray'}">${c.source === 'graph' ? '图谱' : c.source === 'both' ? '向量+图谱' : '向量'}</span>
            </a>`).join('') + `</div>`;
        }
      } catch (e) { /* ignore */ }
    }
    return `<div class="msg ai"><div class="msg-role">PKB</div><div class="msg-bubble md-body">${Md.render(m.content)}</div>${sourcesHtml}</div>`;
  },

  renderUserContent(content) {
    // 用户消息内可能含 [image] 占位
    if (content.startsWith('[图片]')) {
      return `<div class="msg-image-tag">🖼 ${Util.esc(content)}</div>`;
    }
    return Util.esc(content).replace(/\n/g, '<br>');
  },

  async renderRetrievalBar() {
    const bar = this.q('#chatRetrievalBar');
    let kbs = [];
    try { kbs = await Api.get('/kbs') || []; } catch (e) { /* ignore */ }
    this.kbs = kbs;
    if (!this.state.kbIds.length) this.state.kbIds = kbs.map(k => k.kb.id);
    bar.innerHTML = `
      <div class="retrieval-kbs">
        <span class="retrieval-label">知识库</span>
        ${kbs.map(k => `<label class="retrieval-kb ${this.state.kbIds.includes(k.kb.id) ? 'on' : ''}" data-id="${k.kb.id}">
          <input type="checkbox" ${this.state.kbIds.includes(k.kb.id) ? 'checked' : ''}> ${Util.esc(k.kb.name)}
        </label>`).join('')}
        ${kbs.length ? '' : '<span class="hint">尚无知识库，请先到「知识库」创建</span>'}
      </div>
      <div class="retrieval-opts">
        <label class="retrieval-opt">TopK <input type="number" class="input input-inline" id="chatTopK" value="${this.state.topK}" min="1" max="50"></label>
        <label class="retrieval-opt">阈值 <input type="number" class="input input-inline" id="chatMinScore" value="${this.state.minScore}" min="0" max="1" step="0.05"></label>
        <label class="retrieval-opt">重排
          <select class="select select-inline" id="chatRerank">
            <option value="hybrid" ${this.state.rerank === 'hybrid' ? 'selected' : ''}>混合(向量+关键词)</option>
            <option value="llm" ${this.state.rerank === 'llm' ? 'selected' : ''}>LLM 重排</option>
            <option value="none" ${this.state.rerank === 'none' ? 'selected' : ''}>不重排</option>
          </select>
        </label>
        <label class="switch-label"><span class="switch"><input type="checkbox" id="chatGraphRag" ${this.state.graphRag ? 'checked' : ''}><span class="slider"></span></span> 图谱混合检索</label>
        <button class="btn btn-sm btn-ghost" id="chatRetrieveTest">试检索</button>
      </div>
      <div class="retrieval-results" id="chatRetrievalResults" style="display:none"></div>`;
    this.q('#chatTopK').onchange = e => { this.state.topK = Math.max(1, Number(e.target.value) || 8); this.updatePlaceholder(); };
    this.q('#chatMinScore').onchange = e => { this.state.minScore = Math.max(0, Math.min(1, Number(e.target.value) || 0.1)); };
    this.q('#chatRerank').onchange = e => { this.state.rerank = e.target.value; };
    this.q('#chatGraphRag').onchange = e => { this.state.graphRag = e.target.checked; };
    this.q('#chatRetrieveTest').onclick = () => this.testRetrieve();
    this.updatePlaceholder();
  },

  updatePlaceholder() {
    const inp = this.q('#chatInput');
    if (inp) inp.placeholder = `输入问题，Enter 发送，Shift+Enter 换行…（已选中 ${this.state.kbIds.length} 个知识库）`;
  },

  bind() {
    this.q('#chatNewBtn').onclick = () => this.newSession();
    this.q('#chatSessionList').addEventListener('click', e => {
      const del = e.target.closest('[data-del]');
      const item = e.target.closest('.chat-session');
      if (del) {
        e.stopPropagation();
        confirmBox('确定删除该会话？').then(async ok => {
          if (!ok) return;
          try {
            await Api.del(`/chat/sessions/${del.dataset.del}`);
            if (this.current === Number(del.dataset.del)) { this.current = null; this.messages = []; }
            await this.loadSessions();
            this.renderMessages();
          } catch (err) { toast(err.message, 'error'); }
        });
        return;
      }
      if (item) this.openSession(Number(item.dataset.id));
    });
    this.q('#chatSendBtn').onclick = () => this.send();
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
        this.q('#chatAttachBtn').textContent = `🖼 ${f.name.slice(0, 12)}`;
      };
      reader.readAsDataURL(f);
    };
    this.q('#chatRetrievalBar').addEventListener('change', e => {
      const kb = e.target.closest('.retrieval-kb');
      if (!kb) return;
      const id = Number(kb.dataset.id);
      if (kb.querySelector('input').checked) {
        if (!this.state.kbIds.includes(id)) this.state.kbIds.push(id);
        kb.classList.add('on');
      } else {
        this.state.kbIds = this.state.kbIds.filter(x => x !== id);
        kb.classList.remove('on');
      }
      this.updatePlaceholder();
    });
    const attach = this.q('#chatAttachBtn');
    if (attach) attach.title = '附加图片（需知识库开启多模态）';
  },

  q(sel) { return this.el.querySelector(sel); },

  async testRetrieve() {
    const box = this.q('#chatRetrievalResults');
    if (!this.state.kbIds.length) { toast('请先选择知识库', 'warn'); return; }
    const query = this.q('#chatInput').value.trim();
    if (!query) { toast('请先输入检索问题', 'warn'); return; }
    box.style.display = 'block';
    box.innerHTML = '<div class="hint">检索中…</div>';
    try {
      const res = await Api.post('/retrieval', {
        kbIds: this.state.kbIds, query,
        topK: this.state.topK, minScore: this.state.minScore,
        rerank: this.state.rerank, graphRag: this.state.graphRag
      });
      if (!res.length) { box.innerHTML = '<div class="hint">未命中任何内容，可调低阈值或 TopK</div>'; return; }
      box.innerHTML = `<div class="retrieval-results-title">命中 ${res.length} 条</div>` + res.map((c, i) => `
        <div class="retrieval-hit">
          <span class="badge badge-blue">${String(c.score).slice(0, 4)}</span>
          <span class="badge ${c.source === 'graph' ? 'badge-purple' : c.source === 'both' ? 'badge-amber' : 'badge-gray'}">${c.source === 'graph' ? '图谱' : c.source === 'both' ? '向量+图谱' : '向量'}</span>
          ${Util.esc(c.docName)} · 第${c.position + 1}段
          <div class="retrieval-hit-content">${Util.esc(c.content.slice(0, 120))}${c.content.length > 120 ? '…' : ''}</div>
        </div>`).join('');
    } catch (e) {
      box.innerHTML = `<div class="hint" style="color:var(--danger)">${Util.esc(e.message)}</div>`;
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
    this.q('#chatAttachBtn').textContent = '🖼 图片';

    const aiMsg = { role: 'assistant', content: '', sources: null };
    this.messages.push(aiMsg);
    const box = this.q('#chatMessages');
    const aiDiv = Util.el(`<div class="msg ai"><div class="msg-role">PKB</div><div class="msg-bubble md-body"></div><div class="msg-streaming">生成中…</div></div>`);
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
    if (img) { body.imageBase64 = img.base64; body.imageMime = img.mime; }

    await Api.streamChat(body, {
      onDelta: text => {
        aiMsg.content += text;
        aiDiv.querySelector('.md-body').innerHTML = Md.render(aiMsg.content);
        this.scrollBottom();
      },
      onDone: evt => {
        this.streaming = false;
        this.q('#chatSendBtn').disabled = false;
        aiMsg.content = evt.content || aiMsg.content;
        aiMsg.sources = evt.sources || null;
        aiDiv.querySelector('.md-body').innerHTML = Md.render(aiMsg.content);
        const st = aiDiv.querySelector('.msg-streaming');
        if (st) st.remove();
        if (aiMsg.sources && aiMsg.sources.length) {
          aiDiv.insertAdjacentHTML('beforeend', `<div class="msg-sources"><div class="msg-sources-title">来源引用</div>` +
            aiMsg.sources.map(c => `
              <a class="msg-source" href="#/kbs" title="${Util.esc(c.content || '')}">
                <span class="badge badge-blue">[${c.index}]</span> ${Util.esc(c.docName)} · 第${c.position}段
                <span class="badge ${c.source === 'graph' ? 'badge-purple' : c.source === 'both' ? 'badge-amber' : 'badge-gray'}">${c.source === 'graph' ? '图谱' : c.source === 'both' ? '向量+图谱' : '向量'}</span>
              </a>`).join('') + `</div>`);
        }
        this.scrollBottom();
        // 同步会话列表标题
        this.loadSessions();
      },
      onError: msg => {
        this.streaming = false;
        this.q('#chatSendBtn').disabled = false;
        aiMsg.content = '**生成失败**：' + msg;
        aiDiv.querySelector('.md-body').innerHTML = Md.render(aiMsg.content);
        const st = aiDiv.querySelector('.msg-streaming');
        if (st) st.remove();
        this.scrollBottom();
      }
    });
  },

  scrollBottom() {
    const box = this.q('#chatMessages');
    if (box) box.scrollTop = box.scrollHeight;
  }
};
