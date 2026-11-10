#!/usr/bin/env bash
# Inspect only. No containers started, software installed, kernel settings changed or secrets printed.
set -euo pipefail
[[ $(uname -s) == Linux ]] || { echo 'Dedicated Linux VM required' >&2; exit 1; }
[[ ${OJ_LAB_DEDICATED_VM:-} == YES_DISPOSABLE_VM ]] || { echo 'Dedicated VM confirmation required' >&2; exit 1; }
[[ -r /etc/smarttt-oj-disposable-vm ]] || { echo 'Dedicated VM marker required' >&2; exit 1; }
[[ $(cat /etc/smarttt-oj-disposable-vm) == SMARTTT_OJ_DEDICATED_VM_V1 ]] || exit 1
command -v systemd-detect-virt >/dev/null
systemd-detect-virt --vm >/dev/null
if systemd-detect-virt --container >/dev/null; then echo 'A dedicated VM is required' >&2; exit 1; fi
command -v docker >/dev/null
command -v python3 >/dev/null
for ns in ipc mnt net pid user uts cgroup; do test -e "/proc/self/ns/$ns"; done
[[ -r /proc/sys/user/max_user_namespaces && $(cat /proc/sys/user/max_user_namespaces) -gt 0 ]]
[[ $(stat -fc %T /sys/fs/cgroup) == cgroup2fs ]]
for ctl in cpu memory pids; do grep -qw "$ctl" /sys/fs/cgroup/cgroup.controllers; done
grep -q '^Seccomp:' /proc/self/status
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
python3 "$here/local_docker.py" version >/dev/null
python3 "$here/inspect_sandbox.py"
