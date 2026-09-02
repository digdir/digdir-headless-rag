# Q1 v1 — How can I make one form field show up only when another field has a certain value in my Altinn app?

**Grounded source**: `a979511a6b1b` (Dynamic expressions, reference) +
`cab432c85c40` (Build Expressions in Altinn Studio, how-to). Chosen by
browsing the live corpus' most-recently-modified EN docs, then reading actual
content — not from recollection (corpus is continuously revised).
**Topic**: Altinn Studio app logic / conditional form behaviour
**Org/product**: Altinn Studio (product_studio), v8
**Expected diataxis**: reference + how-to-guides
**Register**: developer, moderate vocabulary mismatch — query avoids the corpus
surface terms "dynamic expressions", "hidden", "component", "logic rule".

## Grounding trail (step 1 → 2)

1. `ts-search docs "*" --sort-by lastmod:desc --filter-by language:=en` →
   surfaced current, recently-revised docs. Picked "Expressions"
   (`a979511a6b1b`, 12 chunks, revised 2026-05-08) — substantive and distinct
   from the authorization area.
2. `ts-get --range a979511a6b1b:0-5 --include-fields content_markdown` → read
   real content: dynamic expressions decide whether a form field is
   shown/hidden/required, e.g. `"hidden": ["equals",["component","firstName"],"John"]`.
3. Derived the user need ("show a field only when another field has a value")
   and phrased it in user register, avoiding corpus jargon.

## Goldens (judgment-confirmed by reading content_markdown)

Core goldens (each independently a complete answer — one via JSON syntax, one
via the Studio GUI):
- `46573caa8ca8` — reference ch2: the `hidden` property example hiding
  `lastName` when `firstName == "John"`. Exact JSON-syntax answer.
- `55885dbe10b6` — how-to ch3: the step-by-step Studio Expressions-tool guide
  (choose component data source → function → value). GUI answer.

Supporting goldens (frame/enable the answer):
- `121c6cd3f2b4` — reference ch1: intro — expressions decide whether a field is
  "shown or hidden ... required or read-only".
- `b639390fced0` — reference ch3: table of which properties support expressions
  (`hidden` on all components).
- `ddd75a7ab3b7` — how-to ch2: the `hidden`/`required`/`readOnly` states you can
  attach an expression to in Studio.

## Cited chunks

`46573caa8ca8` `55885dbe10b6` `121c6cd3f2b4` `b639390fced0` `ddd75a7ab3b7`

## Distractors observed (NOT golden)

- `b1cecf79c5ea` (Expression *validation*, 0 chunks) — different feature
  (validating data via expressions), title-adjacent.
- nb-language twins `469e741743b5`, `92d512834d4a` — same content, wrong
  language for an EN query.

## Discovery trail (step 3)

- `ts-search docs "show or hide a form field based on another field value"`
  (user vocab) → **0 hits**. Vocabulary-mismatch signal.
- `ts-search docs "expressions" --query-by linktitle,frontmatter_title,title,url`
  → found both EN docs (`a979511a6b1b` reference, `cab432c85c40` how-to).
- `ts-get --range cab432c85c40:0-6` → confirmed ch3 is the GUI procedure and
  ch2 the attachable states.

## Results

(See results.md roll-up.)
