(ns slides.causal-test
  (:require [clojure.test :refer [deftest is]]
            [slides.causal :as causal]
            [slides.model :as m]
            [slides.pptx-test :refer [zip-entries]]))

(deftest embeds-and-reads-slides-causal-payload
  (let [deck (-> (m/deck "deck" {:slides/title "Causal deck"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Intro"})
                      (m/add-shape (m/text-box "title" "Causal deck")))))
        bytes (causal/embed-deck-bytes deck {:slides-causal/source "unit-test"})
        entries (zip-entries bytes)
        payload (causal/read-payload-bytes bytes)
        graph (causal/read-graph-bytes bytes)]
    (is (contains? entries "ocz/causal.edn"))
    (is (re-find #"Extension=\"edn\"" (entries "[Content_Types].xml")))
    (is (re-find #"rIdKotobaOffice" (entries "_rels/.rels")))
    (is (= "kotoba-lang/office" (:office/generator payload)))
    (is (= "deck" (:slides-causal/deck-id graph)))
    (is (= "unit-test" (:slides-causal/source graph)))
    (is (= deck (causal/read-deck-bytes bytes)))))

(deftest writes-causal-pptx-to-disk
  (let [deck (-> (m/deck "deck" {:slides/title "Causal file"})
                 (m/add-slide (m/slide "s1" {:slides/title "Only"})))
        out (java.io.File/createTempFile "slides-causal" ".pptx")
        out-path (.getAbsolutePath out)]
    (try
      (let [result (causal/write-pptx! out-path deck)
            entries (zip-entries (java.nio.file.Files/readAllBytes
                                  (java.nio.file.Paths/get out-path (into-array String []))))]
        (is (= out-path (:slides/path result)))
        (is (:slides/causal result))
        (is (contains? entries "ocz/causal.edn")))
      (finally
        (.delete out)))))
