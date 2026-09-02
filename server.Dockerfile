# ---------------------------------------------------------------------------
# Build stage. Nothing produced here reaches the shipped image except the
# uberjar itself, so a build step that writes into /app — a config migration
# against a reachable DB, a stray manifest — cannot become a shipped artifact.
# ---------------------------------------------------------------------------
FROM clojure:tools-deps-trixie AS build
WORKDIR /app

# Clojure deps (cached layer)
COPY server/deps.edn deps.edn
RUN clojure -A:build:prod -M -e ::ok   # preload and cache dependencies, only reruns if deps.edn changes

# electric-user-version is computed from git sha during clj build
COPY server/shadow-cljs.edn shadow-cljs.edn
COPY server/src src
COPY server/src-build src-build
COPY server/src-prod src-prod
COPY server/resources resources

ARG VERSION
# NOTE: this ENV is for THIS STAGE ONLY — the uberjar RUN below reads it.
# ENV DOES NOT CROSS A `FROM`, so the runtime stage declares its own; see the
# block after `AS runtime`. Deleting that one silently blanks the panel.
ENV VERSION=$VERSION


RUN clojure -X:build:prod uberjar :version "\"$VERSION\"" :build/jar-name "app.jar"

# ---------------------------------------------------------------------------
# Runtime stage. Same base image on purpose: identical JDK, and the operator
# debugging tools below are the ones people actually reach for on the box.
# ---------------------------------------------------------------------------
FROM clojure:tools-deps-trixie AS runtime
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
    strace \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app

# The image ships with no locale set, which leaves the JVM at POSIX. Measured
# in this exact base image:
#
#   default          LANG=C.UTF-8
#   file.encoding    UTF-8              UTF-8      (JEP 400 — already fine)
#   stdout.encoding  ANSI_X3.4-1968     UTF-8
#   native.encoding  ANSI_X3.4-1968     UTF-8
#   sun.jnu.encoding ANSI_X3.4-1968     UTF-8
#
# On a Norwegian corpus that ASCII stdout mangles every æ, ø and å in the
# logs, and the ASCII sun.jnu.encoding breaks filenames containing them. Note
# which knob does the work: `-Dsun.jnu.encoding` is NOT settable on the
# command line — it is derived from the locale at startup — so the -D flags
# below cannot fix filenames on their own. The locale can, and does.
ENV LANG=C.UTF-8

# ⚠️ DECLARED HERE, IN THE RUNTIME STAGE, BECAUSE ENV DOES NOT CROSS A `FROM`.
# Both were previously set only in the build stage, so neither reached the
# running container: measured on the deployed test service, `VERSION` was UNSET
# while `LANG` — this line — was present, which is what makes that reading a
# measurement rather than a broken probe.
#
# The consequence was not cosmetic: `Server commit` in the Diagnostics panel has
# never populated, which is why identifying a deployed build required probing an
# unrelated HTTP behaviour. A `docker build` needs the ARG redeclared per stage;
# only the ENV carries it into the image config.
#
# ⚠️ REPRODUCIBILITY: a time-varying BUILD_TIMESTAMP means the same git SHA no
# longer produces the same image digest. A LABEL would not avoid this — labels
# live in the image config, which the manifest digest covers — so the choice is
# between the cost and the field, not between the two mechanisms. The cost is
# smaller than it sounds: only the CONFIG BLOB changes, and every filesystem
# layer stays byte-identical, so pulls and layer caches are unaffected.
#
# Checked before accepting it: nothing in deploy.yml, deploy.test.yml, this file
# or bb.edn compares image digests. If something starts to, this is the line to
# revisit.
ARG VERSION
ENV VERSION=$VERSION
ARG BUILD_TIMESTAMP
ENV BUILD_TIMESTAMP=$BUILD_TIMESTAMP

COPY --from=build /app/target/app.jar app.jar

# deps.edn :jvm-opts do not reach this process: the CMD is a bare `java`, not
# the Clojure CLI. The two that production needs are repeated here on purpose.
# Left behind deliberately, both dev-only:
#   -Djdk.attach.allowAttachSelf    self-attaching profilers
#   -Dclojure.spec.skip-macros      macro-expansion behaviour, moot for an
#                                   already-compiled uberjar
# Exec form, not shell form: shell form runs the JVM as a child of `/bin/sh -c`,
# so SIGTERM from `docker stop` or a Kamal deploy reaches the shell and never
# the JVM, and the container is killed on the timeout instead of shutting down.
CMD ["java", \
     "-Dfile.encoding=UTF-8", \
     "-Dstdout.encoding=UTF-8", \
     "-Dstderr.encoding=UTF-8", \
     "--enable-native-access=ALL-UNNAMED", \
     "-cp", "app.jar", "clojure.main", "-m", "prod"]
