#!/usr/bin/env bash
set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REQUIRED_DIRS=(
  app bin config credentials docs evidence logs messages/native
  messages/normalized messages/rejected messages/replay schemas scripts
  services tests/unit tests/integration tests/security tests/replay tools
)
failures=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }

for relative_dir in "${REQUIRED_DIRS[@]}"; do
  [[ -d "${REPO_ROOT}/${relative_dir}" ]] && pass "directory ${relative_dir}" || fail "missing directory ${relative_dir}"
done

for script in bin/safety_preflight scripts/setup_development.sh scripts/setup_ubuntu.sh scripts/collect_platform_inventory.sh scripts/run_acceptance_checks.sh tests/security/verify_host_baseline.sh; do
  [[ -x "${REPO_ROOT}/${script}" ]] && pass "executable ${script}" || fail "not executable ${script}"
  bash -n "${REPO_ROOT}/${script}" && pass "shell syntax ${script}" || fail "shell syntax ${script}"
done

if env ALLOW_OPERATIONAL_OUTPUTS=false "${REPO_ROOT}/bin/safety_preflight" >/dev/null; then
  pass 'interlock accepts literal false'
else
  fail 'interlock rejected literal false'
fi
if env -u ALLOW_OPERATIONAL_OUTPUTS "${REPO_ROOT}/bin/safety_preflight" >/dev/null 2>&1; then
  fail 'interlock accepted absent value'
else
  pass 'interlock rejects absent value'
fi
if env ALLOW_OPERATIONAL_OUTPUTS=true "${REPO_ROOT}/bin/safety_preflight" >/dev/null 2>&1; then
  fail 'interlock accepted true value'
else
  pass 'interlock rejects true value'
fi

# Scan executable/source locations only. Governance documents necessarily name
# prohibited outputs in order to forbid them.
if rg -n -i '(gpio|modbus|relay|siren|elevator.control|emergency.shutdown)' \
  "${REPO_ROOT}/app" "${REPO_ROOT}/bin" "${REPO_ROOT}/scripts" "${REPO_ROOT}/services" \
  "${REPO_ROOT}/src" "${REPO_ROOT}/tools" \
  --glob '!verify_phase0_phase1.sh'; then
  fail 'operational-output vocabulary found in executable source'
else
  pass 'no operational-output implementation found'
fi

validate_protected_credential_tree() {
  local entry relative mode

  while IFS= read -r -d '' entry; do
    relative="${entry#${REPO_ROOT}/}"
    [[ "${relative}" == 'credentials/.gitkeep' ]] && continue

    if [[ -L "${entry}" ]]; then
      fail 'credential tree contains a symlink'
      continue
    fi
    if ! git -C "${REPO_ROOT}" check-ignore -q -- "${relative}"; then
      fail 'credential artifact is not ignored by Git'
      continue
    fi
    if git -C "${REPO_ROOT}" ls-files --error-unmatch -- "${relative}" >/dev/null 2>&1; then
      fail 'credential artifact is tracked by Git'
      continue
    fi

    mode="$(stat -c '%a' "${entry}")"
    if [[ -d "${entry}" ]]; then
      [[ "${mode}" == '700' ]] ||
        fail 'protected credential directory mode is not 0700'
    elif [[ -f "${entry}" ]]; then
      [[ "${mode}" == '600' ]] ||
        fail 'protected credential file mode is not 0600'
      [[ -s "${entry}" ]] ||
        fail 'protected credential file is empty'
      case "${relative}" in
        credentials/scenario/username|credentials/scenario/password)
          [[ "$(stat -c '%s' "${entry}")" -le 65536 ]] ||
            fail 'protected broker credential exceeds the size limit'
          ;;
      esac
    else
      fail 'credential tree contains a non-regular artifact'
    fi
  done < <(find "${REPO_ROOT}/credentials" -mindepth 1 -print0)

  pass 'credential tree validated by metadata only; protected contents were not read'
}

validate_protected_credential_tree

credential_mode=$(stat -c '%a' "${REPO_ROOT}/credentials")
[[ "${credential_mode}" == '700' ]] && pass 'credentials directory mode 0700' || fail "credentials directory mode is ${credential_mode}, expected 700"

if rg -n '/opt|systemctl|useradd|groupadd|tmpfiles|logrotate\.d' \
  "${REPO_ROOT}/bin" "${REPO_ROOT}/scripts" \
  --glob '!setup_ubuntu.sh'; then
  fail 'unexpected privileged deployment reference found'
else
  pass 'privileged deployment is confined to setup_ubuntu.sh'
fi

echo "RESULT: ${failures} failure(s)"
exit "${failures}"
