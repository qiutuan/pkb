# PKB 自主迭代日志

> 自主全栈工程师模式运行记录。每轮：审计 → 规划 → 实现 → 测试 → 提交 → 打 tag。

## R1 — 功能正确性审计（2026-09-15）

### 发现问题
1. **（中）上传脏记录风险**：`DocumentController.uploadOne` 先 INSERT 数据库再 `file.transferTo` 落盘，
   若落盘失败会留下永远 PENDING 的脏文档记录，且无法重试（文件不存在）。
2. **（中）上传无大小/数量上限**：多模态视频等大文件会被 `DocumentPipelineService.describeMedia`
   整读进内存（`Files.readAllBytes`），存在 OOM 风险；单次上传数量亦无限制。
3. **（低）后端分块参数未校验**：`KnowledgeBaseService.save` 只做下限 50 兜底，与需求口径
   （分块 100–4000、重叠 0–500 且 < 分块大小）不一致，前端已校验但后端缺失防线。

### 改动清单
- `fix: 上传先落盘后入库，杜绝 transferTo 失败产生的脏 PENDING 记录`
- `feat: 上传限制（可配单文件上限默认 200MB / 单次数量上限默认 100），新增 pkb.pipeline.max-upload-mb / max-upload-files 配置`
- `fix: 后端分块参数校验对齐需求（100–4000、0–500、重叠 < 分块大小）`
- `test: 新增 smoke-test.sh 冒烟脚本（建库→上传→拒绝图片→检索→会话→单键恢复→清理）`

### 测试结果
- `mvn test`：9/9 通过
- `bash smoke-test.sh`：走通 README 快速验收链路

## R2 — 安全性审计（2026-09-15）

### 审计结论
- **XSS**：前端 Markdown 渲染先整体转义再做标记替换（md.js esc）；会话标题/文档名/Provider 名等用户内容渲染均经 Util.esc —— 未发现可利用注入点。
- **路径穿越**：`TextUtil.safeFileName` 剔除 `\/:*?"<>|` 与空白，磁盘路径安全。
- **API Key**：AES-GCM 加密落盘（.secret），视图仅返回掩码；未发现日志打印明文 Key。
- **暴露面**：无 actuator / h2-console；单机单用户无 CORS 配置（同源访问，接受为设计）。
- **SQL 注入**：全部 JDBC 参数化。
- **发现 1 个低危 Bug**：`/documents/{id}/file` 的 Content-Disposition 直接拼原始文件名，
  文件名含 CR/LF 时可响应头注入 —— 已修复（剔除换行）。

### 改动清单
- `fix: 文档下载响应头剔除 CR/LF，防响应头注入`

## R3 — 稳定性与健壮性审计（2026-09-15）

### 发现问题
1. **（中）SSE 中断丢历史**：客户端断开或生成中途出错时，已生成的部分回答不落库，
   历史里只剩用户消息，形成断裂上下文。
2. **（中）媒体描述 OOM 风险**：`describeMedia` 用 `Files.readAllBytes` 整读媒体文件，
   多模态库上传大视频时会把整个文件读进堆内存。

### 改动清单
- `fix: SSE 中断/出错时仍持久化已生成内容（含引用解析），保证对话历史完整`
- `fix: 媒体描述改为有界读取（>8MB 仅索引文件名，防 OOM）`
- 审计通过项：SSE send 的 IOException 已有 finished 标记防重发；流水线失败状态可重试；图谱抽取尽力而为不阻塞入库。

### 测试结果
- `mvn test`：9/9 通过

## R4 — 测试覆盖补强（2026-09-15）

### 审计发现
- 核心逻辑测试缺口：重排（HybridReranker）、引用解析（ChatService.citations）、设置读写（SettingsService）均无单测。
- `ChatService.citations` 为 private，改为包级静态方法以便直接测试（不改 API 行为）。

### 改动清单
- `test: 新增 13 个用例（22 绿）`：
  - HybridRerankerTest（关键词加权排序、topK 截断、负分钳位、空输入）
  - CitationsTest（区间内引用、越界/非数字忽略、去重、预览截断 200 字）
  - SettingsServiceTest（默认值、round-trip、resetKey 恢复默认、损坏文件容错）

### 测试结果
- `mvn test`：22/22 通过；`smoke-test.sh`：9/9 通过
