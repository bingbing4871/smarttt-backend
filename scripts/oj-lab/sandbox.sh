#!/usr/bin/env bash
# Deployment TEMPLATE: refuses Windows/containers/unapproved hosts. Never uses sudo.
set -euo pipefail
umask 077
fail() { echo "$*" >&2; exit 1; }
[[ $(uname -s) == Linux ]] || fail 'Dedicated Linux VM required; no Windows execution'
[[ ${OJ_LAB_DEDICATED_VM:-} == YES_DISPOSABLE_VM ]] || fail 'Operator-approved disposable VM required'
[[ -r /etc/smarttt-oj-disposable-vm ]] || fail 'Missing administrator-created dedicated-VM marker'
[[ $(cat /etc/smarttt-oj-disposable-vm) == SMARTTT_OJ_DEDICATED_VM_V1 ]] || fail 'Invalid VM marker'
command -v systemd-detect-virt >/dev/null || fail 'Cannot confirm VM boundary'
systemd-detect-virt --vm >/dev/null || fail 'A real VM boundary is required'
if systemd-detect-virt --container >/dev/null; then fail 'Do not run this inside a shared container'; fi
command -v docker >/dev/null || fail 'Preinstalled Docker required'
[[ $(stat -fc %T /sys/fs/cgroup) == cgroup2fs ]] || fail 'This recipe requires cgroup v2'
for ctl in cpu memory pids; do grep -qw "$ctl" /sys/fs/cgroup/cgroup.controllers || fail "Missing $ctl controller"; done
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
state=${XDG_STATE_HOME:-$HOME/.local/state}/smarttt-oj-sandbox
mkdir -p -- "$state"
[[ ! -L $state && $(stat -c %u "$state") == "$(id -u)" ]] || fail 'Private owned state directory required'
chmod 700 -- "$state"
name=smarttt-oj-disposable
network=smarttt-oj-disposable-net
version=v1.9.10
commit=662109c510ae8d42b0f37a833c2449e9ffc778e1
local_docker() { python3 "$here/local_docker.py" "$@"; }
local_docker version >/dev/null
owned_container() { [[ $(local_docker inspect -f '{{index .Config.Labels "smarttt.oj.lab"}}' "$name") == disposable-v1 ]]; }
case ${1:-} in
  build)
    : "${OJ_GO_IMAGE:?Supply golang image by sha256 digest}"
    : "${OJ_DEBIAN_IMAGE:?Supply Debian bookworm image by sha256 digest}"
    : "${OJ_APT_SNAPSHOT:?Supply reviewed Debian snapshot YYYYMMDDTHHMMSSZ}"
    : "${OJ_GXX_VERSION:?Supply exact g++ package version in that snapshot}"
    [[ $OJ_GO_IMAGE =~ ^golang:[^[:space:]]+@sha256:[a-f0-9]{64}$ ]] || fail 'Immutable official Go image required'
    [[ $OJ_DEBIAN_IMAGE =~ ^debian:bookworm[^[:space:]]*@sha256:[a-f0-9]{64}$ ]] || fail 'Immutable bookworm image required'
    [[ $OJ_APT_SNAPSHOT =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || fail 'Invalid apt snapshot'
    [[ $OJ_GXX_VERSION =~ ^[a-zA-Z0-9.+:~-]+$ ]] || fail 'Invalid package version'
    work=$(mktemp -d "$state/build-XXXXXXXX")
    git init -q "$work/repository"
    git -C "$work/repository" -c http.sslVerify=true fetch --depth=1 https://github.com/criyle/go-judge.git "refs/tags/$version"
    [[ $(git -C "$work/repository" rev-parse FETCH_HEAD) == "$commit" ]] || fail 'Unexpected upstream tag commit'
    mkdir "$work/source"
    git -C "$work/repository" archive FETCH_HEAD | tar -x -C "$work/source"
    cp -- "$here/go-judge/"{Dockerfile,mount.yaml,seccomp.yaml} "$work/"
    local_docker build --build-arg "GO_IMAGE=$OJ_GO_IMAGE" --build-arg "DEBIAN_IMAGE=$OJ_DEBIAN_IMAGE" \
      --build-arg "APT_SNAPSHOT=$OJ_APT_SNAPSHOT" --build-arg "GXX_VERSION=$OJ_GXX_VERSION" \
      --iidfile "$state/image-id" "$work"
    printf '%s\n' "$version $commit" > "$state/source-version"
    echo 'Image built; no executor has been started.'
    ;;
  start)
    [[ -f $state/image-id ]] || fail 'Build the pinned image first'
    if local_docker inspect "$name" >/dev/null 2>&1; then fail 'Container already exists; do not overwrite'; fi
    if local_docker network inspect "$network" >/dev/null 2>&1; then fail 'Network already exists; inspect/clean managed resources first'; fi
    image=$(cat "$state/image-id")
    [[ $image =~ ^sha256:[a-f0-9]{64}$ ]] || fail 'Invalid image identity'
    [[ $(local_docker image inspect -f '{{index .Config.Labels "smarttt.oj.version"}}' "$image") == "$version" ]] || fail 'Version label mismatch'
    # Avoid credentials in argv, shell tracing, Git and raw logs. Never print this file.
    python3 - "$state/auth.env" <<'PY'
import pathlib,secrets,sys
pathlib.Path(sys.argv[1]).write_text('ES_AUTH_TOKEN='+secrets.token_hex(32)+'\n')
PY
    chmod 600 "$state/auth.env"
    local_docker network create --internal --label smarttt.oj.lab=disposable-v1 "$network" >/dev/null
    local_docker run -d --name "$name" --label smarttt.oj.lab=disposable-v1 --pull=never \
      --privileged --cgroupns=private --network "$network" -p 127.0.0.1:5050:5050 \
      --cpus=2 --memory=2g --memory-swap=2g --pids-limit=512 --shm-size=256m \
      --restart=no --log-driver=none --env-file "$state/auth.env" "$image" \
      -http-addr=:5050 -parallelism=1 -pre-fork=1 -no-fallback -silent \
      -seccomp-conf=/opt/seccomp.yaml -mount-conf=/opt/mount.yaml -net-share=false \
      -file-timeout=5m -copy-out-limit=16m -open-file-limit=128 -output-limit=16m >/dev/null
    echo 'Started dedicated-VM executor. Run preflight and real probes; startup is NOT acceptance.'
    ;;
  check)
    owned_container || fail 'Not a managed container'
    OJ_GOJUDGE_CONTAINER=$name bash "$here/preflight_linux.sh"
    ;;
  stop)
    owned_container || fail 'Not a managed container'
    local_docker stop -t 15 "$name" >/dev/null
    ;;
  clean)
    owned_container || fail 'Not a managed container'
    [[ $(local_docker inspect -f '{{.State.Running}}' "$name") == false ]] || fail 'Stop before cleanup'
    [[ $(local_docker network inspect -f '{{index .Labels "smarttt.oj.lab"}}' "$network") == disposable-v1 ]] || fail 'Not a managed network'
    local_docker rm "$name" >/dev/null
    local_docker network rm "$network" >/dev/null
    rm -f -- "$state/auth.env"
    echo 'Removed only managed stopped container, network and credential file; image/build evidence retained.'
    ;;
  *) fail 'Usage: sandbox.sh build|start|check|stop|clean' ;;
esac
