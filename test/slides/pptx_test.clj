(ns slides.pptx-test
  (:require [clojure.test :refer [deftest is]]
            [slides.model :as m]
            [slides.pptx :as pptx])
  (:import [java.io ByteArrayInputStream]
           [java.util.zip ZipInputStream]))

(defn zip-entries [bytes]
  (with-open [zip (ZipInputStream. (ByteArrayInputStream. bytes))]
    (loop [entries {}]
      (if-let [entry (.getNextEntry zip)]
        (let [buf (byte-array 8192)
              out (java.io.ByteArrayOutputStream.)]
          (loop []
            (let [n (.read zip buf)]
              (when (pos? n)
                (.write out buf 0 n)
                (recur))))
          (recur (assoc entries (.getName entry) (.toString out "UTF-8"))))
        entries))))

(deftest writes-pptx-package-from-edn
  (let [deck (-> (m/deck "deck" {:slides/title "Board update"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Intro"})
                      (m/add-shape (m/text-box "title" "Board update"))
                      (m/add-shape (m/rect "panel"))))
                 (m/add-slide
                  (-> (m/slide "s2" {:slides/title "Plan"})
                      (m/add-shape (m/text-box "title" "Plan" {:slides/font-size 32})))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (is (contains? entries "[Content_Types].xml"))
    (is (contains? entries "ppt/presentation.xml"))
    (is (contains? entries "ppt/slides/slide1.xml"))
    (is (contains? entries "ppt/slides/slide2.xml"))
    (is (re-find #"Board update" (entries "ppt/slides/slide1.xml")))
    (is (re-find #"Plan" (entries "ppt/slides/slide2.xml")))
    (is (re-find #"presentationml.presentation.main\+xml" (entries "[Content_Types].xml")))))
