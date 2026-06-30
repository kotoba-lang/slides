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

(deftest applies-slides-theme-overrides-when-exporting
  (let [deck (-> (m/deck "deck" {:slides/title "Theme test"
                                 :slides/theme {:slides/colors {:office-style.color/accent1 "ABCDEF"
                                                               :office-style.color/lt1 "FAFAFA"}
                                               :slides/fonts {:office-style.font/majorFont "Meiryo"
                                                             :office-style.font/minorFont "Verdana"}}})
                 (m/add-slide (m/slide "s1" {:slides/title "Only"})))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (is (re-find #"ABCDEF" (entries "ppt/theme/theme1.xml")))
    (is (re-find #"Meiryo" (entries "ppt/theme/theme1.xml")))
    (is (re-find #"Verdana" (entries "ppt/theme/theme1.xml")))))

(deftest legacy-office-style-theme-map-is-accepted
  (let [deck (-> (m/deck "deck" {:slides/title "Legacy theme"
                                 :slides/theme {:office-style/colors {:office-style.color/accent1 "00FF00"}}})
                 (m/add-slide (m/slide "s1" {:slides/title "Only"})))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (is (re-find #"00FF00" (entries "ppt/theme/theme1.xml")))))

(deftest invalid-hex-colors-fall-back-to-defaults
  (let [deck (-> (m/deck "deck" {:slides/title "Invalid color"
                                 :slides/theme {:slides/colors {:office-style.color/accent1 "bad"
                                                               :office-style.color/lt1 "ZZZZZZ"}}})
                 (m/add-slide (m/slide "s1" {:slides/title "Only"})))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (is (re-find #"496B9A" (entries "ppt/theme/theme1.xml")))
    (is (re-find #"F7F8FB" (entries "ppt/theme/theme1.xml")))))

(deftest writes-empty-deck-as-placeholder-slide
  (let [deck (m/deck "deck" {:slides/title "Empty deck"})
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-count (count (filter #(re-find #"^ppt/slides/slide\d+\.xml$" %)
                                  (keys entries)))]
    (is (= 1 slide-count))
    (is (re-find #"Empty deck" (entries "ppt/slides/slide1.xml")))))

(deftest updates-pptx-using-base-path
  (let [deck (-> (m/deck "deck" {:slides/title "Base deck"})
                 (m/add-slide (m/slide "s1" {:slides/title "A"})))
        base (java.io.File/createTempFile "slides-base" ".pptx")
        out (java.io.File/createTempFile "slides-updated" ".pptx")
        base-path (.getAbsolutePath base)
        out-path (.getAbsolutePath out)]
    (try
      (spit base "base-placeholder")
      (let [result (pptx/update-pptx! base-path out-path deck)
            entries (zip-entries (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get out-path (into-array String []))))]
        (is (= out-path (:slides/path result)))
        (is (= 1 (:slides/slides result)))
        (is (= (count (filter #(re-find #"^ppt/slides/slide\d+\.xml$" %)
                              (keys entries)))
               1))
        (is (re-find #"A" (entries "ppt/slides/slide1.xml"))))
      (finally
        (.delete base)
        (.delete out)))))
