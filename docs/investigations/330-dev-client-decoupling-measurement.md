# #330 — does the server serve without a fresh client build? Measurement

**Status:** measurement complete. All four questions answered; the design in #330
survives, with one named requirement it did not previously state.

**Conditions.** Fresh worktree `dev-330` at `91b8151`, created from
`origin/release-v0.1-details`. **No Hyperfiddle activation token**: no
`server/.shadow-cljs` directory at all, so no token slot — asserted before each
run, not assumed. Own DB (`dh_330_v1`) and ports 8610–8613, so no other lane's
`bb dev` could answer a probe of mine. The `bb dev` task chain was deliberately
**bypassed** (`clojure -X:personal/dev:dev dev/-main` invoked directly), because
`-ensure-electric-token` would have linked this machine's personal token in and
destroyed the newcomer condition.

**Predictions were written to a file before any probe ran**, including the
confidence and the residual risk on each. They are reproduced verbatim below,
including the one I got wrong.

---

## P1 — API with no client build and no token

**Predicted:** serves. Confidence HIGH — #165 reordered `dev/-main` to bind Jetty
before `(shadow-cljs/watch :dev)` for exactly this reason. *Stated residual
risk: that prior measurement covered a build GATED on a prompt, not one ABSENT
entirely, and `shadow-cljs-compiler-server/start!` still runs first.*

**Observed: confirmed.** `/up` → `200` (bound in ~65 s). `/api/mcp`
unauthenticated → `401`, which is the correct rejection, so the API middleware
stack is fully wired. The log shows the ordering directly:

```
INFO digdir.api.http: 👉 http://0.0.0.0:8610
[:dev] Compiling ...
Please sign up or login to activate:  https://hyperfiddle-auth.fly.dev/login?...
```

The activation gate fires exactly as `bb.edn` documents, **and the API is
already serving through it.**

## P2 — the UI with no client build: I predicted this wrong

**Predicted:** app fails to boot — confidence HIGH on the outcome, LOW on the
mode, and I said the mode was what mattered. I offered two: (a) index served,
browser 404s on the JS, ~60%; (b) startup throws reading the missing manifest,
~40%, which would have made #330 bigger than it looks.

**Observed: neither.** A missing client asset returns **`200 OK` with
`Content-Type: text/html`** — the SPA catch-all serves index HTML for *any*
unmatched path, including invented filenames:

```
/admin_app/js/main.js            → 200  text/html  1292 bytes
/admin_app/js/does-not-exist.js  → 200  text/html
```

The browser is handed HTML where JavaScript is expected: **blank page, 200 on
every asset, no error in any log.** That is worse than either option I offered,
because both of mine were detectable. It is the same defect shape as #303 — a
failure that reports as a success — and it is the strongest argument for the
mode announcement #330 asks about. See "Consequences" below.

## P3 — a PREBUILT client under dev, no watch running

**Predicted:** yes, it serves. Confidence MEDIUM-LOW. Reasoning recorded at the
time: dev and prod use the *same* manifest path and differ only in `index-path`.
*Stated expected wrinkle: `index.dev.html` may carry a shadow dev-runtime script
expecting a websocket to the watch.*

**Observed: yes — but only through `index.prod.html`.** Server started with **no
shadow watch and no shadow server**, release bundle installed:

| index served | shell references | that asset returns |
| --- | --- | --- |
| `index.prod.html` | `/admin_app/js/main.930A1CC….js` | **200 `text/javascript`, 21,748,733 bytes** ✅ |
| `index.dev.html` | `/admin_app/js/main.js` | **200 `text/html`, 1292 bytes** ❌ |

`index.prod.html` injects the fingerprinted name **from `manifest.edn`**;
`index.dev.html` hardcodes `main.js`, which a release bundle does not contain
(`:module-hash-names true`). The wrinkle I predicted was not the operative one —
the operative difference is manifest injection versus a hardcoded filename.

**This is the requirement #330 did not state: backend-only mode must serve
`index.prod.html`.** It is a path choice, not an Electric or shadow change, so
the estimate holds.

## P4 — does the release client build need the token? **Confirmed by reach, not by reading**

**Predicted:** no. Two *reading*-level signals pointed that way: `shadow-cljs.edn`
puts the Electric hook `hyperfiddle.electric.shadow-cljs.hooks3/reload-clj` on
the **`:dev` build only** (`:prod` declares no `:build-hooks`), and
`server.Dockerfile` builds the client with no token available.

**This distinction is the whole value of the entry.** Both signals are
*appearance*: one is a config file, one is an inference about a build I did not
watch. Neither establishes that a newcomer, on this machine, can produce the
artifact. So the build was **run**:

```
$ clojure -X:build:prod build-client        # token-less worktree, no token slot
[:prod] Build completed. (255 files, 170 compiled, 0 warnings, 99.39s)
activation prompts in log: 0
→ main.10785D5C3294BB2DB272CCC201FB2B72.js   (21,750,345 bytes) + manifest.edn
```

**And the artifact was then served, because exit code 0 is not evidence either.**
Serving that self-built bundle through `index.prod.html`, no watch, no token: the
shell referenced `/admin_app/js/main.10785D5C….js` — the hash *this build*
produced, not the borrowed one — and the asset returned `200 text/javascript`,
**21,750,345 bytes, byte-matching the file on disk.**

---

## Consequences for #330

1. **Backend-only mode must serve `index.prod.html`.** One path, and the reason
   is manifest injection.
2. **The mode must announce itself, and a banner is not enough.** Because a
   missing bundle yields `200 text/html` on every asset (P2), the failure is
   invisible from inside a browser. When `manifest.edn` is absent, backend-only
   mode should say so at startup and serve an explicit page on UI routes, rather
   than a dead SPA shell.
3. **The two builds collide.** `:dev` and `:prod` both output to
   `resources/public/admin_app/js` and share one `manifest.edn`, so only one
   bundle can be current and switching modes rebuilds. Unhandled, this is a
   future "it worked yesterday" report.
4. **The newcomer needs no token for backend work** — build the client once
   (~100 s), then run backend-only indefinitely. **UI development still needs
   the token**, because the `:dev` watch build is what the hook gates. The token
   was never the gate on backend development; it is the gate on hot reload.

## Incidental findings

- **`/auth` is server-rendered HTML with no script tags** (1218 bytes, no JS), so
  the login form is reachable with no client build. The console *behind* it is
  Electric and still needs the bundle: backend-only with no built client is
  API-plus-login-page, not a usable console.
- **A dev JVM ignores `SIGTERM`** — `bb.edn` documents this as #187. Hit while
  cleaning up: a plain `kill` on the `dev/-main` process left it holding port
  8610, and `kill -9` was required.
