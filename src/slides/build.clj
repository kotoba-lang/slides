(ns slides.build
  (:require [clojure.java.io :as io]
            [shadow.css.build :as css]
            [shitsuke.tokens :as tokens]
            [slides.site :as site]))

(defn css-release! []
  (let [result (-> (css/start)
                   (css/index-path (io/file "src") {})
                   (css/index-path (io/file "resources") {})
                   (css/generate '{:main {:include [slides.site shitsuke.components]}})
                   (css/minify)
                   (css/write-outputs-to (io/file "docs")))]
    ;; Tier A of the shitsuke style layer: prepend :root token CSS variables to
    ;; the built main.css so the editor (and shitsuke.components) can consume
    ;; var(--shitsuke-*). The legacy editor CSS in main.css keeps its own --ink
    ;; /--muted/... vars until the per-class shadow-css migration (follow-up).
    (let [out (io/file "docs" "main.css")
          existing (if (.exists out) (slurp out) "")
          root-vars (tokens/css-variables)]
      (spit out (str root-vars "\n" existing)))
    result))

(defn pages [& _]
  (site/write!)
  (css-release!)
  nil)

(defn -main [& args]
  (apply pages args))
