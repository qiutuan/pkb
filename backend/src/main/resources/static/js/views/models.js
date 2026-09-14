/* ===== 模型管理视图 ===== */
const ModelsView = {
  el: null,
  providers: [],
  defaults: { chat: null, embedding: null },

  async init(el) {
    this.el = el;
    await this.load();
    this.render();
    this.bind();
  },

  async load() {
    try {
      const [list, defaults] = await Promise.all([Api.get('/providers'), Api.get('/providers/defaults')]);
      this.providers = list || [];
      this.defaults = defaults || {};
    } catch (e) {
      toast(e.message, 'error');
    }
  },

  render() {
    this.el.innerHTML = `
      <div class="page">
        <div class="kb-head" style="display:flex;justify-content:space-between;align-items:flex-start">
          <div>
            <div class="page-title">模型管理</div>
            <div class="page-sub">聊天模型与 Embedding 模型分离配置 · API Key 加密存储 · 默认聊天 / 默认向量 各一个</div>
          </div>
          <button class="btn btn-primary" id="modelNew">+ 添加模型</button>
        </div>
        <div class="card" style="overflow:auto">
          <table class="table" id="modelTable">
            <thead><tr><th>名称</th><th>类型</th><th>聊天模型</th><th>向量模型</th><th>默认</th><th>状态</th><th>操作</th></tr></thead>
            <tbody id="modelBody"></tbody>
          </table>
        </div>
        <div class="model-tips card" style="margin-top:16px;padding:16px">
          <div style="font-weight:600;margin-bottom:8px">快速开始</div>
          <ol style="padding-left:20px;color:var(--text-2);font-size:13px;line-height:2">
            <li>内置已默认启用一个 <b>本地离线向量模型</b>（512 维），无需任何 Key 即可完成入库与检索。</li>
            <li>添加 <b>OpenAI 兼容</b> 模型：填任意 base_url（如 https://api.deepseek.com/v1）+ api_key + 模型名，即可接入 DeepSeek / 通义 / 智谱 / OpenAI 等。</li>
            <li>添加 <b>Ollama</b>：填地址（如 http://localhost:11434）后点「拉取模型」自动获取本地模型列表。</li>
            <li>将某 provider 设为「默认聊天」后，对话/图谱抽取/Rerank 即使用它；设为「默认向量」用于文档向量化。</li>
          </ol>
        </div>
      </div>`;
  },

  renderBody() {
    const body = this.el.querySelector('#modelBody');
    if (!this.providers.length) {
      body.innerHTML = `<tr><td colspan="7"><div class="empty"><div class="empty-icon">🤖</div><div class="empty-title">暂无模型</div><div>点击右上角添加模型</div></div></td></tr>`;
      return;
    }
    body.innerHTML = this.providers.map(p => `<tr>
      <td style="font-weight:600">${Util.esc(p.name)}${p.providerType === 'local' ? ' <span class="badge badge-teal">离线</span>' : ''}</td>
      <td><span class="badge badge-gray">${Util.providerTypeName(p.providerType)}</span></td>
      <td>${Util.esc(p.chatModel || '-')}</td>
      <td>${Util.esc(p.embeddingModel || '-')}</td>
      <td>${p.defaultChat ? '<span class="badge badge-green">聊天</span>' : ''}${p.defaultEmbedding ? '<span class="badge badge-blue">向量</span>' : ''}</td>
      <td>${p.enabled ? '<span class="badge badge-green">启用</span>' : '<span class="badge badge-red">停用</span>'}</td>
      <td><div class="actions">
        <button class="btn btn-sm" data-test="${p.id}">测试</button>
        <button class="btn btn-sm" data-edit="${p.id}">编辑</button>
        <button class="btn btn-sm btn-danger" data-del="${p.id}">删除</button>
      </div></td>
    </tr>`).join('');
  },

  bind() {
    this.el.querySelector('#modelNew').onclick = () => this.formModal();
    this.el.querySelector('#modelTable').addEventListener('click', e => {
      const t = e.target.closest('[data-test]');
      const ed = e.target.closest('[data-edit]');
      const del = e.target.closest('[data-del]');
      if (t) this.test(Number(t.dataset.test));
      if (ed) {
        const p = this.providers.find(x => x.id === Number(ed.dataset.edit));
        this.formModal(p);
      }
      if (del) {
        const p = this.providers.find(x => x.id === Number(del.dataset.del));
        confirmBox(`删除模型「${p.name}」？`).then(async ok => {
          if (!ok) return;
          try { await Api.del(`/providers/${p.id}`); toast('已删除', 'success'); await this.load(); this.renderBody(); }
          catch (err) { toast(err.message, 'error'); }
        });
      }
    });
    this.renderBody();
  },

  async test(id) {
    const p = this.providers.find(x => x.id === id);
    toast(`正在测试「${p.name}」…`, 'info');
    try {
      const r = await Api.post(`/providers/${id}/test`);
      if (r.ok) toast(`连接成功：${r.detail || r.message}`, 'success');
      else toast(`连接失败：${r.detail || r.message}`, 'error');
    } catch (e) {
      toast(e.message, 'error');
    }
  },

  formModal(p) {
    const isEdit = !!p;
    const isLocal = p?.providerType === 'local';
    const mask = openModal(`
      <div class="modal-title">${isEdit ? '编辑模型' : '添加模型'}</div>
      <div class="form-item">
        <label class="label">名称 *</label>
        <input class="input" id="mpName" value="${Util.esc(p?.name || '')}" placeholder="如：DeepSeek / Ollama 本机">
      </div>
      <div class="form-item">
        <label class="label">类型</label>
        <select class="select" id="mpType" ${isEdit ? 'disabled' : ''}>
          <option value="openai_compatible" ${p?.providerType === 'openai_compatible' ? 'selected' : ''}>OpenAI 兼容（DeepSeek/通义/智谱/OpenAI…）</option>
          <option value="ollama" ${p?.providerType === 'ollama' ? 'selected' : ''}>Ollama 本地</option>
          <option value="anthropic" ${p?.providerType === 'anthropic' ? 'selected' : ''}>Anthropic Claude</option>
          <option value="gemini" ${p?.providerType === 'gemini' ? 'selected' : ''}>Google Gemini</option>
          <option value="local" ${p?.providerType === 'local' ? 'selected' : ''}>内置本地（离线，仅向量）</option>
        </select>
      </div>
      <div class="form-item" id="mpBaseRow" ${isLocal ? 'style="display:none"' : ''}>
        <label class="label" id="mpBaseLabel">Base URL</label>
        <div class="row">
          <input class="input" id="mpBase" value="${Util.esc(p?.baseUrl || '')}" placeholder="${p?.providerType === 'ollama' ? 'http://localhost:11434' : 'https://api.deepseek.com/v1'}">
          <button class="btn" id="mpOllamaFetch" style="${p?.providerType === 'ollama' ? '' : 'display:none'}">拉取模型</button>
        </div>
        <div class="hint" id="mpOllamaList"></div>
      </div>
      <div class="form-item" id="mpKeyRow" ${isLocal ? 'style="display:none"' : ''}>
        <label class="label">API Key ${isEdit && p.hasApiKey ? '（已设置，留空则不修改）' : ''}</label>
        <input class="input" type="password" id="mpKey" placeholder="${p?.providerType === 'ollama' ? 'Ollama 无需 Key' : 'sk-…'}" autocomplete="off">
      </div>
      <div class="form-grid">
        <div class="form-item" id="mpChatRow" ${isLocal ? 'style="display:none"' : ''}>
          <label class="label">聊天模型名</label>
          <input class="input" id="mpChat" value="${Util.esc(p?.chatModel || '')}" placeholder="${p?.providerType === 'ollama' ? 'qwen2.5:7b' : 'deepseek-chat'}">
        </div>
        <div class="form-item" id="mpEmbedRow" ${isLocal ? 'style="display:none"' : ''}>
          <label class="label">Embedding 模型名</label>
          <input class="input" id="mpEmbed" value="${Util.esc(p?.embeddingModel || '')}" placeholder="${p?.providerType === 'ollama' ? 'bge-m3' : 'bge-m3'}">
        </div>
      </div>
      <div class="form-grid">
        <div class="form-item" id="mpTempRow" ${isLocal ? 'style="display:none"' : ''}>
          <label class="label">温度</label>
          <input class="input" type="number" id="mpTemp" value="${p?.temperature ?? 0.7}" step="0.1" min="0" max="2">
        </div>
        <div class="form-item" id="mpMaxRow" ${isLocal ? 'style="display:none"' : ''}>
          <label class="label">最大 Token</label>
          <input class="input" type="number" id="mpMax" value="${p?.maxTokens ?? 2048}" min="1">
        </div>
      </div>
      <div class="form-item" style="display:flex;gap:28px">
        <label class="switch-label"><span class="switch"><input type="checkbox" id="mpDefaultChat" ${p?.defaultChat ? 'checked' : ''}><span class="slider"></span></span> 默认聊天</label>
        <label class="switch-label"><span class="switch"><input type="checkbox" id="mpDefaultEmbed" ${p?.defaultEmbedding ? 'checked' : ''}><span class="slider"></span></span> 默认向量</label>
        <label class="switch-label"><span class="switch"><input type="checkbox" id="mpEnabled" ${p?.enabled === false ? '' : 'checked'}><span class="slider"></span></span> 启用</label>
      </div>
      <div class="hint">本地类型为离线向量模型，维度 512，开箱即用；设为默认向量后新知识库无需配置即可入库。</div>
      <div class="modal-foot">
        <button class="btn" onclick="closeModal()">取消</button>
        <button class="btn btn-primary" id="mpSave">保存</button>
      </div>`);

    const typeSel = mask.querySelector('#mpType');
    const applyType = () => {
      const t = typeSel.value;
      const local = t === 'local';
      mask.querySelector('#mpBaseRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpKeyRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpChatRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpEmbedRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpTempRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpMaxRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpOllamaFetch').style.display = t === 'ollama' ? '' : 'none';
      mask.querySelector('#mpBaseLabel').textContent = t === 'ollama' ? 'Ollama 地址' : 'Base URL';
      mask.querySelector('#mpBase').placeholder = t === 'ollama' ? 'http://localhost:11434' : 'https://api.deepseek.com/v1';
    };
    typeSel.onchange = applyType;
    applyType();

    mask.querySelector('#mpOllamaFetch').onclick = async () => {
      const base = mask.querySelector('#mpBase').value.trim();
      if (!base) { toast('请先填写 Ollama 地址', 'warn'); return; }
      try {
        const names = await Api.post('/providers/ollama/models', { baseUrl: base });
        const listEl = mask.querySelector('#mpOllamaList');
        listEl.innerHTML = '本地模型：' + (names.map(n => `<code>${Util.esc(n)}</code>`).join(' ') || '（无）');
        const chat = mask.querySelector('#mpChat');
        if (names.length && !chat.value) chat.value = names[0];
        const embed = mask.querySelector('#mpEmbed');
        const bge = names.find(n => /bge|m3|embed/i.test(n));
        if (bge) embed.value = bge;
      } catch (e) { toast(e.message, 'error'); }
    };

    mask.querySelector('#mpSave').onclick = async () => {
      const name = mask.querySelector('#mpName').value.trim();
      if (!name) { toast('请填写名称', 'warn'); return; }
      const body = {
        name,
        providerType: typeSel.value,
        baseUrl: mask.querySelector('#mpBase').value.trim(),
        chatModel: mask.querySelector('#mpChat').value.trim(),
        embeddingModel: mask.querySelector('#mpEmbed').value.trim(),
        temperature: Number(mask.querySelector('#mpTemp').value) || 0.7,
        maxTokens: Number(mask.querySelector('#mpMax').value) || 2048,
        defaultChat: mask.querySelector('#mpDefaultChat').checked,
        defaultEmbedding: mask.querySelector('#mpDefaultEmbed').checked,
        enabled: mask.querySelector('#mpEnabled').checked
      };
      const key = mask.querySelector('#mpKey').value;
      if (key) body.apiKey = key;
      if (typeSel.value === 'local') {
        body.baseUrl = ''; body.chatModel = ''; body.embeddingModel = 'local';
      }
      try {
        if (isEdit) await Api.put(`/providers/${p.id}`, body);
        else await Api.post('/providers', body);
        closeModal(); toast('已保存', 'success');
        await this.load(); this.renderBody();
      } catch (e) { toast(e.message, 'error'); }
    };
  }
};
