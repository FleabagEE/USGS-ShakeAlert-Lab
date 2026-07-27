#!/usr/bin/env bash
set -euo pipefail

readonly LAB_ROOT='/opt/quakelogic/shakealert-lab'
readonly SERVICE_USER='shakealert'
readonly SERVICE_GROUP='shakealert'
readonly SAFETY_ENV="${LAB_ROOT}/config/lab.env"
readonly AUDIT_RULES='/etc/audit/rules.d/shakealert-lab.rules'
readonly UBUNTU_DEB822_SOURCES='/etc/apt/sources.list.d/ubuntu.sources'
readonly UBUNTU_LEGACY_SOURCES='/etc/apt/sources.list'
readonly BASELINE_PACKAGES=(
  auditd ca-certificates chrony curl dnsutils git jq logrotate lsof
  netcat-openbsd openssl python3 python3-pip python3-venv rsyslog
  tcpdump traceroute unzip
)
readonly LAB_DIRECTORIES=(
  app bin config credentials docs evidence logs messages/native
  messages/normalized messages/rejected messages/replay schemas scripts
  services tests/unit tests/integration tests/security tests/replay tools
)

if [[ "${EUID}" -ne 0 ]]; then
  echo 'Run this script as root.' >&2
  exit 77
fi

export DEBIAN_FRONTEND=noninteractive
if [[ -r "${UBUNTU_DEB822_SOURCES}" ]]; then
  apt-get \
    -o "Dir::Etc::sourcelist=${UBUNTU_DEB822_SOURCES}" \
    -o 'Dir::Etc::sourceparts=-' \
    -o 'APT::Get::List-Cleanup=0' \
    update
elif [[ -r "${UBUNTU_LEGACY_SOURCES}" ]]; then
  apt-get \
    -o "Dir::Etc::sourcelist=${UBUNTU_LEGACY_SOURCES}" \
    -o 'Dir::Etc::sourceparts=-' \
    -o 'APT::Get::List-Cleanup=0' \
    update
else
  echo 'No readable Ubuntu package source definition was found.' >&2
  exit 69
fi
apt-get install --yes --no-install-recommends "${BASELINE_PACKAGES[@]}"

if ! getent group "${SERVICE_GROUP}" >/dev/null; then
  groupadd --system "${SERVICE_GROUP}"
fi

if ! getent passwd "${SERVICE_USER}" >/dev/null; then
  useradd --system --gid "${SERVICE_GROUP}" --home-dir "${LAB_ROOT}" \
    --no-create-home --shell /usr/sbin/nologin "${SERVICE_USER}"
fi

if [[ "$(id -gn "${SERVICE_USER}")" != "${SERVICE_GROUP}" ]]; then
  echo "Existing ${SERVICE_USER} account has the wrong primary group." >&2
  exit 78
fi
if [[ "$(getent passwd "${SERVICE_USER}" | cut -d: -f7)" != '/usr/sbin/nologin' ]]; then
  echo "Existing ${SERVICE_USER} account has an interactive shell." >&2
  exit 78
fi

install -d -o root -g "${SERVICE_GROUP}" -m 0750 /opt/quakelogic
install -d -o root -g "${SERVICE_GROUP}" -m 0750 "${LAB_ROOT}"
for relative_dir in "${LAB_DIRECTORIES[@]}"; do
  install -d -o root -g "${SERVICE_GROUP}" -m 0750 \
    "${LAB_ROOT}/${relative_dir}"
done

install -d -o "${SERVICE_USER}" -g "${SERVICE_GROUP}" -m 0700 \
  "${LAB_ROOT}/credentials"
for writable_dir in evidence logs messages/native messages/normalized \
  messages/rejected messages/replay; do
  chown "${SERVICE_USER}:${SERVICE_GROUP}" "${LAB_ROOT}/${writable_dir}"
  chmod 0750 "${LAB_ROOT}/${writable_dir}"
done

install -o root -g "${SERVICE_GROUP}" -m 0750 \
  "$(dirname "${BASH_SOURCE[0]}")/../bin/safety_preflight" \
  "${LAB_ROOT}/bin/safety_preflight"

if [[ ! -e "${SAFETY_ENV}" ]]; then
  install -o root -g "${SERVICE_GROUP}" -m 0640 /dev/null "${SAFETY_ENV}"
  {
    echo 'LAB_BANNER="SHAKEALERT LAB — NO OPERATIONAL OUTPUTS"'
    echo 'ALLOW_OPERATIONAL_OUTPUTS=false'
    echo 'APPLICATION_TIMEZONE=UTC'
    echo 'LAB_MODE=passive'
  } >"${SAFETY_ENV}"
fi
chown root:"${SERVICE_GROUP}" "${SAFETY_ENV}"
chmod 0640 "${SAFETY_ENV}"

install -o root -g root -m 0644 "$(dirname "${BASH_SOURCE[0]}")/../config/logrotate.shakealert-lab" "/etc/logrotate.d/shakealert-lab"

install -o root -g root -m 0640 /dev/null "${AUDIT_RULES}"
{
  echo "-w ${LAB_ROOT}/credentials -p wa -k shakealert_credentials"
  echo "-w ${LAB_ROOT}/config -p wa -k shakealert_config"
  echo "-w ${LAB_ROOT}/services -p wa -k shakealert_services"
} >"${AUDIT_RULES}"

install -d -o root -g "${SERVICE_GROUP}" -m 0750 "${LAB_ROOT}/app"
cp -a "$(dirname "${BASH_SOURCE[0]}")/../src/shakealert_lab" "${LAB_ROOT}/app/"
chown -R root:"${SERVICE_GROUP}" "${LAB_ROOT}/app/shakealert_lab"
find "${LAB_ROOT}/app/shakealert_lab" -type d -exec chmod 0750 {} +
find "${LAB_ROOT}/app/shakealert_lab" -type f -exec chmod 0640 {} +
for unit in "$(dirname "${BASH_SOURCE[0]}")/../services/"*.service; do
  install -o root -g root -m 0644 "${unit}" "/etc/systemd/system/$(basename "${unit}")"
  install -o root -g "${SERVICE_GROUP}" -m 0640 "${unit}" "${LAB_ROOT}/services/$(basename "${unit}")"
done
systemctl daemon-reload

systemctl enable --now chrony
systemctl enable --now auditd
systemctl enable --now rsyslog
augenrules --load

echo 'SHAKEALERT LAB — NO OPERATIONAL OUTPUTS'
echo "Ubuntu laboratory baseline provisioned at ${LAB_ROOT}"
echo 'No USGS endpoint was contacted and no credential was installed.'
