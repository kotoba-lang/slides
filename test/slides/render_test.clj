(ns slides.render-test
  (:require [clojure.test :refer [deftest is]]
            [slides.model :as model]
            [slides.render :as render]
            [slides.routes :as routes]))

(deftest index-html-has-shell-and-nav
  (let [html (render/index-html)]
    (is (string? html))
    (is (re-find #"<!doctype html>" html))
    (is (re-find #"GFTD Workspace" html))
    (is (re-find #"Seed Graph" html))
    (is (re-find #"GFTD Slides" html))
    (is (re-find #"GFTD Docs" html))
    (is (re-find #"GFTD Drive" html))
    (is (re-find #"GFTD Sheets" html))
    (is (re-find #"intro-deck" html))
    (is (re-find #"kotoba-lang.github.io/slides/" html))
    (is (re-find #"https://kotoba-lang.github.io/drive/" html))))

(deftest index-html-renders-custom-workspace
  (let [ws (-> (model/workspace "ws" {:slides/title "Workspace"})
               (model/add-item (model/deck "custom-deck" {:slides/title "Deck"})))
        html (render/index-html ws)]
    (is (re-find #"Workspace" html))
    (is (re-find #"custom-deck" html))
    (is (re-find #"</table>" html))))

(deftest app-card-uses-route-host
  (let [cfg (-> (routes/nav) first)
        html (render/app-card cfg)]
    (is (re-find #"<a class=\"card\"" html))
    (is (re-find (re-pattern (str (:slides/host (routes/apps (:app cfg))))) html))))

(deftest app-card-escapes-attributes-and-tolerates-unknown-app
  (let [html (render/app-card {:href "https://example.test/?q=\"x\"&ok=1"
                               :label nil
                               :app :unknown})]
    (is (re-find #"href=\"https://example.test/\?q=&quot;x&quot;&amp;ok=1\"" html))
    (is (re-find #"<span>unknown</span>" html))
    (is (not (re-find #"nil" html)))))

(deftest item-row-escapes-content
  (let [it {:slides/id "bad id"
            :slides/kind :slides/deck
            :slides/title "Unsafe <tag>"}
        html (render/item-row it)]
    (is (re-find #"<tr><td>bad id</td>" html))
    (is (re-find #"<td>Unsafe &lt;tag&gt;</td>" html))))

(deftest item-row-renders-nil-as-empty-cells
  (let [html (render/item-row {:slides/id nil
                               :slides/kind nil
                               :slides/title nil})]
    (is (re-find #"<tr><td></td><td></td><td></td></tr>" html))
    (is (not (re-find #"nil" html)))))

(deftest render-tolerates-malformed-items
  (let [row (render/item-row "not a map")
        html (render/index-html {:slides/id "bad"
                                 :slides/items []
                                 :slides/links []})]
    (is (re-find #"<td>not a map</td>" row))
    (is (re-find #"<td>:unknown</td>" row))
    (is (re-find #"</table>" html))))

(deftest index-html-renders-mixed-map-and-non-map-items
  (let [html (render/index-html {:slides/id "mixed"
                                 :slides/items {"deck" {:slides/id "deck"
                                                        :slides/kind :slides/deck
                                                        :slides/title "Deck"}
                                                "bad" "not a map"}
                                 :slides/links []})]
    (is (re-find #"Deck" html))
    (is (re-find #"not a map" html))
    (is (re-find #":unknown" html))))
