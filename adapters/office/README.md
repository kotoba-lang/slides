# kotoba-lang/slides-office

Draft package boundary for the Office import adapter.

Current implementation lives in `src/slides/office.cljc`. This directory records
the package-system boundary before code is physically moved or published:

- consumes `app.kotoba.slides.deck`
- consumes `app.kotoba.office.graph`
- consumes `app.kotoba.officeStyle.styleIr`
- provides `app.kotoba.slides.officeImport`

The manifest is `:draft-unpublished`; replace repo RID, tree CID, manifest CID,
and signature values before safe package import.
