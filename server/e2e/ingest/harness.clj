(ns ingest-harness
  "The ingest e2e: a REAL ingest in the shipped newcomer stack.

   Run through `bb e2e:ingest` (which passes the compose command bb.edn probes).
   Each step prints the evidence it asserts on and fails loudly; nothing here
   retries a PRODUCT call, because a retry around the product would hide a real
   regression behind a known rough edge (the embedding-model download issue is handled before the product is
   called, not by retrying it).

   Three scenarios, each on its own fresh volumes:

     :stub          phrase generation routed to an OpenAI-compatible stub through
                    `services.llm.*`. Ingest, count, routing, cache, retrieval.
     :azure-no-key  Azure selected and no key. The DOCUMENTED refusal, naming
                    `services.azure-openai.api-key`, must actually fire - a
                    refusal claimed without measurement is how the setup-env provider-prompt issue's did not.
     :staged-store  the config boundary-crossing work's done-condition: a REAL migration, run against a
                    STAGED store, exported and restored into a fresh install,
                    with a query working afterwards. THE STARTING STATE IS
                    CONSTRUCTED BY US (see `staged-store-scenario!`).

   ⚠️ WHAT A GREEN RUN DOES NOT PROVE is listed in server/e2e/README.md, and it
   is not short. Read it before reading green as \"ingestion works\"."
  (:require [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The stack
;; ---------------------------------------------------------------------------

(def project
  "The compose project, UNIQUE PER RUN unless the caller pins INGEST_PROJECT
   (CI does, so its teardown step can name it). A fixed name let two lanes' runs
   on one machine kill each other's stacks - twice in one day - so the
   container names and host ports derive from the run too (the override)."
  (or (not-empty (System/getenv "INGEST_PROJECT"))
      (str "digdir-rag-ingest-" (subs (str (random-uuid)) 0 8))))
(def env-file "server/e2e/ingest/ingest.env")
(def compose-files ["-f" "docker-compose.newcomer.yml"
                    "-f" "server/e2e/ingest/compose.override.yml"])

(def urls
  "Where each service answers on the host. The ports are EPHEMERAL (the
   override publishes `127.0.0.1::<port>`), so they are discovered with
   `compose port` after each service starts - see `discover!`."
  (atom {}))

(defn- url [k]
  (or (get @urls k) (throw (ex-info (str "no URL discovered yet for " k) {:urls @urls}))))

;; The demo dataset's identity, as `digdir.setup.demo-dataset` seeds it.
(def tenant "demo")
(def dataset-id "norquad-docs")
(def pipeline-id "norquad-docs")
;; The console context's key is the TENANT-CONFIG-KEY the dataset tree was
;; seeded under, which is "default": the pipeline id is
;; `demo:default:norquad-docs` (`digdir.pipeline.core/make-pipeline-id`).
(def dataset-config-key "default")

(def fixture-titles
  "The fixture corpus's documents, by their `# ` title. The count assertions
   are EXACT against this set - the newcomer-compose empty-corpus issue was a run that reported success having
   processed nothing, and `> 0` would not have been enough to see a partial
   ingest either."
  #{"The Quillmoor lighthouse" "Ferrovane alloy"
    "The Tandlebury orchard festival" "The Veskirk glacier survey"})

(def fixture-markers
  "One invented name per fixture document, each appearing in that document's
   body and nowhere else in the corpus. Phrase requests are identified by these,
   NOT by the `# ` title: the chunk text a model receives carries no heading
   (measured on this harness's first run, which asserted titles and saw nil)."
  #{"Quillmoor" "Ferrovane" "Tandlebury" "Veskirk"})

(def embedding-model "ts/all-MiniLM-L12-v2")

(def scenarios
  {:stub {"INGEST_AZURE_OPENAI_USE_AZURE" "false"
          "INGEST_OPENAI_API_ENDPOINT" "http://llm-stub:8000/v1"
          "INGEST_OPENAI_API_KEY" "ingest-e2e-stub-key"
          "INGEST_LLM_MODEL" "ingest-stub-model"}
   ;; Endpoint and deployment are set, the key is not - the e2e .env's own
   ;; shape. If the refusal did NOT fire, the call would go to an
   ;; unresolvable host and fail with a DIFFERENT error, which the assertion on
   ;; the named path tells apart.
   ;;
   ;; The openai-compatible side ALSO points at the stub here, so that a
   ;; product which ignored `AZURE_OPENAI_USE_AZURE=true` would reach it and
   ;; turn "nothing reached the stub" red. Without this, that check could not
   ;; fail.
   :azure-no-key {"INGEST_AZURE_OPENAI_USE_AZURE" "true"
                  "INGEST_AZURE_OPENAI_API_ENDPOINT" "https://ingest-e2e.example.invalid"
                  "INGEST_AZURE_OPENAI_DEPLOYMENT_NAME" "ingest-e2e-deployment"
                  "INGEST_LLM_MODEL" "ingest-e2e-deployment"
                  "INGEST_OPENAI_API_ENDPOINT" "http://llm-stub:8000/v1"
                  "INGEST_OPENAI_API_KEY" "ingest-e2e-stub-key"}
   ;; The config boundary-crossing work's done-condition. Its query runs against the STUB, as `:stub`'s does.
   :staged-store {"INGEST_AZURE_OPENAI_USE_AZURE" "false"
                  "INGEST_OPENAI_API_ENDPOINT" "http://llm-stub:8000/v1"
                  "INGEST_OPENAI_API_KEY" "ingest-e2e-stub-key"
                  "INGEST_LLM_MODEL" "ingest-stub-model"}})

(defn- read-env-file [path]
  (into {}
        (keep (fn [line]
                (when-let [[_ k v] (re-matches #"([A-Z_][A-Z0-9_]*)=(.*)" line)]
                  [k v])))
        (str/split-lines (slurp path))))

(def test-env (read-env-file env-file))

;; ---------------------------------------------------------------------------
;; Reporting
;; ---------------------------------------------------------------------------

(defn- say [& xs] (println (apply str xs)) (flush))

(defn- step [title] (say) (say "▸ " title))

(def ^:dynamic *keep-going*
  "Bound to an atom by `--keep-going`: a failed check is RECORDED and the
   scenario carries on, then fails at its end. That is how a sabotage learns
   everything that catches a break, not only the first check - rule 6's
   \"what ELSE catches this?\". Off by default: normally the first false claim
   stops the run."
  nil)

(defn- check!
  "Assert `ok?`, printing `what` and the evidence either way. Throws on failure,
   so the run stops at the first false claim instead of reporting past it -
   unless `*keep-going*` is bound, when the failure is recorded instead."
  [ok? what evidence]
  (say (if ok? "  ✔ " "  ✖ ") what (when evidence (str "  —  " evidence)))
  (when-not ok?
    (if *keep-going*
      (swap! *keep-going* conj what)
      (throw (ex-info (str "check failed: " what) {:evidence evidence})))))

;; ---------------------------------------------------------------------------
;; Compose
;; ---------------------------------------------------------------------------

(def interpolated-names
  "Every variable the two compose files interpolate, DERIVED from their text
   rather than listed, so a variable added to either file is covered too.

   Why it matters: compose interpolation PREFERS the process environment over
   --env-file. So a developer's exported TYPESENSE_API_KEY_ADMIN became
   Typesense's key while the server and this harness kept ingest.env's - a
   split stack, found in review, and a SECOND door the `.env`
   exclusion did not close. `compose-env` strips these names, so ingest.env is
   the only source."
  (->> ["docker-compose.newcomer.yml" "server/e2e/ingest/compose.override.yml"]
       (mapcat #(re-seq #"\$\{([A-Za-z_][A-Za-z0-9_]*)" (slurp %)))
       (map second)
       set))

(def dockerfile "server.Dockerfile")

(def ignore-files
  "The ignore files Docker reads for this build: the context root's, and the
   Dockerfile's own, which BuildKit prefers. The disk walk hashes every file
   whether or not one of these excludes it, so hashing the ignore files
   themselves is what gives a tree that differs only in one of them another
   tag."
  [".dockerignore" (str dockerfile ".dockerignore")])

(def ^:private instruction
  "Every Dockerfile instruction, case-insensitive, as the first word of a line."
  #"(?i)^\s*(ADD|ARG|CMD|COPY|ENTRYPOINT|ENV|EXPOSE|FROM|HEALTHCHECK|LABEL|MAINTAINER|ONBUILD|RUN|SHELL|STOPSIGNAL|USER|VOLUME|WORKDIR)(\s|$)")

(defn copy-sources
  "The build-context paths `dockerfile` COPYs (every COPY without --from), read
   from the Dockerfile's own text so the list cannot drift from it. Fails
   CLOSED: a continued COPY line, no sources at all, or a source that is not on
   disk (a flag such as --chown would be one) throws rather than hash less than
   the build reads.
   Instructions are case-insensitive, and three other shapes read the context
   too, so each is REFUSED rather than parsed: ADD; a --mount whose type is not
   cache, tmpfs, secret or ssh (bind is the default, and a continuation line
   can carry one, so every non-comment line is scanned); and an escape
   directive, which changes what continues a line. A syntax directive
   is refused too: it picks the frontend that
   gives the file its meaning. And every logical line must start with a known instruction, so
   a shape this reader cannot see (a heredoc body, a misread continuation)
   refuses too."
  [root]
  (let [lines (str/split-lines (slurp (io/file root dockerfile)))
        code (remove #(re-find #"^\s*#" %) lines)
        refuse! (fn [what hits]
                  (throw (ex-info (str dockerfile ": " what " - refused, rather than hash less than the build reads")
                                  {:lines hits})))
        copies (keep #(second (re-matches #"(?i)\s*COPY\s+(?!--from)(.+)" %)) lines)
        sources (mapcat #(butlast (str/split (str/trim %) #"\s+")) copies)]
    (when-let [hits (seq (filter #(re-find #"(?i)^\s*#\s*(escape|syntax)\s*=" %) lines))]
      (refuse! "an escape or syntax directive" hits))
    ;; Every logical line must START with an instruction this reader knows: a
    ;; line that does not is a continuation it misread, or a shape it cannot see.
    (when-let [hits (seq (loop [[l & more] lines continuing? false bad []]
                           (cond
                             (nil? l) bad
                             (or (str/blank? l) (re-find #"^\s*#" l)) (recur more continuing? bad)
                             :else (recur more (str/ends-with? (str/trimr l) "\\")
                                          (if (or continuing? (re-find instruction l)) bad (conj bad l))))))]
      (refuse! "a line that starts no known instruction" hits))
    (when-let [hits (seq (filter #(re-find #"(?i)^\s*ADD(\s|$)" %) lines))]
      (refuse! "an ADD" hits))
    (when-let [hits (seq (for [l code
                               [_ spec] (re-seq #"--mount(?:=(\S*))?" l)
                               :let [type (or (second (re-find #"(?:^|,)type=([^,]*)" (str/lower-case (or spec ""))))
                                              "bind")]
                               :when (not (#{"cache" "tmpfs" "secret" "ssh"} type))]
                           l))]
      (refuse! "a --mount that can bind the build context (bind is the default type)" hits))
    (when-let [continued (seq (filter #(str/ends-with? (str/trim %) "\\") copies))]
      (throw (ex-info (str dockerfile ": a continued COPY line, which this reader does not follow") {:lines continued})))
    (when (empty? sources)
      (throw (ex-info (str dockerfile ": no COPY sources - the image hash would cover nothing") {})))
    (when-let [missing (seq (remove #(.exists (io/file root %)) sources))]
      (throw (ex-info (str dockerfile ": COPY sources not on disk: " (str/join " " missing)) {:missing missing})))
    sources))

(def ^:private nofollow
  (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS]))

(defn- entry
  "What Docker copies of one walked entry, as [kind bytes]: a directory's mode;
   a regular file's mode and bytes; a symlink's TARGET STRING (Docker copies
   the link, not what it points at, and copies a dangling one too). Modes are
   the full `unix:mode` permission bits, set-id and sticky included. Anything
   else - a fifo, a socket, a device - is refused."
  [path rel]
  (let [mode #(format "%04o" (bit-and 07777 (java.nio.file.Files/getAttribute path "unix:mode" nofollow)))]
    (cond
      (java.nio.file.Files/isSymbolicLink path)
      ["L" (.getBytes (str (java.nio.file.Files/readSymbolicLink path)) "UTF-8")]
      (java.nio.file.Files/isDirectory path nofollow) [(str "D" (mode)) (byte-array 0)]
      (java.nio.file.Files/isRegularFile path nofollow) [(str "F" (mode)) (java.nio.file.Files/readAllBytes path)]
      :else (throw (ex-info (str rel ": neither a file, a directory nor a symlink - refused") {:path rel})))))

(defn image-input-hash
  "sha256 over the Dockerfile, the `ignore-files` that exist, and EVERY ENTRY
   under each COPY source - directories (an empty one is copied too), regular
   files and symlinks, each by its relative path and `entry` - in path order.
   Hashing file BYTES alone missed what Docker also copies: a mode bit, an empty
   directory, a dangling symlink, a symlink retargeted to identical bytes
  The walk never follows a link, so a link loop is one entry.
   It walks the DISK, not git, because the build context is the disk, so an
   untracked file a COPY takes is an input too. A top-level source that is
   itself a symlink is refused. Answers [entry-count first-12-hex]."
  [root]
  (let [rootp (.toPath (io/file root))
        tops (concat [dockerfile] (filter #(.exists (io/file root %)) ignore-files) (copy-sources root))
        _ (when-let [linked (seq (filter #(java.nio.file.Files/isSymbolicLink (.resolve rootp %)) tops))]
            (throw (ex-info (str "a top-level build input is a symlink: " (str/join " " linked) " - refused") {:paths linked})))
        rels (->> tops
                  (mapcat (fn [rel]
                            (with-open [st (java.nio.file.Files/walk (.resolve rootp rel)
                                                                     (make-array java.nio.file.FileVisitOption 0))]
                              (mapv #(str (.relativize rootp %)) (iterator-seq (.iterator st))))))
                  distinct sort)
        md (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [rel rels]
      (let [[kind body] (entry (.resolve rootp rel) rel)]
        (.update md (.getBytes (str rel (char 0) kind (char 0)) "UTF-8"))
        (.update md ^bytes body)
        (.update md (byte-array [0]))))
    [(count rels) (subs (format "%064x" (BigInteger. 1 (.digest md))) 0 12)]))

(def image-inputs (image-input-hash "."))

(def image
  "The harness's OWN image tag, KEYED ON THE BUILD INPUTS: the Dockerfile and
   what it copies, hashed from this tree. Two trees whose server code differs
   can never share it; two whose inputs are byte-identical share it, and then
   build from the same inputs. The tag is daemon-wide, so the fixed tag this
   replaced let concurrent runs from DIFFERENT trees test each other's code
   - after the fixed `:newcomer` default had already been
   found overwriting every other stack's image. A harness, override or
   ingest.env edit does not change it, so sabotage runs keep `--no-build`."
  (str "digdir-rag-server:ingest-e2e-" (second image-inputs)))

(def passed-through
  "The ONLY caller variables a compose call sees: where the Docker daemon is,
   and how to reach it. Everything else is dropped rather than listed, because
   listing what to drop is the losing move: a build setting
   (DOCKER_DEFAULT_PLATFORM, BUILDX_BUILDER, DOCKER_BUILDKIT, COMPOSE_*), an
   interpolated name, a bare `environment:` pass-through, and whatever Docker
   reads next are all inputs the image tag cannot see. No proxy is passed:
   behind one, add HTTP(S)_PROXY here, knowing it changes what a build fetches."
  #{"PATH" "HOME" "USER" "TMPDIR" "DOCKER_HOST" "DOCKER_CONTEXT" "DOCKER_CONFIG"
    "DOCKER_CERT_PATH" "DOCKER_TLS_VERIFY" "SSH_AUTH_SOCK" "XDG_RUNTIME_DIR"})

(defn- compose-env
  "The environment every compose call runs with: ONLY `passed-through` from
   the caller's (and never a name the compose files interpolate), PLUS this
   harness's image tag and the scenario's INGEST_* values. Nothing else a shell
   exports reaches compose, the build, or the stack."
  [scenario-env]
  (merge (apply dissoc (select-keys (into {} (System/getenv)) passed-through) interpolated-names)
         {"DIGDIR_RAG_IMAGE" image
          "INGEST_PROJECT" project}
         scenario-env))

(defn- docker-out
  "Run a docker command with the compose environment; answers trimmed stdout,
   or nil when it fails."
  [& args]
  (let [r (apply p/shell {:continue true :out :string :err :string :env (compose-env {})} "docker" args)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn- require-native-image!
  "The image must exist, and be built for the DAEMON's own OS and architecture.
   The platform is not in the hash, and the allowlist cannot pin the builder:
   DOCKER_CONFIG and HOME carry buildx's CURRENT builder, and a shell whose
   config selects another builder builds this tag with it. So
   the platform is ASSERTED, whichever builder ran - after a build, and on
   `--no-build`. Answers the platform."
  [no-build?]
  (let [img (docker-out "image" "inspect" "--format" "{{.Os}}/{{.Architecture}}" image)
        daemon (docker-out "version" "--format" "{{.Server.Os}}/{{.Server.Arch}}")]
    (cond
      (nil? img)
      (throw (ex-info (str (if no-build? "--no-build, but there is no " "the build left no ") image
                           ", the image for THIS tree's build inputs (" (first image-inputs) " entries)."
                           (when no-build? " Run once without --no-build."))
                      {:image image}))
      (or (nil? daemon) (not= img daemon))
      (throw (ex-info (str image " is built for " img ", but the daemon is " (or daemon "unreadable")
                           " - another builder or platform built this tag, so it is refused")
                      {:image img :daemon daemon}))
      :else img)))

(defn- compose
  "Run a compose command for this project. `opts` passes through to
   `babashka.process/shell`. The environment is REPLACED, not extended - see
   `compose-env`."
  [{:keys [compose-cmd scenario-env]} opts & args]
  (apply p/shell (merge {:env (compose-env scenario-env)} opts)
         (concat compose-cmd ["-p" project "--env-file" env-file] compose-files args)))

(defn- discover!
  "Ask compose which ephemeral host port `service`'s `container-port` got, and
   record `http://<that>` under `k`."
  [ctx k service container-port]
  (let [r (compose ctx {:out :string :err :string :continue true} "port" service (str container-port))
        addr (str/trim (str (:out r)))]
    (check! (and (zero? (:exit r)) (re-matches #"127\.0\.0\.1:\d+" addr))
            (str service " is published on an ephemeral loopback port") addr)
    (swap! urls assoc k (str "http://" addr))))

(defn- down! [ctx]
  (compose ctx {:continue true} "down" "-v" "--remove-orphans"))

(defn- require-build-config!
  "The tag hashes `dockerfile` and its COPY sources from the repo root, so it
   covers the image only if compose builds EXACTLY that: refuse another
   Dockerfile or context, any other build key (args, additional_contexts,
   target...) whose inputs the hash cannot see, and a service-level `platform`,
   which sets the build's architecture from outside `build:`. The shell's
   DOCKER_DEFAULT_PLATFORM never reaches compose (`passed-through`)."
  [ctx]
  (let [cfg (json/parse-string (:out (compose ctx {:out :string} "config" "--format" "json")) true)
        build (get-in cfg [:services :digdir-rag :build])
        platform (get-in cfg [:services :digdir-rag :platform])
        want {:context (.getCanonicalPath (io/file ".")) :dockerfile dockerfile}]
    (when platform
      (throw (ex-info (str "compose sets digdir-rag's platform to " platform
                           " - the image tag does not key on the platform, so it is refused")
                      {:platform platform})))
    (when-not (= want (update build :context #(some-> % io/file .getCanonicalPath)))
      (throw (ex-info (str "compose builds digdir-rag from " (pr-str build) ", not " (pr-str want)
                           " - the image tag would not cover what that build reads")
                      {:build build})))))

;; ---------------------------------------------------------------------------
;; HTTP
;; ---------------------------------------------------------------------------

(defn- json-body [resp]
  (try (json/parse-string (:body resp) true) (catch Exception _ nil)))

(defn- wait-until
  "Poll `f` every `interval-ms` until it returns truthy or `timeout-ms` passes.
   Answers [value elapsed-ms attempts]; value is nil on timeout."
  [timeout-ms interval-ms f]
  (let [start (System/currentTimeMillis)]
    (loop [attempts 1]
      (let [v (try (f) (catch Exception _ nil))
            elapsed (- (System/currentTimeMillis) start)]
        (cond
          v [v elapsed attempts]
          (> elapsed timeout-ms) [nil elapsed attempts]
          :else (do (Thread/sleep interval-ms) (recur (inc attempts))))))))

(defn- typesense [method path & [body]]
  (http/request {:method method
                 :uri (str (url :typesense) path)
                 :headers {"X-TYPESENSE-API-KEY" (get test-env "TYPESENSE_API_KEY_ADMIN")
                           "Content-Type" "application/json"}
                 :body (some-> body json/generate-string)
                 :throw false}))

;; ---------------------------------------------------------------------------
;; Steps
;; ---------------------------------------------------------------------------

(defn build! [ctx]
  (step "Build the server image, pull the rest")
  (compose ctx {} "build" "digdir-rag")
  (compose ctx {} "pull" "typesense" "llm-stub"))

(defn up-store! [ctx]
  (step "Start Typesense alone, and pre-warm its embedding model")
  (compose ctx {} "up" "-d" "--wait" "typesense")
  (discover! ctx :typesense "typesense" 8108)
  ;; Typesense downloads the model on first use, and a collection that
  ;; embeds with it fails "Model not found" until the download lands. Waiting
  ;; for it HERE, on a throwaway collection, is what lets the product's own
  ;; materialization get exactly one attempt.
  (let [schema {:name "ingest_prewarm"
                :fields [{:name "t" :type "string"}
                         {:name "e" :type "float[]"
                          :embed {:from ["t"] :model_config {:model_name embedding-model}}}]}
        [resp elapsed attempts]
        ;; 401/403 ends the wait AT ONCE: that is the wrong key, not a model
        ;; download, and retrying it for 15 minutes would report it as the embedding-model download issue.
        (wait-until (* 15 60 1000) 5000
                    #(let [r (typesense :post "/collections" schema)]
                       (when (#{200 201 401 403} (:status r)) r)))]
    (check! (#{200 201} (:status resp))
            (str "the embedding model " embedding-model " is usable")
            (cond
              (nil? resp) (str "no answer after " attempts " attempt(s), " (quot elapsed 1000) "s")
              (#{401 403} (:status resp)) (str "HTTP " (:status resp) " - the harness's Typesense key is not "
                                               "the container's. That is a split environment, NOT the embedding-model download issue")
              :else (str attempts " attempt(s), " (quot elapsed 1000) "s"
                         (when (> attempts 1) " - this is the embedding-model download issue's download, measured"))))
    (typesense :delete "/collections/ingest_prewarm")))

(def setup-mains
  "README step 3, verbatim and in order."
  ["digdir.setup.bootstrap" "digdir.setup.first-admin"
   "digdir.setup.demo-tenant" "digdir.setup.demo-dataset"])

(defn setup!
  "Answers each step's full output by namespace: the staged-store scenario reads
   the bootstrap's, which is where boot's migrations log."
  [ctx]
  (step "The README's setup steps, with the server stopped")
  (into {}
        (for [ns-name setup-mains]
          (let [r (compose ctx {:continue true :out :string :err :string}
                           "run" "--rm" "--no-deps" "digdir-rag"
                           "java" "-cp" "app.jar" "clojure.main" "-m" ns-name)
                out (str (:out r) (:err r))]
            (say (str/join "\n" (map #(str "    │ " %) (take-last 12 (str/split-lines out)))))
            (check! (zero? (:exit r)) (str ns-name " exits 0") (str "exit " (:exit r)))
            [ns-name out]))))

(def services #{"typesense" "digdir-rag" "llm-stub"})

(defn- service-states
  "`compose ps --all`, as {service [state health]}. Compose prints one JSON
   object per line (newer) or one JSON array (older); both are read."
  [ctx]
  (let [out (str/trim (str (:out (compose ctx {:out :string} "ps" "--all" "--format" "json"))))
        rows (if (str/starts-with? out "[")
               (json/parse-string out true)
               (map #(json/parse-string % true) (remove str/blank? (str/split-lines out))))]
    (into (sorted-map) (map (fn [r] [(:Service r) [(:State r) (:Health r)]])) rows)))

(defn up-server! [ctx]
  (step "Start the server and the LLM stub")
  (compose ctx {} "up" "-d" "--wait" "digdir-rag" "llm-stub")
  ;; `--wait` waits for HEALTHY only where a service HAS a healthcheck; one
  ;; without it passes as soon as it runs. So assert what compose reports: the
  ;; three services, each running AND healthy, and no other container.
  (let [states (service-states ctx)]
    (check! (and (= services (set (keys states)))
                 (every? #(= ["running" "healthy"] %) (vals states)))
            "exactly the three services, each running AND healthy (compose ps)" states))
  (discover! ctx :server "digdir-rag" 8080)
  (discover! ctx :stub "llm-stub" 8000))

(defn login!
  "The admin login a person does in a browser, scripted. With
   DIGDIR_LOG_CONFIRMATION_CODES the confirmation page pre-fills the code
   (`digdir.auth.views`), so no log scraping is needed."
  []
  (step "Log in as the first admin")
  (let [client (http/client {:cookie-handler (java.net.CookieManager.)
                             :follow-redirects :never})
        email (get test-env "ADMIN_USER_EMAILS")
        r1 (http/post (str (url :server) "/auth")
                      {:client client :form-params {"email" email} :throw false})
        _ (check! (= 302 (:status r1)) "POST /auth redirects"
                  (str (:status r1) " → " (get-in r1 [:headers "location"])))
        page (:body (http/get (str (url :server) "/auth/confirm-email") {:client client :throw false}))
        input (re-find #"<input[^>]*name=\"confirmation-code\"[^>]*>" (or page ""))
        code (some->> input (re-find #"value=\"(\d+)\"") second)
        _ (check! (some? code) "the confirmation page carries the dev code"
                  (if code "found" (str "no code in: " (subs (or page "") 0 (min 200 (count (or page "")))))))
        r3 (http/post (str (url :server) "/auth/confirm-email")
                      {:client client
                       :form-params {"email" email "confirmation-code" code}
                       :throw false})]
    (check! (= 302 (:status r3)) "POST /auth/confirm-email accepts the code"
            (str (:status r3) " → " (get-in r3 [:headers "location"])))
    {:client client :email email}))

(defn- console-url [suffix]
  (str (url :server) "/console-api/datasets/" dataset-id "/pipelines/" pipeline-id suffix
       "?tenant=" tenant "&dataset-config-key=" dataset-config-key))

(defn- execute! [{:keys [client email]}]
  (let [r (http/post (console-url "/execute")
                     {:client client :headers {"X-User-Email" email} :throw false})]
    (check! (= 202 (:status r)) "POST …/execute is accepted"
            (str (:status r) " " (:body r)))
    (:executionId (json-body r))))

(defn- execution [{:keys [client email]} id]
  (->> (http/get (console-url "/executions")
                 {:client client :headers {"X-User-Email" email} :throw false})
       json-body :executions
       (some #(when (= id (:id %)) %))))

(def terminal-statuses
  "The executor's terminal statuses (`digdir.pipeline.executor`, update-execution-
   status!: :running then one of these). Named explicitly, so a status this
   harness does not know is waited on until timeout rather than read as done."
  #{"completed" "failed" "cancelled"})

(defn materialize!
  "One execution, to a TERMINAL status. Answers the execution record."
  [session label]
  (step (str "Materialize (" label ")"))
  (let [id (execute! session)
        [ex elapsed] (wait-until (* 10 60 1000) 2000
                                 #(let [e (execution session id)]
                                    (when (terminal-statuses (:status e)) e)))]
    (check! (some? ex) "the execution reaches a terminal status"
            (if ex (str (:status ex) " after " (quot elapsed 1000) "s") "timed out"))
    (say "    │ " (json/generate-string (select-keys ex [:status :documentsProcessed :documentsFailed :errorMessage])))
    ex))

(defn- dataset-collections
  "Typesense's own view of the dataset's collections, read directly - never
   from the product's report, so the two are independent readings."
  []
  (->> (json-body (typesense :get "/collections"))
       (filter #(str/starts-with? (:name %) "demo_norquad"))
       (map (juxt :name :num_documents))
       (into (sorted-map))))

(defn- collection-count [colls kind]
  (some (fn [[n c]] (when (str/includes? n (str "_" kind "_")) c)) colls))

(defn- sha8 [s]
  (subs (apply str (map #(format "%02x" (bit-and % 0xff))
                        (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                 (.getBytes (str s) "UTF-8"))))
        0 8))

(defn- stub-log []
  (json-body (http/get (str (url :stub) "/_log") {:throw false})))

(defn- server-logs [ctx]
  (:out (compose ctx {:continue true :out :string :err :string}
                 "logs" "--no-color" "digdir-rag")))

(def logs-dir
  "Every scenario's full server log is SAVED here, pass or fail (gitignored; CI
   uploads it). Without it, a claim like \"that error did not recur\" could only
   be checked against whatever a failing run happened to print - which is how
   this harness's first report nearly said the executor's NPE was gone."
  "server/e2e/ingest/.logs")

(def known-error-signatures
  "ERROR-level signals seen during a real ingest that are NOT this harness's
   assertions. Counted and printed per scenario, never asserted: they are
   product observations to report, and a count is only evidence because the
   whole log is saved (see `logs-dir`)."
  {"executor progress handler NPE (executor.clj:123, a signal with no :id)" "Named.getName()"
   "progress flusher interrupted (executor.clj:611)" ":pipeline/progress-flush-error"})

(defn- save-logs! [ctx scenario-key]
  (let [logs (str (server-logs ctx))
        ;; Named by PROJECT too, so two runs in one checkout cannot overwrite
        ;; each other's evidence.
        file (str logs-dir "/" project "-" (name scenario-key) "-digdir-rag.log")]
    (.mkdirs (java.io.File. logs-dir))
    (spit file logs)
    (say)
    (say "▸ Server log saved: " file " (" (count (str/split-lines logs)) " lines)")
    (doseq [[label needle] known-error-signatures]
      (say "    │ " label ": " (count (re-seq (re-pattern (java.util.regex.Pattern/quote needle)) logs))))))

;; ---------------------------------------------------------------------------
;; Scenarios
;; ---------------------------------------------------------------------------

(def retrieval-probes
  "Queries with exactly ONE right answer in the fixture, each asserting that ITS
   OWN document ranks FIRST.

   ⚠️ The TOP hit, not membership. Every query returns all four chunks
   (found-count 4), so \"the right document is in the result\" passed on a
   lighthouse query and on nonsense - a LIVENESS check wearing a relevance
   check's name (measured). Two queries, because one cannot tell
   \"ranks by relevance\" from \"always ranks Ferrovane first\". Matched on the
   MARKER: the product titles a chunk without its leading \"The\",
   so the `# ` heading would mis-match."
  [["which alloy cracks when welded because it cools too quickly" "Ferrovane"]
   ["how many steps do visitors climb to reach the gallery of the lighthouse" "Quillmoor"]])

(defn- chunk-title
  "The document title a retrieved chunk carries. The endpoint nests it under a
   key named for the documents collection, e.g.
   `{:demo_norquad_documents_… {:title \"Ferrovane alloy\" …} …}`."
  [chunk]
  (some (fn [[_ v]] (when (and (map? v) (string? (:title v))) (:title v))) chunk))

(defn- retrieve
  "One query against the debug retrieve endpoint. Its body is EDN."
  [q]
  (let [r (http/get (str (url :server) "/api/debug/typesense-retrieve")
                    {:query-params {"tenant" tenant "dataset-config-key" dataset-config-key "q" q}
                     :headers {"X-Debug-Api-Key" (get test-env "RAG_DEBUG_API_KEY")}
                     :throw false})
        body (try (edn/read-string {:default (fn [_ v] v)} (:body r)) (catch Exception _ nil))]
    {:status (:status r)
     :found (:found-count body)
     :titles (mapv chunk-title (:chunks body))}))

(defn stub-scenario! [ctx]
  (let [session (login!)
        ex (materialize! session "first run")
        colls (dataset-collections)
        log1 (stub-log)]
    (step "The ingest, read twice: the product's report and Typesense's own count")
    (check! (= "completed" (:status ex)) "status = completed" (:status ex))
    (check! (= (count fixture-titles) (:documentsProcessed ex))
            (str "documentsProcessed = " (count fixture-titles) " EXACTLY") (:documentsProcessed ex))
    (check! (= 0 (:documentsFailed ex)) "documentsFailed = 0" (:documentsFailed ex))
    (say "    │ collections: " (pr-str colls))
    (check! (= (count fixture-titles) (collection-count colls "documents"))
            "Typesense's documents collection holds exactly the fixture" (collection-count colls "documents"))

    (step "LLM routing: what the stub was actually sent (measured)")
    (let [chunks (collection-count colls "chunks")
          model (get-in ctx [:scenario-env "INGEST_LLM_MODEL"])]
      (check! (seq log1) "phrase requests reached the openai-compatible endpoint" (str (count log1) " request(s)"))
      (check! (= chunks (count log1)) "one request per chunk" (str (count log1) " requests, " chunks " chunks"))
      (let [asked (map (fn [entry] (filter #(str/includes? (str (:content entry)) %) fixture-markers)) log1)]
        (check! (every? #(= 1 (count %)) asked) "every request was about exactly one fixture document"
                (pr-str (map vec asked)))
        (check! (= fixture-markers (set (mapcat identity asked))) "every fixture document was asked about"
                (pr-str (sort (set (mapcat identity asked))))))
      (check! (every? #(= model (:model %)) log1) (str "every request sent model " model)
              (pr-str (frequencies (map :model log1))))
      (check! (every? #(= "json_schema" (:response_format_type %)) log1)
              "every request used json_schema (the openai-compatible branch)"
              (pr-str (frequencies (map :response_format_type log1))))
      ;; The key's IDENTITY, not its presence: in a provider-resolver change PR the property is
      ;; that the CONFIGURED key travels. The stub logs a
      ;; sha256 prefix, never the key.
      (let [want (sha8 (get-in ctx [:scenario-env "INGEST_OPENAI_API_KEY"]))]
        (check! (every? #(= want (:bearer_sha8 %)) log1)
                (str "every request carried the CONFIGURED key (sha8 " want ")")
                (pr-str (frequencies (map :bearer_sha8 log1))))))

    (step "The local phrase cache serves a second run")
    (let [ex2 (materialize! session "second run")
          log2 (stub-log)]
      (check! (= "completed" (:status ex2)) "status = completed" (:status ex2))
      ;; Without this, an incremental skip - a run that processed nothing -
      ;; would keep "no new requests" green for the wrong reason.
      (check! (= (count fixture-titles) (:documentsProcessed ex2))
              (str "documentsProcessed = " (count fixture-titles) " EXACTLY, again") (:documentsProcessed ex2))
      (check! (= (count log1) (count log2)) "the stub received NO new requests"
              (str (- (count log2) (count log1)) " new"))

      ;; POSITIVE CONTROL: the same run with the run's phrase cache gone must ask
      ;; again, once per chunk - or \"0 new requests\" above could be something
      ;; other than the cache. Only the RUN directory is wiped; the declared
      ;; archive directory is left alone. A no-code sabotage,
      ;; built in.
      (step "Positive control: wipe the run's phrase cache, and it must ask again")
      (let [wipe (compose ctx {:continue true :out :string :err :string}
                          "exec" "-T" "digdir-rag" "sh" "-c"
                          "find /app/cache/folder-search-phrases -mindepth 1 -delete && ls /app/cache/folder-search-phrases | wc -l")]
        (check! (and (zero? (:exit wipe)) (= "0" (str/trim (str (:out wipe)))))
                "the run's phrase cache is empty" (str "exit " (:exit wipe) ", " (str/trim (str (:out wipe) (:err wipe))))))
      (let [ex3 (materialize! session "third run, cache wiped")
            log3 (stub-log)]
        (check! (= (count fixture-titles) (:documentsProcessed ex3))
                (str "documentsProcessed = " (count fixture-titles) " EXACTLY") (:documentsProcessed ex3))
        (check! (= (count log1) (- (count log3) (count log2)))
                "it asked once per chunk again - so run 2's zero WAS the cache"
                (str (- (count log3) (count log2)) " new, against " (count log1) " on the first run"))))

    (step "Retrieval: each query ranks ITS OWN document first (no LLM in the query path)")
    (let [before (count (stub-log))]
      (doseq [[q marker] retrieval-probes]
        (let [{:keys [status titles found]} (retrieve q)]
          (check! (= 200 status) (str "the retrieve endpoint answers \"" q "\"") status)
          (say "    │ found-count " found ", ranked: " (pr-str titles))
          (check! (str/includes? (str (first titles)) marker)
                  (str "\"" q "\" ranks the " marker " document FIRST")
                  (str "top hit: " (pr-str (first titles))))))
      (say "    │ stub requests during the queries: " (- (count (stub-log)) before)))))

(defn azure-no-key-scenario! [ctx]
  (let [session (login!)
        ex (materialize! session "Azure selected, no key")
        logs (server-logs ctx)
        ;; The REFUSAL's own text (`digdir.llm.provider`, line 308:
        ;; `(str path " is unset for tenant " ...)`), not a bare mention of the
        ;; path. A bare mention matched the e2e seed's boot line
        ;; (`:azure-paths-written [...]`) and passed with a key PRESENT and no
        ;; refusal at all - measured by sabotage SB4 on 2026-09-23.
        named? (fn [s] (str/includes? (str s) "services.azure-openai.api-key is unset for tenant"))]
    (step "The documented refusal, measured")
    (check! (= 0 (:documentsProcessed ex)) "no document is processed" (:documentsProcessed ex))
    (check! (= (count fixture-titles) (:documentsFailed ex))
            (str "documentsFailed = " (count fixture-titles) " EXACTLY") (:documentsFailed ex))
    (check! (or (named? (:errorMessage ex)) (named? logs))
            "the refusal fires, naming services.azure-openai.api-key"
            (cond (named? (:errorMessage ex)) "in the execution's errorMessage"
                  (named? logs) "in the server log ONLY - errorMessage does not carry it"
                  :else "named nowhere"))
    (check! (empty? (stub-log)) "nothing reached the stub" (str (count (stub-log)) " request(s)"))
    (say "    │ Typesense after the refusal: " (pr-str (dataset-collections)))
    ;; ⚠ A FINDING, recorded rather than asserted (measured 2026-09-23, the first
    ;; run of this scenario): every document is refused, yet the execution says
    ;; `completed` with no errorMessage, so the admin UI shows a clean run and
    ;; the reason lives only in the server log. That is the newcomer-compose empty-corpus issue's family - a
    ;; success status on a run that ingested nothing. Filed as the silent Azure no-key run issue.
    ;;
    ;; RECORDED, not asserted, so a pre-existing product defect does not break
    ;; the build on this job's first run.
    ;; ⚠️ TRIGGER: WHEN the silent Azure no-key run issue IS FIXED, THIS BECOMES AN ASSERTION - replace the
    ;; block below with a `check!` that the status is not a clean `completed`,
    ;; or that errorMessage names the path, whichever the fix chose.
    (when (and (= "completed" (:status ex)) (nil? (:errorMessage ex)))
      (say "  ⚠ FINDING: status = completed and errorMessage = nil, with every document refused"
           " - the run reads as clean anywhere but the server log. When the silent Azure no-key run issue is fixed, make this an assertion."))))

;; ---------------------------------------------------------------------------
;; The staged store: the mission's done-condition
;; ---------------------------------------------------------------------------

(def done-condition
  "The config boundary-crossing work's done-condition, VERBATIM. Every word is load-bearing: THE STARTING
   STATE IS CONSTRUCTED BY US. The migration is real, and the export and the
   import are real; \"we verified a real deployment backup restores\" would be
   false."
  "A REAL migration, run against a STAGED store, exported and restored into a fresh install, with a query working afterwards.")

(def staged-dir
  "Scripts the image's OWN jar runs against a stack's store, with the server
   stopped (see each file). Nothing here is product code."
  "server/e2e/ingest/staged")

(def round-trip-source
  "The round-trip audit, loaded INTO the container by
   `staged/restore.clj`: the judgement the in-process tests make, applied to a
   store the real boot and the real import built."
  "server/test/digdir/config/round_trip.clj")

(def retracting-migrations
  "The boot migrations (`digdir.config.db`) that retract a DEFINITION. The
   staged store gives each of them something to retract."
  #{"2026-04-24-remove-env-migrated-paths"
    "2026-04-27-rerank-mode-split"
    "2026-05-08-retract-skills-retrieval-enabled"})

(defn- exchange-dir
  "Host directory the two stacks exchange files through: the backup outlives
   stack A's volumes. Under `logs-dir`, so CI uploads the reports with the logs.
   Test data only."
  []
  (str (.getCanonicalPath (io/file logs-dir)) "/" project "-exchange"))

(defn- run-staged!
  "Run `script` from `staged-dir` in a one-off container of the server's image,
   the server stopped: the scripts, the round-trip source and the exchange
   directory mounted. Answers the process result."
  [ctx script & args]
  (let [root (.getCanonicalPath (io/file "."))]
    (apply compose ctx {:continue true :out :string :err :string}
           "run" "--rm" "--no-deps"
           "-v" (str root "/" staged-dir ":/staged:ro")
           "-v" (str root "/" round-trip-source ":/round-trip/round_trip.clj:ro")
           "-v" (str (exchange-dir) ":/exchange")
           "digdir-rag" "java" "-cp" "app.jar" "clojure.main" (str "/staged/" script) args)))

(defn- exchange-edn
  "A script's report. The scripts write FILES, not stdout: Datahike's writer
   thread logs to stdout as a connection closes, and it landed in the middle of
   a printed report."
  [f]
  (let [file (io/file (exchange-dir) f)]
    (when (.exists file) (edn/read-string {:default tagged-literal} (slurp file)))))

(defn- ran? [r report]
  (and (zero? (:exit r)) (some? report)))

(defn- tail
  "Evidence for a one-off container: its exit, and on failure its last lines."
  [r]
  (if (zero? (:exit r))
    "exit 0"
    (str "exit " (:exit r) "; " (str/join " | " (take-last 8 (str/split-lines (str (:out r) (:err r))))))))

(defn staged-store-scenario!
  "The done-condition, as `done-condition` words it.

   STACK A stages a pre-migration store before anything has booted on it (THE
   STARTING STATE IS CONSTRUCTED BY US), then runs the README's setup steps:
   the bootstrap's first connection runs boot's `prepare-store!`, so the
   migrations that retract the staged definitions are the product's own, on
   its own path. Then the real default backup, WITH audit.

   STACK B is the same compose project with EMPTY volumes: no setup step runs
   there. The restore's first connection runs boot on the empty store, so the
   restore lands in what boot makes of a fresh install, and the query that
   follows can only work on what the backup brought."
  [ctx]
  (let [exch (io/file (exchange-dir))]
    (.mkdirs exch)
    (doseq [f (.listFiles exch)] (.delete f)))
  (say "    │ " done-condition)

  (step "Stack A: STAGE a pre-migration store, before anything boots on it. THE STARTING STATE IS CONSTRUCTED BY US")
  (let [r (run-staged! ctx "stage.clj")
        staged (or (exchange-edn "staged.edn") {})
        audit-ids (:audit-ids staged)
        paths (set (keys (:defined staged)))]
    (check! (ran? r (exchange-edn "staged.edn")) "the staging script ran" (tail r))
    (check! (empty? (:migration-markers staged)) "no migration marker yet: nothing has booted on this store"
            (pr-str (:migration-markers staged)))
    (check! (and (seq paths) (every? true? (vals (:defined staged))))
            (str "all " (count paths) " retractable paths are defined") (pr-str (:defined staged)))
    (check! (and (seq paths) (= paths (set (keys audit-ids)))) "each has an audit row"
            (str (count audit-ids) " row(s)"))

    (up-store! ctx)
    (let [boot (get (setup! ctx) "digdir.setup.bootstrap")
          applied (set (map second (re-seq #"Applying (?:one-shot|post-definition) config migration \{:id ([^,]+)," (str boot))))]
      (step "The REAL migrations ran, on the product's own boot path")
      (check! (every? applied retracting-migrations)
              "the bootstrap's first connection applied every definition-retracting migration"
              (pr-str (sort applied))))

    (let [r (run-staged! ctx "inspect.clj" (pr-str audit-ids) "inspect-a.edn")
          a (or (exchange-edn "inspect-a.edn") {})]
      (check! (ran? r (exchange-edn "inspect-a.edn")) "stack A's store can be read" (tail r))
      (check! (every? (set (:migration-markers a)) retracting-migrations) "stack A's store records them as applied"
              (pr-str (:migration-markers a)))
      (check! (and (seq (:paths a)) (not-any? :defined? (vals (:paths a))))
              "every staged definition is GONE: the migrations found them and retracted them"
              (pr-str (update-vals (:paths a) :defined?)))
      (check! (and (seq (:paths a)) (every? (comp zero? :values) (vals (:paths a))))
              "no value names a retracted path" (pr-str (update-vals (:paths a) :values)))
      (check! (and (seq (:audit a))
                   (every? (fn [[p row]] (and (:present? row) (= p (:config-path row)) (nil? (:references row))))
                           (:audit a)))
              "every staged audit row SURVIVED, naming its path and referencing nothing: the shape that broke restores"
              (pr-str (:audit a))))

    (step "Stack A: the default backup, as `bb migration-export` takes it (WITH audit)")
    (let [r (run-staged! ctx "export.clj")
          e (or (exchange-edn "exported.edn") {})
          report (:report e)]
      (check! (and (ran? r (exchange-edn "exported.edn")) (.exists (io/file (exchange-dir) "backup.json")))
              "the export wrote a backup" (tail r))
      (say "    │ " (pr-str report))
      (check! (<= (count audit-ids) (or (:audit report) 0)) "the backup carries at least every staged audit row"
              (str (:audit report) " audit row(s), " (count audit-ids) " staged"))
      (check! (and (pos? (or (:users report) 0)) (pos? (or (:datasets report) 0)))
              "it carries the first admin and the demo dataset: what the query in stack B will need"
              (pr-str (select-keys report [:users :datasets :dataset-pipelines]))))

    (step "Stack B: a FRESH install. Stack A's volumes are removed; only the backup survives, on the host")
    (down! ctx)
    (up-store! ctx)
    (let [r (compose ctx {:continue true :out :string :err :string}
                     "run" "--rm" "--no-deps" "digdir-rag" "sh" "-c"
                     ;; "unset" is its own answer: `[ -e "" ]` is false too,
                     ;; and would read as a fresh store.
                     (str "if [ -z \"$DATAHIKE_FILE_PATH\" ]; then echo unset;"
                          " elif [ -e \"$DATAHIKE_FILE_PATH\" ]; then echo present; else echo absent; fi"))]
      (check! (= "absent" (str/trim (str (:out r))))
              "stack B has NO store yet: the restore's first connection is the install"
              (str (str/trim (str (:out r))) "; " (tail r))))

    (step "Stack B: restore the backup (`bb migration-import`, on-conflict skip), then judge the audit trail")
    (let [r (run-staged! ctx "restore.clj")
          restored (or (exchange-edn "restored.edn") {})]
      (check! (ran? r (exchange-edn "restored.edn")) "the restore ran" (tail r))
      (say "    │ import summary: " (pr-str (:import-summary restored)))
      (check! (= (count audit-ids) (get-in restored [:import-audit :without-definition]))
              (str "the restore REPORTS exactly the " (count audit-ids)
                   " staged row(s) as arriving without a definition here")
              (pr-str (:import-audit restored)))
      (check! (and (exchange-edn "restored.edn") (empty? (:infidelities restored)))
              (str "the audit trail round-trips FAITHFULLY: all " (:backup-audit-rows restored)
                   " row(s) present, no field lost, a reference exactly where this store defines the path")
              (pr-str (:infidelities restored))))
    (let [r (run-staged! ctx "inspect.clj" (pr-str audit-ids) "inspect-b.edn")
          b (or (exchange-edn "inspect-b.edn") {})]
      (check! (ran? r (exchange-edn "inspect-b.edn")) "stack B's store can be read" (tail r))
      (check! (and (seq (:paths b)) (not-any? :defined? (vals (:paths b))))
              "the restore resurrected NO retracted definition" (pr-str (update-vals (:paths b) :defined?)))
      (check! (and (seq (:audit b)) (every? (fn [[p row]] (and (:present? row) (= p (:config-path row)))) (:audit b)))
              "every staged audit row arrived, naming its path" (str (count (:audit b)) " row(s)")))

    (up-server! ctx)
    (step "Stack B: a query working afterwards, on the RESTORED tenant and dataset (no setup step ran here)")
    (let [session (login!)
          ex (materialize! session "on the restored config")]
      (check! (= "completed" (:status ex)) "status = completed" (:status ex))
      (check! (= (count fixture-titles) (:documentsProcessed ex))
              (str "documentsProcessed = " (count fixture-titles) " EXACTLY") (:documentsProcessed ex))
      (doseq [[q marker] retrieval-probes]
        (let [{:keys [status titles found]} (retrieve q)]
          (check! (= 200 status) (str "the retrieve endpoint answers \"" q "\"") status)
          (say "    │ found-count " found ", ranked: " (pr-str titles))
          (check! (str/includes? (str (first titles)) marker)
                  (str "\"" q "\" ranks the " marker " document FIRST")
                  (str "top hit: " (pr-str (first titles)))))))))

(defn run-scenario! [ctx scenario-key f]
  (let [ctx (assoc ctx :scenario-env (get scenarios scenario-key))]
    (say) (say "════ scenario " (name scenario-key) " ════")
    (down! ctx)
    (try
      ;; :staged-store brings up its two stacks itself.
      (when-not (= :staged-store scenario-key)
        (up-store! ctx)
        (setup! ctx)
        (up-server! ctx))
      (f ctx)
      (catch Exception e
        ;; The stack is about to be torn down, so this is the last chance to
        ;; show why - CI's own log step would find nothing left to read.
        (say) (say "── container logs (tail), scenario " (name scenario-key) " ──")
        (doseq [svc ["digdir-rag" "llm-stub" "typesense"]]
          (say "── " svc)
          (compose ctx {:continue true} "logs" "--no-color" "--tail" "150" svc))
        (throw e))
      (finally
        ;; Once, on every path.
        (save-logs! ctx scenario-key)
        (if (:keep ctx)
          (say "▸ Left up (--keep). Tear it down with: INGEST_PROJECT=" project " INGEST_AZURE_OPENAI_USE_AZURE=false "
               (str/join " " (:compose-cmd ctx)) " -p " project " --env-file " env-file " "
               (str/join " " compose-files) " down -v")
          (down! ctx))))))

(defn -main
  "`compose-cmd` is bb.edn's probe result. Args: `--no-build`, `--keep` (leave
   the last scenario's stack up), `--keep-going` (record failed checks and
   continue - for sabotage runs), and scenario names (`stub`, `azure-no-key`,
   `staged-store`) to run a subset."
  [compose-cmd args]
  (let [flags (set args)
        wanted (or (seq (keep #{"stub" "azure-no-key" "staged-store"} args)) ["stub" "azure-no-key" "staged-store"])
        ctx {:compose-cmd compose-cmd :keep (contains? flags "--keep")}]
    (binding [*keep-going* (when (contains? flags "--keep-going") (atom []))]
     (try
      (require-build-config! (assoc ctx :scenario-env (:stub scenarios)))
      (when-not (contains? flags "--no-build")
        (build! (assoc ctx :scenario-env (:stub scenarios))))
      (say "▸ project " project ", image " image " (keyed on " (first image-inputs) " build-input entries), built for "
           (require-native-image! (contains? flags "--no-build")) ", the daemon's own platform"
           ", the only caller variables compose sees (names): "
           (str/join " " (sort (keys (select-keys (into {} (System/getenv)) passed-through)))))
      (doseq [s wanted]
        (let [f (case s "stub" stub-scenario! "azure-no-key" azure-no-key-scenario! "staged-store" staged-store-scenario!)]
          (if *keep-going*
            ;; --keep-going runs EVERY scenario even after one has failed, so
            ;; a sabotage run records everything that catches a break.
            (try (run-scenario! ctx (keyword s) f)
                 (catch Exception e (swap! *keep-going* conj (str s " aborted: " (ex-message e)))))
            (run-scenario! ctx (keyword s) f))))
      (when (seq (some-> *keep-going* deref))
        (throw (ex-info (str (count @*keep-going*) " check(s) failed: " (str/join "; " @*keep-going*))
                        {:failed @*keep-going*})))
      (say) (say "✔ ingest e2e: every check passed for " (str/join ", " wanted))
      (catch clojure.lang.ExceptionInfo e
        (say) (say "✖ ingest e2e FAILED: " (ex-message e))
        (System/exit 1))))))
