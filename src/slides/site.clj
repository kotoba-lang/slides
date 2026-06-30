(ns slides.site
  {:shadow.css/include ["slides/static.css"]}
  (:require [clojure.java.io :as io]
            [shadow.css :refer [css]]
            [slides.hiccup :as hiccup]))

(def $page (css {:min-height "100vh"}))
(def $top (css {:background "#fff"}))
(def $toolbar (css {:align-items "center"}))

(defn index-page []
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "kotoba-lang/slides"]
    [:link {:rel "stylesheet" :href "./main.css"}]]
   [:body {:class $page}
    [:header {:class ["top" $top]}
     [:div
      [:h1 "kotoba-lang/slides"]
      [:div#status.status]]
     [:div {:class ["toolbar" $toolbar]}
      [:button#new-deck "New"]
      [:label.file-label "Open EDN"
       [:input#edn-file {:type "file" :accept ".edn,text/plain"}]]
      [:label.file-label "Open PPTX"
       [:input#pptx-file {:type "file" :accept ".pptx,application/vnd.openxmlformats-officedocument.presentationml.presentation"}]]
      [:button#download-edn "Download EDN"]
      [:button#download-pptx.primary "Download PPTX"]
      [:a {:href "https://github.com/kotoba-lang/slides"} "GitHub"]]]
    [:main
     [:aside
      [:div.rail-actions
       [:button#add-slide "Add"]
       [:button#duplicate-slide "Copy"]]
      [:div#slide-list]]
     [:section.workspace
      [:div.mode-tabs
       [:button#mode-visual "Visual"]
       [:button#mode-edn "EDN"]
       [:button#add-text "Text"]
       [:button#add-rect "Rect"]
       [:button#add-title "Title"]
       [:button#add-panel "Panel"]]
      [:div#visual-pane.canvas-shell
       [:div#canvas-wrap]]
      [:div#edn-pane {:hidden true}
       [:textarea#deck-edn {:spellcheck "false"}]
       [:div.edn-actions
        [:button#apply-edn.primary "Apply EDN"]]]
      [:div#error]]
     [:section#properties.props]]
    [:script {:src "./main.js"}]]])

(defn index-html []
  (str "<!doctype html>\n" (hiccup/->html (index-page)) "\n"))

(defn write! []
  (let [out (io/file "docs" "index.html")]
    (io/make-parents out)
    (spit out (index-html))
    out))
