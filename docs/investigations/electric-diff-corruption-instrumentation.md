# Naming the source of an Electric `:diff-corruption`

How to turn `:diff-corruption` from a search into a lookup. Written while
diagnosing #525, where it reduced a hundred-plus `e/diff-by` sites to one
expression with a file and a line.

## The symptom

```
:diff-corruption [-3 4 0 1 0 0 0 1 0 0 1 0 1 0 1 1 0 1 0 1 0 1 1 0 1 0 0 0]
ERROR hyperfiddle.electric-ring-adapter3: Websocket handler failure
java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1
```

The Electric session dies and the browser reports *"Server process crash"*.

## Two things to know before you read that line

**1. The vector is a LOCATION, not a payload.** `runtime3` prints
`(slot-path slot)`, which walks up the frame chain collecting slot ids. It says
*where* the check failed, not *what* was wrong. An identical vector across runs
means the same code location failed, not that identical data arrived.

**2. The check is a size disagreement.**

```clojure
(when-not (= (- degree grow) (aget session session-slot-size))
  (prn :diff-corruption (slot-path slot)))
```

`(- degree grow)` is the size the incoming diff believes the collection had;
`session-slot-size` is the size the receiving session believes it has. They
disagree. In #525 the numbers were `{:degree 1 :grow 1 :session-size 1}` — a
diff describing growth **from empty** arriving at a collection that already
held one element.

⚠️ **The exception carries nothing.** `ex-data` is empty and
`hyperfiddle.electric.debug3/get-async-trace` returns nothing, because this is
a raw `aset` failure inside Electric's own diff application rather than a
wrapped user-code exception. Do not spend time there.

## The instrumentation

Electric ships its source in the jar, and `server/src` precedes the jar on the
classpath, so the namespace can be shadowed:

```sh
unzip -o -q ~/.m2/.../electric-<version>.jar \
  hyperfiddle/electric/impl/runtime3.cljc -d /tmp/erx
mkdir -p server/src/hyperfiddle/electric/impl
cp /tmp/erx/hyperfiddle/electric/impl/runtime3.cljc \
   server/src/hyperfiddle/electric/impl/
```

Then extend the corruption check:

```clojure
(when-not (= (- degree grow) (aget session session-slot-size))
  (prn :diff-corruption (slot-path slot))
  (let [^Frame f (slot-frame slot)
        nid  (- -1 (slot-id slot))
        cdef (frame-cdef f)]
    (prn :diff-corruption/node
      {:ctor-key    (first (frame-ctor f))
       :node-id     nid
       :node-sites  (vec (.-nodes cdef))
       :signal-meta (.-meta ^Signal (aget ^objects (.-nodes f) nid))
       :sizes       {:degree degree :grow grow
                     :session-size (aget session session-slot-size)}})))
```

## Reading the output

```clojure
{:ctor-key    :digdir.playground.ui/PlaygroundChatFull
 :node-id     2
 :node-sites  [nil nil :client :server ...]
 :signal-meta {:hyperfiddle.electric.impl.lang3/line   1998
               :hyperfiddle.electric.impl.lang3/column 26
               :hyperfiddle.electric.impl.lang3/def    PlaygroundChatFull
               :hyperfiddle.electric.impl.lang3/ns     digdir.playground.ui}}
```

`::lang3/line` and `::lang3/column` are the answer: the exact expression.

**Slot ids are signed, and the sign is the type.** `runtime3` mints
`(node frame id)` as `(->Slot frame (- -1 id))` and `(call frame id)` as
`(->Slot frame id)`:

| slot id | meaning | index |
|---|---|---|
| negative | a **node** (a `let` binding / signal) | `(- -1 slot-id)` |
| non-negative | a **call** | the id itself |

So `-3` is node 2, not call 3. `frame-ctor`'s second element is a cdef
**variant** selector, not a position — do not read it as an index into source.

⚠️ **Source metadata is on the SIGNAL, not the `Cdef`.** `Cdef` is
`[frees nodes calls result build]` and carries no location; its `nodes` vector
holds per-node **sites** (`:client`/`:server`). Looking only at the `Cdef` will
convince you the mapping does not exist. It does — on `(.-meta signal)`.

## Working practice

- **The slot-path is a falsification test.** It is stable across runs, machines
  and unrelated edits. *If a change does not move it, that change did not touch
  the site* — so reject the candidate without a build. During #525 this refuted
  two probes and one plausible fix that had a real mechanism behind it.
- **Prefer `bb dev-fullstack`** for the loop: hot reload is seconds against
  ~70s for a `bb build-client` cycle. It needs `DIGDIR_ALLOW_DEV_CLIENT_EVAL=true`,
  because the `:dev` client loads modules via `goog.globalEval` which the CSP
  otherwise blocks.
- **Remove the shadowed namespace when finished.** A stale copy silently pins
  Electric's runtime to whatever version you extracted.
