(ns build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [clojure.tools.logging :as log]
            [shadow.cljs.devtools.api :as shadow-api]
            [shadow.cljs.devtools.server :as shadow-server]))

(def electric-user-version (b/git-process {:git-args "describe --tags --long --always --dirty"}))
#_(def electric-user-version (slurp "version.txt"))

;; ---------------------------------------------------------------------------
;; Client input hash — the fallback version when nothing supplies one (#524)
;; ---------------------------------------------------------------------------
;;
;; WHY A HASH OF INPUTS RATHER THAN OF THE BUILT CLIENT. The obvious idea is to
;; hash the compiled bundle, and it is CIRCULAR: `version` is consumed as a
;; `:closure-defines` entry DURING `shadow-api/release` below, so it must exist
;; before the client compiles. A hash of the compile's own product cannot be
;; the value baked into that product.
;;
;; WHY NOT A COMMIT SHA. `server.Dockerfile` copies no `.git`, so there is none
;; to read inside the build; Kamal supplies one from the host, plain `docker
;; compose build` supplies nothing, and that asymmetry is the whole of #524. A
;; SHA is also a worse fit on its merits: it changes when nothing client-facing
;; did.
;;
;; ⚠️ THE ERROR DIRECTION IS THE DESIGN. Over-triggering costs one unnecessary
;; "reload" prompt. Under-triggering costs a stale client accepted and then
;; killed by an array-index error while decoding a message it cannot
;; understand — the failure #524 exists to stop, and one that has already cost
;; this project a day of misattributed crashes. Those are not comparable, so
;; this set errs deliberately wide.

(def ^:private client-input-roots
  "Everything on the `:prod` client compile path: `:paths [\"src\" \"resources\"]`
   plus the `:prod` alias's `[\"src-prod\"]` (server/deps.edn:96,118).

   ⚠️ `.clj` files are INCLUDED, not just `.cljs`/`.cljc`. A Clojure namespace
   can be pulled into the client through `:require-macros`, so excluding it
   would leave a hole exactly where the guard is supposed to close one — a
   macro edit would change client output while the hash stayed put. That is the
   under-trigger direction, so the set takes the whole tree instead.

   NOT included: `src-build` (this file — the builder, never compiled into the
   client) and `src-dev` (not on the `:prod` classpath, and not in the image)."
  ["src" "src-prod" "resources"])

(def ^:private client-input-extra-files
  "Not under a source root, but they change what the client compiles to:
   dependency versions and the shadow build configuration."
  ["deps.edn" "shadow-cljs.edn"])

(def ^:private client-input-excluded-prefixes
  "⚠️ BOTH ARE PRODUCED BY THIS BUILD, so hashing them is the circularity this
   design exists to avoid.

   `build-client` happens to delete both before we are called, which would make
   the exclusion redundant TODAY — and that is precisely why it is written out.
   Depending on a delete happening earlier in the same function is the kind of
   implicit coupling that breaks silently when someone reorders it, and the
   symptom would be a hash that stops discriminating: the guard back to being
   vacuous, wearing a different disguise."
  ["resources/public/admin_app/js" "resources/electric-manifest.edn"])

(defn- client-input-files []
  (->> (concat (mapcat (comp file-seq io/file) client-input-roots)
               (map io/file client-input-extra-files))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       (remove (fn [p] (some #(str/starts-with? p %) client-input-excluded-prefixes)))
       ;; Sorted so the digest depends on content, not on filesystem order.
       sort))

(defn client-input-hash
  "A short digest of everything the client compiles from.

   Changes exactly when a client input changes; needs no git, no build arg and
   no network. Returns e.g. \"in-9f3a1c72e4b1\" — prefixed so a value in a log
   or a URL is self-describing about where it came from."
  []
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        files (client-input-files)]
    (doseq [p files]
      ;; Path AND content: a rename changes the client without changing any
      ;; file's bytes.
      (.update md (.getBytes ^String p "UTF-8"))
      (.update md (java.nio.file.Files/readAllBytes (.toPath (io/file p)))))
    (log/info 'client-input-hash {:files (count files)})
    (str "in-" (subs (apply str (map #(format "%02x" %) (.digest md))) 0 12))))

(defn resolve-version
  "The version to bake in: what was supplied, or the input hash when nothing was.

   A BLANK value counts as nothing supplied. That is the #524 case exactly —
   `server.Dockerfile` passes `:version \"$VERSION\"` from an ARG with no
   default, so an unset arg arrives as an empty string rather than as absent."
  [version]
  (if (str/blank? (str version))
    (client-input-hash)
    version))

(defn build-client "
invoke like: clj -X:build:prod build-client`
Note: do not use `clj -T`, because Electric shadow compilation requires
application classpath to be available"
  [{:keys [optimize debug verbose version]
    :or {optimize true, debug false, verbose false, version electric-user-version}
    :as config}]
  (log/info 'build-client (pr-str config #_argmap))
  ;; #524: a blank version is what an unset VERSION build-arg produces, and
  ;; baking it makes the stale-client guard compare "" to "" and pass
  ;; everything. Fall back to a hash of the client's inputs.
  (let [version (resolve-version version)
        config (assoc config :version version)]
  (b/delete {:path "resources/public/admin_app/js"})
  (b/delete {:path "resources/electric-manifest.edn"})

  ; bake electric-user-version into artifact, cljs and clj
  (b/write-file {:path "resources/electric-manifest.edn" :content (-> config (dissoc :version) (assoc :hyperfiddle/electric-user-version version))})

  ; "java.lang.NoClassDefFoundError: com/google/common/collect/Streams" is fixed by
  ; adding com.google.guava/guava {:mvn/version "31.1-jre"} to deps,
  ; see https://hf-inc.slack.com/archives/C04TBSDFAM6/p1692636958361199
  (shadow-server/start!)
  ;; ⚠️ THE FAILURE HAS TO NAME ITS OWN CAUSE. This was
  ;;
  ;;     (assert (= shadow-status :done) "shadow-api/release error")
  ;;
  ;; which throws away everything shadow reported. Heap exhaustion in Closure's
  ;; `:advanced` pass happens on a worker thread, so it never propagates to this
  ;; one; shadow returns a non-`:done` status and the assert rendered it as
  ;; `Assert failed: shadow-api/release error`, a message with no relationship
  ;; to the cause. A user hit exactly that on a clean container build and could
  ;; only find the real `java.lang.OutOfMemoryError: Java heap space` by
  ;; re-running the whole build with `--progress plain` and reading the raw log.
  ;;
  ;; A check that hides its own cause costs more than the fault it reports.
  (let [status (try
                 (shadow-api/release :prod
                   {:debug   debug,
                    :verbose verbose,
                    :config-merge
                    [{:compiler-options {:optimizations (if optimize :advanced :simple)}
                        :closure-defines  {'hyperfiddle.electric-client3/ELECTRIC_USER_VERSION version}}]})
                 (catch Throwable t
                   (throw (ex-info (str "Electric client release build threw: " (.getMessage t))
                                   {:build/phase :shadow-release} t))))]
    (when-not (= status :done)
      (let [described (let [s (pr-str status)]
                        (if (> (count s) 800) (str (subs s 0 800) " ...(truncated)") s))]
        (throw (ex-info (str "Electric client release build did not complete. "
                             "shadow-cljs returned: " described
                             " -- if this build is running in a container, check "
                             "that it has enough memory: the :advanced pass is "
                             "heap-hungry and its failure surfaces here.")
                        {:build/phase :shadow-release
                         :shadow/status status})))))
  (shadow-server/stop!)
  (log/info "client built")))

(def class-dir "target/classes")

(defn uberjar
  [{:keys [optimize debug verbose ::jar-name, ::skip-client, version]
    :or {optimize true, debug false, verbose false, skip-client false, version electric-user-version}
    :as args}]
  ; careful, shell quote escaping combines poorly with clj -X arg parsing, strings read as symbols
  (log/info 'uberjar (pr-str args))
  (b/delete {:path "target"})

  (when-not skip-client
    (build-client (select-keys args [:optimize :debug :verbose :version])))

  (b/copy-dir {:target-dir class-dir :src-dirs ["src" "src-prod" "resources"]})
  (let [jar-name (or (some-> jar-name str) ; override for Dockerfile builds to avoid needing to reconstruct the name
                   (format "%s.jar" version))
        aliases [:prod]]
    (b/uber {:class-dir class-dir
             :uber-file (str "target/" jar-name)
             :basis     (b/create-basis {:project "deps.edn" :aliases aliases})})
    (log/info jar-name)))
