# PKB 自主迭代晨间报告

> 生成时间：2026-09-15 · 自主全栈工程师模式 · 共 7 轮迭代（R1–R7）
> 远程：https://github.com/qiutuan/pkb.git（main 分支，已同步）

---

## 1. 本轮迭代总览

- **轮次**：7 轮（审计 → 规划 → 实现 → 测试 → 提交 → 打 tag，循环执行）
- **提交**：本地 main 新增 6 个功能提交（R1–R6），R7 为收尾验证与报告
- **新增模块**：1 个（对话导出 Markdown）
- **测试**：单元测试 9 → 22 个用例（+13），全绿；冒烟脚本 9/9 通过；每次提交前 `mvn test` + `mvn -DskipTests package` 均通过
- **tag**：`auto-iter-r1` … `auto-iter-r6`、`auto-iter-final`，可随时回滚

## 2. 修复的问题清单

### 阻断性 Bug
- 无（第三轮交付后系统保持可构建可运行）

### 稳定性（R1/R3）
| 问题 | 严重度 | 修复 | Commit |
|---|---|---|---|
| 上传先 INSERT 再落盘，落盘失败留脏 PENDING 记录 | 中 | 先落盘后入库 | `8f9ea9b` |
| 上传无大小/数量上限，多模态大文件整读有 OOM 风险 | 中 | 可配上限（200MB/50 个）+ multipart 收紧 | `8f9ea9b` |
| 媒体描述 `Files.readAllBytes` 整读大文件 | 中 | 有界读取（>8MB 仅索引文件名） | `2fc7eb6` |
| SSE 中断/出错时已生成内容不落库，历史断裂 | 中 | 中断/出错也持久化部分回答（含引用） | `2fc7eb6` |
| 分块参数后端未校验（与需求口径不符） | 低 | 100–4000 / 0–500 / 重叠<块 后端校验 | `8f9ea9b` |

### 安全性（R2）
| 问题 | 严重度 | 修复 | Commit |
|---|---|---|---|
| 文档下载响应头拼原始文件名，CR/LF 可注入 | 低 | 剔除换行 | `bb66f71` |
| 通过项（无需改） | — | XSS 渲染统一转义、路径穿越 safeFileName、API Key AES-GCM+掩码、SQL 参数化、无 actuator/h2 暴露 | — |

### 测试（R4）
| 改动 | Commit |
|---|---|
| 新增 HybridRerankerTest / CitationsTest / SettingsServiceTest（13 例） | `36c4289` |
| ChatService.citations 改包级静态以便直接测试（行为不变） | `36c4289` |

### 文档（R6）
| 改动 | Commit |
|---|---|
| README 上传路由改为现行主路由、配置块补 pipeline 段 | `2811dbd` |

## 3. 新增功能：对话导出 Markdown（R5）

- **动机**：知识库问答产出需沉淀为文档，导出 Markdown 打通「问答成果 → 本地笔记/报告」，零依赖、符合轻量定位。
- **用法**：对话页 → 左侧会话列表悬停任意会话 → 点击「⤓」按钮 → 浏览器下载 `<会话名>.md`。
- **内容**：会话标题、导出时间、「我 / PKB」分节、引用来源列表（文档名 + 片段定位）。
- **接口**：`GET /api/chat/sessions/{id}/export`（附件下载，`text/markdown`）。
- **Commit**：`c5748f9`；README 功能总览已同步。

## 4. 测试覆盖与构建验证

- `cd backend && mvn test`：**22/22 通过**（HnswIndex / ChunkSplitter / LocalEmbedding / EncryptUtil / HybridReranker / Citations / SettingsService）
- `mvn -DskipTests package`：成功，`backend/target/pkb.jar` 可运行
- `bash smoke-test.sh http://127.0.0.1:PORT`：**9/9 通过**（建库→上传→拒图→检索→会话→单键恢复→清理）
- 手动验证：上传数量/大小限制文案、分块参数校验、SSE 持久化、导出接口内容

## 5. 已知遗留问题 / 建议关注

1. **新增 Provider 类型成本**：需改 `ModelFactory` 的 chat/streaming/embedding 三个 switch + 前端类型下拉（约 4 处）。当前 5 类够用，暂不引入注册表（避免过度设计）。
2. **Tomcat multipart 框架级限制**：单请求 part 数约 51+ 时框架先于业务校验报错。已把业务上限调到 50 并用清晰文案覆盖，若未来需要单次传更多文件，需调研容器级参数（如 maxPostSize / maxPartCount）。
3. **无鉴权**：单机个人工具设计，局域网暴露需自行加反代鉴权（README 有说明）。
4. **图谱抽取失败无自动重试**：失败批次在界面可手动重试；如需定时自动重试可后续加。
5. **对话导出仅覆盖已保存消息**：SSE 中断时只保留已生成部分（有界，符合预期）。

## 6. 回滚指南

```bash
git fetch origin
git log --oneline origin/main          # 查看提交
git tag -l 'auto-iter-*'               # 查看迭代 tag

# 回滚到某一轮（如 R3 之后）：
git checkout auto-iter-r3              # 检出该轮状态（或 git switch -c hotfix auto-iter-r3）
# 或回退 main 到某提交并强制更新本地（不 force push）：
git reset --hard auto-iter-r3 && git push origin main   # 需 force，谨慎；推荐用 checkout 分支方式
```

**tag 对照**：

| Tag | 对应提交 | 内容 |
|---|---|---|
| `auto-iter-r1` | `8f9ea9b` | 上传链路健壮性 |
| `auto-iter-r2` | `bb66f71` | 安全：响应头注入 |
| `auto-iter-r3` | `2fc7eb6` | 稳定性：SSE 持久化 + 有界读取 |
| `auto-iter-r4` | `36c4289` | 测试补强（22 例） |
| `auto-iter-r5` | `c5748f9` | 新增：会话导出 Markdown |
| `auto-iter-r6` | `2811dbd` | 文档一致性 |
| `auto-iter-final` | `2811dbd` | 最终交付点（与 r6 相同，收尾验证通过） |

> 注：`auto-iter-final` 打在最后一个提交上；工作区干净、全部测试通过。
