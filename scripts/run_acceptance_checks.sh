#!/usr/bin/env bash
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly OUTPUT="${REPO_ROOT}/evidence/phase-0-1-validation.txt"
umask 027

set +e
"${REPO_ROOT}/tests/security/verify_phase0_phase1.sh" 2>&1 | tee "${OUTPUT}"
status=${PIPESTATUS[0]}
if [[ "${1:-}" == '--host' ]]; then
  "${REPO_ROOT}/tests/security/verify_host_baseline.sh" 2>&1 | tee -a "${OUTPUT}"
  host_status=${PIPESTATUS[0]}
  if [[ "${host_status}" -ne 0 ]]; then
    status="${host_status}"
  fi
elif [[ -n "${1:-}" ]]; then
  echo 'Usage: scripts/run_acceptance_checks.sh [--host]' >&2
  exit 64
fi
set -e
chmod 0640 "${OUTPUT}"
echo "Validation evidence written to ${OUTPUT}"
exit "${status}"
