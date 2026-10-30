FROM eclipse-temurin:25-jre-noble AS java
FROM debian:trixie-slim
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH="/opt/java/openjdk/bin:${PATH}"
USER root
RUN apt-get update && apt-get install -y --no-install-recommends chromium fonts-liberation curl tini ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN printf '#!/bin/sh\nexec /usr/bin/chromium --no-sandbox --disable-dev-shm-usage "$@"\n' > /usr/local/bin/chromium \
    && chmod 755 /usr/local/bin/chromium
WORKDIR /opt/logicov
COPY --chown=10001:10001 . /opt/logicov/
RUN useradd --uid 10001 --home-dir /home/logicov --create-home logicov \
    && install -d -o 10001 -g 10001 /opt/logicov/db
USER 10001:10001
ENV PORT=8080 LOGICOV_WEB_DIRECTORY=/opt/logicov/web
EXPOSE 8080
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/logicov/bin/logicov-server"]
