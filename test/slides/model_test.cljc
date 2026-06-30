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
    (is (= :slides (:slides/app (routes/resolve-host "kotoba-lang.github.io/slides"))))
    (is (= :docs (:slides/app (routes/resolve-host "kotoba-lang.github.io/docs"))))
    (is (= :drive (:slides/app (routes/resolve-host "kotoba-lang.github.io/drive"))))
    (is (= :sheets (:slides/app (routes/resolve-host "kotoba-lang.github.io/sheets"))))))

(deftest remove-item-removes-associated-links
  (let [ws (-> (m/workspace "ws" {:slides/title "Workspace"})
               (m/add-item (m/deck "deck-a" {:slides/title "A"}))
               (m/add-item (m/deck "deck-b" {:slides/title "B"}))
               (m/add-item (m/doc "doc-1" {:slides/title "Doc"}))
               (m/link "deck-a" "doc-1" :contains)
               (m/link "deck-b" "doc-1" :uses)
               (m/link "doc-1" "deck-b" :embeds)
               (m/remove-item "deck-a"))]
    (is (nil? (m/item-by-id ws "deck-a")))
    (is (nil? (some #(= (:slides/from %) "deck-a") (:slides/links ws))))
    (is (= 2 (count (:slides/links ws))))
    (is (some #(and (= "doc-1" (:slides/from %))
                   (= "deck-b" (:slides/to %)))
              (:slides/links ws)))))

(deftest items-by-kind-is-deterministic
  (let [ws (-> (m/workspace "ws" {:slides/title "Workspace"})
               (m/add-item (m/doc "doc-a" {:slides/title "D1"}))
               (m/add-item (m/doc "doc-b" {:slides/title "D2"}))
               (m/add-item (m/deck "deck-c" {:slides/title "C1"})))
        docs (m/items-by-kind ws :slides/doc)]
    (is (= ["doc-a" "doc-b"] (map :slides/id docs)))
    (is (= 2 (count docs)))))
