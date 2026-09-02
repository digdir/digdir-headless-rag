(ns digdir.ui.deployment
  "Deployment identity for the Diagnostics tab: what is running, here, now.

   Every value is a fact read out of this JVM. Nothing is compared against a
   remote and nothing renders a verdict, because the Admin UI is served by the
   process it describes — a currency check made from here could only ever
   confirm itself.

   The two versions come from different mechanisms and can legitimately
   disagree, so each is labelled with its source and neither is reconciled
   against the other:

     VERSION                          the deploy stamps the git SHA into the
                                      container environment (deploy.yml)
     :hyperfiddle/electric-user-version  the client build bakes `git describe`
                                      into resources/electric-manifest.edn

   Absent is a first-class outcome here, not an edge case. A missing value
   renders as the word `unknown` plus the reason it is unknown — never blank,
   never a dash, never `dev`, never a zeroed SHA or a plausible-looking date.
   A field that silently draws nothing is indistinguishable from a field we
   failed to draw."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.lang.management ManagementFactory)
           (java.time Instant)))

(def version-absent
  "unknown — VERSION not set in this environment")

(def electric-version-absent
  "unknown — electric-manifest.edn not present")

(def electric-version-empty
  "unknown — electric-manifest.edn carries no version")

(def electric-version-unreadable
  "unknown — electric-manifest.edn unreadable")

(def started-at-absent
  "unknown — process start time unavailable")

(def built-at-absent
  "unknown — BUILD_TIMESTAMP not set in this environment")

(def built-at-unreadable
  "unknown — BUILD_TIMESTAMP is not an ISO-8601 instant")

(defn- present
  "Trimmed s when it carries a non-whitespace value, else nil.

   `str/blank?` rather than `nil?` deliberately. VERSION arrives from a shell,
   and an empty or whitespace-only export is the ordinary way for it to be set
   and still say nothing; `nil?` would let that through and render a blank
   field that looks like a drawing bug rather than a missing value."
  [s]
  (when-not (str/blank? s) (str/trim s)))

(defn render-version
  "Display string for the deploy-stamped VERSION, given its raw value."
  [raw]
  (or (present raw) version-absent))

(defn render-electric-version
  "Display string for the client build's version, given the manifest's content.

   `nil` content means the resource itself is absent, which is a different
   fact from a manifest that exists and says nothing — the two get different
   reasons rather than one convenient message covering both."
  [content]
  (if (nil? content)
    electric-version-absent
    (try
      (or (present (:hyperfiddle/electric-user-version (edn/read-string content)))
          electric-version-empty)
      (catch Exception _ electric-version-unreadable))))

(defn render-started-at
  "Display string for JVM start time, given epoch millis.

   Labelled `process started` wherever it is shown. It is emphatically not a
   build time: a container restarted without redeploying moves this and leaves
   both versions untouched."
  [millis]
  (if (nil? millis)
    started-at-absent
    (try
      (str (Instant/ofEpochMilli millis))
      (catch Exception _ started-at-absent))))

(defn render-built-at
  "Display string for the image's build time, given its raw value.

   ⚠️ BUILD TIME, NOT DEPLOY TIME, AND THE DIFFERENCE IS NOT A DETAIL. Deploy
   time cannot be baked into an image at all: an image is built once and may be
   deployed many times, so a stamp written at build would name the first deploy
   and quietly misdate every later one. What CAN be baked is when the image was
   made, and that is what this is.

   ⚠️ IT DOES NOT FULLY RESOLVE RESTART-VERSUS-REDEPLOY, and saying so is the
   point of labelling it precisely. Together with `process started` it settles
   the common cases:

     built-at old, started recent      -> a restart, not a deploy
     built-at recent, started recent   -> a new image was deployed

   But redeploying a BYTE-IDENTICAL image looks exactly like a restart from in
   here, because both versions and the build stamp are unchanged and only the
   process start moves. That case is invisible from inside the container by
   construction — the distinguishing fact lives in the container's own creation
   time, which this process cannot see. Do not add a field claiming to answer
   it.

   Parsed rather than passed through, so a malformed stamp is reported as
   malformed instead of being displayed as though it were a date."
  [raw]
  (if-let [v (present raw)]
    (try
      (str (Instant/parse v))
      (catch Exception _ built-at-unreadable))
    built-at-absent))

(defn- manifest-content
  "Content of electric-manifest.edn, or nil when there is no such resource."
  []
  (try
    (some-> (io/resource "electric-manifest.edn") slurp)
    (catch Exception _ nil)))

(defn- start-millis []
  (try
    (.getStartTime (ManagementFactory/getRuntimeMXBean))
    (catch Exception _ nil)))

(defn collect
  "Read deployment identity out of this process, already rendered for display."
  []
  {:version          (render-version (System/getenv "VERSION"))
   :electric-version (render-electric-version (manifest-content))
   :started-at       (render-started-at (start-millis))
   :built-at         (render-built-at (System/getenv "BUILD_TIMESTAMP"))})
