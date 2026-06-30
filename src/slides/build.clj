(ns slides.build
  (:require [clojure.java.io :as io]
            [shadow.css.build :as css]
            [slides.site :as site]))

(defn css-release! []
  (-> (css/start)
      (css/index-path (io/file "src") {})
      (css/index-path (io/file "resources") {})
      (css/generate '{:main {:include [slides.site]}})
      (css/minify)
      (css/write-outputs-to (io/file "docs"))))

(defn pages [& _]
  (site/write!)
  (css-release!)
  nil)

(defn -main [& args]
  (apply pages args))
