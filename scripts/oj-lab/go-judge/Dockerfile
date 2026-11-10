# Template only. Build inside an operator-approved disposable Linux VM.
# Both base images MUST be supplied by immutable digest; there are no latest defaults.
ARG GO_IMAGE
ARG DEBIAN_IMAGE
FROM ${GO_IMAGE} AS build
WORKDIR /src
COPY source/ ./
RUN printf 'v1.9.10\n' > cmd/go-judge/version/version.txt \
    && CGO_ENABLED=0 go build -trimpath -tags seccomp \
    -ldflags="-s -w -X github.com/criyle/go-judge/cmd/go-judge/version.Version=v1.9.10" \
    -o /go-judge ./cmd/go-judge

FROM ${DEBIAN_IMAGE}
ARG APT_SNAPSHOT
ARG GXX_VERSION
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
# Pin the package repository snapshot AND requested compiler package version.
RUN test -n "$APT_SNAPSHOT" && test -n "$GXX_VERSION" \
    && rm -f /etc/apt/sources.list.d/debian.sources \
    && printf 'deb [check-valid-until=no] https://snapshot.debian.org/archive/debian/%s/ bookworm main\n' "$APT_SNAPSHOT" > /etc/apt/sources.list \
    && apt-get -o Acquire::Check-Valid-Until=false update \
    && apt-get install -y --no-install-recommends "g++=$GXX_VERSION" ca-certificates \
    && dpkg-query -W > /compiler-packages.txt \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt
COPY --from=build /go-judge /opt/go-judge
COPY mount.yaml seccomp.yaml /opt/
RUN printf 'sandbox must not read this outer-container file\n' > /opt/oj-host-canary
LABEL smarttt.oj.lab="disposable-v1" smarttt.oj.version="v1.9.10"
ENTRYPOINT ["/opt/go-judge"]
