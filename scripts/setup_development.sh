#!/usr/bin/env bash
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REQUIRED_DIRS=(
  app bin config credentials docs docs/reference evidence logs
  messages/native messages/normalized messages/rejected messages/replay
  schemas scripts services tests/unit tests/integration tests/security
  tests/replay tools
)

if [[ "${EUID}" -eq 0 ]]; then
  echo 'Refusing to run as root; this is a development-only setup.' >&2
  exit 77
fi

for relative_dir in "${REQUIRED_DIRS[@]}"; do
  mkdir -p "${REPO_ROOT}/${relative_dir}"
done

chmod 0700 "${REPO_ROOT}/credentials"
chmod 0750 "${REPO_ROOT}/logs" \
  "${REPO_ROOT}/messages/native" \
  "${REPO_ROOT}/messages/normalized" \
  "${REPO_ROOT}/messages/rejected" \
  "${REPO_ROOT}/messages/replay"

if [[ "${1:-}" == '--create-venv' ]]; then
  python3 -m venv "${REPO_ROOT}/.venv"
elif [[ -n "${1:-}" ]]; then
  echo 'Usage: scripts/setup_development.sh [--create-venv]' >&2
  exit 64
fi

echo 'SHAKEALERT LAB — NO OPERATIONAL OUTPUTS'
echo "Development structure ready at ${REPO_ROOT}"
echo 'No packages, services, users, network rules, or external connections changed.'

