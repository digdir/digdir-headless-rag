#!/usr/bin/env bash
#
# The box-side deploy agent. Design and reasoning: plans/proposed/box-deploy-agent.md
#
# Deploys `test` when the green marker ref moves forward. Holds no GitHub API
# token: the signal arrives over git via a read-only, single-repository deploy
# key. Polls — it does not listen — so it recovers from its own downtime, and so
# there is no inbound endpoint on this host.
#
# ⚠️ EVERY CHECK THAT CANNOT ANSWER MUST REFUSE, NEVER PASS. Where control goes
# when a check cannot answer is the whole safety property; see `is_ancestor`.
set -euo pipefail

BRANCH_REF="${DEPLOY_AGENT_BRANCH:-refs/remotes/origin/release-v0.1-details}"
MARKER_REF="${DEPLOY_AGENT_MARKER:-refs/remotes/origin/deploy/test-green}"
REPO_DIR="${DEPLOY_AGENT_REPO:?repo path required}"
# Must survive a deploy — see "Where the store lives is a correctness property".
STORE_DIR="${DEPLOY_AGENT_STORE:?store path required}"
LOCK_FILE="${DEPLOY_AGENT_LOCK:-$STORE_DIR/agent.lock}"
MAX_ATTEMPTS="${DEPLOY_AGENT_MAX_ATTEMPTS:-3}"
# An ARRAY, not a string: relying on word-splitting to turn one variable into
# several arguments is fragile and shellcheck-flagged (SC2086).
CONTAINER_FILTER=(--filter label=service=digdir-headless-rag
                  --filter label=destination=test
                  --filter label=role=web)

# ---------------------------------------------------------------------------
# Reporting. The URL is a credential: never echoed, never in argv.
# curl reads it from the environment, and only its hash prefix is ever printed.
# ---------------------------------------------------------------------------
url_fingerprint() {
  local v; v="${DEPLOY_AGENT_WEBHOOK_URL:-}"
  printf 'present=%s len=%s sha256[0:8]=%s' \
    "${v:+true}" "${#v}" "$(printf %s "$v" | shasum -a 256 | cut -c1-8)"
}

report() { # report <level> <message>
  local level="$1" msg="$2"
  printf '[%s] %s\n' "$level" "$msg" >&2
  # --data @- keeps the payload off argv too; the URL is read from the env by
  # curl's --url-from-stdin-free form below, never interpolated into a command line.
  printf '{"text":"[deploy-agent/%s] %s"}' "$level" "$msg" \
    | curl -sS -X POST -H 'Content-type: application/json' --data @- "$DEPLOY_AGENT_WEBHOOK_URL" >/dev/null \
    || printf '[error] webhook post failed (%s)\n' "$(url_fingerprint)" >&2
}

refuse() { report refuse "$1"; exit 10; }

# ---------------------------------------------------------------------------
# STARTUP PRECONDITIONS. Each is a reason to REFUSE TO START, not to warn.
# ---------------------------------------------------------------------------
startup_checks() {
  # ⛔ The webhook is a precondition, not an option. An agent with no way to
  # report refusals is precisely the silent failure this design exists to
  # prevent, so it declines to run rather than running mutely.
  if [[ -z "${DEPLOY_AGENT_WEBHOOK_URL:-}" ]]; then
    printf '[fatal] DEPLOY_AGENT_WEBHOOK_URL is not set. Refusing to start: an agent that cannot report refusals fails silently, which is the failure mode this design exists to prevent.\n' >&2
    exit 78
  fi
  # NOT `[[ -d .git ]]`: in a git WORKTREE (and a submodule) .git is a FILE, so
  # the directory test rejects a perfectly good repository. Ask git instead.
  git -C "$REPO_DIR" rev-parse --git-dir >/dev/null 2>&1 \
    || { printf '[fatal] no git repository at %s\n' "$REPO_DIR" >&2; exit 78; }

  # Sufficient history is a prerequisite of BOTH ancestry and monotonicity, and
  # a shallow clone does not error on merge-base — it can answer wrongly. This
  # is the stage-1 failure one layer down, so it is asserted rather than assumed.
  if [[ -f "$REPO_DIR/.git/shallow" ]] || [[ "$(git -C "$REPO_DIR" rev-parse --is-shallow-repository)" == "true" ]]; then
    report fatal "the clone at $REPO_DIR is SHALLOW. merge-base cannot answer reliably on a shallow clone, and the depth required is set by the OLDEST thing compared against — the running container's commit, which may predate any truncation. Refusing to start."
    exit 78
  fi
  [[ -d "$STORE_DIR" ]] || { report fatal "failed-build store $STORE_DIR is absent. An absent store is a missing precondition, not an empty one — creating it is a deliberate act through the override path. Refusing to start."; exit 78; }
  command -v kamal >/dev/null || { report fatal "kamal not on PATH"; exit 78; }
  command -v docker >/dev/null || { report fatal "docker not on PATH"; exit 78; }
}

# ---------------------------------------------------------------------------
# Three-valued ancestry. 0 = yes, 1 = no, 2 = COULD NOT EVALUATE.
# Exit 1 and exit 128 are different answers and must not share a branch: one is
# "answered: no", the other is "the commit is not in this clone at all".
# ---------------------------------------------------------------------------
is_ancestor() { # is_ancestor <maybe-ancestor> <descendant>
  local rc=0
  git -C "$REPO_DIR" merge-base --is-ancestor "$1" "$2" >/dev/null 2>&1 || rc=$?
  case "$rc" in 0) return 0 ;; 1) return 1 ;; *) return 2 ;; esac
}

deployed_baseline() {
  docker ps "${CONTAINER_FILTER[@]}" --format '{{.Image}}' 2>/dev/null \
    | head -1 | sed 's/.*://'
}

tick() {
  # 1. THE FETCH IS A PREREQUISITE OF EVERY CHECK BELOW, not one of them.
  # Checked for SUCCESS, not for output: proceeding on a previous fetch's data
  # makes every check answer correctly about a stale world, and presents as an
  # idle agent rather than a broken one.
  git -C "$REPO_DIR" fetch --quiet origin \
      '+refs/heads/release-v0.1-details:refs/remotes/origin/release-v0.1-details' \
      '+refs/heads/deploy/test-green:refs/remotes/origin/deploy/test-green' \
    || refuse "git fetch failed — refusing rather than acting on the previous fetch's data"

  local marker branch baseline
  marker="$(git -C "$REPO_DIR" rev-parse --verify "$MARKER_REF^{commit}" 2>/dev/null)" \
    || refuse "marker ref $MARKER_REF does not resolve"
  branch="$(git -C "$REPO_DIR" rev-parse --verify "$BRANCH_REF^{commit}" 2>/dev/null)" \
    || refuse "branch ref $BRANCH_REF does not resolve"

  # 3. Baseline from the RUNNING CONTAINER — ground truth, never a state file.
  baseline="$(deployed_baseline)"
  [[ -n "$baseline" ]] \
    || refuse "no running test container, so there is no baseline. This is a missing precondition, not permission — and note the agent is deliberately NOT a recovery mechanism: bringing up a dead service is a human action."

  # 4. A non-SHA tag cannot be compared. Observed in the wild: this service ran
  # a :latest-test tag for three months.
  [[ "$baseline" =~ ^[0-9a-f]{40}$ ]] \
    || refuse "deployed image tag '$baseline' is not a commit SHA, so monotonicity cannot be evaluated"

  # 5. ANCESTRY — phrased so the FAILING branch refuses.
  case "$(is_ancestor "$marker" "$branch"; echo $?)" in
    0) : ;;
    1) refuse "marker ${marker:0:12} is not an ancestor of the branch — refusing" ;;
    *) refuse "cannot evaluate ancestry for ${marker:0:12}; the commit is not in this clone" ;;
  esac

  # 6. MONOTONICITY — is the marker a DESCENDANT of what is running?
  # Phrased as is_ancestor(baseline, marker) so a false NO on a truncated clone
  # falls into REFUSE. The equivalent-looking is_ancestor(marker, baseline)
  # inverted would fall into PROCEED and silently permit a downgrade.
  case "$(is_ancestor "$baseline" "$marker"; echo $?)" in
    0) : ;;
    1) refuse "marker ${marker:0:12} does not descend from deployed ${baseline:0:12} — refusing to move backward. Rolling back is a deliberate human action." ;;
    *) refuse "cannot evaluate monotonicity; deployed ${baseline:0:12} is not in this clone" ;;
  esac

  # 7. Already deployed.
  [[ "$marker" != "$baseline" ]] || { printf '[ok] up to date at %s\n' "${marker:0:12}" >&2; return 0; }

  # 8. CAPPED RETRIES. Bounds both sides without needing to tell a broken commit
  # from a broken environment — which is not reliably possible, since both are a
  # non-zero exit from the same kamal invocation.
  local attempts_file="$STORE_DIR/attempts-$marker" attempts=0
  [[ -f "$attempts_file" ]] && attempts="$(cat "$attempts_file")"
  if (( attempts >= MAX_ATTEMPTS )); then
    printf '[ok] %s exhausted %s attempts; waiting for a new marker\n' "${marker:0:12}" "$MAX_ATTEMPTS" >&2
    return 0
  fi

  # 9. Deploy. TEST ONLY.
  printf '%s' "$((attempts + 1))" > "$attempts_file"
  report info "deploying ${marker:0:12} over ${baseline:0:12} (attempt $((attempts + 1))/$MAX_ATTEMPTS)"
  if git -C "$REPO_DIR" -c advice.detachedHead=false checkout --quiet "$marker" \
     && kamal deploy --config-file=deploy.yml --destination=test; then
    rm -f "$attempts_file"
    report info "deployed ${marker:0:12}"
  else
    if (( attempts + 1 >= MAX_ATTEMPTS )); then
      report alert "deploy of ${marker:0:12} FAILED and has exhausted $MAX_ATTEMPTS attempts. No further attempts until the marker moves."
    else
      report alert "deploy of ${marker:0:12} failed (attempt $((attempts + 1))/$MAX_ATTEMPTS); will retry"
    fi
  fi
}

main() {
  startup_checks
  # One decision at a time. The race is in the READ, not the write: two agents
  # could each read the same baseline, each pass monotonicity, and each act.
  # Kamal's own lock would not prevent that — by the time it engages, both have
  # already decided.
  exec 9>"$LOCK_FILE"
  # EXITING QUIETLY HERE IS DELIBERATE. Reporting every overlap would put routine
  # traffic on the alert path, which is what makes a channel ignored. A lock held
  # by a KILLED process is not a hazard — flock releases on process death. The
  # remaining case, a live-but-stuck agent, is covered at the SYSTEM level: it
  # stops advancing the deployment, so the tip-versus-deployed check goes red.
  # Do not add a "report if skipped N times" counter here: it would ask a
  # possibly-wedged process to report its own wedging, and needs state with the
  # same absent-versus-empty problem as the failed-build store.
  flock -n 9 || { printf '[ok] another tick holds the lock; exiting\n' >&2; exit 0; }
  cd "$REPO_DIR"
  tick
}

main "$@"
