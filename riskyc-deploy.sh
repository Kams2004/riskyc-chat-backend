#!/usr/bin/env bash
#
# riskyc-deploy.sh — start/stop/restart/redeploy the RiskyC Chat server stack.
#
# DISTRIBUTION COPY: this file is checked into the backend repo purely so it
# can be fetched with a plain `git pull` on a server that already has this
# repo cloned. It does NOT run from here — after pulling, copy it up one
# directory to sit beside the frontend clone (see "Server setup" below), then
# run it from THERE. This copy existing inside the repo is a distribution
# convenience, not where it operates from.
#
# Lives ONE LEVEL ABOVE the three deployed repos, expecting this layout:
#
#   riskyc-chat/                     <- "root of the chat project" on the server
#     riskyc-deploy.sh               <- this file
#     riskyc-chat-backend/           <- git clone of github.com/Kams2004/riskyc-chat-backend
#       docker-compose.yml           <-    (includes admin-service — no separate target for it;
#                                            it deploys as part of "backend")
#     riskyc-chat-frontend/          <- git clone of github.com/Kams2004/riskyc-chat-frontend
#       docker-compose.yml
#     riskyc-front-admin/            <- git clone of github.com/Kams2004/riskyc-front-admin
#       docker-compose.yml           <-    (the admin DASHBOARD's own frontend — a separate stack
#                                            from admin-service, which lives inside backend above)
#
# It never lives INSIDE any of the three repos on purpose: `git pull` in any
# clone must never touch, overwrite, or delete it, and it needs to
# orchestrate all three stacks together. Override the paths via
# RISKYC_BACKEND_DIR / RISKYC_FRONTEND_DIR / RISKYC_ADMIN_WEB_DIR if your
# clone directory names differ.
#
# Usage:
#   ./riskyc-deploy.sh start    [backend|frontend|admin-web|all]   # docker-compose up -d (no rebuild)
#   ./riskyc-deploy.sh stop     [backend|frontend|admin-web|all]   # docker-compose down (keeps volumes/data)
#   ./riskyc-deploy.sh restart  [backend|frontend|admin-web|all]   # full teardown (removes containers/networks,
#                                                                   # keeps named volumes) + rebuild + start
#   ./riskyc-deploy.sh pull     [backend|frontend|admin-web|all]   # git pull --ff-only in the target repo(s)
#   ./riskyc-deploy.sh deploy   [backend|frontend|admin-web|all]   # pull, then restart — the one-shot "ship it"
#   ./riskyc-deploy.sh status                                      # docker-compose ps for all three stacks
#   ./riskyc-deploy.sh logs     <backend|frontend|admin-web> [service] [-f]
#   ./riskyc-deploy.sh wipe     [backend|frontend|admin-web|all] --confirm
#                                                         # DESTRUCTIVE: also deletes named volumes
#                                                         # (Postgres data, MinIO objects, Redis). Only
#                                                         # for "burn it down and start over" — requires
#                                                         # --confirm, never runs as part of restart/deploy.
#
# Examples:
#   ./riskyc-deploy.sh deploy backend     # pull latest backend code (incl. admin-service), rebuild, restart
#   ./riskyc-deploy.sh deploy admin-web   # pull latest admin dashboard frontend, rebuild, restart just that
#   ./riskyc-deploy.sh deploy             # same, but for all three stacks
#   ./riskyc-deploy.sh restart frontend   # redeploy from whatever's already checked out, no git pull
#   ./riskyc-deploy.sh logs backend messaging-service -f
#   ./riskyc-deploy.sh logs backend admin-service -f

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
BACKEND_DIR="${RISKYC_BACKEND_DIR:-$SCRIPT_DIR/riskyc-chat-backend}"
FRONTEND_DIR="${RISKYC_FRONTEND_DIR:-$SCRIPT_DIR/riskyc-chat-frontend}"
ADMIN_WEB_DIR="${RISKYC_ADMIN_WEB_DIR:-$SCRIPT_DIR/riskyc-front-admin}"

# Prefer the modern "docker compose" plugin, fall back to the classic
# standalone "docker-compose" binary — whichever this host actually has.
if docker compose version &>/dev/null; then
  DC=(docker compose)
elif command -v docker-compose &>/dev/null; then
  DC=(docker-compose)
else
  echo "Neither 'docker compose' nor 'docker-compose' is available on this host." >&2
  exit 1
fi

color() { printf '\033[%sm%s\033[0m\n' "$1" "$2"; }
info()  { color '1;36' "==> $*"; }
warn()  { color '1;33' "!!  $*"; }
err()   { color '1;31' "xx  $*" >&2; }

usage() {
  sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' | head -n -1
  exit 1
}

dir_for() {
  case "$1" in
    backend) echo "$BACKEND_DIR" ;;
    frontend) echo "$FRONTEND_DIR" ;;
    admin-web) echo "$ADMIN_WEB_DIR" ;;
    *) err "Unknown target '$1' — expected 'backend', 'frontend', or 'admin-web'"; exit 1 ;;
  esac
}

targets_for() {
  case "${1:-all}" in
    all) echo "backend frontend admin-web" ;;
    backend|frontend|admin-web) echo "$1" ;;
    *) err "Unknown target '$1' — expected 'backend', 'frontend', 'admin-web', or 'all'"; exit 1 ;;
  esac
}

require_dir() {
  local dir="$1"
  if [[ ! -d "$dir" ]]; then
    err "Expected a repo checkout at: $dir"
    err "Set RISKYC_BACKEND_DIR / RISKYC_FRONTEND_DIR / RISKYC_ADMIN_WEB_DIR if your clones live elsewhere."
    exit 1
  fi
}

cmd_start() {
  for t in $(targets_for "${1:-all}"); do
    local dir; dir="$(dir_for "$t")"
    require_dir "$dir"
    info "Starting $t ($dir)"
    (cd "$dir" && "${DC[@]}" up -d)
  done
}

cmd_stop() {
  for t in $(targets_for "${1:-all}"); do
    local dir; dir="$(dir_for "$t")"
    require_dir "$dir"
    info "Stopping $t ($dir) — containers/networks removed, named volumes kept"
    (cd "$dir" && "${DC[@]}" down --remove-orphans)
  done
}

cmd_restart() {
  for t in $(targets_for "${1:-all}"); do
    local dir; dir="$(dir_for "$t")"
    require_dir "$dir"
    info "Restarting $t ($dir): clearing existing containers, rebuilding, redeploying"
    (cd "$dir" && "${DC[@]}" down --remove-orphans)
    (cd "$dir" && "${DC[@]}" up -d --build)
  done
}

cmd_pull() {
  for t in $(targets_for "${1:-all}"); do
    local dir; dir="$(dir_for "$t")"
    require_dir "$dir"
    info "Pulling latest for $t ($dir)"
    if ! (cd "$dir" && git pull --ff-only); then
      err "git pull --ff-only failed in $dir — likely local changes or a diverged branch."
      err "Resolve it by hand (git status/git log in that directory), then re-run."
      exit 1
    fi
  done
}

cmd_deploy() {
  local target="${1:-all}"
  cmd_pull "$target"
  cmd_restart "$target"
  info "Deploy complete for: $target"
}

cmd_status() {
  for t in backend frontend admin-web; do
    local dir; dir="$(dir_for "$t")"
    if [[ -d "$dir" ]]; then
      info "Status: $t"
      (cd "$dir" && "${DC[@]}" ps)
    else
      warn "$t not found at $dir — skipped"
    fi
  done
}

cmd_logs() {
  local target="${1:-}"
  [[ -z "$target" ]] && { err "logs needs a target: backend, frontend, or admin-web"; usage; }
  local dir; dir="$(dir_for "$target")"
  require_dir "$dir"
  shift
  (cd "$dir" && "${DC[@]}" logs "$@")
}

cmd_wipe() {
  local target="${1:-all}"
  local confirm="${2:-}"
  if [[ "$confirm" != "--confirm" ]]; then
    err "wipe deletes named volumes too — Postgres data, MinIO objects, Redis, everything."
    err "Re-run as: ./riskyc-deploy.sh wipe $target --confirm"
    exit 1
  fi
  for t in $(targets_for "$target"); do
    local dir; dir="$(dir_for "$t")"
    require_dir "$dir"
    warn "WIPING $t ($dir) — containers, networks, AND named volumes"
    (cd "$dir" && "${DC[@]}" down --remove-orphans --volumes)
  done
}

main() {
  local action="${1:-}"
  [[ -z "$action" ]] && usage
  shift || true
  case "$action" in
    start)   cmd_start "${1:-all}" ;;
    stop)    cmd_stop "${1:-all}" ;;
    restart) cmd_restart "${1:-all}" ;;
    pull)    cmd_pull "${1:-all}" ;;
    deploy)  cmd_deploy "${1:-all}" ;;
    status)  cmd_status ;;
    logs)    cmd_logs "$@" ;;
    wipe)    cmd_wipe "${1:-all}" "${2:-}" ;;
    -h|--help|help) usage ;;
    *) err "Unknown command: $action"; usage ;;
  esac
}

main "$@"
