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
the static CSS resource. The shell is static SSR from the CLJC view model;
browser bundling is host-owned outside this package boundary:

```bash
clojure -M:pages
```

## PPTX

`slides.pptx` writes a minimal PowerPoint Open XML package directly from EDN.
It does not use `pptxgenjs`; the package parts and relationships are emitted by
CLJC code and zipped on the JVM host.

The writer escapes XML text and theme fonts, validates hex colors, falls back on
invalid deck/shape geometry, non-finite numeric values, malformed slide/shape
collections, malformed design overrides, and emits a placeholder slide for empty
decks.
The JVM CLI and host-owned browser adapters use the same
`slides.pptx/pptx-files` package parts, so exported decks are normal ZIP/Open
XML packages with editable text boxes and shapes.

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

## CLI

The PPTX writer is CLJC and the repo-owned command surface is the Clojure CLI.

```bash
clojure -M:cli from-pptx deck.pptx deck.edn
clojure -M:cli pptx deck.edn deck.pptx
clojure -M:cli pptx-causal deck.edn deck-causal.pptx
clojure -M:cli causal-deck deck-causal.pptx recovered.edn
clojure -M:cli svgraph deck.edn deck.svgraph.edn
clojure -M:cli update base.pptx deck.edn updated.pptx
clojure -M:cli render-pptx deck.pptx target/visual/deck 120 96
clojure -M:cli visual-diff before.pptx after.pptx target/visual/diff 120 96
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

GitHub Pages publishes the static SSR shell generated by this library. Browser
editing, local persistence, file import, and download wiring are host-adapter
owned outside this repo:
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

## Coverage matrix

This repo is the *writer*: `slides.pptx` regenerates a full PPTX package from
the EDN deck model, `slides.pptx/update` source-aware-patches an existing one,
and `slides.pptx/import` normalizes `kotoba-lang/presentationml`'s
`:presentationml/*` shape/deck maps into this package's own `:slides/*` keys.
For the *reader* side of each row (`kotoba-lang/drawingml`/
`kotoba-lang/presentationml`), see those repos' own coverage matrices.

| Area | Feature | Status | Notes |
|---|---|---|---|
| Shapes | Text/rect/pic/table/chart/connector writers | ✅ full regen | one writer function per shape kind |
| Shapes | Hidden flag (`cNvPr hidden="1"`) | ✅ full regen | wired into all 6 shape writers |
| Fill/line/effects | Gradient fill (shape + master background) | ✅ full regen | real multi-stop `<a:gradFill>`, not a first-stop approximation |
| Fill/line/effects | Per-slide background override (`:slides/slide-background`) | ✅ full regen | takes precedence over the resolved master background for that one slide; this writer already emits a literal `<p:bg>` on every slide (not only slides with a genuine override), so a re-imported plain slide legitimately carries its own `:slides/slide-background` too, equal to the master's |
| Fill/line/effects | Line cap/join/dash | ✅ full regen | |
| Fill/line/effects | Combined `<a:effectLst>` (glow + shadow + reflection) | ✅ full regen | OOXML allows only one `effectLst` per shape, so all three share one write path |
| Fill/line/effects | Picture crop (`srcRect`) + recolor (grayscale/alpha-mod) | ✅ full regen | `<a:blip>` stays self-closing when neither effect is set, unchanged from before either feature existed |
| Fill/line/effects | Picture lock flags (`picLocks`) | ✅ full regen | fixed a real bug this session — the writer previously hardcoded `noChangeAspect="1"` regardless of the source picture's actual lock state; falls back to that same historical default only when the shape carries no `:slides/locks` at all |
| Fill/line/effects | Text/rect shape lock flags (`spLocks`) | ✅ full regen | fixed a real bug this session — the writer previously emitted a bare, self-closing `<p:cNvSpPr/>` for every shape with no lock element at all, silently discarding any lock flags a source shape had; sibling of the `picLocks` fix above |
| Text/paragraphs | Bullets incl. numbered-list `startAt`, tab stops, body autofit | ✅ full regen | |
| Text/paragraphs | Vertical text direction (`vert`) | ✅ full regen | East Asian vertical writing, WordArt-style stacked text, Mongolian vertical layout |
| Text/paragraphs | Run formatting + CJK-aware `lang` heuristic | ✅ full regen | |
| Hyperlinks | External URL | ✅ full regen + patch | `TargetMode="External"` |
| Hyperlinks | Internal same-deck slide jump | ✅ full regen + patch | writes a valid `Internal` relationship (bare sibling filename, no `TargetMode`) — fixes a real bug where every hyperlink, internal or external, was previously written as external |
| Hyperlinks | Built-in navigation action (`ppaction://...`) | ✅ full regen | self-contained `<a:hlinkClick action="...">`, no relationship at all; takes priority over an r:id-based link on the same run |
| Table | Cell borders (straight + diagonal), margins, vertical anchor | ✅ full regen | |
| Table | Cell text rotation (`vert` on `<a:tcPr>`) | ✅ full regen | distinct from the shape-level `<a:bodyPr>` vert already covered under Text/paragraphs; reuses the same reverse-map |
| Table | Table style flags (firstRow/lastRow/firstCol/lastCol/bandRow/bandCol) | ✅ full regen | fixed a real bug this session: the writer used to hardcode `firstRow`+`bandRow` regardless of the source table's actual flags; now respects them, falling back to that same historical default only when the deck carries none at all |
| Table | Column widths / row heights | ✅ full regen | falls back to even division across `:slides/w`/`:slides/h` only when `:slides/column-widths`/`:slides/row-heights` is absent or its length doesn't match the table's actual column/row count |
| Chart/Table | Graphic frame lock flags (`graphicFrameLocks`) | ✅ full regen | fixed a real bug this session — the writer previously hardcoded `noGrp="1"` regardless of the source table/chart's actual lock state; falls back to that same historical default only when the shape carries no `:slides/locks` at all |
| Chart | Bar/line/pie/area/doughnut/scatter chart bodies | ✅ full regen + patch | scatter uses two value axes (X is itself plotted, not a category label), unlike the other types' one category + one value axis |
| Chart | Embedded SpreadsheetML workbook (the chart's own editable `.xlsx`) | ✅ full regen + patch | a minimal, real, independently-openable `xl/workbook.xml` + `xl/worksheets/sheet1.xml` OPC package, generated fresh on full regen and cell-patched in place on `update` |
| Chart | Legend position + axis titles | ✅ write-only | no chart-XML *reader* exists anywhere in this pipeline (chart import is reference-metadata only — rel-id + resolved chart-part/workbook-part path, never the chart's own visual configuration), so these are settable only when hand-authoring/programmatically building a deck |
| Deck/package parts | Layout refs, slide sections | ✅ full regen | |
| Deck/package parts | Legacy PowerPoint comments | ✅ full regen | includes the deck-wide author-collection pass (comment `authorId` is a shared index into one package-wide `commentAuthors.xml`, not per-slide) |
| Deck/package parts | Handout master | ✅ full regen | presence flag only, gated on `:slides/handout-master?` |
| Deck/package parts | Custom XML parts | ✅ full regen | content + optional `itemProps` preserved as opaque raw XML, never reinterpreted |
| Deck/package parts | Embedded font declarations | ✅ read-only (import passthrough) | no write-side counterpart by design — see `presentationml`'s coverage matrix |
| Patch/update path | Slide text/shape patching against the original XML | ✅ | preserves group membership, placeholder tags, and unrelated package entries |
| Patch/update path | New content added post-import (images, charts, notes, hyperlinks) | ✅ | |
| Patch/update path | Position/size, solid fill, line fill, gradient fill | ✅ | gradient added this session (`patch-gradient-fill`) — replaces whichever fill element (gradFill/solidFill/noFill) a shape already has |
| Patch/update path | Hidden flag | ✅ | `patch-hidden-flag`, added this session — toggles `<p:cNvPr>`'s own `hidden="1"` in place, both hide and un-hide |
| Patch/update path | Picture crop + recolor | ✅ | `patch-picture-crop`/`patch-picture-recolor`, added this session — recolor rebuilds the whole `<a:blip>` via the same `blip-xml` full regen uses, preserving the existing `r:embed`; crop inserts/replaces `<a:srcRect>` right before `<a:stretch>` |
| Patch/update path | Effects (shadow/glow/reflection) | ✅ | `patch-effects`, added this session — regenerates the whole `<a:effectLst>` via the same `effect-lst-xml` full regen uses (OOXML allows only one per shape); explicitly nilling out every effect removes an existing `<a:effectLst>` entirely |
| Patch/update path | Lock flags, vertical text direction, table style flags/dimensions, per-slide background, hyperlinks | ❌ full-regen-only | these features exist in the full-regen writer (see rows above) but `patch-shape-block` doesn't yet mirror them — editing an imported deck via `update` silently drops any change to these, even though a from-scratch `pptx-bytes` build honors them correctly. Prioritize by real-edit frequency: hyperlinks likely highest value remaining |
| Patch/update path | Newly-imported comments/notes text edited *in place* | ❌ not implemented | full-regen-only for now; only brand-new comments/notes on previously-comment/notes-less slides get added by patching |
| Deferred subsystems | SmartArt / OLE / animations (`p:timing`) | ❌ out of scope | large independent subsystems, not started |

## Test

```bash
clojure -M:test
clojure -M:local:test
clojure -M:pages
clojure -M:coverage
clojure -M:coverage-thresholds
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
`test/slides/fixtures/pptx_roundtrip_matrix.edn` records the real-world PPTX
roundtrip matrix. `:guarded` rows run in CI through Office import -> causal PPTX
export -> Office re-import, and include grouped shapes, chart data/workbook
relationships, and placeholder semantics. Source-aware `update` tests additionally
verify that the original group XML, placeholder tags, chart parts, chart rels,
and embedded workbook entries are preserved while patched slide text is updated.
`clojure -M:coverage` runs Cloverage against the JVM/CLJC namespaces and fails
below 85% aggregate coverage. `clojure -M:coverage-thresholds` then checks the generated
LCOV report against namespace-level floors, with a 90% aggregate floor, so CI
blocks broad regressions and local coverage holes in the EDN model, Office/PPTX
bridge, Pages Hiccup shell, and static build pipeline.
Use `:local:test` when developing `slides`, `office`, and `office-style` from
sibling checkouts in this workspace.
