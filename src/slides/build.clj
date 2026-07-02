(ns slides.build
  (:require [clojure.java.io :as io]
            [shitsuke.tokens :as tokens]
            [slides.site :as site]
            [slides.web.styles :as styles]))

(defn css-release!
  "docs/main.css = shitsuke.tokens' generated :root block + the page-chrome
  rules from slides.web.styles (css.core EDN data — see that ns docstring for
  why a second, hand-typed :root block also survives inside `generated`)."
  []
  (let [out (io/file "docs" "main.css")
        root-vars (tokens/css-variables)
        generated (styles/static-css)]
    (io/make-parents out)
    (spit out (str root-vars "\n" generated))
    out))

(defn pages [& _]
  (site/write!)
  (css-release!)
  nil)

(defn -main [& args]
  (apply pages args))
