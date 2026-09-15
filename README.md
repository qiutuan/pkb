# PKB 个人知识库系统

基于 **RAG + 知识图谱 + LLM Wiki** 的个人知识库系统。中文优先，轻量零依赖默认可运行，单机开箱即用。

- 后端：Java 17+ / Spring Boot 3.5 / LangChain4j 1.20
- 前端：原生 SPA（Spring Boot 直接托管，无 Node 构建）
- 向量库：内置 SQLite + HNSW（默认，零依赖）｜ PostgreSQL + pgvector（切换仅改配置）
- 存储：SQLite（WAL），本地优先，API Key AES-256-GCM 加密

## 目录

- [快速启动（≤10 分钟跑通）](#快速启动)
- [功能总览](#功能总览)
- [配置说明](#配置说明)
- [Docker 部署](#docker-部署)
- [模型配置推荐](#模型配置推荐)
- [验收对照](#验收对照)
- [常见问题 FAQ](#常见问题-faq)
- [项目结构](#项目结构)

---

## 快速启动

> 需要 JDK 17+（推荐 21）与 Maven 3.9+。本机无 Maven 可下载便携版：`apache-maven-3.9.9-bin.tar.gz`，解压后加入 PATH。

```bash
# 1. 构建（首次会自动下载依赖，稍慢）
cd backend
mvn -DskipTests package

# 2. 启动（默认端口 8080，数据落在 ./data）
java -jar target/pkb.jar

# 3. 打开浏览器
# http://localhost:8080
```

三步即可看到界面。开箱即用要点：

- 系统**自动内置一个离线 Embedding 模型**（`内置本地向量模型（离线）`，512 维），无需任何 API Key 即可完成：建库 → 上传中文文档 → 索引 → 跨库检索。
- 首次对话需要在「模型管理」添加一个**聊天模型**（OpenAI 协议 / Ollama / Anthropic / Gemini，见[模型配置推荐](#模型配置推荐)）。
- 10 分钟验收路线：启动 → 「模型管理」添加聊天模型并设为默认 → 新建 2 个知识库 → 各自上传中文文档 → 回到「对话」勾选两个库提问。

### 快速验收脚本（可选）

```bash
# 建库、传文档、检索，全部走 API，无需浏览器
curl -X POST localhost:8080/api/kbs -H 'Content-Type: application/json' -d '{"name":"示例库"}'
curl -X POST localhost:8080/api/kbs/1/documents/upload -F "files=@/path/to/中文文档.md"
curl -s 'localhost:8080/api/retrieval' -H 'Content-Type: application/json' \
  -d '{"query":"你的问题","kbIds":[1],"topK":5}'
```

---

## 功能总览

| 模块 | 能力 |
|---|---|
| 模型管理 | 6 类 Provider：OpenAI 协议自定义 / Ollama（自动拉模型列表）/ Anthropic / Gemini / Rerank 模型（/v1/rerank）/ 内置本地。国内模板一键预填（百炼/DeepSeek/智谱/Kimi/MiMo/万相）；聊天与向量模型互相独立、至少配置一项；启用/停用开关、可切换默认、连接测试、API Key 加密存储 |
| 知识库 | 多级分类（≥2 级）、库级独立（文档 / 向量索引 / Embedding / 分块策略 / 表格策略 / Contextual 开关）、上传→解析→分块→向量化→入库流水线（进度/失败原因/重试/重建索引） |
| 检索与问答 | 多知识库跨库检索；生产级检索管线：BM25+向量 RRF 融合（默认开）、外部 Rerank 模型 / LLM 重排、查询改写与 HyDE（可选）、分数归一化；分阶段检索测试面板 + Hit@K 评估落库；top_k / 阈值可调、多轮会话（SSE 流式）、答案引用溯源、会话导出 Markdown |
| 知识图谱 | LLM 自动抽取实体/关系（JSON 宽松解析、实体合并去重）、图谱可视化浏览（点击节点溯源）、GraphRAG 混合检索开关、按库独立维护 |
| 文档格式 | txt / md / pdf / docx / doc / xlsx / xls / csv（表格转文本 / 逐行 JSON / 摘要+明细，>1000 行自动分批）/ pptx / html / epub；纯文本模式同样支持表格 |
| 多模态 | 纯文本模式（图片/视频直接拒绝并提示）／多模态模式（图片可向量化入库、可随对话发给多模态 LLM），开关在知识库级 |
| 系统设置 | 全部 Prompt（系统/抽取/图谱）与分块、检索、图谱参数可在界面修改，落地 `data/settings.yml`，支持恢复默认；亦可直接改 YAML |

**多模态说明**：系统不做自研 OCR / 视频抽帧，图片理解与向量化由你选择的多模态模型承担——纯文本 Embedding 与多模态 Embedding 在知识库配置中切换即可。

---

## 配置说明

### 主要配置（backend/src/main/resources/application.yml）

```yaml
server:
  port: 8080

pkb:
  data-dir: ./data            # 数据目录：SQLite / 密钥 / 上传文件 / settings.yml
  vector:
    mode: embedded            # embedded（内置 SQLite+HNSW） | pgvector
    embedded:
      algorithm: hnsw
      hnsw: { m: 16, ef-construction: 200, ef-search: 128, seed: 42 }
    pgvector:
      host: localhost
      port: 5432
      database: pkb
      username: pkb
      password: pkb
  defaults:                   # 默认参数（界面可改，改动落 data/settings.yml）
    system-prompt: ...        # RAG 回答系统提示词
    extract-prompt: ...       # 知识抽取 Prompt
    graph-prompt: ...         # 图谱构建（实体合并）Prompt
    chunk-strategy: fixed     # fixed | paragraph | parent_child
    chunk-size: 600
    chunk-overlap: 100
    rag-top-k: 8
    rag-min-score: 0.25
    rag-rerank: hybrid        # none | hybrid | llm | rerank_model
    rag-hybrid: true           # 混合检索（BM25 + 向量 + RRF）默认开
    rag-recall-multiplier: 3   # 召回倍率（先召回 topK×倍率再重排）
    rag-score-norm: none       # none | minmax 向量分数归一化
    rag-query-rewrite: false   # 检索前查询改写（增加一次 LLM 调用）
    rag-hyde: false            # HyDE 假设答案（增加一次 LLM 调用）
    rag-rerank-provider: 0     # 外部 Rerank 模型 Provider ID
    rag-graph-hop: 2
    rag-graph-entities: 5
    rag-graph-chunks: 15
    rag-history-limit: 10
    graph-extract-batch: 4
    graph-extract-on-index: true
    graph-entity-merge-threshold: 0.92
  pipeline:                   # 入库流水线
    workers: 2
    queue-capacity: 200
    max-upload-mb: 200        # 单文件上传上限（MB）
    max-upload-files: 50      # 单次上传文件数量上限
```

### 环境变量别名（docker-compose 注入用）

| 环境变量 | 对应配置 |
|---|---|
| `PKB_DATA_DIR` | `pkb.data-dir` |
| `PKB_VECTOR_MODE` | `pkb.vector.mode` |
| `PKB_PG_HOST / PORT / DATABASE / USERNAME / PASSWORD` | `pkb.vector.pgvector.*` |
| `PKB_ENCRYPT_KEY` | API Key 加密密钥（不设则自动生成到 `data/.secret`） |

### 切换向量库：内置 ↔ pgvector

只需改配置，业务代码零改动：

1. 内置模式（默认）：`PKB_VECTOR_MODE=embedded`，什么都不用装。
2. pgvector 模式：准备 PostgreSQL（`pgvector/pgvector:pg16` 镜像自带扩展），执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

然后设置 `PKB_VECTOR_MODE=pgvector` 与 PG 连接信息即可。每个知识库一张向量表（`pkb_vectors_<kbId>`），建表自动完成。

---

## Docker 部署

两种模式各一份 compose 示例：

```bash
# 内置向量库模式（单容器）
docker compose -f docker-compose-embedded.yml up -d --build

# pgvector 模式（Postgres + 应用）
docker compose -f docker-compose-pgvector.yml up -d --build
```

访问 `http://localhost:8080`。数据均落卷，重启不丢。

---

## 模型配置推荐

详细清单见 [默认模型推荐.md](默认模型推荐.md)。速查：

| 用途 | 推荐 |
|---|---|
| 中文 Embedding（默认推荐） | BGE-M3（Ollama：`bge-m3`；OpenAI 协议：bge-m3 兼容服务）、智谱 `embedding-3`、通义 `text-embedding-v4` |
| 通用中文对话 | DeepSeek-V3 / deepseek-chat、通义千问 qwen-plus、智谱 GLM-4-Flash（免费）、Ollama 本地 `qwen2.5:7b` |
| 无外部依赖离线 | 系统内置 `local-embed-v2`（512 维），无需 Key 即可跑通全链路 |
| 图谱抽取（推荐有工具调用/JSON 能力） | GLM-4-Flash / qwen-plus / DeepSeek |

OpenAI 协议自定义模型：填 `base_url`（如 `https://api.deepseek.com/v1`，需含 `/v1`）、`api_key`、`model_name` 即可，兼容任意 `/v1/chat/completions` 服务。

Ollama：填地址（如 `http://localhost:11434`），点「拉取模型列表」自动填充可用模型。

---

## 验收对照

| 需求 | 达成方式 |
|---|---|
| 配置自定义 OpenAI 协议 / Ollama 后 10 分钟首对话 | 「模型管理」添加即可，开箱另有内置离线 Embedding 免 Key 链路 |
| ≥2 独立知识库、独立检索 / 跨库检索 | 知识库完全隔离，对话页多选勾选跨库 |
| 纯文本拒图 / 多模态入库问答 | 知识库级多模态开关，纯文本上传图片返回明确提示 |
| 图谱实体/关系 + 可视化 + GraphRAG 开关 | 图谱页可视化（节点/边/溯源），对话页「图谱混合检索」开关 |
| 向量库切换仅改配置 | `PKB_VECTOR_MODE` 一个开关 |
| Prompt / 分块参数界面即时生效 | 「系统设置」保存即生效，支持恢复默认 |

---

## 常见问题 FAQ

**Q：启动报 `java: command not found`？**
A：需要 JDK 17+。`java -version` 确认版本；无 JDK 可下载 Temurin 21。

**Q：没有 API Key 能玩起来吗？**
A：能。系统内置离线 Embedding 模型，上传/索引/检索/图谱 API 全部可用；仅「生成回答」需要聊天模型（任一 OpenAI 协议服务或 Ollama 均可，GLM-4-Flash 免费）。

**Q：Ollama 配置后报连不上？**
A：确认 `ollama serve` 已启动、地址正确；本机访问默认 `http://localhost:11434`。容器内访问宿主机 Ollama 需用 `host.docker.internal`。

**Q：上传后文档一直 PROCESSING / 失败？**
A：知识库详情页可见失败原因与重试按钮。常见：Embedding Provider 不可达（检查模型管理中的默认 Embedding 连接测试）、文档损坏。失败任务可「重试」或「重建索引」。

**Q：中文检索效果差？**
A：默认内置离线 Embedding 为兜底模型（512 维特征哈希），追求效果请配置 BGE-M3 等中文 Embedding（见模型推荐清单）并设为默认，然后对已有知识库「重建索引」。

**Q：API Key 存在哪？安全吗？**
A：AES-256-GCM 加密后存 SQLite，密钥在 `data/.secret`（600 权限）；可用 `PKB_ENCRYPT_KEY` 环境变量指定固定密钥以便备份迁移。

**Q：切换 pgvector 后旧数据还在吗？**
A：业务元数据（知识库/文档/会话/设置）在 SQLite 中保留；向量索引需对知识库重新索引（pgvector 每库一张表）。

**Q：日志与排障？**
A：入库/检索链路有分级日志（默认 `logs/`，可配置）。重试、失败原因、抽取进度均有 API 与界面呈现。

**Q：前端是构建产物吗？**
A：前端为原生 HTML/JS/CSS，由 Spring Boot 直接托管（`src/main/resources/static`），无需 Node 环境。

---

## 项目结构

```
pkb/
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/pkb/
│       │   ├── config/       # 配置、数据库、异步
│       │   ├── security/     # CryptoService（AES-GCM）
│       │   ├── util/         # JSON、加密、中文分词/关键词、向量编解码
│       │   ├── dao/          # SQLite DAO
│       │   ├── model/        # Provider 抽象、LocalEmbeddingModel、ModelFactory
│       │   ├── vector/       # VectorStore 接口 + embedded(HNSW/Flat) + pgvector
│       │   ├── knowledge/    # 分类/知识库/文档/分块/流水线
│       │   ├── rag/          # 检索、重排、Reranker、ChatService(SSE)
│       │   ├── graph/        # 图谱抽取/召回/服务
│       │   ├── settings/     # 设置（YAML 落盘）
│       │   └── system/       # 健康检查
│       └── resources/
│           ├── application.yml
│           └── static/       # 前端 SPA（index.html + js/ + css/）
├── Dockerfile
├── docker-compose-embedded.yml
├── docker-compose-pgvector.yml
└── 默认模型推荐.md
```

---

## 第四轮优化（v1.4）使用说明

### 检索增强（默认向后兼容，不开新开关行为不变）

| 能力 | 说明 | 入口 |
|---|---|---|
| 混合检索（BM25 + 向量 + RRF） | 自建中文倒排索引做 BM25 全文召回，与向量召回按 RRF（k=60）融合，默认开启；关闭即回退纯向量 | 系统设置 → 分块与检索 → 混合检索开关 |
| 外部 Rerank 模型 | 在模型管理添加类型「Rerank」的 Provider（填 base_url + api_key + 模型名，兼容 bge-reranker / Cohere 类 OpenAI 协议 `/v1/rerank` 服务）；重排策略选「Rerank 模型」，失败自动回退混合重排 | 模型管理 → 添加模型（提供商模板选自定义、类型选 Rerank）；系统设置 → 重排策略 + Rerank 模型 |
| 查询改写 / HyDE | 检索前用聊天模型改写口语化/指代问题，或生成假设答案辅助召回；均独立开关、均注明增加一次 LLM 调用，默认关 | 系统设置 → 分块与检索 |
| 分数归一化 | 不同 Embedding 模型相似度分布差异大，min-max 归一化便于统一阈值；检索测试面板给出当前库分数分布建议 | 系统设置 → 分块与检索 |
| 检索测试面板 | 对话页检索设置 → 试检索：分四列展示向量 / 关键词 / 融合 / 重排结果与分数；输入多组问题可跑 Hit@K 评估，结果落 `data/eval/eval-*.json` | 对话页 ⚙ → 试检索 |

### 分块与入库

- **父子分块**：知识库分块策略选「父子分块」——小子块（=分块大小÷3，下限 120 字）用于向量检索，命中后返回父块完整内容进 Prompt，兼顾精度与上下文。
- **Contextual 模式**：知识库表单打开开关后，入库时由默认聊天模型为每个片段生成一句文档上下文头（如「本文档为《XX规范》第3节，讨论……」）拼入向量文本；一次性入库成本，失败片段自动跳过不阻塞。

### 文档格式

- **表格（xlsx / xls / csv）**：知识库级「表格解析策略」三选一——
  - 表格转文本：每 Sheet 转 Markdown 表格分块入库（表头保留）；
  - 逐行 JSON：每行一条 JSON（列名→字段值），适合「张三的成绩是多少」类结构化问答；
  - Sheet 摘要 + 明细：LLM 为每 Sheet 生成一句话概述 + 逐行明细。
  - 超过 1000 行的 Sheet 自动按批切分（每批重复表头）。纯文本模式同样支持表格。
- **pptx**：逐页文本框转文本；**html/htm**：正文提取（去除脚本/样式/导航）；**epub**：按 spine 顺序提取章节。
- 知识库详情页「格式说明 ?」可查看各格式解析方式；不支持格式前端直接拦截提示。

### 模型管理

- 添加模型为「提供商模板 → 类型」两级选择：百炼 DashScope、DeepSeek、智谱 GLM、Kimi、小米 MiMo、阿里万相（多模态）等模板自动预填 base_url 与推荐模型名，只需填 API Key；所有字段仍可修改。
- 聊天模型名与向量模型名互相独立、各自可留空，但**至少配置一项**（Ollama 向量可空共用内置向量模型）。
- 只有聊天模型的 Provider 出现在对话/抽取/重排下拉；只有向量模型的 Provider 只能选作默认向量/知识库 Embedding；两者都有则两处都出现。
- 新增 Provider 默认停用；停用后不出现在模型下拉；默认聊天/默认向量的 Provider 不可停用（需先转移默认身份）。

---

## 规划（Roadmap）

以下能力已记入路线图，后续迭代实现：

- **Agentic RAG**：多跳问题自主规划检索（Self-RAG / CRAG 思想），先拆解子问题再逐跳检索汇聚
- **LightRAG 风格图谱社区摘要**：对图谱实体按社区聚类生成轻量摘要，提升全局性问题回答质量
- **对外检索 API**：提供鉴权的 HTTP 检索接口，供其他系统调用本知识库
- **深色模式**：全站深色主题

---

## 第三轮优化（v1.3）

### 新增/变更功能

| 模块 | 说明 |
|---|---|
| Logo | 全新「知识之树」矢量 Logo（`static/logo.svg`），已替换顶部导航、favicon 与关于页 |
| 上传入口 | 知识库卡片/详情页均有「上传文档」主按钮；上传后弹出成功/失败明细（含失败原因），卡片显示索引状态徽标（待处理/索引中/完成/失败）并自动轮询刷新 |
| 模型管理 | 每个 Provider 支持列表内联启停开关；新增 Provider 默认停用；默认聊天/默认向量不可停用或删除（需先转移默认身份）；同一 Provider 需同时配置聊天模型与向量模型（Ollama 向量可空、Anthropic 仅聊天除外）；模型能力多选（文本/视觉）；列表启用优先、停用置灰 |
| 对话页 | 输入区精简为「模型选择 + 知识库橙 Tag 多选 + 输入框 + 图片 + 发送」；TopK/阈值/重排/图谱开关/试检索收纳进齿轮 Popover；会话支持重命名/删除；会话记忆所选聊天模型；空状态三步引导卡（配置模型 → 建库传文档 → 开始提问） |
| 知识库 | 新建时可选模式：纯文本（默认）/ 多模态，纯文本库前端直接拦截图片视频；分块策略新增「按段落优先（推荐中文）」；分块大小/重叠即时校验；分类树支持重命名、删除、同级拖拽排序，「未分类」为不可删除的默认分组 |
| 知识图谱 | 知识库多选（含全选），支持跨库合并可视化与统计；实体按类型着色图例；「开始抽取」显示批次进度；「清空图谱」二次确认；聚焦实体高亮溯源 |
| 系统设置 | 左侧分组锚点导航（提示词/分块与检索/知识图谱/关于）；设置项按「标签+说明+控件」三行式；长文本控件支持单键重置；TopK(1–50)/阈值(0–1)/分块大小(100–4000)/重叠(0–500 且小于分块)红字校验并禁止保存；图谱新增「抽取模型」下拉（已启用聊天模型） |
| 视觉 | 全站重做：主色 #F97316、页面 #F5F6F8、白卡片 12px 圆角、按钮 36px/8px、150ms 过渡、表头 #F9FAFB 行高 44px、空状态 SVG 插画 + 引导语 + 主操作 |

### 数据库自动迁移

老库首次启动自动补列（无需手工操作）：

- `model_provider.capabilities`（模型能力，逗号分隔：text/vision）
- `chat_session.model_provider_id`（会话记忆的聊天模型）
- `category.sort_order`（分类同级排序）

### 新增/变更 API（向后兼容）

| 接口 | 说明 |
|---|---|
| `POST /api/kbs/{kbId}/documents/upload` | 详细上传：返回 `{ok:[文档], failed:[{fileName,reason}]}`（原 `/documents` 保留） |
| `POST /api/providers/{id}/status` | 启用/停用（默认聊天/向量不可停用） |
| `PUT /api/chat/sessions/{id}` | 会话重命名 |
| `POST /api/chat/sessions/{id}/model` | 会话记忆聊天模型 |
| `POST /api/graph/multi/data` / `/multi/stats` | 多知识库合并图谱数据/统计 |
| `POST /api/categories/reorder` | 分类同级拖拽排序 |
| `POST /api/settings/reset`（可带 `{key}`） | 全部或单键恢复默认设置 |
