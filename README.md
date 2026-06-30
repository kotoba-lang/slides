# slides

[![CI](https://github.com/kotoba-lang/slides/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/slides/actions/workflows/ci.yml)

Portable CLJC model for the GFTD workspace surface:

- `kotoba-lang.github.io/slides/` — decks, scenes, speaker notes, publishing
- `kotoba-lang.github.io/docs/` — documents, outlines, decisions
- `kotoba-lang.github.io/drive/` — files, folders, immutable object refs
- `kotoba-lang.github.io/sheets/` — tables, ranges, formulas, facts

The library keeps those surfaces in one EDN-native workspace graph. Hosts can
render it as web apps, persist it in Datomic/kotoba, or expose it over XRPC; the
core stays pure data and pure functions.

## Model

```clojure
(require '[slides.model :as m])

(def ws
  (-> (m/workspace "gftd")
      (m/add-item (m/deck "deck-1" {:slides/title "Investor update"}))
      (m/add-item (m/doc "memo-1" {:slides/title "Narrative"}))
      (m/add-item (m/sheet "plan-1" {:slides/title "Plan"}))
      (m/link "deck-1" "memo-1" :uses)
      (m/link "deck-1" "plan-1" :embeds)))

(m/items-by-kind ws :slides/deck)
```

## Host Routing

```clojure
(require '[slides.routes :as r])

(r/resolve-host "kotoba-lang.github.io/slides")
;;=> {:slides/host "kotoba-lang.github.io/slides", :slides/app :slides, ...}
```

## Office PPTX のインポート (EDN/CLJC)

`slides.office/deck-from-office-bytes` は `office` と `office-style` を使って
`.pptx` を `slides` の deck EDN へ変換します。

```clojure
(require '[slides.office :as office])

(def deck (office/deck-from-office-bytes pptx-bytes {:title "Q1 Update"}))
(def deck-edn (office/deck-edn-from-office-bytes pptx-bytes {:title "Q1 Update"}))
(def edited-pptx-bytes (office/pptx-bytes-from-deck-edn deck-edn))
```

`deck` にはソースのスライド順（`:office-style/slides`）と
テキストノード（`:office/kind :text`）を slide/shape として落とし込みます。  
`:office-style` が欠落している場合は既定テーマと 16:9 サイズへフォールバックし、  
テキストを含まないスライドでも空スライドを維持したまま変換します。
空タイトルは `"Imported deck"` にフォールバックします。
`deck-edn-from-office-bytes` と `pptx-bytes-from-deck-edn` を使うと、
PPTX bytes → deck EDN → PPTX bytes の編集/export 境界を EDN だけにできます。

## Validation

```clojure
(require '[slides.validate :as v])

(v/valid? ws)
(v/problems ws)
```

## Render

`slides.render` emits static HTML for simple host shells. The checked-in GitHub
Pages artifact is under `docs/` and is generated from `slides.site` Hiccup plus
`shadow-css` output:

```bash
npm run build:pages
```

## PPTX

`slides.pptx` writes a minimal PowerPoint Open XML package directly from EDN.
It does not use `pptxgenjs`; the package parts and relationships are emitted by
CLJC code and zipped on the JVM host.

The writer escapes XML text and theme fonts, validates hex colors, falls back on
invalid deck/shape geometry, non-finite numeric values, malformed slide/shape
collections, malformed design overrides, and emits a placeholder slide for empty
decks.
The browser editor and JVM CLI use the same `slides.pptx/pptx-files` package
parts, so downloaded decks are normal ZIP/Open XML packages with editable text
boxes and shapes.

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

## EDN design system

Reusable design lives in plain EDN under `:slides/design` or the top-level
`:slides/theme`, `:slides/master`, `:slides/guides`, `:slides/text-styles`, and
`:slides/components` keys. Shapes can reference components and styles instead
of repeating coordinates and typography.

```clojure
(require '[slides.design :as design]
         '[slides.model :as m])

(def deck
  (-> (m/deck "template"
              {:slides/title "Template deck"
               :slides/design design/default-design
               :slides/master {:slides/background "FAFAFA"
                               :slides/footer {:slides/enabled true
                                               :slides/text "Confidential"}}
               :slides/components {:hero-title {:slides/shape :text
                                                :slides/text-style :title
                                                :slides/x 0.8 :slides/y 0.9
                                                :slides/w 8.4 :slides/h 0.9}}})
      (m/add-slide
       (-> (m/slide "s1" {:slides/title "Reusable"})
           (m/add-shape {:slides/id "title"
                         :slides/component :hero-title
                         :slides/text "Beautiful EDN decks"})))))
```

The default design includes theme colors, title/body fonts, a clean slide
master, layout guides, and reusable `:title`, `:subtitle`, `:body`, `:panel`,
`:eyebrow`, and `:accent-bar` components.

## CLI / npm

The core writer is CLJC. The npm package only provides a thin `node` bin wrapper
that invokes the Clojure CLI, so `clojure` must be installed on the host.

```bash
clojure -M:cli from-pptx deck.pptx deck.edn
clojure -M:cli pptx deck.edn deck.pptx
clojure -M:cli update base.pptx deck.edn updated.pptx

npx @kotoba-lang/slides pptx deck.edn deck.pptx
```

GitHub Pages includes a browser-only EDN/PPTX editor. It can open `.edn`, open
`.pptx` in the browser, convert text/theme metadata into deck EDN, edit the deck,
and download a fresh editable `.pptx`:
https://kotoba-lang.github.io/slides/

## Test

```bash
clojure -X:test
clojure -M:local:test
npm run build:pages
npm run coverage
npm run test:e2e
npm run test:all
```

The test suite covers the EDN workspace model, validation, routing, HTML render,
Office PPTX import, CLI commands, theme handling, PPTX export/update, and
fallback behavior for invalid geometry, colors, fonts, empty decks, malformed
slide/shape collections, non-finite numeric values, malformed design overrides, and malformed
workspace/deck/slide/shape EDN structures, including semantic shape warnings for
malformed design/theme overrides, missing slide ids/titles, missing shape ids, and renderer fallback
kinds/components across default and deck component definitions, plus malformed
item rendering fallbacks.
The e2e tests start from the built Pages app, import `docs/sample.pptx`, edit a
browser shape, check the EDN conversion surface, download the browser-generated
PPTX, and inspect the resulting Open XML slide/theme XML. They also apply an EDN
deck with reusable components/styles and verify that the exported PPTX keeps
editable text, font size, and color in the package XML.
`npm run coverage` runs Cloverage against the JVM/CLJC namespaces and fails below
85% aggregate coverage, so CI blocks broad regressions in the core model,
Office/PPTX bridge, Pages Hiccup shell, and static build pipeline.
Use `:local:test` when developing `slides`, `office`, and `office-style` from
sibling checkouts in this workspace.
