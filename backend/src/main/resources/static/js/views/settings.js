/* ===== 系统设置视图 ===== */
const SettingsView = {
  el: null,
  values: {},
  tab: 'prompts',

  async init(el) {
    this.el = el;
    await this.load();
    this.render();
    this.bind();
  },

  async load() {
    try {
      this.values = await Api.get('/settings') || {};
    } catch (e) {
      toast(e.message, 'error');
      this.values = {};
    }
  },

  render() {
    this.el.innerHTML = `
      <div class="page">
        <div class="kb-head" style="display:flex;justify-content:space-between;align-items:flex-start">
          <div>
            <div class="page-title">系统设置</div>
            <div class="page-sub">Prompt 与关键参数均可界面修改，保存后即时生效；也可直接编辑 data/settings.yml（YAML）</div>
          </div>
          <div style="display:flex;gap:8px">
            <button class="btn" id="setReset">恢复默认</button>
            <button class="btn btn-primary" id="setSave">保存</button>
          </div>
        </div>
        <div class="settings-tabs card" style="padding:8px;display:flex;gap:4px;margin-bottom:16px">
          <button class="btn btn-sm ${this.tab === 'prompts' ? 'btn-primary' : ''}" data-tab="prompts">提示词</button>
          <button class="btn btn-sm ${this.tab === 'rag' ? 'btn-primary' : ''}" data-tab="rag">分块与检索</button>
          <button class="btn btn-sm ${this.tab === 'graph' ? 'btn-primary' : ''}" data-tab="graph">知识图谱</button>
          <button class="btn btn-sm ${this.tab === 'about' ? 'btn-primary' : ''}" data-tab="about">关于</button>
        </div>
        <div class="card" style="padding:20px" id="setContent"></div>
      </div>`;
  },

  renderContent() {
    const box = this.el.querySelector('#setContent');
    const v = this.values;
    if (this.tab === 'prompts') {
      box.innerHTML = `
        <div class="form-item">
          <label class="label">系统提示词（对话 RAG 回答）</label>
          <textarea class="textarea" id="sSystemPrompt" rows="7">${Util.esc(v.systemPrompt || '')}</textarea>
          <div class="hint">支持 {context} 与 {history} 占位符，{sources} 自动追加来源引用</div>
        </div>
        <div class="form-item">
          <label class="label">知识抽取 Prompt（LLM 抽取实体与关系）</label>
          <textarea class="textarea" id="sExtractPrompt" rows="8">${Util.esc(v.extractPrompt || '')}</textarea>
        </div>
        <div class="form-item">
          <label class="label">图谱构建 Prompt（实体合并去重）</label>
          <textarea class="textarea" id="sGraphPrompt" rows="6">${Util.esc(v.graphPrompt || '')}</textarea>
        </div>`;
    } else if (this.tab === 'rag') {
      box.innerHTML = `
        <div class="settings-grid">
          <div class="form-item">
            <label class="label">默认分块策略（知识库级可覆盖）</label>
            <select class="select" id="sChunkStrategy">
              <option value="fixed" ${v.chunkStrategy !== 'paragraph' ? 'selected' : ''}>固定长度（按字符）</option>
              <option value="paragraph" ${v.chunkStrategy === 'paragraph' ? 'selected' : ''}>按段落</option>
            </select>
          </div>
          <div class="form-item">
            <label class="label">分块大小（字符）</label>
            <input class="input" type="number" id="sChunkSize" value="${v.chunkSize ?? 600}" min="50" max="4000">
          </div>
          <div class="form-item">
            <label class="label">分块重叠（字符）</label>
            <input class="input" type="number" id="sChunkOverlap" value="${v.chunkOverlap ?? 100}" min="0" max="1000">
          </div>
          <div class="form-item">
            <label class="label">检索 TopK</label>
            <input class="input" type="number" id="sRagTopK" value="${v.ragTopK ?? 8}" min="1" max="50">
          </div>
          <div class="form-item">
            <label class="label">相似度阈值</label>
            <input class="input" type="number" id="sRagMinScore" value="${v.ragMinScore ?? 0.25}" min="0" max="1" step="0.05">
          </div>
          <div class="form-item">
            <label class="label">重排策略</label>
            <select class="select" id="sRagRerank">
              <option value="hybrid" ${v.ragRerank === 'hybrid' ? 'selected' : ''}>混合（向量 0.65 + 关键词 0.35）</option>
              <option value="llm" ${v.ragRerank === 'llm' ? 'selected' : ''}>LLM 重排（需默认聊天模型）</option>
              <option value="none" ${v.ragRerank === 'none' ? 'selected' : ''}>不重排</option>
            </select>
          </div>
          <div class="form-item">
            <label class="label">图谱召回跳数（GraphRAG）</label>
            <input class="input" type="number" id="sRagGraphHop" value="${v.ragGraphHop ?? 2}" min="1" max="4">
          </div>
          <div class="form-item">
            <label class="label">图谱召回实体数</label>
            <input class="input" type="number" id="sRagGraphEntities" value="${v.ragGraphEntities ?? 5}" min="1" max="20">
          </div>
          <div class="form-item">
            <label class="label">图谱召回片段数</label>
            <input class="input" type="number" id="sRagGraphChunks" value="${v.ragGraphChunks ?? 15}" min="1" max="50">
          </div>
          <div class="form-item">
            <label class="label">对话历史保留轮数</label>
            <input class="input" type="number" id="sRagHistoryLimit" value="${v.ragHistoryLimit ?? 10}" min="1" max="50">
          </div>
        </div>`;
    } else if (this.tab === 'graph') {
      box.innerHTML = `
        <div class="settings-grid">
          <div class="form-item">
            <label class="label">抽取批大小（每批 chunk 数）</label>
            <input class="input" type="number" id="sGraphBatch" value="${v.graphExtractBatch ?? 4}" min="1" max="20">
          </div>
          <div class="form-item">
            <label class="label">实体合并阈值（Levenshtein 相似度）</label>
            <input class="input" type="number" id="sGraphMerge" value="${v.graphEntityMergeThreshold ?? 0.92}" min="0.5" max="1" step="0.01">
          </div>
          <div class="form-item" style="grid-column:1/-1;display:flex;align-items:center;gap:12px">
            <label class="switch-label"><span class="switch"><input type="checkbox" id="sGraphOnIndex" ${v.graphExtractOnIndex ? 'checked' : ''}><span class="slider"></span></span> 文档入库后自动抽取图谱（需默认聊天模型，建议先配置）</label>
          </div>
        </div>`;
    } else {
      box.innerHTML = `<div id="setAbout"><div class="hint">加载中…</div></div>`;
      Api.get('/system/info').then(info => {
        const el = this.el.querySelector('#setAbout');
        if (!el) return;
        el.innerHTML = `
          <div class="about-card">
            <div class="about-logo">知</div>
            <div class="about-name">${Util.esc(info.name || 'PKB 个人知识库系统')} v${Util.esc(info.version || '')}</div>
            <div class="about-row"><span>向量存储</span><b>${Util.esc(info.vectorMode || '')}</b></div>
            <div class="about-row"><span>索引算法</span><b>${Util.esc(info.indexAlgorithm || '')}</b></div>
            <div class="about-row"><span>数据目录</span><b>${Util.esc(info.dataDir || '')}</b></div>
            <div class="about-row"><span>模型 Provider</span><b>${info.providers ?? 0} 个</b></div>
            <div class="about-row"><span>知识库</span><b>${info.knowledgeBases ?? 0} 个</b></div>
            <div class="about-row"><span>文档 / 分块</span><b>${info.documents ?? 0} / ${info.chunks ?? 0}</b></div>
            <div class="about-row"><span>会话</span><b>${info.chatSessions ?? 0} 个</b></div>
            <div class="hint" style="margin-top:12px">切换向量库（内置 ↔ pgvector）仅需修改配置 pkb.vector.mode，无需改业务代码。</div>
          </div>`;
      }).catch(e => {
        const el = this.el.querySelector('#setAbout');
        if (el) el.innerHTML = `<div class="hint" style="color:var(--danger)">${Util.esc(e.message)}</div>`;
      });
    }
  },

  bind() {
    this.el.querySelectorAll('[data-tab]').forEach(b => {
      b.onclick = () => {
        this.tab = b.dataset.tab;
        this.render();
        this.bind();
      };
    });
    this.el.querySelector('#setSave').onclick = async () => {
      const body = {};
      const get = id => this.el.querySelector('#' + id);
      if (this.tab === 'prompts') {
        body.systemPrompt = get('sSystemPrompt').value;
        body.extractPrompt = get('sExtractPrompt').value;
        body.graphPrompt = get('sGraphPrompt').value;
      } else if (this.tab === 'rag') {
        body.chunkStrategy = get('sChunkStrategy').value;
        body.chunkSize = Number(get('sChunkSize').value) || 600;
        body.chunkOverlap = Number(get('sChunkOverlap').value) || 100;
        body.ragTopK = Number(get('sRagTopK').value) || 8;
        body.ragMinScore = Number(get('sRagMinScore').value) || 0.25;
        body.ragRerank = get('sRagRerank').value;
        body.ragGraphHop = Number(get('sRagGraphHop').value) || 2;
        body.ragGraphEntities = Number(get('sRagGraphEntities').value) || 5;
        body.ragGraphChunks = Number(get('sRagGraphChunks').value) || 15;
        body.ragHistoryLimit = Number(get('sRagHistoryLimit').value) || 10;
      } else if (this.tab === 'graph') {
        body.graphExtractBatch = Number(get('sGraphBatch').value) || 4;
        body.graphEntityMergeThreshold = Number(get('sGraphMerge').value) || 0.92;
        body.graphExtractOnIndex = get('sGraphOnIndex').checked;
      }
      try {
        await Api.post('/settings', body);
        toast('已保存并即时生效', 'success');
        await this.load();
      } catch (e) {
        toast(e.message, 'error');
      }
    };
    this.el.querySelector('#setReset').onclick = () => {
      confirmBox('恢复全部默认设置？自定义 Prompt 与参数将被覆盖。').then(async ok => {
        if (!ok) return;
        try {
          await Api.post('/settings/reset');
          toast('已恢复默认', 'success');
          await this.load();
          this.render();
          this.bind();
        } catch (e) { toast(e.message, 'error'); }
      });
    };
    this.renderContent();
  }
};
