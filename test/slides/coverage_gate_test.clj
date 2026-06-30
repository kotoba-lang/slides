(ns slides.coverage-gate-test
  (:require [clojure.test :refer [deftest is]]
            [slides.coverage-gate :as gate]))

(def sample-lcov
  "TN:
SF:/repo/src/slides/model.cljc
DA:1,1
DA:2,0
DA:3,2
end_of_record
SF:src/slides/pptx.cljc
DA:1,1
DA:2,1
end_of_record
")

(deftest parses-lcov-records-and-computes-summary
  (let [summary (gate/coverage-summary (gate/parse-lcov sample-lcov))]
    (is (= 2 (count (:files summary))))
    (is (= 3 (get-in summary [:files "slides/model.cljc" :total])))
    (is (= 2 (get-in summary [:files "slides/model.cljc" :covered])))
    (is (= 5 (get-in summary [:aggregate :total])))
    (is (= 4 (get-in summary [:aggregate :covered])))))

(deftest reports-failures-for-low-or-missing-files
  (let [failed (gate/failures
                {:files {"slides/model.cljc" {:percent 89.0}
                         "slides/design.cljc" {:percent 96.0}
                         "slides/hiccup.cljc" {:percent 86.0}
                         "slides/office.cljc" {:percent 95.0}
                         "slides/pptx.cljc" {:percent 90.0}
                         "slides/render.cljc" {:percent 95.0}
                         "slides/routes.cljc" {:percent 100.0}
                         "slides/site.clj" {:percent 90.0}
                         "slides/validate.cljc" {:percent 95.0}}
                 :aggregate {:percent 91.0}})
        paths (set (map :path failed))]
    (is (contains? paths "slides/model.cljc"))
    (is (contains? paths "slides/build.clj"))
    (is (contains? paths "slides/cli.cljc"))
    (is (some :missing? failed))))
