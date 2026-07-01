# slides

[![CI](https://github.com/kotoba-lang/slides/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/slides/actions/workflows/ci.yml)

Portable CLJC model for the GFTD workspace surface:

- `kotoba-lang.github.io/slides/` — decks, scenes, speaker notes, publishing
- `kotoba-lang.github.io/docs/` — documents, outlines, decisions
- `kotoba-lang.github.io/drive/` — files, folders, immutable object refs
- `kotoba-lang.github.io/sheets/` — tables, ranges, formulas, facts

The library keeps those surfaces in one EDN-native workspace graph. Hosts can
render it as web apps, persist it in Datomic/kotoba, or expose it over XRPC; the
model stays pure data and pure functions.

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

The package boundary for this integration is recorded as
`kotoba-lang/slides-office` under `adapters/office/package-manifest.edn`. The
current code still lives in `src/slides/office.cljc`; the adapter manifest makes
the dependency boundary explicit before publication.

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

## Causal payload

`slides.causal` writes the same PPTX package with an embedded `ocz/causal.edn`
part using the `office.embed` convention. The payload carries a slides causal
graph plus the source deck, so a downstream Office workflow can preserve and
recover the EDN deck instead of relying only on XML text extraction.

```clojure
(require '[slides.causal :as causal])

(def bytes (causal/embed-deck-bytes deck {:slides-causal/source "pipeline"}))
(causal/read-deck-bytes bytes)
(causal/write-pptx! "deck-causal.pptx" deck)
```

This is intentionally separate from `slides.pptx`: callers choose whether the
exported deck should carry Kotoba provenance data.

## SVGraph

`slides.svgraph/presentation` projects a deck into the
`svgraph-presentation/1` contract. This gives `slides` a direct bridge to the
same graph surface that `office-style` can emit from Office style metadata.

```clojure
(require '[slides.svgraph :as svgraph])

(svgraph/presentation deck)
;;=> {:svgraph/version "svgraph-presentation/1", ...}
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

The PPTX writer is CLJC. The npm package only provides a thin `node` bin wrapper
that invokes the Clojure CLI, so `clojure` must be installed on the host.

```bash
clojure -M:cli from-pptx deck.pptx deck.edn
clojure -M:cli pptx deck.edn deck.pptx
clojure -M:cli pptx-causal deck.edn deck-causal.pptx
clojure -M:cli causal-deck deck-causal.pptx recovered.edn
clojure -M:cli svgraph deck.edn deck.svgraph.edn
clojure -M:cli update base.pptx deck.edn updated.pptx
clojure -M:cli render-pptx deck.pptx target/visual/deck
clojure -M:cli visual-diff before.pptx after.pptx target/visual/diff

npx @kotoba-lang/slides pptx deck.edn deck.pptx
```

When imported shapes carry `:ooxml/source` locators, `update` patches matching
source slide XML parts in the base PPTX and preserves unrelated package entries.
Decks without locators still fall back to normalized PPTX regeneration.
Imported OOXML semantics are retained in EDN metadata for patch-safe workflows:
group membership is carried as `:slides/group`, placeholders as
`:slides/placeholder`, and charts record their slide relationship, chart part,
and embedded workbook part via `:slides/chart-rel-id`, `:slides/chart-part`, and
`:slides/workbook-part`.
Chart data can be edited semantically by adding `:slides/chart-data` to an
imported chart shape:

```clojure
{:slides/chart-data {:sheet "Sheet1"
                     :anchor "A1"
                     :rows [["Quarter" "Revenue"]
                            ["Q1" 120]
                            ["Q2" 180]]}}
```

`update` patches both the chart cache XML and the embedded workbook `.xlsx`
entry while preserving the rest of the original PPTX package.

`render-pptx` and `visual-diff` provide a PowerPoint/Keynote-free visual
roundtrip harness for CI and fixture audits. They render PPTX through
LibreOffice headless, split the intermediate PDF into per-slide PNGs with
`pdftoppm`, and compare slide PNGs with ImageMagick `compare` or `magick
compare`. Missing tools are reported explicitly; install them on macOS with:

```bash
brew install --cask libreoffice
brew install poppler imagemagick
```

This visual check complements the OOXML package checks: it is good for catching
large layout drift across all slides, while PowerPoint itself remains the
strictest compatibility oracle for repair dialogs and Microsoft-specific
rendering behavior.

GitHub Pages includes a browser-only EDN/PPTX editor. It can open `.edn`, open
`.pptx` in the browser, convert text/theme metadata into deck EDN, edit the deck,
and download a fresh editable `.pptx`:
https://kotoba-lang.github.io/slides/

## Package boundary

`package-manifest.edn` declares `kotoba-lang/slides` as a zero-capability
`:library` package that provides:

- `:app.kotoba.slides.deck`
- `:app.kotoba.slides.workspace`
- `:app.kotoba.slides.pptx`
- `:app.kotoba.slides.causalPayload`
- `:app.kotoba.svgraph.presentation`

`kotoba.lock.edn` records the draft workspace surface lock for `slides`,
`office`, `office-style`, `docs`, `sheets`, `drive`, and `forms`. The Office
adapter is recorded separately as `kotoba-lang/slides-office`.

These package files are currently `:draft-unpublished`: repo RID, tree CID,
manifest CID, and signatures are placeholders until the Kotoba package publish
flow replaces them with real signed CIDs.

## Test

```bash
clojure -X:test
clojure -M:local:test
npm run build:pages
npm run coverage
npm run coverage:thresholds
npm run test:e2e
npm run test:all
```

The test suite covers the EDN workspace model, validation, routing, HTML render,
Office PPTX import, CLI commands, theme handling, PPTX export/update, causal
payload embedding/readback, svgraph projection, and
fallback behavior for invalid geometry, colors, fonts, empty decks, malformed
slide/shape collections, non-finite numeric values, malformed design overrides, and malformed
workspace/deck/slide/shape EDN structures, including semantic shape warnings for
malformed design/theme overrides, missing slide ids/titles, missing shape ids, and renderer fallback
kinds/components across default and deck component definitions, malformed item
rendering fallbacks, and package manifest/lock boundary conformance.
The nbb/CLJS e2e tests start from the built Pages app, import `docs/sample.pptx`, edit a
browser shape, check the EDN conversion surface, download the browser-generated
PPTX, and inspect the resulting Open XML slide/theme XML. They also apply an EDN
deck with reusable components/styles and verify that the exported PPTX keeps
editable text, font size, and color in the package XML.
`test/slides/fixtures/pptx_roundtrip_matrix.edn` records the real-world PPTX
roundtrip matrix. `:guarded` rows run in CI through Office import -> causal PPTX
export -> Office re-import, and include grouped shapes, chart data/workbook
relationships, and placeholder semantics. Source-aware `update` tests additionally
verify that the original group XML, placeholder tags, chart parts, chart rels,
and embedded workbook entries are preserved while patched slide text is updated.
`npm run coverage` runs Cloverage against the JVM/CLJC namespaces and fails below
85% aggregate coverage. `npm run coverage:thresholds` then checks the generated
LCOV report against namespace-level floors, with a 90% aggregate floor, so CI
blocks broad regressions and local coverage holes in the EDN model, Office/PPTX
bridge, Pages Hiccup shell, and static build pipeline.
Use `:local:test` when developing `slides`, `office`, and `office-style` from
sibling checkouts in this workspace.
