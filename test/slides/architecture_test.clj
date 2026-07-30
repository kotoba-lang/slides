(ns slides.architecture-test
  (:require [clojure.test :refer [deftest is testing]]
            [slides.architecture :as architecture]
            [slides.architecture-site :as site]))

(deftest catalog-is-edn-and-complete
  (is (= #{:light :dark :executive :technical}
         (set (keys architecture/themes))))
  (is (= #{:service-card :database-node :boundary :lane :arrow-label}
         (set (keys architecture/components))))
  (is (>= (count architecture/icons) 9))
  (is (= 4 (count architecture/samples))))

(deftest layout-is-deterministic-and-bounded
  (let [sample (first architecture/samples)
        a (architecture/layout-diagram sample)
        b (architecture/layout-diagram sample)]
    (is (= a b))
    (is (= {:width 1280 :height 720} (:canvas a)))
    (is (every? #(pos? (get-in % [:frame :w]))
                (mapcat :nodes (:lanes a))))))

(deftest svg-keeps-semantics-and-labels
  (let [svg (architecture/diagram->svg (first architecture/samples))]
    (is (.startsWith svg "<svg"))
    (is (re-find #"data-kind=\"lane\"" svg))
    (is (re-find #"data-kind=\"service\"" svg))
    (is (re-find #"data-kind=\"database\"" svg))
    (is (re-find #"data-kind=\"relation\"" svg))
    (is (re-find #"marker-end=\"url\(#arrow-" svg))
    (is (not (re-find #"(?:x|y|width|height)=\"[0-9]+/[0-9]+\"" svg)))
    (is (re-find #"Cloud commerce platform" svg))))

(deftest layout-projects-to-slide-edn
  (let [slide (architecture/diagram->slide (first architecture/samples))]
    (is (= "aws-cloud" (:slides/id slide)))
    (is (seq (:slides/shapes slide)))
    (is (map? (:slides/architecture slide)))))

(deftest gallery-renders-all-themes-and-edn-source
  (let [html (site/index-html)]
    (testing "GitHub Pages document"
      (is (.startsWith html "<!doctype html>"))
      (is (re-find #"Architecture as data" html)))
    (doseq [label ["Light" "Dark" "Executive" "Technical"]]
      (is (re-find (re-pattern label) html)))
    (is (re-find #"EDN source" html))
    (is (re-find #"data-theme=\"dark\"" html))))
