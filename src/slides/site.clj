(ns slides.site
  "Per 90-docs/adr/2607022800-kotoba-lang-default-uiux-appkit-uikit-interface-fundamentals:
  the docs/ Pages shell is rendered via kotoba-lang/html (kotoba.html/html5),
  the standalone extracted substrate shitsuke.hiccup/->html is itself built on
  (byte-identical render-node/escape rules) — not a second, divergent renderer."
  (:require [clojure.java.io :as io]
            [kotoba.html :as html]
            [slides.web.ssr :as ssr]))

;; Shell-level class. The page body is rendered from the same CLJC view tree
;; consumed by browser hosts, keeping the checked-in Pages artifact free of
;; generated JavaScript authority.
(def page-class "slides-page")

(defn index-page []
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "kotoba-lang/slides"]
    [:link {:rel "stylesheet" :href "./main.css"}]]
   [:body {:class page-class}
    [:div#app {:data-kotoba-render "ssr"}
     [:hiccup/raw (ssr/root-html)]]]])

(defn index-html []
  (str (html/html5 (index-page)) "\n"))

(defn write! []
  (let [out (io/file "docs" "index.html")]
    (io/make-parents out)
    (spit out (index-html))
    out))
