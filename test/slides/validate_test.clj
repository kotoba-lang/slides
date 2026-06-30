(ns slides.validate-test
  (:require [clojure.test :refer [deftest is]]
            [slides.model :as model]
            [slides.validate :as validate]
            [slides.routes :as routes]))

(deftest valid-seed-workspace-has-no-errors
  (let [problems (validate/problems (model/seed-workspace))]
    (is (empty? (filter #(= :error (:slides/severity %)) problems)))
    (is (empty? (validate/route-problems)))))

(deftest detects-item-problems
  (let [ws (-> (model/workspace "ws")
               (assoc :slides/items
                      {"wrong-id" (model/item :slides/deck "id2" {:slides/title ""})
                       "doc-1" (model/item :slides/doc "doc-1" {:slides/title "Doc"})}))]
    (let [problems (validate/problems ws)]
      (is (some #(= :item/id-key-mismatch (:slides/code %)) problems))
      (is (some #(= :item/missing-title (:slides/code %)) problems))
      (is (some #(= :error (:slides/severity %)) problems)))))

(deftest detects-workspace-structure-problems
  (let [problems (validate/problems {:slides/id "bad"
                                     :slides/items []
                                     :slides/links {}})]
    (is (some #(= :workspace/items-not-map (:slides/code %)) problems))
    (is (some #(= :workspace/links-not-sequential (:slides/code %)) problems))
    (is (not (validate/valid? {:slides/id "bad"
                               :slides/items []
                               :slides/links {}})))))

(deftest detects-non-map-items-and-links
  (let [ws {:slides/id "ws"
            :slides/type :workspace
            :slides/items {"bad-item" "not a map"}
            :slides/links ["not a map"]}
        problems (validate/problems ws)]
    (is (some #(= :item/not-map (:slides/code %)) problems))
    (is (some #(= :link/not-map (:slides/code %)) problems))
    (is (not (validate/valid? ws)))))

(deftest detects-link-problems
  (let [ws (-> (model/workspace "ws")
               (model/add-item (model/deck "d1" {:slides/title "Deck"}))
               (assoc :slides/links [{:slides/from "missing"
                                     :slides/to "d1"
                                     :slides/link-kind :uses}]))]
    (let [problems (validate/problems ws)]
      (is (some #(= :link/dangling-from (:slides/code %)) problems))
      (is (some #(= :error (:slides/severity %)) problems)))))

(deftest detects-invalid-route-configuration
  (let [required #{"kotoba-lang.github.io/slides"
                   "kotoba-lang.github.io/docs"
                   "kotoba-lang.github.io/drive"
                   "kotoba-lang.github.io/sheets"}]
    (with-redefs [routes/hosts {"kotoba-lang.github.io/slides" (routes/apps :slides)}]
      (let [problems (validate/route-problems)]
        (is (= 3 (count problems)))
        (is (every? #(= :error (:slides/severity %)) problems))
        (is (every? #(contains? required (:slides/id %)) problems))))))
