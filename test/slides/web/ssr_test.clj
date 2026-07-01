(ns slides.web.ssr-test
  "SSR parity: the same views reagent mounts must render to stable HTML on the
  JVM via shitsuke.hiccup/->html. Mirrors the kami-mangaka-reader parity test."
  (:require [clojure.test :refer [deftest is testing]]
            [slides.web.ssr :as ssr]
            [slides.web.views :as views]
            [shitsuke.hiccup :as hic]))

(deftest root-html-stable-test
  (let [html (ssr/root-html)]
    (is (clojure.string/starts-with? html "<div class=\"shitsuke__app\">"))
    (is (clojure.string/includes? html "kotoba-lang/slides"))
    (is (clojure.string/includes? html "data-act=\"new-deck\""))
    (is (clojure.string/includes? html "data-act=\"download-pptx\""))
    (is (clojure.string/includes? html "data-act=\"zoom-in\""))
    (is (clojure.string/includes? html "thumb-preview"))
    (is (clojure.string/includes? html "data-slide=\"0\""))))

(deftest shape-selection-renders-properties-test
  (testing "no shape selected → slide properties panel"
    (let [html (hic/->html (views/root (ssr/sample-db 0 nil)))]
      (is (clojure.string/includes? html "panel-title\">Slide"))
      (is (clojure.string/includes? html "data-field=\"slide.title\""))))
  (testing "shape selected → shape properties panel"
    (let [html (hic/->html (views/root (ssr/sample-db 0 0)))]
      (is (clojure.string/includes? html "panel-title\">Shape"))
      (is (clojure.string/includes? html "data-field=\"shape.x\""))
      (is (clojure.string/includes? html "data-resize=\"se\""))
      (is (clojure.string/includes? html "data-act=\"duplicate-shape\"")))))

(deftest canvas-shape-inline-style-test
  "shape inline style (reagent :style map) renders to CSS string via shitsuke.hiccup."
  (let [html (hic/->html (views/root (ssr/sample-db 0 0)))]
    (is (clojure.string/includes? html "style=\"left:"))
    (is (clojure.string/includes? html "font-size:"))))

(deftest ssr-vs-reagent-data-parity-test
  "The view tree is plain data; rendering it twice yields the same string."
  (is (= (hic/->html (views/root (ssr/sample-db)))
         (hic/->html (views/root (ssr/sample-db))))))
