#!/usr/bin/env bb
;; Deployment smoke tests — UNAUTHENTICATED ONLY.
;;
;; Two checks, each with the condition that makes it go red written beside it,
;; and each proven red once before being trusted (see the PR body for the runs).
;;
;; ⚠️ TWO WAYS A GREEN HERE COULD BE MEANINGLESS, both measured on this service
;; rather than assumed:
;;
;;   1. An auth redirect ERASES DISTINCTIONS. Unauthenticated, a real page and a
;;      fabricated one both return 302 to /auth — same status, same target, same
;;      zero-length body. So a 302 can never be evidence that a page exists, and
;;      check B says so in its own text rather than leaving a reader to infer it.
;;
;;   2. The SPA fallback answers 200 for paths that do not exist
;;      (`wrap-index-page`, http.clj:244 — see the #330 comment at :254).
;;
;;      MEASURED: the fallback IS reachable unauthenticated, on any path whose
;;      extension is exempt from auth. Measured on this service:
;;        /admin_app/styles.css               200  164751   <- REAL asset, real content
;;        /admin_app/DOES-NOT-EXIST-zzz.css   200    1512   <- does not exist
;;        /DOES-NOT-EXIST-zzz.js              200    1512   <- does not exist
;;        /DOES-NOT-EXIST-zzz.png             200    1512   <- does not exist
;;        /DOES-NOT-EXIST-zzz.html            302       0
;;        /DOES-NOT-EXIST-zzz                 302       0
;;
;;      MECHANISM, from source: http.clj:153 computes static-url? from
;;        #"\.(css|js|png|jpg|jpeg|gif|ico|svg)$"
;;      and :157 -- `static-url? (next-handler ring-req)  ; Allow static files`
;;      -- is the FIRST cond clause, ahead of every auth check. Exempt extension
;;      means no auth, straight through to the fallback. That regex is the
;;      complete list: .json and .html are absent from it, which is why they
;;      redirect. Ordering explains the redirects; it does not explain the 200s.
;;
;;      CONSEQUENCE: a status-only assertion on a static asset is worthless
;;      TODAY, authenticated or not. "Is the JS bundle served?" returns 200 for a
;;      bundle that is not there. LENGTH DOES discriminate: a real asset returns
;;      its real body (164,751 bytes for the stylesheet) and the fallback is
;;      always 1,512. Assert on size or content, never status. See #406.
;;
;;      NOTE ON PATHS: this app serves assets ONLY under /admin_app/ (plus
;;      /system-explorer.html). Paths like /js/main.js and /style.css are not
;;      real here -- they return 1,512 because they do not exist, not because a
;;      real asset was shadowed. There is no live #330 shadowing demonstrated.
;;
;; What survives as a real discriminator is the API surface and /up, which is
;; what check A uses.
;;
;; Usage:  bb smoke                     (defaults to the test host)
;;         SMOKE_HOST=https://other-host.example bb smoke
;;
;; The usage line is deliberately ASCII: a Unicode ellipsis here pastes into a
;; shell as a literal character and produces a URL nobody can debug by reading.
(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def ^:private host (or (System/getenv "SMOKE_HOST") "https://test.rag.digdir.cloud"))
(def ^:private failures (atom 0))
(def ^:private unrunnable (atom 0))

;; EXIT CODES ARE TWO DIFFERENT FACTS, and a pipeline must act on them
;; oppositely:
;;   0  every check ran and passed
;;   1  A CHECK RAN AND FAILED           -> the deployment is broken: roll back
;;   2  THE HARNESS COULD NOT RUN A CHECK -> fix the configuration; that check
;;                                          judged nothing about the deployment
;; Conflating them is worse than losing one, because the second masquerades as
;; the first: a missing secret looks like a failing deployment and sends someone
;; to debug a service that is fine.

(def ^:private no-redirect-client
  "A CLIENT-level setting, not a request-level one. `:follow-redirects` passed in
   the request map is silently ignored — the probe then follows the redirect and
   reports 200 with no location header, which reads as \"auth is not enforced\".
   Caught because B1/B2 went red against a service curl had just shown returning
   302; a check asserting 200 would have gone green off the same broken probe."
  (http/client {:follow-redirects :never}))

(defn- GET-with
  "GET with one extra header. Separate from GET so the header name and value are
   data — never interpolated into a command line, which ps and a transcript would
   capture (this is credential-shaped input even when the credentials are fake)."
  [path header value]
  (try
    (http/get (str host path)
              {:client no-redirect-client :throw false :timeout 15000
               :headers {header value}})
    (catch Exception e {:status :unreachable :error (ex-message e)})))

(defn- GET [path]
  (try
    (http/get (str host path)
              {:client no-redirect-client :throw false :timeout 15000})
    (catch Exception e {:status :unreachable :error (ex-message e)})))

(defn- cannot-run
  "A check that could not execute. NOT a failure — nothing was judged. Counted
   separately so the exit code can say which of the two happened."
  [id desc cause]
  (swap! unrunnable inc)
  (println (format "  UNRUN %-4s %s" id desc))
  (println (format "        cause:    %s" cause))
  (println  "        nothing was judged by this check; the deployment is not implicated"))

(defn- check [id desc red-when ok? detail]
  (if ok?
    (println (format "  PASS  %-4s %s" id desc))
    (do (swap! failures inc)
        (println (format "  FAIL  %-4s %s" id desc))
        (println (format "        red when: %s" red-when))
        (println (format "        observed: %s" detail)))))

;; ---------------------------------------------------------------------------
;; POSITIVE CONTROL — runs first, so a green run cannot come from a probe that
;; could not reach the host at all. Every assertion below is conditional on the
;; host answering; without this, "all redirects as expected" and "nothing
;; resolves" look identical.
;; ---------------------------------------------------------------------------
(println (str "smoke: " host))
(let [{:keys [status body]} (GET "/up")]
  (when-not (= 200 status)
    (println (format "  ABORT  positive control failed: /up returned %s" status))
    (println  "         Every check below would report a meaningless green.")
    (System/exit 2))
  (println (format "  ctrl  /up reachable, %s bytes — probe can see the host" (count (str body)))))

;; ---------------------------------------------------------------------------
;; CHECK A — the service is up and serving THIS build
;; ---------------------------------------------------------------------------
(let [{:keys [status body]} (GET "/up")]
  (check "A1" "/up returns 200 with body \"ok\""
         "the service is down, or something fronts it that answers before the app"
         (and (= 200 status) (= "ok" (str/trim (str body))))
         (format "status=%s body=%s" status (pr-str (str body)))))

(let [{:keys [status body]} (GET "/v1/models")
      parsed (try (json/parse-string (str body) true) (catch Exception _ nil))]
  (check "A2" "/v1/* 401 carries OpenAI's error OBJECT with invalid_api_key"
         "the deployed build predates 130ee8a, or /v1 is not routed"
         (and (= 401 status) (= "invalid_api_key" (get-in parsed [:error :code])))
         (format "status=%s error=%s" status (pr-str (:error parsed)))))

(let [{:keys [status body]} (GET "/api/nope")
      parsed (try (json/parse-string (str body) true) (catch Exception _ nil))]
  (check "A3" "/api/* 401 carries the platform's error STRING, not an object"
         "the per-surface 401 has regressed to one shape, or /api is not routed"
         (and (= 401 status) (string? (:error parsed)))
         (format "status=%s error=%s" status (pr-str (:error parsed)))))

(let [real (GET "/admin_app/styles.css")
      fake (GET "/admin_app/DOES-NOT-EXIST-zzz.css")
      rlen (count (str (:body real)))
      flen (count (str (:body fake)))]
  ;; Both paths carry an exempt extension (http.clj:153), so both bypass auth and
  ;; BOTH RETURN 200 — status cannot tell them apart, and neither can a
  ;; status-only assertion on any static asset.
  ;;
  ;; DIFFERENTIAL rather than a hardcoded fallback size, and the honest reason is
  ;; narrower than "a constant would break": an order-of-magnitude comparison
  ;; against a hardcoded 1512 would still discriminate correctly today even
  ;; though the shell now measures 1510. It would be STALE BUT FUNCTIONAL.
  ;;
  ;; The reason to fetch the sibling is that a constant is a FACT SOMEBODY MUST
  ;; MAINTAIN, and nothing here would tell them it had drifted — the check stays
  ;; green while its stated basis quietly stops being true. This form has no
  ;; number to maintain: it measures the fallback in the same run it compares
  ;; against.
  ;;
  ;; Not the num_documents shape. That gate fails toward GREEN when its constant
  ;; drifts, because the repair is to update the number. This one would fail
  ;; toward RED. Opposite direction, so that comparison does not transfer.
  (check "A4" "a REAL static asset returns its own body, not the SPA fallback"
         "the asset is missing from the deployed image and the shell is served instead — invisible to a status check"
         (and (= 200 (:status real)) (= 200 (:status fake)) (> rlen (* 10 flen)))
         (format "real=%s bytes fake=%s bytes (both status %s/%s)"
                 rlen flen (:status real) (:status fake))))

(def ^:private credential-shapes
  "Malformed or invalid ways of presenting a credential. A2/A3 cover the ABSENT
   case; these cover the PRESENT-BUT-BAD case, which nothing else does."
  [["Authorization" "Bearer"]                    ; scheme, no token
   ["Authorization" "Bearer "]                   ; scheme, empty token
   ["Authorization" "Basic YWJjOmRlZg=="]        ; wrong scheme
   ["Authorization" "total-garbage"]             ; no scheme at all
   ["Authorization" "Bearer rag_deadbeef"]       ; well-formed, invalid
   ["X-API-Key" "rag_deadbeef"]                  ; invalid
   ["X-API-Key" ""]])                            ; empty

(def ^:private expected-credential-shapes 7)

;; ⚠️ WHAT THIS CHECK DOES NOT DO, written before the assertion rather than after
;; it, because the flag is what disciplines the claim.
;;
;; IT DOES NOT SEPARATE "REJECTED" FROM "NEVER LOOKED AT". Measured 2026-08-31,
;; all seven shapes above AND the no-header baseline return byte-identical
;; responses on /v1/models: 401, 106 bytes, error.code invalid_api_key. A header
;; that is parsed and refused, and a header that is discarded unread, are
;; therefore indistinguishable from outside on an endpoint that requires auth —
;; both fail closed to the same response.
;;
;; ⇒ QUEUED ASSERTION, to land the day RAG_API_TEST_KEY arrives and not before:
;;      a VALID key succeeds, and that SAME valid key with trailing garbage
;;      appended fails; therefore the header was PARSED rather than discarded.
;;   That is the only construction that separates read-from-discarded, and it
;;   costs one line. Do not approximate it with anything weaker.
;;
;; ⚠️ THE SAME ERASURE RUNS ON THE API SURFACE, and the next person will try to
;; use it for existence. Measured 2026-08-31, unauthenticated:
;;      /api/datasets       401 len=38    /api/qqqq-not-a-real-endpoint 401 len=38
;;      /api/conversations  401 len=38    /api/datasets-zzz-fake        401 len=38
;;      /v1/models          401 len=106   /v1/qqqq-not-real             401 len=106
;;   Real and fabricated endpoints are BYTE-IDENTICAL within a surface. The auth
;;   middleware refuses before routing resolves whether the endpoint exists, so a
;;   401 says as little about existence as the 302 does on the UI surface — same
;;   erasure, different mechanism.
;;
;;   Which makes three surfaces and one rule: UNAUTHENTICATED, EXISTENCE IS
;;   INVISIBLE EVERYWHERE. UI erases it with a 302, API with a 401, and static
;;   assets return 200 either way — leaking it ONLY through body size, which is
;;   why A4 asserts on size and nothing here asserts on status.
;;
;; WHAT IT DOES DO is the half that matters for safety: prove that no malformed
;; or invalid credential shape produces a SUCCESS. Ignoring a malformed header
;; and proceeding is the standard fail-open shape, and it would show up here as a
;; 2xx or a redirect rather than a 401.
(let [results (for [[h v] credential-shapes]
                (let [{:keys [status]} (GET-with "/v1/models" h v)]
                  [(str h ": " v) status]))
      bad (remove (fn [[_ st]] (= 401 st)) results)]
  (check "C1" (format "all %d malformed/invalid credential shapes are refused"
                      (count credential-shapes))
         "a malformed credential is ignored and the request proceeds — fail-open"
         (and (= expected-credential-shapes (count credential-shapes))
              (empty? bad))
         (if (empty? bad)
           (format "%d shapes, all 401" (count results))
           (format "not refused: %s" (pr-str bad))))
  (println "  note  C1 proves nothing was ACCEPTED. It cannot prove the header was")
  (println "        READ — every shape here, and no header at all, return the same")
  (println "        401/106 bytes. That distinction needs a valid key."))

(def ^:private api-key-env "RAG_API_TEST_KEY")

(defn- key-fingerprint
  "Presence, length and a truncated digest — never the value. Printed so a run
   log PROVES which credential was used without disclosing it, and so a run made
   with the wrong key is distinguishable after the fact."
  [v]
  (if (str/blank? v)
    (format "%s present=false len=0" api-key-env)
    (format "%s present=true len=%s sha256[0:8]=%s"
            api-key-env (count v)
            (subs (->> (java.security.MessageDigest/getInstance "SHA-256")
                       (#(.digest % (.getBytes ^String v "UTF-8")))
                       (map #(format "%02x" %))
                       (apply str))
                  0 8))))

;; ---------------------------------------------------------------------------
;; CHECK C2 — THE HEADER IS PARSED, NOT DISCARDED
;;
;; This is the construction pinned when the key was unavailable, built verbatim
;; and NOT approximated: a valid key SUCCEEDS, and that same valid key with
;; trailing garbage appended FAILS — therefore the header was parsed.
;;
;; Why nothing weaker will do: C1 shows no bad credential is accepted, but every
;; bad shape and the no-header baseline return byte-identical 401s, so C1 cannot
;; tell a header that was read and refused from one that was never read. Only a
;; pair whose members differ ONLY in trailing garbage isolates the parse.
;;
;; The success arm asserts on what the response CONTAINS, not its status: a 200
;; with an empty or shapeless body would pass a status check and prove nothing.
;; ---------------------------------------------------------------------------
(let [k (or (System/getenv api-key-env) "")]
  (println (str "  key   " (key-fingerprint k)))
  (if (str/blank? k)
    ;; FAIL LOUDLY, never skip. A skipped check reads as a passing one in a
    ;; summary, and this is the only check in the file that can prove acceptance.
    (cannot-run "C2" "a valid key is accepted and a mangled one is not"
                (format "%s is absent from the environment. Set it and re-run. This is a HARNESS problem, not a deployment problem — exit 2, not 1."
                        api-key-env))
    (let [good (GET-with "/v1/models" "Authorization" (str "Bearer " k))
          bad  (GET-with "/v1/models" "Authorization" (str "Bearer " k "trailing-garbage"))
          parsed (try (json/parse-string (str (:body good)) true) (catch Exception _ nil))
          models (:data parsed)
          first-id (:id (first models))]
      (check "C2a" "the valid key is ACCEPTED, and the body carries real models"
             "the key is rejected, or a 200 arrives with nothing in it"
             (and (= 200 (:status good))
                  (seq models)
                  (string? first-id)
                  (str/includes? (str first-id) "__"))
             (format "status=%s models=%s first-id=%s"
                     (:status good) (count models) (pr-str first-id)))

      (check "C2b" "the SAME key with trailing garbage is REFUSED"
             "the trailing garbage is ignored — the header was not parsed, only pattern-matched"
             (= 401 (:status bad))
             (format "status=%s" (:status bad)))

      (println "  note  C2a + C2b together are the proof: the two requests differ")
      (println "        ONLY in trailing garbage, so a server that accepts both is")
      (println "        not reading the header, and one that accepts neither has")
      (println "        rejected a valid key. Neither arm alone shows this."))))

;; ---------------------------------------------------------------------------
;; CHECK B — auth is enforced. NOTHING MORE.
;; ---------------------------------------------------------------------------
(let [real (GET "/playground")
      fake (GET "/qqqq-fabricated-does-not-exist-zzz")
      loc  (fn [r] (get-in r [:headers "location"]))]
  (check "B1" "a real page redirects to /auth when unauthenticated"
         "auth stops being enforced on the UI surface"
         (and (= 302 (:status real)) (str/includes? (str (loc real)) "/auth"))
         (format "status=%s location=%s" (:status real) (loc real)))

  (check "B2" "a FABRICATED page redirects identically — auth pre-empts routing"
         "the redirect stops covering unknown paths, exposing the SPA fallback"
         (and (= 302 (:status fake)) (str/includes? (str (loc fake)) "/auth"))
         (format "status=%s location=%s" (:status fake) (loc fake)))

  (println "  note  B1 and B2 are IDENTICAL by design: same status, same target.")
  (println "        That is the point. This check proves auth is ENFORCED and")
  (println "        proves NOTHING about whether /playground exists — a page")
  (println "        deleted from the build would still pass B1.")
  (println "        Page existence needs an authenticated probe, and that probe")
  (println "        must not use 200 as its signal (see the header of this file)."))

(println (format "\n%s  %d failure(s), %d could not run"
                 (cond (pos? @unrunnable) "SMOKE INCOMPLETE"
                       (pos? @failures)   "SMOKE FAIL"
                       :else              "SMOKE PASS")
                 @failures @unrunnable))
;; Unrunnable DOMINATES: a misconfigured harness has not judged the deployment,
;; and re-running after fixing config is the only way to know. A genuine failure
;; is still printed above and survives the re-run.
(System/exit (cond (pos? @unrunnable) 2
                   (pos? @failures)   1
                   :else              0))
