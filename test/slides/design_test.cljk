(ns slides.design-test
  (:require [clojure.test :refer [deftest is]]
            [slides.design :as design]))

(deftest deck-design-ignores-malformed-top-level-overrides
  (let [deck {:slides/title "Bad design"
              :slides/theme "not a theme"
              :slides/master "not a master"
              :slides/guides "not guides"
              :slides/text-styles "not styles"
              :slides/components "not components"
              :slides/design "not a design"}
        merged (design/deck-design deck)]
    (is (= "496B9A"
           (get-in merged [:slides/theme :slides/colors :office-style.color/accent1])))
    (is (= "kotoba-clean"
           (get-in merged [:slides/master :slides/id])))
    (is (= 12
           (get-in merged [:slides/guides :slides/columns])))
    (is (map? (:slides/text-styles merged)))
    (is (map? (:slides/components merged)))))

(deftest deck-design-keeps-valid-map-overrides
  (let [merged (design/deck-design
                {:slides/theme {:slides/colors {:office-style.color/accent1 "00FF00"}}
                 :slides/design {:slides/master {:slides/background "101010"}}})]
    (is (= "00FF00"
           (get-in merged [:slides/theme :slides/colors :office-style.color/accent1])))
    (is (= "101010"
           (get-in merged [:slides/master :slides/background])))))

(deftest resolve-shape-tolerates-non-map-input
  (is (= {} (design/resolve-shape {} "not a shape")))
  (is (= {} (design/resolve-shape {} nil))))
