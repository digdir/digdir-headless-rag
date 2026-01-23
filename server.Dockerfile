FROM clojure:tools-deps-trixie AS build
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    netcat-openbsd \
    vim \
    less \
    htop \
    net-tools \
    iputils-ping \
    dnsutils \
    telnet \
    traceroute \
    jq \
    tmux \
    nano \
    procps \
    lsof \
    strace
WORKDIR /app

# Clojure deps (cached layer)
COPY admin/deps.edn deps.edn
COPY shared shared
# Patch deps.edn to use local shared directory
RUN sed -i 's|"../shared"|"./shared"|' deps.edn
RUN clojure -A:build:prod -M -e ::ok   # preload and cache dependencies, only reruns if deps.edn changes

# electric-user-version is computed from git sha during clj build
COPY admin/shadow-cljs.edn shadow-cljs.edn
COPY admin/src src
COPY admin/src-build src-build
COPY admin/src-prod src-prod
COPY admin/resources resources
COPY config config

ARG VERSION
ENV VERSION=$VERSION

RUN clojure -X:build:prod uberjar :version "\"$VERSION\"" :build/jar-name "app.jar"

CMD java -cp target/app.jar clojure.main -m prod
