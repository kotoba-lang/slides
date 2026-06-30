(ns slides.site
  {:shadow.css/include ["slides/static.css"]}
  (:require [clojure.java.io :as io]
            [shadow.css :refer [css]]
            [shitsuke.hiccup :as hiccup]))

;; Shell-level shadow-css class. The editor body is rendered by reagent into
;; #app; its classes come from the legacy editor CSS in docs/main.css plus the
;; shitsuke :root token vars that slides.build prepends to main.css.
(def $page (css {:min-height "100vh"}))

(defn index-page []
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "kotoba-lang/slides"]
    [:link {:rel "stylesheet" :href "./main.css"}]]
   [:body {:class $page}
    [:div#app]
    [:script {:src "./main.js"}]]])

(defn index-html []
  (str "<!doctype html>\n" (hiccup/->html (index-page)) "\n"))

(defn write! []
  (let [out (io/file "docs" "index.html")]
    (io/make-parents out)
    (spit out (index-html))
    out))
