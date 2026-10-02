(ns digdir.boot.phrase-cache
  "Unpack the committed warm phrase cache on the first boot that finds its
   directory empty (yardarm-warmcache).

   WHAT THIS SAVES. `mk-distill-search-phrases-t` makes one LLM call per chunk
   whose phrases are not already on disk. A newcomer's first materialisation of
   the demo corpus therefore pays that bill in full, for chunks whose phrases we
   have already generated and which are identical on every installation.

   ⛔ THE KEY IS NO LONGER INSTALLATION-INDEPENDENT, AND THIS PARAGRAPH USED TO
   SAY IT WAS. The key is now
   `<sha256-short(chunk text)>-<sha256-short(provider)>-<sha256-short(model SENT)>-<sha256-short(prompt)>-<parser-version>`.
   It used to hash the configured `gpt-4o` constant, which is the same
   everywhere — that is what made a key generated here the key generated there.
   It now hashes **the provider that answers and the model actually sent**, so
   an entry cannot be served as if a different model had produced it.
   `services.llm.model`,
   `services.azure-openai.deployment-name` and `…model-name` are all
   `deployment-specific` — 'no correct global default' — so that identity
   differs per installation by construction.

   HOW THE ARCHIVE IS STILL REACHED: by DECLARING whose output it is, not by
   weakening the key. `phrase-cache-folder-v2.identity.edn` names the generator;
   `ensure-warm!` checks that the declaration describes every key, then COPIES
   the archive AS SHIPPED into a directory of its own that no run writes;
   and the lookup tries the local identity first, then the declared one,
   in whichever key grammar that archive was built.

   THE INVARIANT: a key segment is evidence only of the run
   that GENERATED the entry, so nothing downstream of generation - this unpack,
   the builder, a copy - writes one. The unpack writes nothing of its own: keys
   and phrases land exactly as shipped. It used to ADD a provider segment to
   four-segment keys, copied from the declaration, and packing that directory
   then 'verified' the provider against the very declaration it had been copied
   from.

   WHAT THE RECORD CAN THEREFORE SAY. `:verified` names exactly the identity
   segments the archive's keys carry, each checked against the declaration:
     - FOUR segments, the committed archive (built before the phrase negative-cache issue): the model
       segment, which `main` wrote from the CONFIGURED model - so
       `#{:configured-model}`. There is no provider segment; the provider exists
       only in the declaration, and nothing checks it.
     - FIVE segments, a rebuild: `#{:provider :model}`, as its generating run
       recorded them.
   Either way the check shows that the declaration and the generating run's
   record AGREE. Neither can say which model actually wrote an entry: a key
   holds names, and two generators that share a name share a key.

   ⛔ FOUR-SEGMENT KEYS ARE ACCEPTED FROM ONE ARCHIVE ONLY: the committed one,
   pinned by content hash. The old key cannot tell its generators
   apart - on main every demo install keyed on the CONFIGURED `gpt-4o`,
   whatever it sent - so accepting the SHAPE would unpack any directory of
   entries from before the phrase negative-cache issue under the declared name, foreign ones included.

   That acceptance is explicit and narrow — ONE declared, spot-checked
   generator, READ-ONLY (it lives where no run writes; writing there would be
   the laundering this must never do) and POSITIVE-ONLY (a 'this chunk has no
   phrases' claim must be local). The alternative is not safety: before the phrase negative-cache issue,
   two installations that happened to share a model name already served each
   other's entries silently, with no policy and no record.

   ⚠️ THE KEY IS NOT PROMISED TO BE STABLE, AND THIS IS BUILT FOR THAT. A change
   to the chunker, the prompt or `parser-version` orphans every archived entry —
   they are simply never read again, exactly as the cache-key docstring says. A
   change to the LOCAL model does not: tier 2 looks the archive up under the
   declared identity, whatever this installation sends. Nothing here assumes
   the key holds: the archive is data, its filename carries the parser version
   it was built under, and regenerating it is `bb phrase-cache-archive` plus a
   declaration naming the rebuilding installation (see the corpus README). There
   is no migration to write, because an orphaned entry is inert rather than
   wrong.

   ⚠️ AND IT IS AN OPTIMISATION, SO IT NEVER FAILS THE BOOT. A missing, corrupt
   or unreadable archive logs and continues. The system's behaviour is identical
   with and without it, apart from the size of the first LLM bill — which is also
   what makes the verification honest: a run with the archive absent must still
   log `:search-phrases/cache-miss`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [digdir.docs.pipeline.search-phrases :as sp])
  (:import [java.security MessageDigest]
           [java.util.zip GZIPInputStream]))

(def archive-resource
  "Classpath resource, so it rides inside the uberjar and needs no Dockerfile
   change. `server/resources` is already on `:paths` and already COPYed into the
   build stage."
  "demo-corpus/phrase-cache-folder-v2.edn.gz")

(def ^:private shipped-four-segment-archive-sha256
  "SHA-256 of the committed archive's DECOMPRESSED content - the one archive
   whose four-segment keys are accepted. Scoped to the ARTEFACT, not
   to the key shape.

   WHY A REBUILD CANNOT OBTAIN IT:
     - `bb phrase-cache-archive` refuses to pack any key that is not five
       segments, so no rebuilt archive carries a four-segment key at all;
     - an archive assembled WITHOUT the builder reaches the four-segment branch
       only if its content is byte-identical to the committed one - adding,
       dropping or changing one entry changes the hash;
     - it lives HERE, in the unpack's source, not in the identity resource,
       which is the one file the rebuild steps tell you to edit.
   What it does not defend against is somebody editing this constant. That is a
   change to the unpack's code, visible in review, and nothing in-repo can stop
   it. It also says nothing about whether the committed entries are GOOD - that
   is the spot check and the unvetted phrase-archive issue - only that they are THOSE entries.

   Once the committed archive is REBUILT FROM A FRESH MATERIALISATION
   (five-segment), this, the four-segment branch of `described-by?` and
   `sp/legacy-cache-key` can all be deleted. PACKING THE DECLARED DIRECTORY IS
   NOT A REBUILD: it holds this archive's own four-segment keys, which the
   builder refuses, and a five-segment declared directory repacks only the
   record it already had."
  "6317610796658c8df0c436eefbd4854fa92a6ffc634633e4e10e6813b608e80b")

(defn- sha256-hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (MessageDigest/getInstance "SHA-256") bs))))

(def cache-dir
  "The folder corpus's DECLARED directory - the one tier 2 reads - and NOT the
   directory a run writes. Relative, so under the container's /app it lands
   inside the `digdir-cache` volume mounted at /app/cache.

   ⛔ It used to be the run's own directory, and that was the provider-aware phrase-cache key: a local
   install whose provider and model are named like the declared ones writes its
   misses at the declared key, so in a shared directory they joined the
   archive's population and could not be told apart. In a directory nothing
   else writes, read-only is a property of the layout rather than a rule a
   write path has to remember."
  (sp/declared-cache-dir "folder"))

(defn- already-warm?
  "Does the declared directory already hold entries? Only this unpack writes it,
   so anything present is a previous unpack, and it is not redone on every boot.

   ⚠️ The consequence: a volume keeps its FIRST unpack. An image that ships a
   rebuilt archive does not replace it until the declared directory is removed."
  [^java.io.File dir]
  (boolean (some-> (.listFiles dir) seq)))

(defn- key-shape
  "How many segments an archived key has: 4 before the phrase negative-cache issue, 5 since."
  [k]
  (count (str/split k #"-")))

(def ^:private carried-by-shape
  "Which identity segments a key of each shape CARRIES, as the run that
   generated it wrote them. Nothing downstream of generation writes a segment,
   so this is also exactly what the unpack can check against the declaration,
   and `:verified` is derived from it rather than assembled per path.
   A four-segment key's model segment is `main`'s, which hashed the
   CONFIGURED model, and the name says so."
  {4 #{:configured-model}
   5 #{:provider :model}})

(defn- described-by?
  "Does `identity` describe the archived key `k`, on every identity segment
   the key carries? It only checks: the key is never rewritten.

     FOUR segments, the committed archive: content · model · prompt · version.
       The model segment must equal the declared model's hash. There is no
       provider segment to check. `ensure-warm!` reaches this only for the
       pinned archive (`shipped-four-segment-archive-sha256`).
     FIVE segments, an archive rebuilt under the current key: BOTH
       the provider and the model segment must equal the declaration's.

   The prompt and parser-version segments are not compared: they stay in the
   key, so an installation whose prompt or parser differs computes a different
   key and MISSES, exactly as it should."
  [k identity]
  (let [parts (str/split k #"-")
        [provider-seg model-seg] (sp/identity-segments identity)]
    (case (count parts)
      4 (= (nth parts 1) model-seg)
      5 (and (= (nth parts 1) provider-seg) (= (nth parts 2) model-seg))
      false)))

(defn ensure-warm!
  "Unpack the archive into `cache-dir` unless it is already populated.

   Returns a map describing what happened, never a bare nil, so a caller can log
   it and a test can assert on it:
     {:action :unpacked  :written n :verified #{:configured-model} | #{:provider :model}}
     {:action :skipped   :reason :already-populated :existing n}
     {:action :skipped   :reason :no-archive}
     {:action :skipped   :reason :no-declared-identity}
     {:action :skipped   :reason :mixed-key-shapes :shapes #{…}}
     {:action :skipped   :reason :four-segment-archive-not-the-shipped-one …}
     {:action :skipped   :reason :declaration-mismatch …}
     {:action :skipped   :reason :unreadable :error \"...\"}

   Entries land AS SHIPPED - key and phrases unchanged - so nothing in the
   declared directory was written by this system apart from the copy itself
  They are read there under the DECLARED generator's identity
   (the phrase negative-cache issue option e), never under this installation's. A declaration that does
   not describe this archive refuses the unpack rather than letting its entries
   be read under a name nobody checked.

   `archive` is anything `io/input-stream` opens; the boot passes the classpath
   resource, and a test can pass a file the real builder wrote.

   There is deliberately no zero-arity: `warm!` names `cache-dir` itself, so the
   directory the boot unpacks into is visible at the one call production makes.
  "
  ([dir-path] (ensure-warm! dir-path (io/resource archive-resource)))
  ([dir-path archive]
   (let [dir (io/file dir-path)]
     (cond
       (already-warm? dir)
       {:action :skipped :reason :already-populated
        :existing (count (.listFiles dir))}

       (nil? archive)
       {:action :skipped :reason :no-archive}

       (nil? (sp/declared-identity))
       {:action :skipped :reason :no-declared-identity}

       :else
       (try
         (let [identity (sp/declared-identity)
               content (with-open [in (GZIPInputStream. (io/input-stream archive))]
                         (.readAllBytes in))
               m (edn/read-string (String. ^bytes content "UTF-8"))
               shapes (set (map (comp key-shape key) m))
               described (count (filter #(described-by? (key %) identity) m))]
           (cond
             ;; ONE key shape per archive. A mix has no single record - some
             ;; keys carry a provider and others do not - and it is what a
             ;; builder made of a directory still holding entries written before
             ;; the phrase negative-cache fix (the builder now refuses that itself).
             (< 1 (count shapes))
             {:action :skipped :reason :mixed-key-shapes :shapes shapes
              :entries (count m)}

             ;; FOUR-segment keys from the ONE pinned archive only.
             ;; The shape cannot tell generators from before the phrase negative-cache issue apart, so any other
             ;; four-segment archive - a directory of old entries, foreign ones
             ;; included - would pass the model check below and be unpacked
             ;; under the declared name.
             (and (contains? shapes 4)
                  (not= shipped-four-segment-archive-sha256 (sha256-hex content)))
             {:action :skipped :reason :four-segment-archive-not-the-shipped-one
              :entries (count m)}

             ;; ALL or NOTHING on the declaration: one key the declaration does
             ;; not describe means it is about a different archive - or, for a
             ;; rebuilt one, that the directory held entries under more than
             ;; one IDENTITY - and unpacking the rest would ship entries under a
             ;; name nobody checked. By NAME only: two generators that share a
             ;; provider and model name share a key, and nothing here sees them.
             ;;
             (not= described (count m))
             {:action :skipped :reason :declaration-mismatch
              :declared (select-keys identity [:provider :model])
              :entries (count m) :described described}

             :else
             (do
               (.mkdirs dir)
               ;; AS SHIPPED. Writing a key here that the generating run did
               ;; not write was an earlier defect.
               (doseq [[k phrases] m]
                 (spit (io/file dir (str k ".edn")) (pr-str phrases)))
               {:action :unpacked :written (count m)
                :verified (carried-by-shape (first shapes))})))
         (catch Throwable t
           ;; An optimisation must not be able to stop a boot.
           {:action :skipped :reason :unreadable :error (.getMessage t)}))))))

(defn warm!
  "Boot entry point: unpack and log. Logs at INFO on every path, because the
   verification for this feature is reading the log — a silent success and a
   silent skip are indistinguishable to the person checking whether it worked."
  []
  (let [result (ensure-warm! cache-dir)]
    (log/info (str "Warm phrase cache: " (pr-str result)))
    result))
