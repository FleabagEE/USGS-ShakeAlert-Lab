#!/usr/bin/env bash
set -euo pipefail

readonly LAB_ROOT='/opt/quakelogic/shakealert-lab'
failures=0

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }

getent passwd shakealert >/dev/null \
  && pass 'shakealert account exists' || fail 'shakealert account is absent'
[[ "$(getent passwd shakealert 2>/dev/null | cut -d: -f7)" == '/usr/sbin/nologin' ]] \
  && pass 'shakealert account has no interactive shell' \
  || fail 'shakealert account shell is not /usr/sbin/nologin'
if id -nG shakealert 2>/dev/null | tr ' ' '\n' | grep -qxE 'sudo|admin'; then
  fail 'shakealert account has an administrative group'
else
  pass 'shakealert account has no administrative group'
fi

for relative_dir in \
  app bin config credentials docs evidence logs messages/native \
  messages/normalized messages/rejected messages/replay schemas scripts \
  services tests/unit tests/integration tests/security tests/replay tools; do
  [[ -d "${LAB_ROOT}/${relative_dir}" ]] \
    && pass "host directory ${relative_dir}" \
    || fail "missing host directory ${relative_dir}"
done

mode_of() { stat -c '%a' "$1" 2>/dev/null || true; }
owner_of() { stat -c '%U:%G' "$1" 2>/dev/null || true; }

[[ "$(mode_of "${LAB_ROOT}/credentials")" == '700' ]] \
  && pass 'host credentials mode 0700' || fail 'host credentials mode is not 0700'
[[ "$(owner_of "${LAB_ROOT}/credentials")" == 'shakealert:shakealert' ]] \
  && pass 'host credentials ownership is isolated' \
  || fail 'host credentials ownership is not shakealert:shakealert'
[[ "$(mode_of "${LAB_ROOT}/config/lab.env")" == '640' ]] \
  && pass 'host safety configuration mode 0640' \
  || fail 'host safety configuration mode is not 0640'

if (
  set -a
  source "${LAB_ROOT}/config/lab.env"
  set +a
  "${LAB_ROOT}/bin/safety_preflight" >/dev/null
); then
  pass 'installed safety interlock accepts passive configuration'
else
  fail 'installed safety interlock rejected passive configuration'
fi

for service_name in chrony auditd rsyslog; do
  systemctl is-active --quiet "${service_name}" \
    && pass "${service_name} is active" || fail "${service_name} is not active"
done

for command_name in curl jq git openssl chronyc python3 tcpdump nc dig \
  traceroute lsof logger logrotate unzip auditctl; do
  command -v "${command_name}" >/dev/null 2>&1 \
    && pass "${command_name} is available" || fail "${command_name} is missing"
done

auditctl -l 2>/dev/null | grep -q 'shakealert_credentials' \
  && pass 'credential audit rule is loaded' || fail 'credential audit rule is not loaded'

for unit_name in shakealert-scenario-receiver shakealert-production-receiver shakealert-lab-dashboard; do
  [[ -f "/etc/systemd/system/${unit_name}.service" ]] \
    && pass "${unit_name} unit is installed" || fail "${unit_name} unit is absent"
  if systemctl is-enabled --quiet "${unit_name}.service"; then
    fail "${unit_name} must remain disabled before endpoint approval"
  else
    pass "${unit_name} is disabled"
  fi
  if systemctl is-active --quiet "${unit_name}.service"; then
    fail "${unit_name} must remain inactive before endpoint approval"
  else
    pass "${unit_name} is inactive"
  fi
done
[[ -f "${LAB_ROOT}/app/shakealert_lab/cli.py" ]] \
  && pass "application framework is deployed" || fail "application framework is absent"

echo "RESULT: ${failures} failure(s)"
exit "${failures}"
