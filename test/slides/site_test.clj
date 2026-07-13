(ns slides.site-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [slides.build :as build]
            [slides.site :as site]))

(deftest index-html-renders-github-pages-shell
  (let [html (site/index-html)]
    ;; kotoba-ui.core/->page emits the lowercase doctype (HTML doctype is
    ;; case-insensitive) and the whole head chrome — see slides.site ns
    ;; docstring / ADR-2607122200.
    (is (.startsWith html "<!doctype html>"))
    (is (re-find #"<title>kotoba-lang/slides</title>" html))
    ;; library-owned head chrome: notch-aware viewport + per-scheme
    ;; theme-color metas + the inlined layered theme CSS bundle.
    (is (re-find #"viewport-fit=cover" html))
    (is (re-find #"name=\"theme-color\"" html))
    (is (str/includes? html "@layer kotoba.hig, kotoba.glass;"))
    (is (re-find #"<link rel=\"stylesheet\" href=\"\./main\.css\">" html))
    (is (re-find #"<body class=\"slides-page\">" html))
    (is (re-find #"<div id=\"app\" data-kotoba-render=\"ssr\">" html))
    (is (re-find #"Web generated deck" html))
    ;; The hydration bundle IS shipped (this deliberately reverses the old
    ;; SSR-only/no-JS assertion, on owner instruction — ADR-2607122200
    ;; follow-up): the page still SSRs the full editor, and docs/js/main.js
    ;; mounts the same views live.
    (is (re-find #"<script src=\"\./js/main\.js\" defer" html))))

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
        ;; main.css is now app-chrome-only: the layered kotoba-ui theme
        ;; bundle is inlined into docs/index.html by kotoba-ui.core/->page
        ;; (slides.site), not duplicated here.
        (is (not (str/includes? css "@layer kotoba.hig")))
        ;; the app-chrome rules, UNLAYERED (css.core renders
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
