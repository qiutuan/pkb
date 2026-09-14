#!/usr/bin/env bash
# ============================================================
# PKB 冒烟测试：走通 README 快速验收链路（建库→上传→检索）
# 用法: bash smoke-test.sh [BASE_URL]   (默认 http://localhost:8080)
# 要求: 服务已启动；需要 curl
# ============================================================
set -u
BASE="${1:-http://localhost:8080}"
API="$BASE/api"
PASS=0; FAIL=0

ok()   { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ✘ $1"; }

check() { # check <描述> <期望子串> <实际输出>
  if echo "$3" | grep -qF "$2"; then ok "$1"; else bad "$1 → 期望包含「$2」，实际: $(echo "$3" | head -c 160)"; fi
}

echo "== PKB 冒烟测试 @ $BASE =="

# 1. 健康检查
R=$(curl -s -m 5 "$API/system/info")
check "system/info 可访问" '"code":0' "$R"

# 2. 建库（按段落优先）
R=$(curl -s -m 5 -X POST "$API/kbs" -H 'Content-Type: application/json' \
  -d '{"name":"冒烟测试库","description":"smoke","categoryId":null,"embeddingProviderId":null,"chunkStrategy":"paragraph","chunkSize":600,"chunkOverlap":100,"multimodal":false,"graphEnabled":false}')
check "建库成功" '"code":0' "$R"
KBID=$(echo "$R" | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)
[ -z "$KBID" ] && { bad "无法解析知识库 id"; exit 1; }

# 3. 上传中文文档
TMP=$(mktemp -d)
printf '知识图谱是一种基于图的数据结构。RAG 检索增强生成结合向量数据库与图谱召回。中文分词对检索效果至关重要。' > "$TMP/中文.md"
R=$(curl -s -m 15 -X POST "$API/kbs/$KBID/documents/upload" -F "files=@$TMP/中文.md")
check "上传文档成功" '"ok"' "$R"
check "上传无失败" '"failed":[]' "$R"

# 4. 纯文本库拒绝图片
printf 'PNGDATA' > "$TMP/pic.png"
R=$(curl -s -m 15 -X POST "$API/kbs/$KBID/documents/upload" -F "files=@$TMP/pic.png")
check "纯文本库拒绝图片" '纯文本模式' "$R"

# 5. 检索（等待索引完成）
sleep 2
R=$(curl -s -m 15 -X POST "$API/retrieval" -H 'Content-Type: application/json' \
  -d "{\"kbIds\":[$KBID],\"query\":\"知识图谱是什么\",\"topK\":5,\"minScore\":0.1,\"rerank\":\"hybrid\",\"graphRag\":false}")
check "检索命中片段" '"content"' "$R"
check "检索返回来源文档" '中文.md' "$R"

# 6. 会话与消息
R=$(curl -s -m 5 -X POST "$API/chat/sessions" -H 'Content-Type: application/json' -d '{"title":"冒烟会话"}')
check "创建会话" '"code":0' "$R"
SID=$(echo "$R" | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)

# 7. 设置单键恢复
curl -s -m 5 -X POST "$API/settings" -H 'Content-Type: application/json' -d '{"ragTopK":42}' > /dev/null
R=$(curl -s -m 5 -X POST "$API/settings/reset" -H 'Content-Type: application/json' -d '{"key":"ragTopK"}')
check "设置单键恢复" '"code":0' "$R"

# 8. 清理
curl -s -m 5 -X DELETE "$API/kbs/$KBID" > /dev/null
curl -s -m 5 -X DELETE "$API/chat/sessions/$SID" > /dev/null
rm -rf "$TMP"

echo
echo "结果: $PASS 通过, $FAIL 失败"
[ "$FAIL" -eq 0 ] || exit 1
