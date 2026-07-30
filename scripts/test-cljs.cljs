#!/usr/bin/env nbb
;; The same test namespaces, on the other host.
;;
;; `clojure -M:test` runs them on the JVM, which is where they were written
;; and where they all passed while the outline strokes covered a slide in
;; grey blocks. That defect was a picture problem, not a host problem — but
;; it is the reason to look, and looking found that the four portable
;; namespaces here had never run anywhere but the JVM.
;;
;; A `.cljc` library that only ever runs on one host is a `.clj` library with
;; extra reader conditionals: `sheets` had two functions wrong under
;; ClojureScript for a fortnight because nothing ran them there. These four
;; are the ones an application would call from nbb — the model it edits, the
;; wire it sends, and the two pictures it draws — so these are the four that
;; have to work on both.
;;
;;   nbb --classpath "$(clojure -Spath):test" scripts/test-cljs.cljs
;;
;; The `.clj` tests — the package, the site, the CLI — are absent here rather
;; than failing, which is the correct outcome and not a gap.

(require '[clojure.test :as t]
         'slides.model-test
         'slides.svg-test
         'slides.svgraph-test
         'slides.wire-test)

(let [{:keys [fail error]} (t/run-tests 'slides.model-test
                                        'slides.svg-test
                                        'slides.svgraph-test
                                        'slides.wire-test)]
  (when (pos? (+ (or fail 0) (or error 0)))
    (js/process.exit 1)))
