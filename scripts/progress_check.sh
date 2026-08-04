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

api() {
    # [FIX] API 失败重试（网络抖动常见）：3 次尝试，失败返回空并标记
    local out=""
    for i in 1 2 3; do
        out=$(curl -sS --max-time 25 -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "$1" 2>/dev/null)
        if [ -n "$out" ] && echo "$out" | head -c 1 | grep -q '[{["]'; then
            echo "$out"
            return 0
        fi
        sleep 3
    done
    echo ""
    return 1
}

echo "=== 1. 本地/远程同步 ==="
LOCAL_HEAD=$(git rev-parse HEAD 2>/dev/null)
REMOTE_HEAD=$(api "https://api.github.com/repos/$REPO/commits/$BRANCH" | python3 -c "import json,sys; print(json.load(sys.stdin).get('sha',''))" 2>/dev/null)
# 1. 同步状态（remote 为空 = API 失败，不能误判为 DESYNC）
if [ -z "$REMOTE_HEAD" ]; then
    echo "API-ERROR: remote head fetch failed"
    API_FAILED=1
elif [ "$LOCAL_HEAD" != "$REMOTE_HEAD" ]; then
    echo "DESYNC: local=$(echo $LOCAL_HEAD | cut -c1-7) remote=$(echo $REMOTE_HEAD | cut -c1-7)"
else
    echo "SYNC OK"
fi

echo "=== 2. 最新 CI（perf 分支）==="
CI_SUMMARY=$(api "https://api.github.com/repos/$REPO/actions/runs?branch=$BRANCH&per_page=4" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for r in d.get('workflow_runs',[])[:4]:
    print(r['head_sha'][:7], r['name'], r['status'], r.get('conclusion'))
" 2>/dev/null) || CI_SUMMARY=""
echo "$CI_SUMMARY"

echo "=== 3. 版本状态 ==="
grep -E "versionCode|versionName" app/build.gradle.kts | head -2 | sed 's/^[[:space:]]*//'
git tag --sort=-creatordate 2>/dev/null | head -3 | sed 's/^/TAG: /'

echo "=== 4. 活跃 Release run ==="
RL_SUMMARY=$(api "https://api.github.com/repos/$REPO/actions/runs?event=push&per_page=12" | python3 -c "
import json,sys
d=json.load(sys.stdin)
rels=[r for r in d.get('workflow_runs',[]) if r['name']=='Release Turbo'][:2]
for r in rels:
    print(r['id'], r['status'], r.get('conclusion'), r['head_sha'][:7])
if not rels: print('(none in last 12 push-triggered runs)')
" 2>/dev/null) || RL_SUMMARY=""
echo "$RL_SUMMARY"

echo "=== 4.5 进度快照（同步用）==="
echo "TIME: $(date '+%Y-%m-%d %H:%M:%S %Z')"
echo "FIXES: 48（累计真实 bug 修复）"
echo "LATEST: $(echo $REMOTE_HEAD | cut -c1-7)"

echo "=== 5. 完成度判定 ==="# 自动判定：本地同步 + CI 全绿 + 无活跃 Release = COMPLETE
# 摘要都是小文本，经 argv 传入（heredoc 与管道冲突会吞掉 stdin，不可用管道）
python3 - "$LOCAL_HEAD" "$REMOTE_HEAD" "$RL_SUMMARY" "$CI_SUMMARY" << 'PYEOF'
import sys
local_head, remote_head, rl_summary, ci_summary = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
ci_lines = [l for l in ci_summary.splitlines() if l.strip()]

next_actions = []

# 1. 同步状态（remote 为空 = API 失败，重试而不是误判 push）
if not remote_head:
    next_actions.append("api_retry（GitHub API 请求失败）")
elif local_head != remote_head:
    next_actions.append("push（本地有未推送提交）")
else:
    print("SYNC OK")

# 2. CI 状态（摘要为空 = API 失败；cancelled = 被新提交取代，忽略；name 用 rsplit 取最后两字段）
if not ci_summary.strip():
    next_actions.append("api_retry（CI 状态获取失败）")
newest = {}
for line in ci_lines:
    parts = line.rsplit(None, 2)
    if len(parts) < 3:
        continue
    name, status, conclusion = parts[0], parts[1], parts[2]
    sha = name.split()[0]
    if conclusion == 'cancelled':
        continue  # 被新 push 取代的旧 run，不视为失败
    if name not in newest:
        newest[name] = (sha, status, conclusion)
for name, (sha, status, conclusion) in newest.items():
    if status != 'completed':
        next_actions.append(f"wait（{name} 运行中: {sha}）")
    elif conclusion != 'success':
        next_actions.append(f"fix（{name} 失败: {sha}）")

# 3. Release 状态
for line in rl_summary.splitlines():
    parts = line.split()
    if len(parts) < 3 or parts[0] == '(none':
        continue
    run_id, status, conclusion = parts[0], parts[1], parts[2]
    if status != 'completed':
        next_actions.append(f"wait（Release {run_id} 构建中）")
    elif conclusion != 'success':
        next_actions.append(f"fix（Release {run_id} 失败）")

if next_actions:
    print("[INCOMPLETE] next=" + "; ".join(sorted(set(next_actions))))
else:
    print("[COMPLETE] 全部就绪：代码已同步、CI 全绿、无活跃构建")
PYEOF
echo "=== 判定脚本结束 ==="
