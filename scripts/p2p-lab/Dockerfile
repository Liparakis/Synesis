# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jdk-jammy

RUN apt-get update \
    && apt-get install --no-install-recommends -y iproute2 iputils-ping procps ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/synesis
COPY --from=dist / /opt/synesis/
COPY --from=testclasses / /lab/test-classes/
COPY entrypoint.sh /usr/local/bin/synesis-p2p-lab

RUN chmod 0755 /usr/local/bin/synesis-p2p-lab

ENTRYPOINT ["/usr/local/bin/synesis-p2p-lab"]
