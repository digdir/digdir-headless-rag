(ns user)

;; Under the full :dev alias, auto-load `dev` so the REPL is ready immediately.
;; Lightweight tooling aliases can opt out and avoid pulling in shadow-cljs.
(when-not (Boolean/getBoolean "digdir.skip-user-dev")
  (require 'dev))
