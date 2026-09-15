/* ===== 系统设置视图 ===== */
const SettingsView = {
  el: null,
  values: {},
  tab: 'prompts',
  providers: [],
  rerankProviders: [],

  async init(el) {
    this.el = el;
    await Promise.all([this.load(), this.loadProviders()]);
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

  async loadProviders() {
    try {
      const list = await Api.get('/providers') || [];
      // 聊天/抽取可用：启用且有聊天模型（排除 Rerank 专用 Provider）
      this.providers = list.filter(p => p.enabled && p.chatModel && p.providerType !== 'rerank');
      // Rerank 模型：类型为 rerank 且启用
      this.rerankProviders = list.filter(p => p.enabled && p.providerType === 'rerank');
    } catch (e) { this.providers = []; this.rerankProviders = []; }
  },

  render() {
    this.el.innerHTML = `
      <div class="page">
        <div class="settings-head">
          <div>
            <div class="page-title">系统设置</div>
            <div class="page-sub">Prompt 与关键参数均可界面修改，保存后即时生效；也可直接编辑 data/settings.yml（YAML）</div>
          </div>
          <div style="display:flex;gap:8px">
            <button class="btn" id="setReset">恢复默认</button>
            <button class="btn btn-primary" id="setSave">保存</button>
          </div>
        </div>
        <div class="settings-layout">
          <nav class="settings-nav card">
            <div class="settings-nav-item ${this.tab === 'prompts' ? 'active' : ''}" data-tab="prompts">提示词</div>
            <div class="settings-nav-item ${this.tab === 'rag' ? 'active' : ''}" data-tab="rag">分块与检索</div>
            <div class="settings-nav-item ${this.tab === 'graph' ? 'active' : ''}" data-tab="graph">知识图谱</div>
            <div class="settings-nav-item ${this.tab === 'about' ? 'active' : ''}" data-tab="about">关于</div>
          </nav>
          <div class="card settings-content" id="setContent"></div>
        </div>
      </div>`;
  },

  renderContent() {
    const box = this.el.querySelector('#setContent');
    const v = this.values;
    const setItem = (id, label, desc, controlHtml, resetKey) => `
      <div class="set-item">
        <div class="set-label">${label}</div>
        <div class="set-desc">${desc}</div>
        <div class="set-control">
          ${controlHtml}
          ${resetKey ? `<button class="btn btn-sm set-reset" data-reset="${resetKey}" title="恢复该项默认值">重置</button>` : ''}
        </div>
        <div class="form-hint" id="hint-${id}"></div>
      </div>`;
    if (this.tab === 'prompts') {
      box.innerHTML = `
        ${setItem('systemPrompt', '系统提示词', '对话 RAG 回答的系统提示，支持 {context} 与 {history} 占位符，{sources} 自动追加来源引用。',
          `<textarea class="textarea" id="sSystemPrompt" rows="8">${Util.esc(v.systemPrompt || '')}</textarea>`, 'systemPrompt')}
        ${setItem('extractPrompt', '知识抽取 Prompt', '入库时由 LLM 从文档中抽取实体与关系（JSON 输出），修改后对新入库文档生效。',
          `<textarea class="textarea" id="sExtractPrompt" rows="8">${Util.esc(v.extractPrompt || '')}</textarea>`, 'extractPrompt')}`;
    } else if (this.tab === 'rag') {
      box.innerHTML = `
        <div class="settings-grid">
          ${setItem('ragHybrid', '混合检索（BM25 + 向量 + RRF）', '向量召回与 BM25 全文召回用 RRF（k=60）融合，显著提升关键词精确命中；关闭后回退为纯向量检索。',
            `<label class="switch-label"><span class="switch"><input type="checkbox" id="sRagHybrid" ${v.ragHybrid === false ? '' : 'checked'}><span class="slider"></span></span></label>`, 'ragHybrid')}
          ${setItem('ragRecallMultiplier', '召回倍率', '先召回 TopK×倍率 再重排截断，倍率越大重排选择面越广；范围 1–5。',
            `<input class="input" type="number" id="sRagRecallMultiplier" value="${v.ragRecallMultiplier ?? 3}" min="1" max="5">`, 'ragRecallMultiplier')}
          ${setItem('ragScoreNorm', '分数归一化', '不同 Embedding 模型相似度分布差异大；min-max 把向量分归一化到 0–1 便于统一阈值，默认不变。',
            `<select class="select" id="sRagScoreNorm">
              <option value="none" ${(v.ragScoreNorm || 'none') === 'none' ? 'selected' : ''}>不变（默认）</option>
              <option value="minmax" ${v.ragScoreNorm === 'minmax' ? 'selected' : ''}>min-max 归一化</option>
            </select>`, 'ragScoreNorm')}
          ${setItem('ragQueryRewrite', '检索前查询改写', '用聊天模型把口语化/指代问题改写为独立检索式（结合对话历史）；<b>会增加一次 LLM 调用</b>，默认关闭。',
            `<label class="switch-label"><span class="switch"><input type="checkbox" id="sRagQueryRewrite" ${v.ragQueryRewrite ? 'checked' : ''}><span class="slider"></span></span></label>`, 'ragQueryRewrite')}
          ${setItem('ragHyde', 'HyDE 假设答案', '检索前生成一段假设资料片段辅助向量召回；<b>会增加一次 LLM 调用</b>，默认关闭。',
            `<label class="switch-label"><span class="switch"><input type="checkbox" id="sRagHyde" ${v.ragHyde ? 'checked' : ''}><span class="slider"></span></span></label>`, 'ragHyde')}
          ${setItem('chunkStrategy', '默认分块策略', '知识库级可覆盖；中文文档推荐「按段落优先」，兼顾语义完整与检索精度。',
            `<select class="select" id="sChunkStrategy">
              <option value="paragraph" ${v.chunkStrategy === 'paragraph' ? 'selected' : ''}>按段落优先（推荐中文）</option>
              <option value="fixed" ${v.chunkStrategy !== 'paragraph' ? 'selected' : ''}>固定长度（按字符）</option>
            </select>`, 'chunkStrategy')}
          ${setItem('chunkSize', '分块大小（字符）', '范围 100–4000，段落优先策略下为单段上限。',
            `<input class="input" type="number" id="sChunkSize" value="${v.chunkSize ?? 600}" min="100" max="4000">`, 'chunkSize')}
          ${setItem('chunkOverlap', '分块重叠（字符）', '范围 0–500，且必须小于分块大小。',
            `<input class="input" type="number" id="sChunkOverlap" value="${v.chunkOverlap ?? 100}" min="0" max="500">`, 'chunkOverlap')}
          ${setItem('ragTopK', '检索 TopK', '每次检索返回的片段数量，范围 1–50。',
            `<input class="input" type="number" id="sRagTopK" value="${v.ragTopK ?? 8}" min="1" max="50">`, 'ragTopK')}
          ${setItem('ragMinScore', '相似度阈值', '低于该分数的片段不参与回答，范围 0–1。',
            `<input class="input" type="number" id="sRagMinScore" value="${v.ragMinScore ?? 0.25}" min="0" max="1" step="0.05">`, 'ragMinScore')}
          ${setItem('ragRerank', '重排策略', '混合（向量+关键词）/ LLM 重排（需默认聊天模型）/ 外部 Rerank 模型（OpenAI 协议 /v1/rerank）/ 不重排。',
            `<select class="select" id="sRagRerank">
              <option value="hybrid" ${v.ragRerank === 'hybrid' ? 'selected' : ''}>混合（向量 0.65 + 关键词 0.35）</option>
              <option value="llm" ${v.ragRerank === 'llm' ? 'selected' : ''}>LLM 重排（需默认聊天模型）</option>
              <option value="rerank_model" ${v.ragRerank === 'rerank_model' ? 'selected' : ''}>Rerank 模型（/v1/rerank）</option>
              <option value="none" ${v.ragRerank === 'none' ? 'selected' : ''}>不重排</option>
            </select>`, 'ragRerank')}
          ${setItem('ragRerankProvider', 'Rerank 模型', '选择类型为「Rerank」的 Provider；不选则使用默认 Rerank Provider（在模型管理配置）。',
            `<select class="select" id="sRagRerankProvider">
              <option value="">使用默认 Rerank Provider</option>
              ${this.rerankProviders.map(p => `<option value="${p.id}" ${Number(v.ragRerankProvider) === p.id ? 'selected' : ''}>${Util.esc(p.name)}（${Util.esc(p.chatModel)}）</option>`).join('')}
            </select>`, 'ragRerankProvider')}
          ${setItem('ragGraphHop', '图谱召回跳数', 'GraphRAG 沿实体关系的扩展跳数，范围 1–4。',
            `<input class="input" type="number" id="sRagGraphHop" value="${v.ragGraphHop ?? 2}" min="1" max="4">`, 'ragGraphHop')}
          ${setItem('ragGraphEntities', '图谱召回实体数', '每次图谱召回实体数量上限，范围 1–20。',
            `<input class="input" type="number" id="sRagGraphEntities" value="${v.ragGraphEntities ?? 5}" min="1" max="20">`, 'ragGraphEntities')}
          ${setItem('ragGraphChunks', '图谱召回片段数', '图谱召回补充的文档片段上限，范围 1–50。',
            `<input class="input" type="number" id="sRagGraphChunks" value="${v.ragGraphChunks ?? 15}" min="1" max="50">`, 'ragGraphChunks')}
          ${setItem('ragHistoryLimit', '对话历史保留轮数', '多轮对话携带的历史轮数，范围 1–50。',
            `<input class="input" type="number" id="sRagHistoryLimit" value="${v.ragHistoryLimit ?? 10}" min="1" max="50">`, 'ragHistoryLimit')}
        </div>`;
    } else if (this.tab === 'graph') {
      box.innerHTML = `
        ${setItem('graphPrompt', '图谱问答 Prompt', '开启图谱混合检索（GraphRAG）时追加的系统提示，随图谱线索一并注入对话。',
          `<textarea class="textarea" id="sGraphPrompt" rows="6">${Util.esc(v.graphPrompt || '')}</textarea>`, 'graphPrompt')}
        ${setItem('graphBuildPrompt', '图谱构建 Prompt', '抽取时相似实体落入模糊区间（阈值 −0.15 ~ 阈值）时，由该 Prompt 决定是否合并。',
          `<textarea class="textarea" id="sGraphBuildPrompt" rows="5">${Util.esc(v.graphBuildPrompt || '')}</textarea>`, 'graphBuildPrompt')}
        <div class="settings-grid">
          ${setItem('graphExtractProvider', '抽取模型', '图谱抽取使用的聊天模型；不选则使用默认聊天模型。',
            `<select class="select" id="sGraphExtractProvider">
              <option value="">使用默认聊天模型</option>
              ${this.providers.map(p => `<option value="${p.id}" ${Number(v.graphExtractProvider) === p.id ? 'selected' : ''}>${Util.esc(p.name)}（${Util.esc(p.chatModel)}）</option>`).join('')}
            </select>`, 'graphExtractProvider')}
          ${setItem('graphBatch', '抽取批大小', '每批处理的 chunk 数，范围 1–20。',
            `<input class="input" type="number" id="sGraphBatch" value="${v.graphExtractBatch ?? 4}" min="1" max="20">`, 'graphExtractBatch')}
          ${setItem('graphMerge', '实体合并阈值', 'Levenshtein 相似度阈值，范围 0.5–1。',
            `<input class="input" type="number" id="sGraphMerge" value="${v.graphEntityMergeThreshold ?? 0.92}" min="0.5" max="1" step="0.01">`, 'graphEntityMergeThreshold')}
          ${setItem('graphOnIndex', '入库自动抽取', '文档入库后自动触发图谱抽取；需已配置可用的聊天模型。',
            `<label class="switch-label"><span class="switch"><input type="checkbox" id="sGraphOnIndex" ${v.graphExtractOnIndex ? 'checked' : ''}><span class="slider"></span></span></label>`, 'graphExtractOnIndex')}
        </div>`;
    } else {
      box.innerHTML = `<div id="setAbout"><div class="hint">加载中…</div></div>`;
      Api.get('/system/info').then(info => {
        const el = this.el.querySelector('#setAbout');
        if (!el) return;
        el.innerHTML = `
          <div class="about-card">
            <img src="logo.svg" alt="PKB" class="about-logo">
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

  /** 表单校验：不合规红字提示并返回 false（禁止保存） */
  validate() {
    let ok = true;
    const hint = (id, msg) => {
      const h = this.el.querySelector('#hint-' + id);
      if (h) h.textContent = msg || '';
    };
    const num = (id, min, max, intOnly) => {
      const el = this.el.querySelector('#' + id);
      if (!el) return null;
      const raw = el.value.trim();
      let val = Number(raw);
      if (raw === '' || Number.isNaN(val)) { hint(id, '请输入数字'); ok = false; return null; }
      if (intOnly && !Number.isInteger(val)) { hint(id, '请输入整数'); ok = false; return null; }
      if (val < min || val > max) { hint(id, `取值范围 ${min}–${max}`); ok = false; return null; }
      hint(id, '');
      return val;
    };
    if (this.tab === 'rag') {
      num('sRagTopK', 1, 50, true);
      num('sRagRecallMultiplier', 1, 5, true);
      num('sRagMinScore', 0, 1, false);
      const cs = num('sChunkSize', 100, 4000, true);
      const ov = num('sChunkOverlap', 0, 500, true);
      if (cs !== null && ov !== null && ov >= cs) {
        hint('sChunkOverlap', '重叠必须小于分块大小');
        ok = false;
      }
    } else if (this.tab === 'graph') {
      num('sGraphBatch', 1, 20, true);
      num('sGraphMerge', 0.5, 1, false);
      num('sRagGraphHop', 1, 4, true);
    }
    return ok;
  },

  bind() {
    this.el.querySelectorAll('.settings-nav-item').forEach(b => {
      b.onclick = () => {
        this.tab = b.dataset.tab;
        this.render();
        this.bind();
      };
    });
    // 单键恢复默认
    this.el.querySelector('#setContent').addEventListener('click', e => {
      const btn = e.target.closest('[data-reset]');
      if (!btn) return;
      const key = btn.dataset.reset;
      confirmBox(`恢复「${key}」为默认值？`).then(async ok => {
        if (!ok) return;
        try {
          await Api.post('/settings/reset', { key });
          toast('已恢复该项默认', 'success');
          await this.load();
          this.render();
          this.bind();
        } catch (err) { toast(err.message, 'error'); }
      });
    });
    this.el.querySelector('#setSave').onclick = async () => {
      if (!this.validate()) { toast('参数不合法，请修正后保存', 'warn'); return; }
      const body = {};
      const get = id => this.el.querySelector('#' + id);
      if (this.tab === 'prompts') {
        body.systemPrompt = get('sSystemPrompt').value;
        body.extractPrompt = get('sExtractPrompt').value;
      } else if (this.tab === 'rag') {
        body.ragHybrid = get('sRagHybrid').checked;
        body.ragRecallMultiplier = Number(get('sRagRecallMultiplier').value);
        body.ragScoreNorm = get('sRagScoreNorm').value;
        body.ragQueryRewrite = get('sRagQueryRewrite').checked;
        body.ragHyde = get('sRagHyde').checked;
        body.chunkStrategy = get('sChunkStrategy').value;
        body.chunkSize = Number(get('sChunkSize').value);
        body.chunkOverlap = Number(get('sChunkOverlap').value);
        body.ragTopK = Number(get('sRagTopK').value);
        body.ragMinScore = Number(get('sRagMinScore').value);
        body.ragRerank = get('sRagRerank').value;
        body.ragRerankProvider = Number(get('sRagRerankProvider').value) || 0;
        body.ragGraphHop = Number(get('sRagGraphHop').value);
        body.ragGraphEntities = Number(get('sRagGraphEntities').value);
        body.ragGraphChunks = Number(get('sRagGraphChunks').value);
        body.ragHistoryLimit = Number(get('sRagHistoryLimit').value);
      } else if (this.tab === 'graph') {
        body.graphPrompt = get('sGraphPrompt').value;
        body.graphBuildPrompt = get('sGraphBuildPrompt').value;
        body.graphExtractProvider = Number(get('sGraphExtractProvider').value) || 0;
        body.graphExtractBatch = Number(get('sGraphBatch').value);
        body.graphEntityMergeThreshold = Number(get('sGraphMerge').value);
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
