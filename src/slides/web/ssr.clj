(ns slides.web.ssr
  "SSR parity: render slides.web.views with a sample db via shitsuke.hiccup/->html.

  Proves the dual-render contract — the SAME views reagent mounts in the browser
  also render to HTML on the JVM. The live slides site is client-only (loads the
  deck from localStorage); this namespace is exercised by the parity test, not
  by the served shell."
  (:require [shitsuke.hiccup :as hic]
            [slides.web.views :as views]
            [slides.web.sample :as sample]))

(defn sample-db
  "A representative app-db for parity rendering."
  ([]
   (sample-db 0 nil))
  ([slide-idx shape-idx]
   {:deck sample/sample-deck
    :selected-slide slide-idx
    :selected-shape shape-idx
    :mode :visual
    :error nil}))

(defn root-html
  "Render the full editor view tree to an HTML string (SSR)."
  ([]
   (root-html (sample-db)))
  ([db]
   (hic/->html (views/root db))))
