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
            <div class="page-sub">聊天模型与 Embedding 模型分离配置 · API Key 加密存储 · 新增模型默认停用</div>
          </div>
          <button class="btn btn-primary" id="modelNew">+ 添加模型</button>
        </div>
        <div class="card" style="overflow:auto">
          <table class="table" id="modelTable">
            <thead><tr><th>名称</th><th>类型</th><th>聊天模型</th><th>向量模型</th><th>能力</th><th>默认</th><th>启用</th><th>操作</th></tr></thead>
            <tbody id="modelBody"></tbody>
          </table>
        </div>
        <div class="model-tips card" style="margin-top:16px;padding:16px">
          <div style="font-weight:600;margin-bottom:8px">快速开始</div>
          <ol style="padding-left:20px;color:var(--text-2);font-size:13px;line-height:2">
            <li>内置已默认启用一个 <b>本地离线向量模型</b>（512 维），无需任何 Key 即可完成入库与检索。</li>
            <li>添加 <b>OpenAI 兼容</b> 模型：填 base_url（如 https://api.deepseek.com/v1）+ api_key + 聊天模型名 + 向量模型名（可同一 Provider 同时承载）。</li>
            <li>添加 <b>Ollama</b>：填地址（如 http://localhost:11434）后点「拉取模型列表」自动获取本地模型；向量模型可留空（共用内置本地向量）。</li>
            <li><b>新增 Provider 默认停用</b>：手动开启后才可被对话 / 抽取 / 向量化调用；默认聊天 / 默认向量不可停用。</li>
            <li>勾选「多模态」能力后，多模态知识库才能上传图片/视频，对话页也会出现图片按钮。</li>
          </ol>
        </div>
      </div>`;
  },

  renderBody() {
    const body = this.el.querySelector('#modelBody');
    if (!this.providers.length) {
      body.innerHTML = `<tr><td colspan="8"><div class="empty"><div class="empty-icon">🤖</div><div class="empty-title">暂无模型</div><div>点击右上角添加模型</div></div></td></tr>`;
      return;
    }
    const sorted = [...this.providers].sort((a, b) => (a.enabled === b.enabled ? a.id - b.id : (a.enabled ? -1 : 1)));
    body.innerHTML = sorted.map(p => {
      const locked = (p.defaultChat || p.defaultEmbedding);
      const caps = (p.capabilities || 'text').split(',').filter(Boolean);
      return `<tr class="${p.enabled ? '' : 'model-row-off'}">
      <td style="font-weight:600">${Util.esc(p.name)}${p.providerType === 'local' ? ' <span class="badge badge-teal">离线</span>' : ''}</td>
      <td><span class="badge badge-gray">${Util.providerTypeName(p.providerType)}</span></td>
      <td>${Util.esc(p.chatModel || '-')}</td>
      <td>${Util.esc(p.embeddingModel || '-')}</td>
      <td>${caps.map(c => c === 'vision'
        ? '<span class="badge badge-purple" title="多模态视觉">多模态</span>'
        : '<span class="badge badge-gray">文本</span>').join(' ')}</td>
      <td>${p.defaultChat ? '<span class="badge badge-green">聊天</span>' : ''}${p.defaultEmbedding ? '<span class="badge badge-blue">向量</span>' : ''}</td>
      <td><label class="switch switch-sm" title="${locked ? '默认聊天/默认向量的模型不可停用，请先转移默认身份' : (p.enabled ? '停用' : '启用')}">
        <input type="checkbox" data-toggle="${p.id}" ${p.enabled ? 'checked' : ''} ${locked ? 'disabled' : ''}><span class="slider"></span></label></td>
      <td><div class="actions">
        <button class="btn btn-sm" data-test="${p.id}">测试</button>
        <button class="btn btn-sm" data-edit="${p.id}">编辑</button>
        <button class="btn btn-sm btn-danger" data-del="${p.id}">删除</button>
      </div></td>
    </tr>`;
    }).join('');
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
        confirmBox(`删除模型「${p.name}」？${p.defaultChat || p.defaultEmbedding ? '\n（默认模型不可删除，请先转移默认身份）' : ''}`).then(async ok => {
          if (!ok) return;
          try { await Api.del(`/providers/${p.id}`); toast('已删除', 'success'); await this.load(); this.renderBody(); }
          catch (err) { toast(err.message, 'error'); }
        });
      }
    });
    this.el.querySelector('#modelTable').addEventListener('change', async e => {
      const toggle = e.target.closest('[data-toggle]');
      if (!toggle) return;
      const id = Number(toggle.dataset.toggle);
      const enabled = toggle.checked;
      try {
        const updated = await Api.post(`/providers/${id}/status`, { enabled });
        const idx = this.providers.findIndex(x => x.id === id);
        if (idx >= 0) this.providers[idx] = { ...this.providers[idx], enabled: updated.enabled };
        toast(enabled ? '已启用' : '已停用', 'success');
      } catch (err) {
        toggle.checked = !enabled;
        toast(err.message, 'error');
      }
      this.renderBody();
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
    const caps = (p?.capabilities || 'text').split(',');
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
          <button class="btn" id="mpOllamaFetch" style="${p?.providerType === 'ollama' ? '' : 'display:none'}">拉取模型列表</button>
        </div>
        <div class="hint" id="mpOllamaList"></div>
      </div>
      <div class="form-item" id="mpKeyRow" ${isLocal || p?.providerType === 'ollama' ? 'style="display:none"' : ''}>
        <label class="label">API Key ${isEdit && p.hasApiKey ? '（已设置，留空则不修改）' : ''}</label>
        <input class="input" type="password" id="mpKey" placeholder="sk-…" autocomplete="off">
      </div>
        <div class="form-grid">
        <div class="form-item" id="mpChatRow" ${isLocal ? 'style="display:none"' : ''}>
          <label class="label">聊天模型名</label>
          <input class="input" id="mpChat" value="${Util.esc(p?.chatModel || '')}" placeholder="${p?.providerType === 'ollama' ? 'qwen2.5:7b' : 'deepseek-chat'}">
        </div>
        <div class="form-item" id="mpEmbedRow" ${isLocal ? 'style="display:none"' : ''}>
          <label class="label" id="mpEmbedLabel">向量模型名</label>
          <input class="input" id="mpEmbed" value="${Util.esc(p?.embeddingModel || '')}" placeholder="bge-m3">
        </div>
      </div>
      <div class="form-item" id="mpBothHint" style="display:none">
        <div class="hint">聊天模型与向量模型互相独立、可分别留空，但至少配置一项；只有聊天模型的 Provider 出现在对话/抽取/重排下拉，只有向量模型的 Provider 仅用于向量化。</div>
      </div>
      <div class="form-item" id="mpEmbedHint" style="display:none"></div>
      <div class="form-item" id="mpChatOnlyHint" style="display:none"></div>
      <div class="form-item" id="mpLocalRow" ${isLocal ? '' : 'style="display:none"'}>
        <div class="hint">内置本地离线向量模型，维度 512，无需 Key 即可开箱即用；设为默认向量后新知识库无需配置即可入库。</div>
      </div>
      <div class="form-item" id="mpCapsRow" ${isLocal ? 'style="display:none"' : ''}>
        <label class="label">模型能力</label>
        <div class="row" style="gap:20px">
          <label class="check-label"><input type="checkbox" id="mpCapText" ${caps.includes('text') ? 'checked' : ''}> 文本</label>
          <label class="check-label"><input type="checkbox" id="mpCapVision" ${caps.includes('vision') ? 'checked' : ''}> 多模态（视觉）</label>
        </div>
        <div class="hint">勾选「多模态」后，多模态知识库可上传图片/视频，对话页出现图片按钮</div>
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
      <div class="form-item" style="display:flex;gap:28px;flex-wrap:wrap">
        <label class="switch-label"><span class="switch"><input type="checkbox" id="mpDefaultChat" ${p?.defaultChat ? 'checked' : ''}><span class="slider"></span></span> 默认聊天</label>
        <label class="switch-label"><span class="switch"><input type="checkbox" id="mpDefaultEmbed" ${p?.defaultEmbedding ? 'checked' : ''}><span class="slider"></span></span> 默认向量</label>
        <label class="switch-label"><span class="switch"><input type="checkbox" id="mpEnabled" ${isEdit ? (p?.enabled ? 'checked' : '') : ''}><span class="slider"></span></span> 启用<span class="hint">（新增默认停用）</span></label>
      </div>
      <div class="modal-foot">
        <button class="btn" onclick="closeModal()">取消</button>
        <button class="btn btn-primary" id="mpSave">保存</button>
      </div>`);

    const typeSel = mask.querySelector('#mpType');
    const typeHint = {
      openai_compatible: { chat: 'deepseek-chat', embed: 'bge-m3' },
      ollama: { chat: 'qwen2.5:7b', embed: 'bge-m3' },
      anthropic: { chat: 'claude-sonnet-4-5', embed: '' },
      gemini: { chat: 'gemini-2.0-flash', embed: 'text-embedding-004' }
    };

    const applyType = () => {
      const t = typeSel.value;
      const local = t === 'local';
      const ollama = t === 'ollama';
      const anthropic = t === 'anthropic';
      mask.querySelector('#mpBaseRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpKeyRow').style.display = (local || ollama) ? 'none' : '';
      mask.querySelector('#mpChatRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpEmbedRow').style.display = (local || anthropic) ? 'none' : '';
      mask.querySelector('#mpEmbedHint').style.display = ollama ? '' : 'none';
      mask.querySelector('#mpChatOnlyHint').style.display = anthropic ? '' : 'none';
      mask.querySelector('#mpLocalRow').style.display = local ? '' : 'none';
      mask.querySelector('#mpCapsRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpTempRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpMaxRow').style.display = local ? 'none' : '';
      mask.querySelector('#mpOllamaFetch').style.display = ollama ? '' : 'none';
      mask.querySelector('#mpBaseLabel').textContent = t === 'ollama' ? 'Ollama 地址' : 'Base URL';
      mask.querySelector('#mpBase').placeholder = t === 'ollama' ? 'http://localhost:11434' : 'https://api.deepseek.com/v1';
      mask.querySelector('#mpChat').placeholder = typeHint[t]?.chat || 'deepseek-chat';
      mask.querySelector('#mpEmbed').placeholder = typeHint[t]?.embed || 'bge-m3';
      mask.querySelector('#mpBothHint').style.display = local ? 'none' : '';
      mask.querySelector('#mpEmbedHint').innerHTML = 'Ollama 可共用本地内置向量模型，向量模型可留空';
      mask.querySelector('#mpChatOnlyHint').innerHTML = 'Anthropic 不提供 Embedding 接口，仅需配置聊天模型';
      // 校验提示重置
      mask.querySelector('#mpChatHint')?.remove();
      mask.querySelector('#mpEmbedHint2')?.remove();
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
      const t = typeSel.value;
      const chat = mask.querySelector('#mpChat').value.trim();
      const embed = mask.querySelector('#mpEmbed').value.trim();
      const capText = mask.querySelector('#mpCapText')?.checked ?? true;
      const capVision = mask.querySelector('#mpCapVision')?.checked ?? false;
      const capsVal = [capText ? 'text' : null, capVision ? 'vision' : null].filter(Boolean).join(',') || 'text';
      const showHint = (elId, msg) => {
        const old = mask.querySelector('#' + elId);
        if (old) old.remove();
        const div = document.createElement('div');
        div.id = elId;
        div.className = 'form-hint-error';
        div.style.cssText = 'color:var(--danger);font-size:12px;margin-top:4px';
        div.textContent = msg;
        const target = elId.includes('Chat') ? mask.querySelector('#mpChatRow') : mask.querySelector('#mpEmbedRow');
        target.appendChild(div);
      };
      // 校验：聊天模型与向量模型互相独立、各自可留空，但至少配置一项（Ollama 向量可空 / Anthropic 仅聊天）
      if (t !== 'local' && !chat && !embed) {
        showHint('mpChatHint', '聊天模型与向量模型至少配置一项');
        return;
      }
      if (mask.querySelector('#mpDefaultChat').checked && !chat) {
        showHint('mpChatHint', '「默认聊天」只能选择配置了聊天模型的 Provider');
        return;
      }
      if (mask.querySelector('#mpDefaultEmbed').checked && !embed) {
        showHint('mpEmbedHint2', '「默认向量」只能选择配置了向量模型的 Provider');
        return;
      }
      const body = {
        name,
        providerType: t,
        baseUrl: t === 'local' ? '' : mask.querySelector('#mpBase').value.trim(),
        chatModel: t === 'local' ? '' : chat,
        embeddingModel: t === 'local' ? 'local' : embed,
        temperature: Number(mask.querySelector('#mpTemp').value) || 0.7,
        maxTokens: Number(mask.querySelector('#mpMax').value) || 2048,
        defaultChat: mask.querySelector('#mpDefaultChat').checked,
        defaultEmbedding: mask.querySelector('#mpDefaultEmbed').checked,
        enabled: mask.querySelector('#mpEnabled').checked,
        capabilities: capsVal
      };
      const key = mask.querySelector('#mpKey').value;
      if (key) body.apiKey = key;
      try {
        if (isEdit) await Api.put(`/providers/${p.id}`, body);
        else await Api.post('/providers', body);
        closeModal(); toast('已保存' + (body.enabled ? '' : '（已停用，开启后生效）'), 'success');
        await this.load(); this.renderBody();
      } catch (e) { toast(e.message, 'error'); }
    };
  }
};
