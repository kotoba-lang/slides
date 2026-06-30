(ns slides.site-test
  (:require [clojure.test :refer [deftest is]]
            [shadow.css.build :as css-build]
            [slides.build :as build]
            [slides.site :as site]))

(deftest index-html-renders-github-pages-shell
  (let [html (site/index-html)]
    (is (.startsWith html "<!doctype html>"))
    (is (re-find #"<link rel=\"stylesheet\" href=\"\./main\.css\">" html))
    (is (re-find #"Open PPTX" html))
    (is (re-find #"id=\"pptx-file\"" html))
    (is (re-find #"id=\"deck-edn\"" html))
    (is (re-find #"src=\"\./main\.js\"" html))))

(deftest css-release-runs-shadow-css-pipeline
  (let [calls (atom [])]
    (with-redefs [css-build/start (fn []
                                    (swap! calls conj [:start])
                                    :started)
                  css-build/index-path (fn [state path opts]
                                         (swap! calls conj [:index-path state (.getPath path) opts])
                                         state)
                  css-build/generate (fn [state config]
                                       (swap! calls conj [:generate state config])
                                       state)
                  css-build/minify (fn [state]
                                     (swap! calls conj [:minify state])
                                     state)
                  css-build/write-outputs-to (fn [state path]
                                               (swap! calls conj [:write state (.getPath path)])
                                               :written)]
      (is (= :written (build/css-release!)))
      (is (= [[:start]
              [:index-path :started "src" {}]
              [:index-path :started "resources" {}]
              [:generate :started '{:main {:include [slides.site]}}]
              [:minify :started]
              [:write :started "docs"]]
             @calls)))))

(deftest pages-writes-html-before-css-release
  (let [calls (atom [])]
    (with-redefs [site/write! (fn []
                                (swap! calls conj :write-html)
                                :html)
                  build/css-release! (fn []
                                       (swap! calls conj :css)
                                       :css)]
      (is (nil? (build/pages)))
      (is (= [:write-html :css] @calls)))))
