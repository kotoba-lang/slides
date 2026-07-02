(ns slides.build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [shitsuke.tokens :as tokens]
            [slides.site :as site]))

(defn css-release! []
  (let [out (io/file "docs" "main.css")
        static-css (slurp (io/resource "slides/static.css"))
        root-vars (tokens/css-variables)
        generated (str/replace static-css (str root-vars "\n") "")]
    (io/make-parents out)
    (spit out (str root-vars "\n" generated))
    out))

(defn pages [& _]
  (site/write!)
  (css-release!)
  nil)

(defn -main [& args]
  (apply pages args))
