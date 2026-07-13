(ns slides.build
  (:require [clojure.java.io :as io]
            [kotoba-ui.core :as ui]
            [slides.site :as site]
            [slides.web.styles :as styles]))

(defn css-release!
  "docs/main.css = the kotoba-ui theme bundle (layer-order declaration + HIG
  tokens/base + liquid-glass material + shell structural rules, all inside
  `@layer kotoba.hig, kotoba.glass`) followed by the editor's UNLAYERED
  app-chrome rules from slides.web.styles (css.core EDN data) — app rules
  always win over the layered library CSS. Same pipeline shape as before the
  paved-road migration (ADR-2607122200): one `:pages` entrypoint emits both
  docs/index.html and docs/main.css."
  []
  (let [out (io/file "docs" "main.css")
        theme-bundle (ui/theme-css styles/theme)
        app (styles/static-css)]
    (io/make-parents out)
    (spit out (str theme-bundle "\n" app))
    out))

(defn pages [& _]
  (site/write!)
  (css-release!)
  nil)

(defn -main [& args]
  (apply pages args))
