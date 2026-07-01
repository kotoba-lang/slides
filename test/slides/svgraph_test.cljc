(ns slides.svgraph-test
  (:require [clojure.test :refer [deftest is]]
            [slides.model :as m]
            [slides.svgraph :as svgraph]))

(deftest projects-deck-to-svgraph-presentation
  (let [deck (-> (m/deck "deck" {:slides/title "Graph deck"
                                 :slides/width 4
                                 :slides/height 3
                                 :slides/theme {:slides/colors {:accent "ABCDEF"}}})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Intro"})
                      (m/add-shape (m/text-box "title" "Graph deck"
                                               {:slides/x 1
                                                :slides/y 0.5
                                                :slides/w 2
                                                :slides/h 1
                                                :slides/color "123456"
                                                :slides/bold true}))
                      (m/add-shape (m/rect "panel")))))
        projection (svgraph/presentation deck)
        slide (first (:svgraph/slides projection))
        shape (first (:svgraph/shapes slide))]
    (is (= "svgraph-presentation/1" (:svgraph/version projection)))
    (is (= [3657600 2743200] (:svgraph/slide-size projection)))
    (is (= "deck" (get-in projection [:svgraph/source :svgraph/deck-id])))
    (is (= "s1" (:svgraph/id slide)))
    (is (= "Graph deck" (:svgraph/text shape)))
    (is (= [914400 457200 1828800 914400] (:svgraph/bounds shape)))
    (is (= "123456" (get-in shape [:svgraph/style :svgraph/color])))
    (is (true? (get-in shape [:svgraph/style :svgraph/bold])))))

(deftest projects-empty-deck-as-placeholder-slide
  (let [projection (svgraph/presentation (m/deck "empty" {:slides/title "Empty"}))]
    (is (= 1 (count (:svgraph/slides projection))))
    (is (= "Empty" (:svgraph/title (first (:svgraph/slides projection)))))))
