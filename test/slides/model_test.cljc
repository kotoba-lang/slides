(ns slides.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [slides.model :as m]
            [slides.routes :as routes]
            [slides.validate :as v]))

(deftest workspace-graph
  (let [ws (-> (m/workspace "gftd")
               (m/add-item (m/deck "deck" {:slides/title "Deck"}))
               (m/add-item (m/doc "doc" {:slides/title "Doc"}))
               (m/link "deck" "doc" :uses))]
    (is (= ["deck"] (map :slides/id (m/items-by-kind ws :slides/deck))))
    (is (= 1 (count (m/outgoing ws "deck"))))
    (is (v/valid? ws))))

(deftest validation-finds-dangling-links
  (let [ws (-> (m/workspace "gftd")
               (m/add-item (m/deck "deck" {:slides/title "Deck"}))
               (m/link "deck" "missing" :uses))]
    (is (= [:link/dangling-to]
           (map :slides/code (v/problems ws))))))

(deftest host-routes
  (testing "required gftd hosts resolve"
    (is (= :slides (:slides/app (routes/resolve-host "slides.gftd.ai"))))
    (is (= :docs (:slides/app (routes/resolve-host "docs.gftd.ai"))))
    (is (= :drive (:slides/app (routes/resolve-host "drive.gftd.ai"))))
    (is (= :sheets (:slides/app (routes/resolve-host "sheets.gftd.ai"))))))
