#!/usr/bin/env bash
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly OUTPUT="${REPO_ROOT}/evidence/phase-0-1-validation.txt"
umask 027

set +e
"${REPO_ROOT}/tests/security/verify_phase0_phase1.sh" 2>&1 | tee "${OUTPUT}"
status=${PIPESTATUS[0]}
set -e
chmod 0640 "${OUTPUT}"
echo "Validation evidence written to ${OUTPUT}"
exit "${status}"

