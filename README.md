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

## PPTX

`slides.pptx` writes a minimal PowerPoint Open XML package directly from EDN.
It does not use `pptxgenjs`; the package parts and relationships are emitted by
CLJC code and zipped on the JVM host.

```clojure
(require '[slides.model :as m]
         '[slides.pptx :as pptx])

(def deck
  (-> (m/deck "deck-1" {:slides/title "Investor update"})
      (m/add-slide
       (-> (m/slide "slide-1" {:slides/title "Overview"})
           (m/add-shape (m/text-box "title" "Investor update"
                                    {:slides/font-size 36}))
           (m/add-shape (m/rect "panel"
                                {:slides/y 2.0
                                 :slides/fill "EAF0F8"}))))))

(pptx/write-pptx! "deck.pptx" deck)
```

## CLI / npm

The core writer is CLJC. The npm package only provides a thin `node` bin wrapper
that invokes the Clojure CLI, so `clojure` must be installed on the host.

```bash
clojure -M:cli pptx deck.edn deck.pptx
clojure -M:cli update base.pptx deck.edn updated.pptx

npx @kotoba-lang/slides pptx deck.edn deck.pptx
```

GitHub Pages includes a browser-only EDN editor and PPTX download surface:
https://kotoba-lang.github.io/slides/

## Test

```bash
clojure -X:test
```
