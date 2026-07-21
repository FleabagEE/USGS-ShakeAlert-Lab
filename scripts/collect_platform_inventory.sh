#!/usr/bin/env bash
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly OUTPUT="${1:-${REPO_ROOT}/evidence/platform-inventory.txt}"

case "${OUTPUT}" in
  "${REPO_ROOT}"/*) ;;
  *) echo 'Output must remain inside the development repository.' >&2; exit 64 ;;
esac

umask 027
mkdir -p "$(dirname "${OUTPUT}")"
{
  echo 'SHAKEALERT LAB — NO OPERATIONAL OUTPUTS'
  echo "collected_utc=$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
  echo "architecture=$(uname -m)"
  echo "kernel=$(uname -r)"
  if [[ -r /etc/os-release ]]; then
    . /etc/os-release
    echo "os=${PRETTY_NAME:-unknown}"
  fi
  echo "python=$(python3 --version 2>&1 || echo 'not installed')"
  echo "java=$(java -version 2>&1 | head -n 1 || echo 'not installed')"
  echo "openssl=$(openssl version 2>&1 || echo 'not installed')"
  echo "timezone=$(timedatectl show -p Timezone --value 2>/dev/null || date +%Z)"
  echo
  echo '[timedatectl]'
  timedatectl status 2>&1 || true
  echo
  echo '[chronyc tracking]'
  chronyc tracking 2>&1 || true
  echo
  echo '[chronyc sources]'
  chronyc sources -v 2>&1 || true
  echo
  echo '[baseline command availability]'
  for command_name in curl jq git openssl chronyc python3 tcpdump nc dig traceroute lsof logger logrotate unzip auditctl; do
    if command -v "${command_name}" >/dev/null 2>&1; then
      echo "${command_name}=available"
    else
      echo "${command_name}=missing"
    fi
  done
} >"${OUTPUT}"
chmod 0640 "${OUTPUT}"
echo "Wrote sanitized local inventory to ${OUTPUT}"

