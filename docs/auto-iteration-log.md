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
