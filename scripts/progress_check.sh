#!/bin/bash
# ============================================================
# progress_check.sh — 主线任务完成度判定脚本
# 每次定时器触发时第一步运行。输出 [INCOMPLETE] 或 [COMPLETE]
# 及原因/下一步动作。AI 据此决定：未完成 → 继续执行下一步。
# ============================================================
set -uo pipefail
cd "$(dirname "$0")/.."

REPO="dcs666/rikkahub-turbo"
BRANCH="perf/rendering-and-streaming"
TOKEN=$(grep -o 'ghp_[A-Za-z0-9]*' ~/.git-credentials | head -1)
[ -z "$TOKEN" ] && { echo "[ERROR] no GitHub token"; exit 2; }

api() { curl -sS --max-time 25 -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "$1"; }

echo "=== 1. 本地/远程同步 ==="
LOCAL_HEAD=$(git rev-parse HEAD 2>/dev/null)
REMOTE_HEAD=$(api "https://api.github.com/repos/$REPO/commits/$BRANCH" | python3 -c "import json,sys; print(json.load(sys.stdin).get('sha',''))" 2>/dev/null)
if [ "$LOCAL_HEAD" = "$REMOTE_HEAD" ]; then echo "SYNC: local==remote ($(echo $LOCAL_HEAD | cut -c1-7))"
else echo "DESYNC: local=$(echo $LOCAL_HEAD | cut -c1-7) remote=$(echo $REMOTE_HEAD | cut -c1-7)"; fi

echo "=== 2. 最新 CI（perf 分支）==="
CI_JSON=$(api "https://api.github.com/repos/$REPO/actions/runs?branch=$BRANCH&per_page=4")
python3 - "$CI_JSON" << 'PYEOF'
import json, sys
d = json.loads(sys.argv[1])
for r in d.get('workflow_runs', [])[:4]:
    print(r['head_sha'][:7], r['name'], r['status'], r.get('conclusion'))
PYEOF

echo "=== 3. 版本状态 ==="
grep -E "versionCode|versionName" app/build.gradle.kts | head -2 | sed 's/^[[:space:]]*//'
git tag --sort=-creatordate 2>/dev/null | head -3 | sed 's/^/TAG: /'

echo "=== 4. 活跃 Release run ==="
RL_JSON=$(api "https://api.github.com/repos/$REPO/actions/runs?event=push&per_page=12")
python3 - "$RL_JSON" << 'PYEOF'
import json, sys
d = json.loads(sys.argv[1])
rels = [r for r in d.get('workflow_runs', []) if r['name'] == 'Release Turbo'][:2]
for r in rels:
    print(r['id'], r['status'], r.get('conclusion'), r['head_sha'][:7])
if not rels:
    print('(none in last 12 push-triggered runs)')
PYEOF

echo "=== 5. 完成度判定 ==="
# 自动判定：本地同步 + CI 全绿 + 无活跃 Release + 最新提交已发布 = COMPLETE
python3 - "$LOCAL_HEAD" "$REMOTE_HEAD" "$CI_JSON" "$RL_JSON" << 'PYEOF'
import json, sys
local_head, remote_head, ci_json, rl_json = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

next_actions = []

# 1. 同步状态
if local_head != remote_head:
    next_actions.append("push（本地有未推送提交）")
else:
    print("SYNC OK")

# 2. CI 状态（只看最新提交的 run）
try:
    runs = json.loads(ci_json).get('workflow_runs', [])
    newest = {}
    for r in runs:
        key = r['name']
        if key not in newest:
            newest[key] = r
    for name, r in newest.items():
        if r['status'] != 'completed':
            next_actions.append(f"wait（{name} 运行中: {r['head_sha'][:7]}）")
        elif r.get('conclusion') != 'success':
            next_actions.append(f"fix（{name} 失败: {r['head_sha'][:7]}）")
except Exception as e:
    next_actions.append(f"ci检查异常（{e}）")

# 3. Release 状态
try:
    rels = [r for r in json.loads(rl_json).get('workflow_runs', []) if r['name'] == 'Release Turbo'][:1]
    for r in rels:
        if r['status'] != 'completed':
            next_actions.append(f"wait（Release {r['id']} 构建中）")
        elif r.get('conclusion') != 'success':
            next_actions.append(f"fix（Release {r['id']} 失败）")
except Exception as e:
    next_actions.append(f"release检查异常（{e}）")

if next_actions:
    print("[INCOMPLETE] next=" + "; ".join(sorted(set(next_actions))))
else:
    print("[COMPLETE] 全部就绪：代码已同步、CI 全绿、无活跃构建")
PYEOF
echo "=== 判定脚本结束 ==="
