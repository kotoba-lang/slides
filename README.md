# slides

[![CI](https://github.com/kotoba-lang/slides/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/slides/actions/workflows/ci.yml)

Portable CLJC model for the GFTD workspace surface:

- `slides.gftd.ai` — decks, scenes, speaker notes, publishing
- `docs.gftd.ai` — documents, outlines, decisions
- `drive.gftd.ai` — files, folders, immutable object refs
- `sheets.gftd.ai` — tables, ranges, formulas, facts

The library keeps those surfaces in one EDN-native workspace graph. Hosts can
render it as web apps, persist it in Datomic/kotoba, or expose it over XRPC; the
core stays pure data and pure functions.

## Model

```clojure
(require '[slides.model :as m])

(def ws
  (-> (m/workspace "gftd")
      (m/add-item (m/deck "deck-1" {:title "Investor update"}))
      (m/add-item (m/doc "memo-1" {:title "Narrative"}))
      (m/add-item (m/sheet "plan-1" {:title "Plan"}))
      (m/link "deck-1" "memo-1" :uses)
      (m/link "deck-1" "plan-1" :embeds)))

(m/items-by-kind ws :slides/deck)
```

## Host Routing

```clojure
(require '[slides.routes :as r])

(r/resolve-host "slides.gftd.ai")
;;=> {:slides/host "slides.gftd.ai", :slides/app :slides, ...}
```

## Validation

```clojure
(require '[slides.validate :as v])

(v/valid? ws)
(v/problems ws)
```

## Render

`slides.render` emits static HTML for GitHub Pages and simple host shells. The
checked-in Pages artifact is under `docs/`.

## Test

```bash
clojure -X:test
```
