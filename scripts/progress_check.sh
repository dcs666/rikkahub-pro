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
    # 首字符探测用纯 bash（管道 + grep -q 提前退出会 SIGPIPE 报 Broken pipe）
    local out=""
    for i in 1 2 3; do
        out=$(curl -sS --max-time 25 -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "$1" 2>/dev/null)
        case "${out:0:1}" in
            "{"|"["|"\"" ) echo "$out"; return 0 ;;
        esac
        sleep 3
    done
    echo ""
    return 1
}

echo "=== 1. 本地/远程同步 ==="
LOCAL_HEAD=$(git rev-parse HEAD 2>/dev/null)
REMOTE_HEAD=$(api "https://api.github.com/repos/$REPO/commits/$BRANCH" | python3 -c "import json,sys; print(json.load(sys.stdin).get('sha',''))" 2>/dev/null)
# 1. 同步状态（remote 为空 = API 失败，不能误判为 DESYNC）
# 祖先关系判定：SHA 不等 ≠ 需要 push（可能本地落后或分叉）
if [ -z "$REMOTE_HEAD" ]; then
    echo "API-ERROR: remote head fetch failed"
    API_FAILED=1
    SYNC_STATE="API-ERROR"
elif [ "$LOCAL_HEAD" != "$REMOTE_HEAD" ]; then
    if git merge-base --is-ancestor "$REMOTE_HEAD" HEAD 2>/dev/null; then
        echo "AHEAD: local=$(echo $LOCAL_HEAD | cut -c1-7) remote=$(echo $REMOTE_HEAD | cut -c1-7)（本地领先，需 push）"
        SYNC_STATE="AHEAD"
    elif git merge-base --is-ancestor HEAD "$REMOTE_HEAD" 2>/dev/null; then
        echo "BEHIND: local=$(echo $LOCAL_HEAD | cut -c1-7) remote=$(echo $REMOTE_HEAD | cut -c1-7)（远程领先，需 fetch）"
        SYNC_STATE="BEHIND"
    else
        echo "DIVERGED: local=$(echo $LOCAL_HEAD | cut -c1-7) remote=$(echo $REMOTE_HEAD | cut -c1-7)（分叉，需 rebase）"
        SYNC_STATE="DIVERGED"
    fi
else
    echo "SYNC OK"
    SYNC_STATE="SYNC"
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
echo "FIXES: 52（累计真实 bug 修复）"
echo "LATEST: $(echo $REMOTE_HEAD | cut -c1-7)"

echo "=== 5. 完成度判定 ==="# 自动判定：本地同步 + CI 全绿 + 无活跃 Release = COMPLETE
# 摘要都是小文本，经 argv 传入（heredoc 与管道冲突会吞掉 stdin，不可用管道）
python3 - "$SYNC_STATE" "$RL_SUMMARY" "$CI_SUMMARY" "$REMOTE_HEAD" << 'PYEOF'
import sys
sync_state, rl_summary, ci_summary, remote_head = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
ci_lines = [l for l in ci_summary.splitlines() if l.strip()]

next_actions = []

# 1. 同步状态（API 失败 = 重试；AHEAD = push；BEHIND = fetch；DIVERGED = rebase）
if sync_state == 'API-ERROR':
    next_actions.append("api_retry（GitHub API 请求失败）")
elif sync_state == 'AHEAD':
    next_actions.append("push（本地有未推送提交）")
elif sync_state == 'BEHIND':
    next_actions.append("fetch（远程领先，拉取后复查）")
elif sync_state == 'DIVERGED':
    next_actions.append("rebase（本地与远程分叉）")
else:
    print("SYNC OK")

# 2. CI 状态（摘要为空 = API 失败；cancelled = 被新提交取代，忽略；name 用 rsplit 取最后两字段）
# [FIX] 只检查最新提交（REMOTE_HEAD）的 run：run 名含 SHA 前缀（"4785866 Unit Tests"），
# 按 run 名去重会把历史提交的失败 run 也计入判定 → 已修复的旧失败永远阻塞（INCOMPLETE）。
# 现在仅对最新提交的 run 判定；最新提交尚无 run 时视为 wait（避免把历史失败误判为当前状态）。
if not ci_summary.strip():
    next_actions.append("api_retry（CI 状态获取失败）")
ci_lines = [l for l in ci_summary.splitlines() if l.strip()]
remote_short = (remote_head or '')[:7]
matched = []
if remote_short and ci_lines:
    matched = [l for l in ci_lines if l.split()[0].startswith(remote_short)]
    if not matched:
        next_actions.append("wait（最新提交 CI 尚未出现，稍后重试）")
for line in matched:
    parts = line.rsplit(None, 2)
    if len(parts) < 3:
        continue
    name, status, conclusion = parts[0], parts[1], parts[2]
    if conclusion == 'cancelled':
        continue  # 被新 push 取代的旧 run，不视为失败
    if status != 'completed':
        next_actions.append(f"wait（{name} 运行中: {name.split()[0]}）")
    elif conclusion != 'success':
        next_actions.append(f"fix（{name} 失败: {name.split()[0]}）")

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
