(ns slides.site-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [slides.build :as build]
            [slides.site :as site]))

(deftest index-html-renders-github-pages-shell
  (let [html (site/index-html)]
    ;; kotoba.html/html5 emits uppercase DOCTYPE (HTML doctype is case-
    ;; insensitive; this is the same substrate shitsuke.hiccup itself is
    ;; built on, see slides.site ns docstring / 90-docs/adr/2607022800)
    (is (.startsWith html "<!DOCTYPE html>"))
    (is (re-find #"<title>kotoba-lang/slides</title>" html))
    (is (re-find #"<link rel=\"stylesheet\" href=\"\./main\.css\">" html))
    (is (re-find #"<body class=\"slides-page\">" html))
    (is (re-find #"<div id=\"app\" data-kotoba-render=\"ssr\">" html))
    (is (re-find #"Web generated deck" html))
    (is (not (re-find #"src=\"\./main\.js\"" html)))))

(deftest write-persists-index-html
  (let [dir (java.nio.file.Files/createTempDirectory "slides-site-test" (make-array java.nio.file.attribute.FileAttribute 0))
        out (io/file (.toFile dir) "index.html")]
    (with-redefs [io/file (fn [& _] out)]
      (is (= out (site/write!)))
      (is (.exists out))
      (is (re-find #"<title>kotoba-lang/slides</title>" (slurp out))))))

(deftest css-release-writes-static-css-with-token-vars
  (let [dir (java.nio.file.Files/createTempDirectory "slides-css-test" (make-array java.nio.file.attribute.FileAttribute 0))
        out (io/file (.toFile dir) "main.css")]
    (with-redefs [io/file (fn [& _] out)]
      (is (= out (build/css-release!)))
      (let [css (slurp out)]
        ;; the kotoba-ui theme bundle leads: the cascade-layer order
        ;; declaration first, then the layered HIG/glass/shell rules with the
        ;; brand accent threaded into --hig-color-tint.
        (is (.startsWith css "@layer kotoba.hig, kotoba.glass;"))
        (is (str/includes? css "--hig-color-tint: #496B9A"))
        (is (str/includes? css "@layer kotoba.glass"))
        ;; the app-chrome rules follow, UNLAYERED (css.core renders
        ;; "selector { prop: val; ... }" — check rule presence + a declaration
        ;; it contains rather than an exact minified substring).
        (is (str/includes? css ".editor-main {"))
        (is (str/includes? css "grid-template-columns"))))))

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
