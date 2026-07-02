(ns slides.site-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [shitsuke.tokens :as tokens]
            [slides.build :as build]
            [slides.site :as site]))

(deftest index-html-renders-github-pages-shell
  (let [html (site/index-html)]
    (is (.startsWith html "<!doctype html>"))
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
        (is (.startsWith css (tokens/css-variables)))
        (is (str/includes? css ".top{display:flex"))))))

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
