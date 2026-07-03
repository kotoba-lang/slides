(ns slides.pptx-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slides.design :as design]
            [slides.model :as m]
            [slides.office :as office]
            [slides.pptx :as pptx])
  (:import [java.io ByteArrayInputStream]
           [java.util.zip ZipEntry ZipInputStream ZipOutputStream]))

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

(defn zip-entry-bytes [bytes path]
  (with-open [zip (ZipInputStream. (ByteArrayInputStream. bytes))]
    (loop []
      (when-let [entry (.getNextEntry zip)]
        (let [buf (byte-array 8192)
              out (java.io.ByteArrayOutputStream.)]
          (loop []
            (let [n (.read zip buf)]
              (when (pos? n)
                (.write out buf 0 n)
                (recur))))
          (if (= path (.getName entry))
            (.toByteArray out)
            (recur)))))))

(defn zip-bytes [entries]
  (let [out (java.io.ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[path text] entries]
        (.putNextEntry zip (ZipEntry. path))
        (.write zip (if (string? text) (.getBytes text "UTF-8") text))
        (.closeEntry zip)))
    (.toByteArray out)))

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

(deftest writes-table-shape-as-native-graphic-frame
  (let [deck (-> (m/deck "deck" {:slides/title "With table"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Data"})
                      (m/add-shape {:slides/id "t1" :slides/shape :table
                                    :slides/x 0.5 :slides/y 0.5 :slides/w 6.0 :slides/h 2.0
                                    :slides/rows [["Quarter" "Revenue"] ["Q1" "120"] ["Q2" "180"]]}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "a native <p:graphicFrame><a:tbl> is emitted, not a plain text box"
      (is (re-find #"<p:graphicFrame>" slide-xml))
      (is (re-find #"<a:tbl>" slide-xml))
      (is (re-find #"drawingml/2006/table" slide-xml)))
    (testing "every cell's text survives, each in its own cell (not joined into one text run)"
      (is (= 3 (count (re-seq #"<a:tr\b" slide-xml))))
      (is (= 6 (count (re-seq #"<a:tc>" slide-xml))))
      (is (re-find #"<a:t>Quarter</a:t>" slide-xml))
      (is (re-find #"<a:t>Revenue</a:t>" slide-xml))
      (is (re-find #"<a:t>Q1</a:t>" slide-xml))
      (is (re-find #"<a:t>120</a:t>" slide-xml)))
    (testing "table geometry uses <p:xfrm>, not <a:xfrm> inside <p:spPr> (graphicFrame convention)"
      (is (re-find #"<p:xfrm>" slide-xml)))))

(deftest writes-and-round-trips-table-style-flags
  (testing "explicit :slides/table-style-flags override the writer's own default"
    (let [deck (-> (m/deck "deck" {:slides/title "Banded columns"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape {:slides/id "t1" :slides/shape :table
                                      :slides/w 4.0 :slides/h 1.0
                                      :slides/rows [["A" "B"] ["1" "2"]]
                                      :slides/table-style-flags {:band-col? true}}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:tblPr bandCol=\"1\">" slide-xml))
      (is (not (re-find #"firstRow" slide-xml)))
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            table (first (filter #(= :table (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= {:band-col? true} (:slides/table-style-flags table))))))
  (testing "no :slides/table-style-flags -- the historical firstRow+bandRow default, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape {:slides/id "t1" :slides/shape :table
                                      :slides/w 4.0 :slides/h 1.0
                                      :slides/rows [["A" "B"] ["1" "2"]]}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:tblPr firstRow=\"1\" bandRow=\"1\">" slide-xml)))))

(deftest writes-and-round-trips-table-cell-merge-and-fill
  (let [deck (-> (m/deck "deck" {:slides/title "Merged header"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Data"})
                      (m/add-shape {:slides/id "t1" :slides/shape :table
                                    :slides/x 0.5 :slides/y 0.5 :slides/w 6.0 :slides/h 2.0
                                    :slides/rows [["Header" "Header"] ["Q1" "10"]]
                                    :slides/cells [[{:text "Header" :col-span 2 :fill "DDEEFF"} :h-merge]
                                                  ["Q1" "10"]]}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing ":slides/cells (the richer grid) is preferred over :slides/rows when both are present"
      (is (re-find #"<a:tc gridSpan=\"2\">" slide-xml))
      (is (re-find #"<a:solidFill><a:srgbClr val=\"DDEEFF\"/></a:solidFill>" slide-xml))
      (is (re-find #"<a:tc hMerge=\"1\">" slide-xml))
      (is (= 1 (count (re-seq #"<a:t>Header</a:t>" slide-xml))) "the merge-continuation cell has NO text of its own"))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            table (first (filter #(= :table (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= [{:text "Header" :col-span 2 :fill "DDEEFF"} :h-merge] (first (:slides/cells table))))
        (is (= ["Q1" "10"] (second (:slides/cells table)))))))
  (testing "a plain :slides/rows table (no :slides/cells) still writes exactly as before"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape {:slides/id "t1" :slides/shape :table
                                                                  :slides/w 4.0 :slides/h 1.0
                                                                  :slides/rows [["A" "B"] ["1" "2"]]}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (not (re-find #"gridSpan" slide-xml)))
      (is (not (re-find #"hMerge" slide-xml))))))

(deftest writes-and-round-trips-table-cell-borders
  (let [borders {:left {:width 1.0 :color "112233"} :top {:width 2.0 :color "445566"}}
        deck (-> (m/deck "deck" {:slides/title "Bordered header"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape {:slides/id "t1" :slides/shape :table
                                    :slides/x 0.5 :slides/y 0.5 :slides/w 6.0 :slides/h 2.0
                                    :slides/cells [[{:text "Header" :borders borders} "Plain"]
                                                  ["Q1" "10"]]}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "border sides are written as <a:tcPr>'s own lnL/lnT children, before the fill"
      (is (re-find #"<a:tcPr><a:lnL w=\"12700\"><a:solidFill><a:srgbClr val=\"112233\"/></a:solidFill></a:lnL><a:lnT w=\"25400\"><a:solidFill><a:srgbClr val=\"445566\"/></a:solidFill></a:lnT></a:tcPr>"
                    slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            table (first (filter #(= :table (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= borders (:borders (first (first (:slides/cells table))))))))))

(deftest writes-and-round-trips-table-cell-diagonal-borders-margins-and-anchor
  (let [borders {:diagonal-down {:width 1.0 :color "112233"} :diagonal-up {:width 2.0 :color "445566"}}
        deck (-> (m/deck "deck" {:slides/title "Diagonal + centered"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape {:slides/id "t1" :slides/shape :table
                                    :slides/x 0.5 :slides/y 0.5 :slides/w 6.0 :slides/h 2.0
                                    :slides/cells [[{:text "X" :borders borders
                                                     :margin-left 0.1 :margin-top 0.05 :anchor :center}
                                                    "Plain"]
                                                   ["Q1" "10"]]}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "diagonal sides are written as <a:tcPr>'s own lnTlToBr/lnBlToRt children, after the four straight sides"
      (is (re-find #"<a:lnTlToBr w=\"12700\"><a:solidFill><a:srgbClr val=\"112233\"/></a:solidFill></a:lnTlToBr><a:lnBlToRt w=\"25400\"><a:solidFill><a:srgbClr val=\"445566\"/></a:solidFill></a:lnBlToRt>"
                    slide-xml)))
    (testing "margins/anchor are written as <a:tcPr>'s own attributes"
      (is (re-find #"<a:tcPr marL=\"91440\" marT=\"45720\" anchor=\"ctr\">" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            table (first (filter #(= :table (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))
            cell (first (first (:slides/cells table)))]
        (is (= borders (:borders cell)))
        (is (= 0.1 (:margin-left cell)))
        (is (= 0.05 (:margin-top cell)))
        (is (= :center (:anchor cell)))))))

(deftest table-shape-with-ragged-or-empty-rows-still-produces-a-valid-grid
  (let [deck (-> (m/deck "deck" {:slides/title "Ragged"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Ragged"})
                      (m/add-shape {:slides/id "t1" :slides/shape :table
                                    :slides/w 4.0 :slides/h 1.0
                                    :slides/rows [["A" "B" "C"] ["only-one"]]}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (is (= 2 (count (re-seq #"<a:tr\b" slide-xml))))
    ;; every row padded to the widest row's column count (3), so both rows
    ;; emit 3 <a:tc> cells each = 6 total, never a jagged/invalid grid.
    (is (= 6 (count (re-seq #"<a:tc>" slide-xml)))))
  (let [deck (-> (m/deck "deck" {:slides/title "Empty"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Empty"})
                      (m/add-shape {:slides/id "t1" :slides/shape :table
                                    :slides/w 4.0 :slides/h 1.0}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (is (re-find #"<a:tbl>" slide-xml) "a table with no :slides/rows still emits a valid (single blank cell) table")))

;; A minimal valid 1x1 transparent PNG, base64-encoded -- real bytes, not a
;; placeholder string, so the zip round-trip and content-type wiring are
;; exercised against actual binary data.
(def one-pixel-png-base64
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")

(deftest writes-image-shape-as-native-pic-with-embedded-media
  (let [deck (-> (m/deck "deck" {:slides/title "With image"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Photo"})
                      (m/add-shape (m/image "logo" one-pixel-png-base64
                                            {:slides/x 1.0 :slides/y 1.0 :slides/w 2.0 :slides/h 2.0})))))
        bytes (pptx/pptx-bytes deck)
        entries (zip-entries bytes)
        slide-xml (entries "ppt/slides/slide1.xml")
        rels-xml (entries "ppt/slides/_rels/slide1.xml.rels")]
    (testing "a native <p:pic> is emitted, not a plain text box"
      (is (re-find #"<p:pic>" slide-xml))
      (is (re-find #"<a:blip r:embed=\"rId2\"" slide-xml)))
    (testing "the image is embedded as a real media part with the correct bytes"
      (is (contains? entries "ppt/media/image1.png"))
      (let [embedded-bytes (with-open [zip (ZipInputStream. (ByteArrayInputStream. bytes))]
                             (loop []
                               (when-let [entry (.getNextEntry zip)]
                                 (if (= "ppt/media/image1.png" (.getName entry))
                                   (let [out (java.io.ByteArrayOutputStream.)
                                         buf (byte-array 4096)]
                                     (loop []
                                       (let [n (.read zip buf)]
                                         (when (pos? n) (.write out buf 0 n) (recur))))
                                     (.toByteArray out))
                                   (recur)))))
            expected-bytes (.decode (java.util.Base64/getDecoder) one-pixel-png-base64)]
        (is (= (seq expected-bytes) (seq embedded-bytes)))))
    (testing "the slide's own .rels wires rId2 to the media part, alongside the layout rel"
      (is (re-find #"Id=\"rId1\"[^>]*slideLayout" rels-xml))
      (is (re-find #"Id=\"rId2\"[^>]*media/image1.png" rels-xml)))
    (testing "[Content_Types].xml declares the png extension"
      (is (re-find #"Extension=\"png\"" (entries "[Content_Types].xml"))))))

(deftest writes-and-round-trips-picture-locks
  (let [locks {:no-change-aspect? true :no-move? true}
        deck (-> (m/deck "deck" {:slides/title "Locked"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Photo"})
                      (m/add-shape (m/image "logo" one-pixel-png-base64
                                            {:slides/x 1.0 :slides/y 1.0 :slides/w 2.0 :slides/h 2.0
                                             :slides/locks locks})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "the captured lock flags are written, replacing the default"
      (is (re-find #"<a:picLocks noChangeAspect=\"1\" noMove=\"1\"/>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shape (some #(when (= "logo" (:slides/id %)) %) (-> reimported :slides/slides first :slides/shapes))]
        (is (= locks (:slides/locks shape))))))
  (testing "no :slides/locks -- the historical noChangeAspect=\"1\" default, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/image "logo" one-pixel-png-base64)))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:picLocks noChangeAspect=\"1\"/>" slide-xml)))))

(deftest writes-and-round-trips-shape-locks
  (let [locks {:no-grp? true :no-rot? true}
        deck (-> (m/deck "deck" {:slides/title "Locked shapes"})
                 (m/add-slide (-> (m/slide "s1")
                                  (m/add-shape (m/text-box "t" "Locked text" {:slides/locks locks}))
                                  (m/add-shape (m/rect "r" {:slides/locks locks})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "the captured lock flags are written on both the text and rect shape"
      (is (= 2 (count (re-seq #"<a:spLocks noGrp=\"1\" noRot=\"1\"/>" slide-xml)))))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shapes (-> reimported :slides/slides first :slides/shapes)]
        (is (= locks (:slides/locks (some #(when (= "t" (:slides/id %)) %) shapes))))
        (is (= locks (:slides/locks (some #(when (= "r" (:slides/id %)) %) shapes)))))))
  (testing "no :slides/locks -- no <a:spLocks> at all, <p:cNvSpPr> stays self-closing, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1")
                                    (m/add-shape (m/text-box "t" "Plain"))
                                    (m/add-shape (m/rect "r")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (not (re-find #"<a:spLocks" slide-xml)))
      (is (re-find #"<p:cNvSpPr txBox=\"1\"/>" slide-xml))
      (is (re-find #"<p:cNvSpPr/>" slide-xml)))))

(deftest writes-and-round-trips-graphic-frame-locks
  (let [locks {:no-grp? true :no-resize? true}
        deck (-> (m/deck "deck" {:slides/title "Locked table"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape {:slides/id "t1" :slides/shape :table
                                    :slides/w 4.0 :slides/h 1.0
                                    :slides/rows [["A" "B"] ["1" "2"]]
                                    :slides/locks locks}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "the captured lock flags are written, replacing the default"
      (is (re-find #"<a:graphicFrameLocks noGrp=\"1\" noResize=\"1\"/>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            table (first (filter #(= :table (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= locks (:slides/locks table))))))
  (testing "no :slides/locks -- the historical noGrp=\"1\" default, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape {:slides/id "t1" :slides/shape :table
                                      :slides/w 4.0 :slides/h 1.0
                                      :slides/rows [["A" "B"] ["1" "2"]]}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:graphicFrameLocks noGrp=\"1\"/>" slide-xml)))))

(deftest writes-and-round-trips-picture-crop
  (let [deck (-> (m/deck "deck" {:slides/title "Cropped"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Photo"})
                      (m/add-shape (m/image "logo" one-pixel-png-base64
                                            {:slides/x 1.0 :slides/y 1.0 :slides/w 2.0 :slides/h 2.0
                                             :slides/crop {:left 10.0 :top 5.0 :right 10.0}})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "<a:srcRect> is written right after <a:blip>, only the non-zero sides"
      (is (re-find #"<a:blip r:embed=\"rId2\"/><a:srcRect l=\"10000\" t=\"5000\" r=\"10000\"/><a:stretch>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shape (some #(when (= "logo" (:slides/id %)) %) (-> reimported :slides/slides first :slides/shapes))]
        (is (= {:left 10.0 :top 5.0 :right 10.0} (:slides/crop shape))))))
  (testing "no :slides/crop -- no <a:srcRect> at all, unchanged from before this feature existed"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/image "logo" one-pixel-png-base64)))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (not (re-find #"srcRect" slide-xml))))))

(deftest writes-and-round-trips-picture-recolor
  (let [deck (-> (m/deck "deck" {:slides/title "Recolored"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Photo"})
                      (m/add-shape (m/image "logo" one-pixel-png-base64
                                            {:slides/x 1.0 :slides/y 1.0 :slides/w 2.0 :slides/h 2.0
                                             :slides/recolor {:grayscale? true :alpha-mod 50.0}})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "<a:blip> is no longer self-closing, and its recolor children are written"
      (is (re-find #"<a:blip r:embed=\"rId2\"><a:alphaModFix amt=\"50000\"/><a:grayscl/></a:blip>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shape (some #(when (= "logo" (:slides/id %)) %) (-> reimported :slides/slides first :slides/shapes))]
        (is (= {:grayscale? true :alpha-mod 50.0} (:slides/recolor shape))))))
  (testing "no :slides/recolor -- <a:blip> stays self-closing, unchanged from before this feature existed"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/image "logo" one-pixel-png-base64)))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:blip r:embed=\"rId2\"/>" slide-xml)))))

(deftest image-shape-with-invalid-image-data-falls-back-to-text-safely
  (let [deck (-> (m/deck "deck" {:slides/title "Bad image"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Bad"})
                      (m/add-shape {:slides/id "broken" :slides/shape :image
                                    :slides/x 1.0 :slides/y 1.0 :slides/w 2.0 :slides/h 2.0
                                    :slides/image-data "not valid base64!!"}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (is (not (re-find #"<p:pic>" slide-xml))
        "malformed image data never becomes a dangling r:embed reference")
    (is (re-find #"<p:sp>" slide-xml)
        "falls back to a plain text box instead of corrupting the package")))

(deftest writes-chart-shape-as-native-graphic-frame-with-embedded-workbook
  (let [deck (-> (m/deck "deck" {:slides/title "With chart"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Revenue"})
                      (m/add-shape {:slides/id "c1" :slides/shape :chart
                                    :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0
                                    :slides/chart-data {:rows [["Quarter" "Revenue"]
                                                               ["Q1" 120] ["Q2" 180] ["Q3" 240]]}}))))
        bytes (pptx/pptx-bytes deck)
        entries (zip-entries bytes)
        slide-xml (entries "ppt/slides/slide1.xml")
        rels-xml (entries "ppt/slides/_rels/slide1.xml.rels")
        chart-xml (entries "ppt/charts/chart1.xml")
        chart-rels-xml (entries "ppt/charts/_rels/chart1.xml.rels")]
    (testing "a native <p:graphicFrame><c:chart> reference is emitted, not a plain text box"
      (is (re-find #"<p:graphicFrame>" slide-xml))
      (is (re-find #"<c:chart\b[^>]*r:id=\"rId2\"" slide-xml)))
    (testing "the slide's own .rels wires rId2 to the chart part"
      (is (re-find #"Id=\"rId2\"[^>]*charts/chart1.xml" rels-xml)))
    (testing "the chart part itself has a real <c:barChart> with one <c:ser> per data column"
      (is (some? chart-xml))
      (is (re-find #"<c:barChart>" chart-xml))
      (is (= 1 (count (re-seq #"<c:ser>" chart-xml))))
      (is (re-find #"Revenue" chart-xml)))
    (testing "the chart part's own .rels wires to a freshly embedded xlsx workbook"
      (is (some? chart-rels-xml))
      (is (re-find #"Type=\"[^\"]*relationships/package\"" chart-rels-xml))
      (is (re-find #"embeddings/Microsoft_Excel_Sheet1.xlsx" chart-rels-xml)))
    (testing "the embedded workbook is a real, non-empty, independently-openable xlsx package"
      (is (contains? entries "ppt/embeddings/Microsoft_Excel_Sheet1.xlsx"))
      (let [xlsx-bytes (zip-entry-bytes bytes "ppt/embeddings/Microsoft_Excel_Sheet1.xlsx")
            xlsx-inner-entries (zip-entries xlsx-bytes)]
        (is (pos? (count xlsx-bytes)))
        (is (contains? xlsx-inner-entries "xl/workbook.xml"))
        (is (contains? xlsx-inner-entries "xl/worksheets/sheet1.xml"))
        (is (re-find #"Revenue" (xlsx-inner-entries "xl/worksheets/sheet1.xml")))
        (is (re-find #"<v>120</v>" (xlsx-inner-entries "xl/worksheets/sheet1.xml")))))
    (testing "[Content_Types].xml declares both the chart XML and the xlsx extension"
      (is (re-find #"PartName=\"/ppt/charts/chart1.xml\"" (entries "[Content_Types].xml")))
      (is (re-find #"Extension=\"xlsx\"" (entries "[Content_Types].xml"))))))

(deftest writes-chart-legend-position-and-axis-titles
  (testing "an explicit legend position overrides the writer's own default bottom placement"
    (let [deck (-> (m/deck "deck" {:slides/title "Legend right"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape {:slides/id "c1" :slides/shape :chart
                                      :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0
                                      :slides/chart-legend-position :right
                                      :slides/chart-data {:rows [["Quarter" "Revenue"] ["Q1" 120]]}}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          chart-xml (entries "ppt/charts/chart1.xml")]
      (is (re-find #"<c:legendPos val=\"r\"/>" chart-xml))))
  (testing ":none omits <c:legend> entirely"
    (let [deck (-> (m/deck "deck" {:slides/title "No legend"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape {:slides/id "c1" :slides/shape :chart
                                      :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0
                                      :slides/chart-legend-position :none
                                      :slides/chart-data {:rows [["Quarter" "Revenue"] ["Q1" 120]]}}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          chart-xml (entries "ppt/charts/chart1.xml")]
      (is (not (re-find #"<c:legend>" chart-xml)))))
  (testing "no :slides/chart-legend-position -- the historical bottom default, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Default legend"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape {:slides/id "c1" :slides/shape :chart
                                      :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0
                                      :slides/chart-data {:rows [["Quarter" "Revenue"] ["Q1" 120]]}}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          chart-xml (entries "ppt/charts/chart1.xml")]
      (is (re-find #"<c:legendPos val=\"b\"/>" chart-xml))))
  (testing "axis titles are written on the category and value axes, schema-ordered before <c:crossAx>"
    (let [deck (-> (m/deck "deck" {:slides/title "Titled axes"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape {:slides/id "c1" :slides/shape :chart
                                      :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0
                                      :slides/chart-axis-titles {:category "Quarter" :value "Revenue ($)"}
                                      :slides/chart-data {:rows [["Quarter" "Revenue"] ["Q1" 120]]}}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          chart-xml (entries "ppt/charts/chart1.xml")]
      (is (re-find #"<c:axPos val=\"b\"/><c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Quarter</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title><c:crossAx"
                    chart-xml))
      (is (re-find #"<c:axPos val=\"l\"/><c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Revenue \(\$\)</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title><c:crossAx"
                    chart-xml))))
  (testing "no :slides/chart-axis-titles -- no <c:title> at all, unchanged from before this feature existed"
    (let [deck (-> (m/deck "deck" {:slides/title "No titles"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape {:slides/id "c1" :slides/shape :chart
                                      :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0
                                      :slides/chart-data {:rows [["Quarter" "Revenue"] ["Q1" 120]]}}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          chart-xml (entries "ppt/charts/chart1.xml")]
      (is (not (re-find #"<c:title>" chart-xml))))))

(deftest chart-shape-with-multiple-series-and-line-type-renders-all-series
  (let [deck (-> (m/deck "deck" {:slides/title "Multi-series"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Trend"})
                      (m/add-shape {:slides/id "c1" :slides/shape :chart :slides/chart-type :line
                                    :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0
                                    :slides/chart-data {:rows [["Month" "Actual" "Forecast"]
                                                               ["Jan" 10 12]
                                                               ["Feb" 14 15]]}}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        chart-xml (entries "ppt/charts/chart1.xml")]
    (is (re-find #"<c:lineChart>" chart-xml))
    (is (= 2 (count (re-seq #"<c:ser>" chart-xml))) "one series per data column (Actual, Forecast)")
    (is (re-find #"Actual" chart-xml))
    (is (re-find #"Forecast" chart-xml))))

(deftest area-chart-type-renders-with-axes
  (let [deck (-> (m/deck "deck" {:slides/title "Area"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Growth"})
                      (m/add-shape {:slides/id "c1" :slides/shape :chart :slides/chart-type :area
                                    :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0
                                    :slides/chart-data {:rows [["Month" "Users"]
                                                               ["Jan" 100] ["Feb" 150]]}}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        chart-xml (entries "ppt/charts/chart1.xml")]
    (is (re-find #"<c:areaChart>" chart-xml))
    (is (re-find #"<c:catAx>" chart-xml) "area, like bar/line, plots against category + value axes")
    (is (re-find #"<c:valAx>" chart-xml))))

(deftest scatter-chart-type-plots-x-y-value-pairs-with-two-value-axes
  (let [deck (-> (m/deck "deck" {:slides/title "Scatter"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Correlation"})
                      (m/add-shape {:slides/id "c1" :slides/shape :chart :slides/chart-type :scatter
                                    :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0
                                    :slides/chart-data {:rows [["X" "Y"]
                                                               [1 10] [2 15] [3 22]]}}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        chart-xml (entries "ppt/charts/chart1.xml")]
    (testing "a real <c:scatterChart> with X-Y value pairs, not cat+val"
      (is (re-find #"<c:scatterChart>" chart-xml))
      (is (re-find #"<c:xVal><c:numRef>" chart-xml))
      (is (re-find #"<c:yVal><c:numRef>" chart-xml))
      (is (not (re-find #"<c:cat>" chart-xml))))
    (testing "BOTH axes are value axes -- no category axis at all, unlike bar/line/area"
      (is (= 2 (count (re-seq #"<c:valAx>" chart-xml))))
      (is (not (re-find #"<c:catAx>" chart-xml))))))

(deftest pie-and-doughnut-chart-types-plot-one-series-with-no-axes
  (doseq [[chart-type expected-tag] [[:pie "c:pieChart"] [:doughnut "c:doughnutChart"]]]
    (let [deck (-> (m/deck "deck" {:slides/title "Share"})
                   (m/add-slide
                    (-> (m/slide "s1" {:slides/title "Market share"})
                        (m/add-shape {:slides/id "c1" :slides/shape :chart :slides/chart-type chart-type
                                      :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0
                                      :slides/chart-data {:rows [["Segment" "Share"]
                                                                 ["A" 40] ["B" 35] ["C" 25]]}}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          chart-xml (entries "ppt/charts/chart1.xml")]
      (testing (str chart-type)
        (is (re-find (re-pattern (str "<" expected-tag ">")) chart-xml))
        (is (= 1 (count (re-seq #"<c:ser>" chart-xml))) "exactly one series, unlike bar/line/area's one-per-column")
        (is (not (re-find #"<c:catAx>" chart-xml)))
        (is (not (re-find #"<c:valAx>" chart-xml))))))
  (testing "doughnut's one structural difference from pie: holeSize"
    (let [deck (-> (m/deck "deck" {:slides/title "Share"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape {:slides/id "c1" :slides/shape :chart :slides/chart-type :doughnut
                                      :slides/chart-data {:rows [["Segment" "Share"] ["A" 40] ["B" 60]]}}))))
          entries (zip-entries (pptx/pptx-bytes deck))]
      (is (re-find #"<c:holeSize val=\"50\"/>" (entries "ppt/charts/chart1.xml"))))))

(deftest chart-shape-without-chart-data-falls-back-to-text-safely
  (let [deck (-> (m/deck "deck" {:slides/title "No data"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Empty"})
                      (m/add-shape {:slides/id "c1" :slides/shape :chart
                                    :slides/x 1.0 :slides/y 1.0 :slides/w 4.0 :slides/h 3.0}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (is (not (re-find #"<c:chart\b" slide-xml)))
    (is (not (contains? entries "ppt/charts/chart1.xml")))
    (is (re-find #"<p:sp>" slide-xml) "falls back to a plain text box, not a dangling chart reference")))

(deftest theme-xml-carries-east-asian-and-complex-script-typefaces
  (let [deck (m/deck "deck" {:slides/title "CJK theme"
                             :slides/theme {:slides/fonts
                                            {:office-style.font/majorFont "Aptos Display"
                                             :office-style.font/majorFont-ea "游ゴシック"
                                             :office-style.font/minorFont "Aptos"
                                             :office-style.font/minorFont-ea "メイリオ"}}})
        xml (pptx/theme-xml (design/theme deck))]
    (is (re-find #"<a:ea typeface=\"游ゴシック\"/>" xml))
    (is (re-find #"<a:ea typeface=\"メイリオ\"/>" xml))))

(deftest japanese-text-run-gets-ja-jp-lang-and-east-asian-typeface
  (let [deck (-> (m/deck "deck" {:slides/title "日本語デッキ"
                                 :slides/theme {:slides/fonts
                                                {:office-style.font/majorFont-ea "游ゴシック"
                                                 :office-style.font/minorFont-ea "メイリオ"}}})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "スライド1"})
                      (m/add-shape (m/text-box "title" "四半期業績アップデート" {:slides/font-size 32}))
                      (m/add-shape (m/text-box "body" "Revenue, margin, and roadmap"
                                                {:slides/font-size 18 :slides/y 2.0})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "a run whose text is Japanese gets lang=\"ja-JP\" and the East Asian typeface"
      (is (re-find #"<a:rPr lang=\"ja-JP\"[^>]*sz=\"3200\"" slide-xml))
      (is (re-find #"<a:ea typeface=\"游ゴシック\"/>" slide-xml)))
    (testing "a plain-Latin run keeps en-US and doesn't get a spurious <a:ea>"
      (is (re-find #"<a:rPr lang=\"en-US\"[^>]*sz=\"1800\"" slide-xml)))))

(deftest multiline-text-splits-into-separate-paragraphs-not-one-run
  (let [deck (-> (m/deck "deck" {:slides/title "Multiline"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Lines"})
                      (m/add-shape (m/text-box "body" "Line one\nLine two\nLine three")))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "three lines become three <a:p> paragraphs, not one <a:t> with embedded newlines"
      ;; +1 for the default design master footer shape's own paragraph.
      (is (= 4 (count (re-seq #"<a:p>" slide-xml))))
      (is (re-find #"<a:t>Line one</a:t>" slide-xml))
      (is (re-find #"<a:t>Line two</a:t>" slide-xml))
      (is (re-find #"<a:t>Line three</a:t>" slide-xml))
      (is (not (re-find #"Line one\\nLine two" slide-xml))))))

(deftest writes-structured-paragraphs-with-bullets-alignment-and-line-spacing
  (let [deck (-> (m/deck "deck" {:slides/title "Bulleted"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "List"})
                      (m/add-shape {:slides/id "body" :slides/shape :text
                                    :slides/x 0.8 :slides/y 1.0 :slides/w 8.0 :slides/h 2.0
                                    :slides/font-size 18
                                    :slides/paragraphs
                                    [{:text "Centered heading" :align :center :line-spacing 1.5}
                                     {:text "Bulleted item" :bullet {:type :char :char "•"}}
                                     {:text "Numbered item" :bullet {:type :auto-num :scheme "arabicPeriod"}}
                                     {:text "Explicitly no bullet" :bullet {:type :none}}
                                     {:text "Plain line"}]}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    ;; +1 for the default design master footer shape's own paragraph.
    (is (= 6 (count (re-seq #"<a:p>" slide-xml))))
    (is (re-find #"<a:pPr algn=\"ctr\"><a:lnSpc><a:spcPct val=\"150000\"/></a:lnSpc>" slide-xml))
    (is (re-find #"<a:buChar char=\"•\"/>" slide-xml))
    (is (re-find #"<a:buAutoNum type=\"arabicPeriod\"/>" slide-xml))
    (is (re-find #"<a:buNone/>" slide-xml))
    (testing "a paragraph with none of align/bullet/line-spacing gets no <a:pPr> at all"
      (let [plain-p (second (re-find #"(<a:p>(?:(?!</a:p>).)*Plain line(?:(?!</a:p>).)*</a:p>)" slide-xml))]
        (is (not (re-find #"<a:pPr" plain-p)))))))

(deftest writes-and-round-trips-numbered-bullet-start-at
  (let [deck (-> (m/deck "deck" {:slides/title "Restarted list"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "List"})
                      (m/add-shape {:slides/id "body" :slides/shape :text
                                    :slides/x 0.8 :slides/y 1.0 :slides/w 8.0 :slides/h 2.0
                                    :slides/font-size 18
                                    :slides/paragraphs
                                    [{:text "Fifth item" :bullet {:type :auto-num :scheme "arabicPeriod" :start-at 5}}
                                     {:text "Plain numbered item" :bullet {:type :auto-num :scheme "arabicPeriod"}}]}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "startAt is written only for the paragraph that carries it"
      (is (re-find #"<a:buAutoNum type=\"arabicPeriod\" startAt=\"5\"/>" slide-xml))
      (is (re-find #"<a:buAutoNum type=\"arabicPeriod\"/>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            body (some #(when (= "body" (:slides/id %)) %) (-> reimported :slides/slides first :slides/shapes))]
        (is (= {:type :auto-num :scheme "arabicPeriod" :start-at 5} (:bullet (first (:slides/paragraphs body)))))
        (is (= {:type :auto-num :scheme "arabicPeriod"} (:bullet (second (:slides/paragraphs body)))))))))

(deftest writes-and-round-trips-paragraph-tab-stops
  (let [tab-stops [{:position 1.0} {:position 2.0 :align :right} {:position 3.0 :align :decimal}]
        deck (-> (m/deck "deck" {:slides/title "Tabbed"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "List"})
                      (m/add-shape {:slides/id "body" :slides/shape :text
                                    :slides/x 0.8 :slides/y 1.0 :slides/w 8.0 :slides/h 2.0
                                    :slides/font-size 18
                                    :slides/paragraphs
                                    [{:text "Item\tValue\t1.5" :tab-stops tab-stops}
                                     {:text "Plain line"}]}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing ":left (the schema default) is omitted from algn; the other alignments are written"
      (is (re-find #"<a:tabLst><a:tab pos=\"914400\"/><a:tab pos=\"1828800\" algn=\"r\"/><a:tab pos=\"2743200\" algn=\"dec\"/></a:tabLst>"
                    slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            body (some #(when (= "body" (:slides/id %)) %) (-> reimported :slides/slides first :slides/shapes))]
        (is (= tab-stops (:tab-stops (first (:slides/paragraphs body)))))
        (is (not (contains? (second (:slides/paragraphs body)) :tab-stops)))))))

(deftest text-shape-with-fill-writes-a-styled-non-rect-autoshape
  (let [deck (-> (m/deck "deck" {:slides/title "Callout"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Callout"})
                      (m/add-shape {:slides/id "note" :slides/shape :text
                                    :slides/x 1.0 :slides/y 1.0 :slides/w 3.0 :slides/h 1.0
                                    :slides/text "Rounded callout"
                                    :slides/geometry :roundRect
                                    :slides/fill "9BC15C" :slides/line "445566"}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "the real preset geometry and fill/line are written, not the historical noFill rect box"
      (is (re-find #"<a:prstGeom prst=\"roundRect\">" slide-xml))
      (is (re-find #"<a:solidFill><a:srgbClr val=\"9BC15C\"/></a:solidFill>" slide-xml))
      (is (re-find #"<a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"445566\"/>" slide-xml))))
  (testing "a plain text box (no :slides/fill/:slides/geometry) keeps the historical noFill rect box"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Plain text")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:prstGeom prst=\"rect\">" slide-xml))
      (is (re-find #"<a:noFill/><a:ln><a:noFill/></a:ln>" slide-xml)))))

(deftest geometry-preset-rejects-unsafe-values
  (let [deck (-> (m/deck "deck" {:slides/title "Bad geometry"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape {:slides/id "r" :slides/shape :rect
                                    :slides/x 1.0 :slides/y 1.0 :slides/w 2.0 :slides/h 1.0
                                    :slides/geometry "rect\"/><bad/>"}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (is (re-find #"<a:prstGeom prst=\"rect\">" slide-xml)
        "a geometry value that isn't a bare alphanumeric identifier falls back to \"rect\"")
    (is (not (re-find #"<bad/>" slide-xml)))))

(deftest writes-connector-shape-as-native-cxnsp
  (let [deck (-> (m/deck "deck" {:slides/title "Flow"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Flow"})
                      (m/add-shape {:slides/id "arrow1" :slides/shape :connector
                                    :slides/x 1.0 :slides/y 2.0 :slides/w 3.0 :slides/h 0.0
                                    :slides/line "445566"}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (is (re-find #"<p:cxnSp>" slide-xml))
    (is (re-find #"<a:prstGeom prst=\"straightConnector1\">" slide-xml))
    (is (re-find #"<a:ln><a:solidFill><a:srgbClr val=\"445566\"/>" slide-xml))))

(deftest writes-and-round-trips-connector-connections
  (let [connections {:start {:shape-id 2 :idx 1} :end {:shape-id 3 :idx 3}}
        deck (-> (m/deck "deck" {:slides/title "Flow"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Flow"})
                      (m/add-shape {:slides/id "arrow1" :slides/shape :connector
                                    :slides/x 1.0 :slides/y 2.0 :slides/w 3.0 :slides/h 0.0
                                    :slides/line "445566" :slides/connections connections}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "both ends are written as <p:cNvCxnSpPr>'s own <a:stCxn>/<a:endCxn> children"
      (is (re-find #"<p:cNvCxnSpPr><a:stCxn id=\"2\" idx=\"1\"/><a:endCxn id=\"3\" idx=\"3\"/></p:cNvCxnSpPr>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shape (some #(when (= "arrow1" (:slides/id %)) %) (-> reimported :slides/slides first :slides/shapes))]
        (is (= connections (:slides/connections shape))))))
  (testing "no :slides/connections -- bare <p:cNvCxnSpPr/>, unchanged from before this feature existed"
    (let [deck (-> (m/deck "deck" {:slides/title "Flow"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape {:slides/id "arrow1" :slides/shape :connector
                                      :slides/x 1.0 :slides/y 2.0 :slides/w 3.0 :slides/h 0.0}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<p:cNvCxnSpPr/>" slide-xml)))))

(deftest writes-and-round-trips-shape-hidden-flag
  (let [deck (-> (m/deck "deck" {:slides/title "Hidden shapes"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/text-box "hidden-text" "Backup note" {:slides/hidden true}))
                      (m/add-shape (m/rect "hidden-rect" {:slides/hidden true}))
                      (m/add-shape (m/text-box "visible-text" "Plain")))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "hidden shapes get hidden=\"1\" on their own <p:cNvPr>"
      (is (re-find #"<p:cNvPr id=\"\d+\" name=\"hidden-text\" hidden=\"1\"/>" slide-xml))
      (is (re-find #"<p:cNvPr id=\"\d+\" name=\"hidden-rect\" hidden=\"1\"/>" slide-xml)))
    (testing "a plain shape's own <p:cNvPr> carries no hidden attribute at all"
      (is (re-find #"<p:cNvPr id=\"\d+\" name=\"visible-text\"/>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shapes (-> reimported :slides/slides first :slides/shapes)
            hidden-text (some #(when (= "hidden-text" (:slides/id %)) %) shapes)
            visible-text (some #(when (= "visible-text" (:slides/id %)) %) shapes)]
        (is (true? (:slides/hidden hidden-text)))
        (is (not (contains? visible-text :slides/hidden)))))))

(deftest writes-rotation-and-flip-attributes-on-xfrm
  (let [deck (-> (m/deck "deck" {:slides/title "Rotated"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/rect "r" {:slides/rotation 45 :slides/flip-h true})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (is (re-find #"<a:xfrm rot=\"2700000\" flipH=\"1\">" slide-xml)))
  (testing "a shape with no rotation/flip gets a plain <a:xfrm> with no extra attributes"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/rect "r")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:xfrm><a:off" slide-xml)))))

(deftest writes-run-formatting-attributes
  (let [deck (-> (m/deck "deck" {:slides/title "Formatted"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/text-box "t" "Fancy"
                                               {:slides/bold true :slides/italic true
                                                :slides/underline true :slides/strikethrough true
                                                :slides/baseline 30.0})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (is (re-find #"<a:rPr[^>]*\bb=\"1\"" slide-xml))
    (is (re-find #"<a:rPr[^>]*\bi=\"1\"" slide-xml))
    (is (re-find #"<a:rPr[^>]*\bu=\"sng\"" slide-xml))
    (is (re-find #"<a:rPr[^>]*\bstrike=\"sngStrike\"" slide-xml))
    (is (re-find #"<a:rPr[^>]*\bbaseline=\"30000\"" slide-xml)))
  (testing "plain text has none of these attributes"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Plain")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (not (re-find #"\bi=\"1\"" slide-xml)))
      (is (not (re-find #"\bu=\"sng\"" slide-xml)))
      (is (not (re-find #"\bstrike=\"sngStrike\"" slide-xml)))
      (is (not (re-find #"\bbaseline=" slide-xml))))))

(deftest writes-hyperlink-as-native-hlinkclick-and-relationship
  (let [deck (-> (m/deck "deck" {:slides/title "Linked"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/text-box "link" "Click here" {:slides/hyperlink "https://example.com/"})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")
        rels-xml (entries "ppt/slides/_rels/slide1.xml.rels")]
    (testing "the run's rPr carries an hlinkClick pointing at a relationship id"
      (is (re-find #"<a:hlinkClick r:id=\"(rId\d+)\"/>" slide-xml)))
    (testing "that relationship id resolves, in the slide's own .rels, to an External hyperlink target"
      (let [rel-id (second (re-find #"<a:hlinkClick r:id=\"(rId\d+)\"/>" slide-xml))]
        (is (re-find (re-pattern (str "Id=\"" rel-id "\"[^>]*Type=\"[^\"]*hyperlink\"[^>]*Target=\"https://example.com/\"[^>]*TargetMode=\"External\"")) rels-xml))))
    (testing "a shape with no :slides/hyperlink gets no hlinkClick at all"
      (let [deck2 (-> (m/deck "deck" {:slides/title "Unlinked"})
                      (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Plain")))))
            entries2 (zip-entries (pptx/pptx-bytes deck2))]
        (is (not (re-find #"hlinkClick" (entries2 "ppt/slides/slide1.xml"))))))))

(deftest writes-and-round-trips-internal-slide-jump-hyperlink
  (let [deck (-> (m/deck "deck" {:slides/title "Jump"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/text-box "link" "Next slide" {:slides/hyperlink-slide-part "ppt/slides/slide2.xml"}))))
                 (m/add-slide (-> (m/slide "s2") (m/add-shape (m/text-box "t" "Second slide")))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")
        rels-xml (entries "ppt/slides/_rels/slide1.xml.rels")]
    (testing "the run's rPr carries an hlinkClick, same as an external link"
      (is (re-find #"<a:hlinkClick r:id=\"(rId\d+)\"/>" slide-xml)))
    (testing "that relationship's Target is the bare sibling filename, with NO TargetMode attribute at all (Internal is the schema default)"
      (let [rel-id (second (re-find #"<a:hlinkClick r:id=\"(rId\d+)\"/>" slide-xml))]
        (is (re-find (re-pattern (str "Id=\"" rel-id "\"[^>]*Type=\"[^\"]*hyperlink\"[^>]*Target=\"slide2.xml\"[^>]*/>")) rels-xml))
        (is (not (re-find #"TargetMode" rels-xml)))))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shape (some #(when (= "link" (:slides/id %)) %) (-> reimported :slides/slides first :slides/shapes))]
        (is (= "ppt/slides/slide2.xml" (:slides/hyperlink-slide-part shape)))
        (is (not (contains? shape :slides/hyperlink)))))))

(deftest writes-and-round-trips-hyperlink-navigation-action
  (let [deck (-> (m/deck "deck" {:slides/title "Nav"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/text-box "next" "Next" {:slides/hyperlink-action :next-slide})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")
        rels-xml (entries "ppt/slides/_rels/slide1.xml.rels")]
    (testing "the run's rPr carries a self-contained hlinkClick action, no r:id and no relationship at all"
      (is (re-find #"<a:hlinkClick action=\"ppaction://hlinkshowjump\?jump=nextslide\"/>" slide-xml))
      (is (not (re-find #"hyperlink" rels-xml))))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shape (some #(when (= "next" (:slides/id %)) %) (-> reimported :slides/slides first :slides/shapes))]
        (is (= :next-slide (:slides/hyperlink-action shape)))))))

(deftest writes-line-dash-pattern
  (let [deck (-> (m/deck "deck" {:slides/title "Dashed"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/rect "r" {:slides/line "445566" :slides/line-dash :dash})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (is (re-find #"<a:prstDash val=\"dash\"/>" slide-xml)))
  (testing "no :slides/line-dash -- no prstDash element, solid line"
    (let [deck (-> (m/deck "deck" {:slides/title "Solid"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/rect "r" {:slides/line "445566"})))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (not (re-find #"<a:prstDash" slide-xml))))))

(deftest writes-and-round-trips-custom-geometry
  (let [custom-geom [{:width 1000000.0 :height 1000000.0
                      :commands [{:cmd :moveTo :pts [{:x 0.0 :y 500000.0}]}
                                 {:cmd :lnTo :pts [{:x 500000.0 :y 0.0}]}
                                 {:cmd :arcTo :w-radius 100000.0 :h-radius 100000.0 :start-angle 0.0 :swing-angle 5400000.0}
                                 {:cmd :close}]}]
        deck (-> (m/deck "deck" {:slides/title "Custom shape"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/rect "r" {:slides/geometry :custom :slides/custom-geometry custom-geom
                                                :slides/fill "445566"})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "a real <a:custGeom>/<a:pathLst> is written, not a prstGeom"
      (is (re-find #"<a:custGeom>" slide-xml))
      (is (re-find #"<a:path w=\"1000000\" h=\"1000000\">" slide-xml))
      (is (re-find #"<a:moveTo><a:pt x=\"0\" y=\"500000\"/></a:moveTo>" slide-xml))
      (is (re-find #"<a:arcTo wR=\"100000\" hR=\"100000\" stAng=\"0\" swAng=\"5400000\"/>" slide-xml))
      (is (re-find #"<a:close/>" slide-xml))
      (is (re-find #"<p:spPr>[^<]*<a:xfrm[^>]*>.*?</a:xfrm><a:custGeom>" slide-xml)
          "the custom shape's OWN spPr uses custGeom (the deck's synthetic footer shape, elsewhere in the doc, still uses plain prstGeom)"))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            rect (first (filter #(= :rect (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= :custom (:slides/geometry rect)))
        (is (= custom-geom (:slides/custom-geometry rect))))))
  (testing "no :slides/custom-geometry -- historical <a:prstGeom>, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/rect "r")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:prstGeom prst=\"rect\">" slide-xml))
      (is (not (re-find #"<a:custGeom" slide-xml))))))

(deftest writes-and-round-trips-outer-shadow
  (let [deck (-> (m/deck "deck" {:slides/title "Shadowed"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/rect "r" {:slides/shadow {:blur 4.0 :distance 2.0 :angle 45.0
                                                                :color "112233" :alpha 40.0}})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "a real <a:effectLst><a:outerShdw> is written with the correct converted units"
      (is (re-find #"<a:outerShdw blurRad=\"50800\" dist=\"25400\" dir=\"2700000\" rotWithShape=\"0\">" slide-xml))
      (is (re-find #"<a:srgbClr val=\"112233\"><a:alpha val=\"40000\"/></a:srgbClr>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            rect (first (filter #(= :rect (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= {:blur 4.0 :distance 2.0 :angle 45.0 :color "112233" :alpha 40.0} (:slides/shadow rect))))))
  (testing "no :slides/shadow -- no <a:effectLst> at all, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/rect "r")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (not (re-find #"<a:effectLst" slide-xml))))))

(deftest writes-and-round-trips-glow-and-reflection
  (let [glow {:radius 5.0 :color "00B0F0" :alpha 60.0}
        reflection {:blur 1.0 :distance 0.5 :angle 90.0 :start-alpha 50.0 :end-alpha 0.0}
        deck (-> (m/deck "deck" {:slides/title "Glowing and reflected"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/rect "r" {:slides/glow glow :slides/reflection reflection})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "both effects land in the SAME <a:effectLst>, in schema order (glow before reflection)"
      (is (re-find #"<a:effectLst><a:glow rad=\"63500\"><a:srgbClr val=\"00B0F0\"><a:alpha val=\"60000\"/></a:srgbClr></a:glow><a:reflection blurRad=\"12700\" dist=\"6350\" dir=\"5400000\" stA=\"50000\" endA=\"0\"/></a:effectLst>"
                    slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            rect (first (filter #(= :rect (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= glow (:slides/glow rect)))
        (is (= reflection (:slides/reflection rect))))))
  (testing "shadow + glow together combine into ONE <a:effectLst>, not two"
    (let [deck (-> (m/deck "deck" {:slides/title "Shadow and glow"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape (m/rect "r" {:slides/shadow {:blur 4.0 :distance 2.0 :angle 45.0 :color "112233" :alpha 40.0}
                                                  :slides/glow {:radius 5.0 :color "00B0F0" :alpha 60.0}})))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (= 1 (count (re-seq #"<a:effectLst>" slide-xml))))
      (is (re-find #"<a:effectLst><a:glow[\s\S]*?</a:glow><a:outerShdw[\s\S]*?</a:outerShdw></a:effectLst>" slide-xml)
          "glow precedes outerShdw, per CT_EffectList's own schema order"))))

(deftest writes-and-round-trips-shape-adjustment-values
  (let [deck (-> (m/deck "deck" {:slides/title "Adjusted"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/rect "r" {:slides/geometry :roundRect
                                                :slides/adjustments [{:name "adj" :fmla "val 8333"}]})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "the custom adjustment is written, not the historical empty <a:avLst/>"
      (is (re-find #"<a:avLst><a:gd name=\"adj\" fmla=\"val 8333\"/></a:avLst>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            rect (first (filter #(= :rect (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= [{:name "adj" :fmla "val 8333"}] (:slides/adjustments rect))))))
  (testing "no :slides/adjustments -- historical empty <a:avLst/>, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Default"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/rect "r" {:slides/geometry :roundRect})))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:avLst/>" slide-xml))
      (is (not (re-find #"<a:gd" slide-xml))))))

(deftest writes-placeholder-type-on-full-regen
  (let [deck (-> (m/deck "deck" {:slides/title "Deck"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/text-box "title" "Hello"
                                               {:slides/placeholder {:type "title"}})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "a shape with :slides/placeholder gets a real <p:ph>, and its OWN cNvSpPr drops txBox=\"1\" (a placeholder isn't a free text box -- the deck's synthetic footer shape, elsewhere in the doc, is still a plain txBox, so this must be scoped to the title shape's own nvSpPr, not a doc-wide absence check)"
      (is (re-find #"<p:ph type=\"title\"/>" slide-xml))
      (is (re-find #"<p:cNvSpPr/><p:nvPr><p:ph type=\"title\"/></p:nvPr>" slide-xml))))
  (testing "a shape with idx/sz/orient carries all of them"
    (let [deck (-> (m/deck "deck" {:slides/title "Deck"})
                   (m/add-slide
                    (-> (m/slide "s1")
                        (m/add-shape (m/text-box "body" "Body text"
                                                 {:slides/placeholder {:type "body" :idx "1" :size "half" :orient "horz"}})))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<p:ph type=\"body\" idx=\"1\" sz=\"half\" orient=\"horz\"/>" slide-xml))))
  (testing "no :slides/placeholder -- historical plain textbox, unchanged (the deck's synthetic footer shape, elsewhere in the doc, now legitimately DOES carry <p:ph type=\"ftr\"> -- see writes-real-footer-placeholder -- so this only checks the \"t\" shape's own nvSpPr, not a doc-wide absence)"
    (let [deck (-> (m/deck "deck" {:slides/title "Deck"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Plain")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<p:cNvSpPr txBox=\"1\"/><p:nvPr/>" slide-xml))
      (is (re-find #"<p:cNvPr id=\"10\" name=\"t\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/>" slide-xml)
          "the \"t\" shape's own nvSpPr specifically has no <p:ph>"))))

(deftest writes-real-footer-placeholder
  (let [deck (-> (m/deck "deck" {:slides/title "Deck"})
                 (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Plain")))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "the default design's footer is a real <p:ph type=\"ftr\"> placeholder, not a plain textbox"
      (is (re-find #"<p:cNvPr id=\"11\" name=\"master-footer\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"ftr\"/></p:nvPr>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            footer (some #(when (= "ftr" (get-in % [:slides/placeholder :type])) %)
                         (-> reimported :slides/slides first :slides/shapes))]
        (is (some? footer))))))

(deftest writes-and-round-trips-line-width
  (let [deck (-> (m/deck "deck" {:slides/title "Thick line"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/rect "r" {:slides/line "445566" :slides/line-width 3.0})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "3pt -> 38100 EMU"
      (is (re-find #"<a:ln w=\"38100\">" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            rect (first (filter #(= :rect (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= 3.0 (:slides/line-width rect))))))
  (testing "no :slides/line-width -- historical 1pt (12700 EMU) default, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Default"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/rect "r" {:slides/line "445566"})))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:ln w=\"12700\">" slide-xml))))
  (testing "a connector with no :slides/line-width still omits w= entirely, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Connector"})
                   (m/add-slide (-> (m/slide "s1")
                                    (m/add-shape {:slides/id "arrow" :slides/shape :connector
                                                  :slides/x 1.0 :slides/y 1.0 :slides/w 2.0 :slides/h 0.0}))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:ln><a:solidFill>" slide-xml)))))

(deftest writes-and-round-trips-line-cap-and-join
  (let [deck (-> (m/deck "deck" {:slides/title "Cap and join"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/rect "r" {:slides/line "445566"
                                                :slides/line-cap :round
                                                :slides/line-join {:type :miter :limit 800.0}})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "cap is an <a:ln> attribute, join its one child, miter's limit converted back to thousandths-of-a-percent"
      (is (re-find #"<a:ln w=\"12700\" cap=\"rnd\">" slide-xml))
      (is (re-find #"<a:miter lim=\"800000\"/>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            rect (first (filter #(= :rect (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= :round (:slides/line-cap rect)))
        (is (= {:type :miter :limit 800.0} (:slides/line-join rect))))))
  (testing "no :slides/line-cap/:slides/line-join -- no cap attribute, no join child, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/rect "r")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (not (re-find #"cap=" slide-xml)))
      (is (not (re-find #"<a:round/>|<a:bevel/>|<a:miter" slide-xml))))))

(deftest writes-picture-fill-as-shape-fill
  (let [png-bytes (.getBytes "PNGDATA" "UTF-8")
        b64 (.encodeToString (java.util.Base64/getEncoder) png-bytes)
        deck (-> (m/deck "deck" {:slides/title "Picture-filled"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/rect "card" {:slides/fill-image-data b64 :slides/media-type "image/png"})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")
        rels-xml (entries "ppt/slides/_rels/slide1.xml.rels")]
    (testing "the shape's own fill is a real <a:blipFill>, not a solid color"
      (is (re-find #"<a:blipFill><a:blip r:embed=\"rId\d+\"/>" slide-xml))
      (is (re-find #"</a:prstGeom><a:blipFill>" slide-xml)
          "the FILL immediately after prstGeom is blipFill, not solidFill (the line's own solidFill, further along, is unrelated)"))
    (testing "a media part + image relationship were added, same machinery as an :image shape"
      (is (some #(str/starts-with? % "ppt/media/image") (keys entries)))
      (is (re-find #"Type=\"[^\"]*relationships/image\"" rels-xml))))
  (testing "a :rect with no :slides/fill-image-data still writes the historical solidFill"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/rect "r" {:slides/fill "445566"})))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:solidFill><a:srgbClr val=\"445566\"/></a:solidFill>" slide-xml))
      (is (not (re-find #"<a:blipFill>" slide-xml))))))

(deftest fill-image-reference-round-trips-through-import
  (let [deck (-> (m/deck "deck" {:slides/title "Deck"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/rect "card" {:ooxml/source {:ooxml/part "ppt/slides/slide1.xml" :ooxml/kind :p/sp :ooxml/index 0}})))))]
    (testing "an EXISTING blipFill shape survives update-path patching untouched (patch-solid-fill no-ops without :slides/fill)"
      (let [base-bytes (zip-bytes {"[Content_Types].xml" "<Types/>"
                                   "_rels/.rels" "<Relationships/>"
                                   "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                                   "ppt/slides/slide1.xml"
                                   (str "<p:sld><p:cSld><p:spTree>"
                                        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"card\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
                                        "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm>"
                                        "<a:blipFill><a:blip r:embed=\"rId5\"/></a:blipFill></p:spPr></p:sp>"
                                        "</p:spTree></p:cSld></p:sld>")})
            reexported (pptx/update-pptx-bytes base-bytes deck)
            slide-xml (get (zip-entries reexported) "ppt/slides/slide1.xml")]
        (is (re-find #"<a:blipFill><a:blip r:embed=\"rId5\"/></a:blipFill>" slide-xml))))))

(deftest writes-speaker-notes-as-native-notes-slide-part
  (let [deck (-> (m/deck "deck" {:slides/title "With notes"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Intro" :slides/notes "Remember to mention Q4 numbers"})
                      (m/add-shape (m/text-box "title" "Intro")))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "a notesSlide part is written, wired from the slide's own .rels"
      (is (contains? entries "ppt/notesSlides/notesSlide1.xml"))
      (is (re-find #"Remember to mention Q4 numbers" (entries "ppt/notesSlides/notesSlide1.xml")))
      (is (re-find #"Type=\"[^\"]*notesSlide\"" (entries "ppt/slides/_rels/slide1.xml.rels"))))
    (testing "the notesMaster + its wiring from presentation.xml.rels are included"
      (is (contains? entries "ppt/notesMasters/notesMaster1.xml"))
      (is (re-find #"Type=\"[^\"]*notesMaster\"" (entries "ppt/_rels/presentation.xml.rels"))))
    (testing "[Content_Types].xml declares both parts"
      (is (re-find #"PartName=\"/ppt/notesMasters/notesMaster1.xml\"" (entries "[Content_Types].xml")))
      (is (re-find #"PartName=\"/ppt/notesSlides/notesSlide1.xml\"" (entries "[Content_Types].xml")))))
  (testing "a deck with no notes on any slide writes no notesMaster/notesSlides at all"
    (let [deck (-> (m/deck "deck" {:slides/title "No notes"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Plain")))))
          entries (zip-entries (pptx/pptx-bytes deck))]
      (is (not (contains? entries "ppt/notesMasters/notesMaster1.xml")))
      (is (not (re-find #"notesSlide" (entries "ppt/slides/_rels/slide1.xml.rels")))))))

(deftest writes-and-round-trips-handout-master
  (let [deck (-> (m/deck "deck" {:slides/title "With handout master" :slides/handout-master? true})
                 (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Slide")))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "the handoutMaster part + its wiring from presentation.xml.rels are included"
      (is (contains? entries "ppt/handoutMasters/handoutMaster1.xml"))
      (is (contains? entries "ppt/handoutMasters/_rels/handoutMaster1.xml.rels"))
      (is (re-find #"Type=\"[^\"]*handoutMaster\"" (entries "ppt/_rels/presentation.xml.rels"))))
    (testing "[Content_Types].xml declares it"
      (is (re-find #"PartName=\"/ppt/handoutMasters/handoutMaster1.xml\"" (entries "[Content_Types].xml"))))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})]
        (is (true? (:slides/handout-master? reimported))))))
  (testing "no :slides/handout-master? -- no handoutMaster part at all, the common case"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"}) (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Hi")))))
          entries (zip-entries (pptx/pptx-bytes deck))]
      (is (not (contains? entries "ppt/handoutMasters/handoutMaster1.xml")))
      (is (not (re-find #"handoutMaster" (entries "ppt/_rels/presentation.xml.rels")))))))

(deftest writes-and-round-trips-custom-xml-parts
  (let [custom-xml-parts [{:content "<root xmlns=\"http://example.com/schema\"><field>value</field></root>"
                           :props-content
                           (str "<ds:datastoreItem ds:itemID=\"{11111111-1111-1111-1111-111111111111}\" "
                                "xmlns:ds=\"http://schemas.openxmlformats.org/officeDocument/2006/customXml\">"
                                "<ds:schemaRefs/></ds:datastoreItem>")}]
        deck (-> (m/deck "deck" {:slides/title "With custom XML" :slides/custom-xml-parts custom-xml-parts})
                 (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Slide")))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "the item + itemProps + item's own .rels are all written verbatim"
      (is (= "<root xmlns=\"http://example.com/schema\"><field>value</field></root>"
             (entries "customXml/item1.xml")))
      (is (= (:props-content (first custom-xml-parts)) (entries "customXml/itemProps1.xml")))
      (is (re-find #"Type=\"[^\"]*customXmlProps\"" (entries "customXml/_rels/item1.xml.rels"))))
    (testing "presentation.xml.rels wires a relationship to the item, up one level from ppt/"
      (is (re-find #"Type=\"[^\"]*customXml\"[^/]*Target=\"\.\./customXml/item1\.xml\"" (entries "ppt/_rels/presentation.xml.rels"))))
    (testing "[Content_Types].xml declares itemProps' own content type (item1.xml uses the default xml mapping)"
      (is (re-find #"PartName=\"/customXml/itemProps1\.xml\"" (entries "[Content_Types].xml"))))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})]
        (is (= custom-xml-parts (:slides/custom-xml-parts reimported))))))
  (testing "no :slides/custom-xml-parts -- no customXml/ entries at all, the common case"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"}) (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Hi")))))
          entries (zip-entries (pptx/pptx-bytes deck))]
      (is (not (some #(str/starts-with? % "customXml/") (keys entries))))
      (is (not (re-find #"customXml" (entries "ppt/_rels/presentation.xml.rels")))))))

(deftest embedded-fonts-round-trip-through-import
  (let [presentation-xml
        (str "<p:presentation><p:embeddedFontLst>"
             "<p:embeddedFont><p:font typeface=\"Calibri\"/>"
             "<p:regular r:id=\"rId5\"/></p:embeddedFont>"
             "</p:embeddedFontLst></p:presentation>")
        entries {"ppt/presentation.xml" presentation-xml
                 "ppt/_rels/presentation.xml.rels"
                 (str "<Relationships>"
                      "<Relationship Id=\"rId5\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/font\" Target=\"fonts/font1.fntdata\"/>"
                      "</Relationships>")
                 "ppt/slides/slide1.xml" "<p:sld><p:cSld><p:spTree></p:spTree></p:cSld></p:sld>"}
        reimported (office/deck-from-office-bytes (zip-bytes entries) {})]
    (testing "typeface + resolved rel-id/target-path survive import, reference-metadata only (no font BYTES to re-embed on export)"
      (is (= [{:typeface "Calibri" :regular {:rel-id "rId5" :target-path "ppt/fonts/font1.fntdata"}}]
             (:slides/embedded-fonts reimported)))))
  (testing "no <p:embeddedFontLst> at all -- no :slides/embedded-fonts key, the overwhelming common case"
    (let [entries {"ppt/slides/slide1.xml" "<p:sld><p:cSld><p:spTree></p:spTree></p:cSld></p:sld>"}
          reimported (office/deck-from-office-bytes (zip-bytes entries) {})]
      (is (not (contains? reimported :slides/embedded-fonts))))))

(deftest writes-review-comments-as-native-comments-part
  (let [comments [{:author "Jun Kawasaki" :text "Looks good" :date "2026-07-02T00:00:00.000" :x 1.0 :y 0.5}
                  {:author "Jun Kawasaki" :text "Second comment, no position"}]
        deck (-> (m/deck "deck" {:slides/title "Reviewed"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Intro" :slides/comments comments})
                      (m/add-shape (m/text-box "title" "Intro")))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "a comments part is written, wired from the slide's own .rels"
      (is (contains? entries "ppt/comments/comment1.xml"))
      (is (re-find #"<p:cm authorId=\"0\" dt=\"2026-07-02T00:00:00.000\" idx=\"1\"><p:pos x=\"914400\" y=\"457200\"/><p:text>Looks good</p:text></p:cm>"
                    (entries "ppt/comments/comment1.xml")))
      (is (re-find #"<p:cm authorId=\"0\" idx=\"2\"><p:text>Second comment, no position</p:text></p:cm>"
                    (entries "ppt/comments/comment1.xml")))
      (is (re-find #"Type=\"[^\"]*comments\"" (entries "ppt/slides/_rels/slide1.xml.rels"))))
    (testing "commentAuthors.xml + its wiring from presentation.xml.rels are included"
      (is (contains? entries "ppt/commentAuthors.xml"))
      (is (re-find #"<p:cmAuthor id=\"0\" name=\"Jun Kawasaki\" initials=\"JK\"" (entries "ppt/commentAuthors.xml")))
      (is (re-find #"Type=\"[^\"]*commentAuthors\"" (entries "ppt/_rels/presentation.xml.rels"))))
    (testing "[Content_Types].xml declares both parts"
      (is (re-find #"PartName=\"/ppt/commentAuthors.xml\"" (entries "[Content_Types].xml")))
      (is (re-find #"PartName=\"/ppt/comments/comment1.xml\"" (entries "[Content_Types].xml")))))
  (testing "a deck with no comments on any slide writes no comment parts at all"
    (let [deck (-> (m/deck "deck" {:slides/title "No comments"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Plain")))))
          entries (zip-entries (pptx/pptx-bytes deck))]
      (is (not (contains? entries "ppt/commentAuthors.xml")))
      (is (not (re-find #"comments" (entries "ppt/slides/_rels/slide1.xml.rels")))))))

(deftest review-comments-round-trip-through-import
  (let [comments [{:author "Jun Kawasaki" :text "Looks good" :date "2026-07-02T00:00:00.000" :x 1.0 :y 0.5}]
        deck (-> (m/deck "deck" {:slides/title "Round trip"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Slide" :slides/comments comments})
                      (m/add-shape (m/text-box "title" "Slide")))))
        reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})]
    (is (= comments (-> reimported :slides/slides first :slides/comments)))))

(deftest multiple-authors-across-slides-get-stable-shared-ids
  (let [deck (-> (m/deck "deck" {:slides/title "Two authors"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/comments [{:author "Alice" :text "A comment"}]})
                      (m/add-shape (m/text-box "t" "One"))))
                 (m/add-slide
                  (-> (m/slide "s2" {:slides/comments [{:author "Bob" :text "B comment"}
                                                       {:author "Alice" :text "Another Alice comment"}]})
                      (m/add-shape (m/text-box "t" "Two")))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "commentAuthors.xml lists each DISTINCT author once, in first-appearance order across the whole deck"
      (is (re-find #"<p:cmAuthor id=\"0\" name=\"Alice\"" (entries "ppt/commentAuthors.xml")))
      (is (re-find #"<p:cmAuthor id=\"1\" name=\"Bob\"" (entries "ppt/commentAuthors.xml")))
      (is (= 2 (count (re-seq #"<p:cmAuthor " (entries "ppt/commentAuthors.xml"))))))
    (testing "each slide's own comments reference the SAME author id, consistent across parts"
      (is (re-find #"authorId=\"0\"" (entries "ppt/comments/comment1.xml")))
      (is (re-find #"authorId=\"1\"" (entries "ppt/comments/comment2.xml")))
      (is (re-find #"authorId=\"0\"" (entries "ppt/comments/comment2.xml"))))))

(deftest pic-media-references-round-trip-through-import
  (let [entries
        {"ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\"/></p:presentation>"
         "ppt/slides/slide1.xml"
         (str "<p:sld><p:cSld><p:spTree>"
              "<p:pic><p:nvPicPr><p:cNvPr id=\"3\" name=\"Video\"/><p:cNvPicPr/>"
              "<p:nvPr><a:videoFile r:link=\"rId6\"/></p:nvPr></p:nvPicPr>"
              "<p:blipFill><a:blip r:embed=\"rId5\"/></p:blipFill>"
              "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"1828800\"/></a:xfrm></p:spPr>"
              "</p:pic>"
              "</p:spTree></p:cSld></p:sld>")
         "ppt/slides/_rels/slide1.xml.rels"
         (str "<Relationships>"
              "<Relationship Id=\"rId5\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image1.png\"/>"
              "<Relationship Id=\"rId6\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/video\" Target=\"../media/media1.mp4\"/>"
              "</Relationships>")}
        reimported (office/deck-from-office-bytes (zip-bytes entries) {})
        pic (first (-> reimported :slides/slides first :slides/shapes))]
    (testing "both the poster-frame image reference AND the video reference survive import"
      (is (= "rId5" (:slides/image-rel-id pic)))
      (is (= "ppt/media/image1.png" (:slides/image-part pic)))
      (is (= "rId6" (:slides/video-rel-id pic)))
      (is (= "ppt/media/media1.mp4" (:slides/video-part pic))))))

(deftest speaker-notes-round-trip-through-import
  (let [deck (-> (m/deck "deck" {:slides/title "Round trip"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Slide" :slides/notes "Speaker note text\nSecond line"})
                      (m/add-shape (m/text-box "title" "Slide")))))
        bytes (pptx/pptx-bytes deck)
        reimported (office/deck-from-office-bytes bytes {})]
    (is (= "Speaker note text\nSecond line"
           (-> reimported :slides/slides first :slides/notes)))))

(deftest formatting-hyperlink-and-line-dash-round-trip-through-import
  (let [deck (-> (m/deck "deck" {:slides/title "Round trip"})
                 (m/add-slide
                  (-> (m/slide "s1")
                      (m/add-shape (m/text-box "t" "Fancy linked text"
                                               {:slides/bold true :slides/italic true
                                                :slides/underline true :slides/strikethrough true
                                                :slides/baseline 30.0
                                                :slides/hyperlink "https://example.com/"}))
                      (m/add-shape (m/rect "r" {:slides/line "445566" :slides/line-dash :dash})))))
        bytes (pptx/pptx-bytes deck)
        reimported (office/deck-from-office-bytes bytes {})
        shapes (-> reimported :slides/slides first :slides/shapes)
        text-shape (first (filter #(= :text (:slides/shape %)) shapes))
        rect-shape (first (filter #(= :rect (:slides/shape %)) shapes))]
    (is (true? (:slides/bold text-shape)))
    (is (true? (:slides/italic text-shape)))
    (is (true? (:slides/underline text-shape)))
    (is (true? (:slides/strikethrough text-shape)))
    (is (= 30.0 (:slides/baseline text-shape)))
    (is (= "https://example.com/" (:slides/hyperlink text-shape)))
    (is (= :dash (:slides/line-dash rect-shape)))))

(deftest writes-multiple-slide-masters-and-layouts-for-decks-with-master-refs
  (let [deck (m/deck "deck" {:slides/title "Sectioned"
                             :slides/masters [{:slides/id "dark" :slides/background "111111"}
                                              {:slides/id "light" :slides/background "EEEEEE"}]})
        deck (-> deck
                (m/add-slide (-> (m/slide "s1" {:slides/master-ref "dark"}) (m/add-shape (m/text-box "t" "Dark section"))))
                (m/add-slide (-> (m/slide "s2" {:slides/master-ref "light"}) (m/add-shape (m/text-box "t" "Light section"))))
                (m/add-slide (-> (m/slide "s3") (m/add-shape (m/text-box "t" "Default master")))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "one slideMaster/slideLayout PART PER DISTINCT master used, plus the implicit default"
      (is (contains? entries "ppt/slideMasters/slideMaster1.xml"))
      (is (contains? entries "ppt/slideMasters/slideMaster2.xml"))
      (is (contains? entries "ppt/slideMasters/slideMaster3.xml"))
      (is (contains? entries "ppt/slideLayouts/slideLayout3.xml")))
    (testing "each master's own background is written, not always the deck's single default"
      (is (re-find #"111111" (entries "ppt/slideMasters/slideMaster2.xml")))
      (is (re-find #"EEEEEE" (entries "ppt/slideMasters/slideMaster3.xml"))))
    (testing "each slide's .rels references the layout matching its OWN master, not always layout1"
      (is (re-find #"slideLayout2\.xml" (entries "ppt/slides/_rels/slide1.xml.rels")))
      (is (re-find #"slideLayout3\.xml" (entries "ppt/slides/_rels/slide2.xml.rels")))
      (is (re-find #"slideLayout1\.xml" (entries "ppt/slides/_rels/slide3.xml.rels"))))
    (testing "presentation.xml lists all three masters"
      (is (= 3 (count (re-seq #"<p:sldMasterId " (entries "ppt/presentation.xml")))))
      (is (re-find #"<p:sldMasterId[^>]*r:id=\"rId3\"" (entries "ppt/presentation.xml"))))
    (testing "presentation.xml.rels wires all three masters before the slides, so slide rIds continue past them"
      (is (re-find #"Id=\"rId4\"[^>]*slides/slide1\.xml" (entries "ppt/_rels/presentation.xml.rels"))))
    (testing "a deck with NO :slides/master-ref anywhere still gets exactly one master (unchanged behavior)"
      (let [plain-deck (m/deck "deck" {:slides/title "Plain"})
            plain-deck (m/add-slide plain-deck (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Hi"))))
            plain-entries (zip-entries (pptx/pptx-bytes plain-deck))]
        (is (contains? plain-entries "ppt/slideMasters/slideMaster1.xml"))
        (is (not (contains? plain-entries "ppt/slideMasters/slideMaster2.xml")))))))

(deftest round-trips-multi-stop-gradient-master-background-through-import
  (let [gradient-bg {:stops [[0 "112233"] [50 "334455"] [100 "556677"]] :angle 45}
        deck (m/deck "deck" {:slides/title "Gradient masters"
                             :slides/masters [{:slides/id "gradient" :slides/background gradient-bg}
                                              {:slides/id "plain" :slides/background "EEEEEE"}]})
        deck (-> deck
                (m/add-slide (-> (m/slide "s1" {:slides/master-ref "gradient"}) (m/add-shape (m/text-box "t" "Gradient section"))))
                (m/add-slide (-> (m/slide "s2" {:slides/master-ref "plain"}) (m/add-shape (m/text-box "t" "Plain section")))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "a real multi-stop <a:gradFill> is written for the gradient master's own background"
      (is (re-find #"<a:gradFill rotWithShape=\"1\"><a:gsLst><a:gs pos=\"0\">.*112233.*<a:gs pos=\"50000\">.*334455.*<a:gs pos=\"100000\">.*556677"
                    (entries "ppt/slideMasters/slideMaster2.xml"))))
    (testing "round-trips through import -- previously always collapsed to a single flat first-stop color"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            gradient-master (some #(when (map? (:slides/background %)) %) (:slides/masters reimported))]
        (is (some? gradient-master))
        (is (= 3 (count (:stops (:slides/background gradient-master)))))
        (is (= 45.0 (:angle (:slides/background gradient-master))))))))

(deftest writes-multiple-layouts-within-a-single-master
  (let [deck (m/deck "deck" {:slides/title "Layout diversity"
                             :slides/layouts [{:slides/id "title-slide" :slides/layout-type "title"
                                               :slides/placeholders [{:type "ctrTitle" :x 0.5 :y 2.0 :w 9.0 :h 1.5}]}
                                              {:slides/id "two-content" :slides/layout-type "twoObj"}]})
        deck (-> deck
                (m/add-slide (-> (m/slide "s1" {:slides/layout-ref "title-slide"}) (m/add-shape (m/text-box "t" "Title"))))
                (m/add-slide (-> (m/slide "s2" {:slides/layout-ref "two-content"}) (m/add-shape (m/text-box "t" "Content"))))
                (m/add-slide (-> (m/slide "s3") (m/add-shape (m/text-box "t" "Default")))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "one layout PART PER DISTINCT layout used, all belonging to the SAME single master"
      (is (contains? entries "ppt/slideLayouts/slideLayout1.xml"))
      (is (contains? entries "ppt/slideLayouts/slideLayout2.xml"))
      (is (contains? entries "ppt/slideLayouts/slideLayout3.xml"))
      (is (not (contains? entries "ppt/slideMasters/slideMaster2.xml"))
          "still exactly one master -- these are layout VARIANTS, not different masters"))
    (testing "each layout carries its own real type= attribute, not always \"blank\""
      (is (re-find #"<p:sldLayout[^>]*\btype=\"title\"" (entries "ppt/slideLayouts/slideLayout2.xml")))
      (is (re-find #"<p:sldLayout[^>]*\btype=\"twoObj\"" (entries "ppt/slideLayouts/slideLayout3.xml")))
      (is (re-find #"<p:sldLayout[^>]*\btype=\"blank\"" (entries "ppt/slideLayouts/slideLayout1.xml"))))
    (testing "a layout's own placeholder template is written as a real positioned <p:ph>"
      (is (re-find #"<p:ph type=\"ctrTitle\"/>" (entries "ppt/slideLayouts/slideLayout2.xml"))))
    (testing "the master's own <p:sldLayoutIdLst> lists ALL THREE layouts, not just one"
      (is (= 3 (count (re-seq #"<p:sldLayoutId " (entries "ppt/slideMasters/slideMaster1.xml"))))))
    (testing "each slide's .rels references the correct layout, not always layout1"
      (is (re-find #"slideLayout2\.xml" (entries "ppt/slides/_rels/slide1.xml.rels")))
      (is (re-find #"slideLayout3\.xml" (entries "ppt/slides/_rels/slide2.xml.rels")))
      (is (re-find #"slideLayout1\.xml" (entries "ppt/slides/_rels/slide3.xml.rels")))))
  (testing "no :slides/layout-ref anywhere -- exactly one (blank) layout, unchanged behavior"
    (let [plain-deck (m/deck "deck" {:slides/title "Plain"})
          plain-deck (m/add-slide plain-deck (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Hi"))))
          plain-entries (zip-entries (pptx/pptx-bytes plain-deck))]
      (is (contains? plain-entries "ppt/slideLayouts/slideLayout1.xml"))
      (is (not (contains? plain-entries "ppt/slideLayouts/slideLayout2.xml"))))))

(deftest writes-multiple-masters-EACH-with-multiple-layouts
  (let [deck (m/deck "deck" {:slides/title "Full combo"
                             :slides/masters [{:slides/id "dark" :slides/background "111111"}]
                             :slides/layouts [{:slides/id "dark-title" :slides/layout-type "title"}
                                              {:slides/id "light-title" :slides/layout-type "title"}]})
        deck (-> deck
                (m/add-slide (-> (m/slide "s1" {:slides/master-ref "dark" :slides/layout-ref "dark-title"})
                                 (m/add-shape (m/text-box "t" "Dark title"))))
                (m/add-slide (-> (m/slide "s2" {:slides/layout-ref "light-title"})
                                 (m/add-shape (m/text-box "t" "Light title"))))
                (m/add-slide (-> (m/slide "s3" {:slides/master-ref "dark"})
                                 (m/add-shape (m/text-box "t" "Dark blank")))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "two masters (default + dark), each owning its own layout(s) -- 3 layouts total: default's blank+light-title, dark's dark-title+blank"
      (is (contains? entries "ppt/slideMasters/slideMaster1.xml"))
      (is (contains? entries "ppt/slideMasters/slideMaster2.xml"))
      (is (contains? entries "ppt/slideLayouts/slideLayout4.xml"))
      (is (not (contains? entries "ppt/slideLayouts/slideLayout5.xml"))))
    (testing "master 1 (default)'s own sldLayoutIdLst lists its 2 layouts (blank default + light-title); master 2 (dark)'s lists its 2 (dark-title + blank default)"
      (is (= 2 (count (re-seq #"<p:sldLayoutId " (entries "ppt/slideMasters/slideMaster1.xml")))))
      (is (= 2 (count (re-seq #"<p:sldLayoutId " (entries "ppt/slideMasters/slideMaster2.xml"))))))
    (testing "each slide's .rels points at the layout belonging to the RIGHT master, not just the right layout-ref in isolation"
      ;; layout-entries are computed master-major: master 1 (default) contributes
      ;; its blank (global idx 1) + light-title (idx 2) first, THEN master 2
      ;; (dark) contributes its own blank (idx 3) + dark-title (idx 4).
      (is (re-find #"slideLayout4\.xml" (entries "ppt/slides/_rels/slide1.xml.rels"))
          "slide1: dark master's dark-title layout")
      (is (re-find #"slideLayout2\.xml" (entries "ppt/slides/_rels/slide2.xml.rels"))
          "slide2: default master's light-title layout")
      (is (re-find #"slideLayout3\.xml" (entries "ppt/slides/_rels/slide3.xml.rels"))
          "slide3: dark master's own implicit blank layout (a SEPARATE part from the default master's blank)"))))

(deftest multi-master-deck-round-trips-through-import
  (let [deck (m/deck "deck" {:slides/title "Sectioned"
                             :slides/masters [{:slides/id "dark" :slides/background "111111"}
                                              {:slides/id "light" :slides/background "EEEEEE"}]})
        deck (-> deck
                (m/add-slide (-> (m/slide "s1" {:slides/master-ref "dark"}) (m/add-shape (m/text-box "t" "Dark section"))))
                (m/add-slide (-> (m/slide "s2" {:slides/master-ref "light"}) (m/add-shape (m/text-box "t" "Light section")))))
        bytes (pptx/pptx-bytes deck)
        reimported (office/deck-from-office-bytes bytes {})]
    (testing "two distinct masters survive the round trip"
      (is (= 2 (count (:slides/masters reimported)))))
    (testing "each slide keeps a master-ref, and the two slides refer to DIFFERENT masters"
      (let [refs (map :slides/master-ref (:slides/slides reimported))]
        (is (every? some? refs))
        (is (= 2 (count (distinct refs))))))))

(deftest writes-gradient-background-when-configured
  (let [deck (m/deck "deck" {:slides/title "Gradient bg"
                             :slides/master {:slides/background
                                             {:stops [[0 "112233"] [100 "AABBCC"]] :angle 45}}})
        deck (m/add-slide deck (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Hi"))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "the slide and master both get a real <a:gradFill>, not a flattened solid color"
      (is (re-find #"<a:gradFill rotWithShape=\"1\"><a:gsLst>" (entries "ppt/slides/slide1.xml")))
      (is (re-find #"<a:gs pos=\"0\"><a:srgbClr val=\"112233\"/></a:gs>" (entries "ppt/slides/slide1.xml")))
      (is (re-find #"<a:gs pos=\"100000\"><a:srgbClr val=\"AABBCC\"/></a:gs>" (entries "ppt/slides/slide1.xml")))
      (is (re-find #"<a:lin ang=\"2700000\" scaled=\"1\"/>" (entries "ppt/slides/slide1.xml")))
      (is (re-find #"<a:gradFill" (entries "ppt/slideMasters/slideMaster1.xml")))))
  (testing "a plain hex :slides/background still writes the historical solidFill"
    (let [deck (m/deck "deck" {:slides/title "Solid bg" :slides/master {:slides/background "336699"}})
          deck (m/add-slide deck (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Hi"))))
          entries (zip-entries (pptx/pptx-bytes deck))]
      (is (re-find #"<a:solidFill><a:srgbClr val=\"336699\"/></a:solidFill>" (entries "ppt/slides/slide1.xml"))))))

(deftest writes-and-round-trips-per-slide-background-override
  (let [deck (-> (m/deck "deck" {:slides/title "Divider" :slides/master {:slides/background "336699"}})
                 (m/add-slide (-> (m/slide "s1" {:slides/slide-background "9B1C2E"})
                                  (m/add-shape (m/text-box "t" "Section"))))
                 (m/add-slide (-> (m/slide "s2") (m/add-shape (m/text-box "t" "Plain")))))
        entries (zip-entries (pptx/pptx-bytes deck))]
    (testing "the overriding slide's own <p:bg> uses its own color, not the master's"
      (is (re-find #"<a:solidFill><a:srgbClr val=\"9B1C2E\"/></a:solidFill>" (entries "ppt/slides/slide1.xml"))))
    (testing "a slide with no override still derives its <p:bg> from the master, unchanged"
      (is (re-find #"<a:solidFill><a:srgbClr val=\"336699\"/></a:solidFill>" (entries "ppt/slides/slide2.xml"))))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            [s1 s2] (:slides/slides reimported)]
        (is (= "9B1C2E" (:slides/slide-background s1)))
        ;; this writer emits a literal <p:bg> on EVERY slide (matching the
        ;; resolved master background), not just slides with a genuine
        ;; override, so a re-imported plain slide legitimately shows its
        ;; own :slides/slide-background too -- equal to the master's own,
        ;; not distinguishable from a real override by XML shape alone.
        (is (= "336699" (:slides/slide-background s2)))))))

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

(deftest theme-fonts-are-escaped
  (let [deck (-> (m/deck "deck" {:slides/title "Escaped theme"
                                 :slides/theme {:slides/fonts {:office-style.font/majorFont "Aptos <Display>"
                                                             :office-style.font/minorFont "Body & Text"}}})
                 (m/add-slide (m/slide "s1" {:slides/title "Only"})))
        entries (zip-entries (pptx/pptx-bytes deck))
        theme (entries "ppt/theme/theme1.xml")]
    (is (re-find #"Aptos &lt;Display&gt;" theme))
    (is (re-find #"Body &amp; Text" theme))))

(deftest invalid-shape-geometry-and-font-size-fall-back
  (let [deck (-> (m/deck "deck" {:slides/title "Invalid shape"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Only"})
                      (m/add-shape (m/text-box "bad-text" "Bad"
                                               {:slides/x "bad"
                                                :slides/y nil
                                                :slides/w -1
                                                :slides/h "bad"
                                                :slides/font-size "large"})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide (entries "ppt/slides/slide1.xml")]
    (is (re-find #"off x=\"0\" y=\"0\"" slide))
    (is (re-find #"ext cx=\"914400\" cy=\"914400\"" slide))
    (is (re-find #"sz=\"2400\"" slide))))

(deftest non-finite-numeric-values-fall-back
  (let [deck (-> (m/deck "deck" {:slides/title "Non finite"
                                 :slides/width Double/POSITIVE_INFINITY
                                 :slides/height Double/NaN})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Only"})
                      (m/add-shape (m/text-box "bad-text" "Bad"
                                               {:slides/x Double/NaN
                                                :slides/y Double/POSITIVE_INFINITY
                                                :slides/w Double/POSITIVE_INFINITY
                                                :slides/h Double/NaN
                                                :slides/font-size Double/POSITIVE_INFINITY})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        presentation (entries "ppt/presentation.xml")
        slide (entries "ppt/slides/slide1.xml")]
    (is (re-find #"p:sldSz cx=\"9144000\" cy=\"5143500\"" presentation))
    (is (re-find #"off x=\"0\" y=\"0\"" slide))
    (is (re-find #"ext cx=\"914400\" cy=\"914400\"" slide))
    (is (re-find #"sz=\"2400\"" slide))))

(deftest shape-xml-escapes-ids-and-unknown-shapes
  (let [deck (-> (m/deck "deck" {:slides/title "Escaped shapes"})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Only"})
                      (m/add-shape {:slides/id "bad \"id\" & <tag>"
                                    :slides/shape :text
                                    :slides/text "Tom & Jerry <Q>"})
                      (m/add-shape {:slides/id "unknown \"shape\""
                                    :slides/title "Fallback <title>"
                                    :slides/shape :unknown}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide (entries "ppt/slides/slide1.xml")]
    (is (re-find #"bad &quot;id&quot; &amp; &lt;tag&gt;" slide))
    (is (re-find #"Tom &amp; Jerry &lt;Q&gt;" slide))
    (is (re-find #"unknown &quot;shape&quot;" slide))
    (is (re-find #"Fallback &lt;title&gt;" slide))))

(deftest invalid-deck-size-falls-back-to-defaults
  (let [deck (-> (m/deck "deck" {:slides/title "Invalid size"
                                 :slides/width "wide"
                                 :slides/height -1})
                 (m/add-slide (m/slide "s1" {:slides/title "Only"})))
        entries (zip-entries (pptx/pptx-bytes deck))
        presentation (entries "ppt/presentation.xml")]
    (is (re-find #"p:sldSz cx=\"9144000\" cy=\"5143500\"" presentation))))

(deftest components-and-master-design-render-to-editable-shapes
  (let [deck (-> (m/deck "deck" {:slides/title "Design deck"
                                 :slides/master {:slides/background "FAFAFA"
                                                 :slides/footer {:slides/enabled true
                                                                 :slides/text "Footer text"}}
                                 :slides/components {:hero-title {:slides/shape :text
                                                                  :slides/text-style :title
                                                                  :slides/x 1 :slides/y 1
                                                                  :slides/w 8 :slides/h 1}}
                                 :slides/text-styles {:title {:slides/font-size 44
                                                             :slides/color "123456"
                                                             :slides/bold true}}})
                 (m/add-slide
                  (-> (m/slide "s1" {:slides/title "Only"})
                      (m/add-shape {:slides/id "hero"
                                    :slides/component :hero-title
                                    :slides/text "Component title"}))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide (entries "ppt/slides/slide1.xml")
        master (entries "ppt/slideMasters/slideMaster1.xml")]
    (is (re-find #"Component title" slide))
    (is (re-find #"sz=\"4400\"" slide))
    (is (re-find #"b=\"1\"" slide))
    (is (re-find #"123456" slide))
    (is (re-find #"Footer text" slide))
    (is (re-find #"FAFAFA" master))))

(deftest writes-empty-deck-as-placeholder-slide
  (let [deck (m/deck "deck" {:slides/title "Empty deck"})
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-count (count (filter #(re-find #"^ppt/slides/slide\d+\.xml$" %)
                                  (keys entries)))]
    (is (= 1 slide-count))
    (is (re-find #"Empty deck" (entries "ppt/slides/slide1.xml")))))

(deftest malformed-slide-data-falls-back-to-placeholder-content
  (let [bad-slides {:slides/id "bad"
                    :slides/title "Bad deck"
                    :slides/slides "not slides"}
        bad-slide-items {:slides/id "bad-slide-items"
                         :slides/title "Bad slide items deck"
                         :slides/slides ["not a slide"
                                         {:slides/id "s2"
                                          :slides/title "Good slide"
                                          :slides/shapes []}]}
        bad-shapes {:slides/id "bad-shapes"
                    :slides/title "Bad shapes deck"
                    :slides/slides [{:slides/id "s1"
                                     :slides/title "Bad shapes"
                                     :slides/shapes "not shapes"}]}
        bad-shape-items {:slides/id "bad-shape-items"
                         :slides/title "Bad shape items deck"
                         :slides/slides [{:slides/id "s1"
                                          :slides/title "Bad shape items"
                                          :slides/shapes ["not a shape"]}]}
        entries-a (zip-entries (pptx/pptx-bytes bad-slides))
        entries-b (zip-entries (pptx/pptx-bytes bad-slide-items))
        entries-c (zip-entries (pptx/pptx-bytes bad-shapes))
        entries-d (zip-entries (pptx/pptx-bytes bad-shape-items))]
    (is (re-find #"Bad deck" (entries-a "ppt/slides/slide1.xml")))
    (is (re-find #"Good slide" (entries-b "ppt/slides/slide1.xml")))
    (is (not (contains? entries-b "ppt/slides/slide2.xml")))
    (is (re-find #"Bad shapes" (entries-c "ppt/slides/slide1.xml")))
    (is (re-find #"Bad shape items" (entries-d "ppt/slides/slide1.xml")))))

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

(deftest update-pptx-patches-imported-ooxml-parts
  (let [base-entries {"[Content_Types].xml" "<Types><Override PartName=\"/ppt/slides/slide1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/></Types>"
                      "_rels/.rels" "<Relationships><Relationship Id=\"rId1\" Type=\"officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>"
                      "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                      "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                                    "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                                                    "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                                    "<p:txBody><a:p><a:r><a:rPr sz=\"2400\"><a:solidFill><a:srgbClr val=\"111111\"/></a:solidFill></a:rPr><a:t>Old title</a:t></a:r></a:p></p:txBody></p:sp>"
                                                    "</p:spTree></p:cSld></p:sld>")
                      "ppt/media/image1.png" "PNG-BYTES"}
        base-bytes (let [out (java.io.ByteArrayOutputStream.)]
                     (with-open [zip (java.util.zip.ZipOutputStream. out)]
                       (doseq [[path text] base-entries]
                         (.putNextEntry zip (java.util.zip.ZipEntry. path))
                         (.write zip (.getBytes text "UTF-8"))
                         (.closeEntry zip)))
                     (.toByteArray out))
        deck {:slides/id "imported"
              :slides/slides [{:slides/id "slide-1"
                               :slides/shapes [{:slides/id "Title"
                                                :slides/shape :text
                                                :slides/text "Patched title"
                                                :slides/x 1.5
                                                :slides/y 2.0
                                                :slides/w 3.0
                                                :slides/h 1.25
                                                :slides/font-size 32
                                                :slides/color "ABCDEF"
                                                :ooxml/source {:ooxml/part "ppt/slides/slide1.xml"
                                                               :ooxml/kind :p/sp
                                                               :ooxml/index 0}}]}]}
        entries (zip-entries (pptx/update-pptx-bytes base-bytes deck))
        slide (entries "ppt/slides/slide1.xml")]
    (is (= "PNG-BYTES" (entries "ppt/media/image1.png")))
    (is (re-find #"Patched title" slide))
    (is (re-find #"off x=\"1371600\" y=\"1828800\"" slide))
    (is (re-find #"ext cx=\"2743200\" cy=\"1143000\"" slide))
    (is (re-find #"sz=\"3200\"" slide))
    (is (re-find #"ABCDEF" slide))
    (is (not (contains? entries "ppt/theme/theme1.xml")))))

(deftest update-pptx-patches-literal-dollar-text
  (let [base-entries {"[Content_Types].xml" "<Types/>"
                      "_rels/.rels" "<Relationships/>"
                      "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                      "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                                    "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                                                    "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                                    "<p:txBody><a:p><a:r><a:t>Old title</a:t></a:r></a:p></p:txBody></p:sp>"
                                                    "</p:spTree></p:cSld></p:sld>")}
        base-bytes (zip-bytes base-entries)
        deck {:slides/id "imported"
              :slides/slides [{:slides/id "slide-1"
                               :slides/shapes [{:slides/id "Title"
                                                :slides/shape :text
                                                :slides/text "Revenue is $1.2M & growing"
                                                :slides/x 1
                                                :slides/y 1
                                                :slides/w 4
                                                :slides/h 1
                                                :ooxml/source {:ooxml/part "ppt/slides/slide1.xml"
                                                               :ooxml/kind :p/sp
                                                               :ooxml/index 0}}]}]}
        entries (zip-entries (pptx/update-pptx-bytes base-bytes deck))
        slide (entries "ppt/slides/slide1.xml")]
    (is (re-find #"Revenue is \$1\.2M &amp; growing" slide))))

(deftest update-pptx-preserves-group-placeholder-chart-and-workbook-parts
  (let [base-entries {"[Content_Types].xml" "<Types/>"
                      "_rels/.rels" "<Relationships/>"
                      "docProps/core.xml" "<cp:coreProperties><dc:title>Semantics Deck</dc:title></cp:coreProperties>"
                      "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                      "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                                    "<p:grpSp><p:nvGrpSpPr><p:cNvPr id=\"7\" name=\"Group 1\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm/></p:grpSpPr>"
                                                    "<p:sp><p:nvSpPr><p:cNvPr id=\"8\" name=\"Grouped Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr><p:ph type=\"title\" idx=\"1\"/></p:nvPr></p:nvSpPr>"
                                                    "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                                    "<p:txBody><a:p><a:r><a:t>Grouped Title</a:t></a:r></a:p></p:txBody></p:sp></p:grpSp>"
                                                    "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"12\" name=\"Revenue Chart\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>"
                                                    "<p:xfrm><a:off x=\"914400\" y=\"1371600\"/><a:ext cx=\"5486400\" cy=\"2743200\"/></p:xfrm>"
                                                    "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart r:id=\"rId2\"/></a:graphicData></a:graphic></p:graphicFrame>"
                                                    "</p:spTree></p:cSld></p:sld>")
                      "ppt/slides/_rels/slide1.xml.rels" "<Relationships><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chart1.xml\"/></Relationships>"
                      "ppt/charts/chart1.xml" "<c:chartSpace><c:chart><c:title><c:tx><c:rich><a:p><a:r><a:t>Revenue</a:t></a:r></a:p></c:rich></c:tx></c:title></c:chart></c:chartSpace>"
                      "ppt/charts/_rels/chart1.xml.rels" "<Relationships><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/package\" Target=\"../embeddings/Microsoft_Excel_Worksheet1.xlsx\"/></Relationships>"
                      "ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx" "workbook-bytes"}
        base-bytes (zip-bytes base-entries)
        imported (office/deck-from-office-bytes base-bytes {:source "semantics.pptx"})
        grouped (-> imported :slides/slides first :slides/shapes first)
        chart (-> imported :slides/slides first :slides/shapes second)
        edited (assoc-in imported [:slides/slides 0 :slides/shapes 0 :slides/text] "Patched Grouped Title")
        entries (zip-entries (pptx/update-pptx-bytes base-bytes edited))
        slide (entries "ppt/slides/slide1.xml")]
    (is (= {:index 0 :id "Group 1"} (:slides/group grouped)))
    (is (= {:type "title" :idx "1"} (:slides/placeholder grouped)))
    (is (= "ppt/charts/chart1.xml" (:slides/chart-part chart)))
    (is (= "ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx" (:slides/workbook-part chart)))
    (is (re-find #"<p:grpSp>" slide))
    (is (re-find #"<p:ph type=\"title\" idx=\"1\"/>" slide))
    (is (re-find #"Patched Grouped Title" slide))
    (is (= (base-entries "ppt/charts/chart1.xml") (entries "ppt/charts/chart1.xml")))
    (is (= (base-entries "ppt/charts/_rels/chart1.xml.rels") (entries "ppt/charts/_rels/chart1.xml.rels")))
    (is (= "workbook-bytes" (entries "ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx")))))

(deftest update-pptx-patches-chart-data-into-chart-cache-and-workbook
  (let [workbook-bytes (zip-bytes {"xl/workbook.xml" "<workbook><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
                                   "xl/_rels/workbook.xml.rels" "<Relationships><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"
                                   "xl/worksheets/sheet1.xml" "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Quarter</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>Revenue</t></is></c></row><row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Q1</t></is></c><c r=\"B2\"><v>10</v></c></row><row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>Q2</t></is></c><c r=\"B3\"><v>20</v></c></row></sheetData></worksheet>"})
        base-entries {"[Content_Types].xml" "<Types/>"
                      "_rels/.rels" "<Relationships/>"
                      "docProps/core.xml" "<cp:coreProperties><dc:title>Chart Data Deck</dc:title></cp:coreProperties>"
                      "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                      "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                                    "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"12\" name=\"Revenue Chart\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>"
                                                    "<a:graphic><a:graphicData><c:chart r:id=\"rId2\"/></a:graphicData></a:graphic></p:graphicFrame>"
                                                    "</p:spTree></p:cSld></p:sld>")
                      "ppt/slides/_rels/slide1.xml.rels" "<Relationships><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chart1.xml\"/></Relationships>"
                      "ppt/charts/chart1.xml" "<c:chartSpace><c:chart><c:plotArea><c:barChart><c:ser><c:tx><c:v>Revenue</c:v></c:tx><c:cat><c:strRef><c:strCache><c:ptCount val=\"2\"/><c:pt idx=\"0\"><c:v>Q1</c:v></c:pt><c:pt idx=\"1\"><c:v>Q2</c:v></c:pt></c:strCache></c:strRef></c:cat><c:val><c:numRef><c:numCache><c:ptCount val=\"2\"/><c:pt idx=\"0\"><c:v>10</c:v></c:pt><c:pt idx=\"1\"><c:v>20</c:v></c:pt></c:numCache></c:numRef></c:val></c:ser></c:barChart></c:plotArea></c:chart></c:chartSpace>"
                      "ppt/charts/_rels/chart1.xml.rels" "<Relationships><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/package\" Target=\"../embeddings/Microsoft_Excel_Worksheet1.xlsx\"/></Relationships>"
                      "ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx" workbook-bytes}
        base-bytes (zip-bytes base-entries)
        imported (office/deck-from-office-bytes base-bytes {:source "chart-data.pptx"})
        edited (assoc-in imported [:slides/slides 0 :slides/shapes 0 :slides/chart-data]
                         {:sheet "Sheet1"
                          :anchor "A1"
                          :rows [["Quarter" "Revenue"]
                                 ["Q1" 120]
                                 ["Q2" 180]]})
        updated (pptx/update-pptx-bytes base-bytes edited)
        entries (zip-entries updated)
        chart (entries "ppt/charts/chart1.xml")
        workbook (zip-entries (zip-entry-bytes updated "ppt/embeddings/Microsoft_Excel_Worksheet1.xlsx"))
        sheet (workbook "xl/worksheets/sheet1.xml")]
    (is (re-find #"<c:v>120</c:v>" chart))
    (is (re-find #"<c:v>180</c:v>" chart))
    (is (re-find #"<c r=\"B2\"><v>120</v></c>" sheet))
    (is (re-find #"<c r=\"B3\"><v>180</v></c>" sheet))))

(deftest update-pptx-patches-graphic-frame-position
  (let [base-entries {"[Content_Types].xml" "<Types/>"
                      "_rels/.rels" "<Relationships/>"
                      "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                      "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                                    "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"12\" name=\"Revenue Chart\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>"
                                                    "<p:xfrm><a:off x=\"914400\" y=\"1371600\"/><a:ext cx=\"5486400\" cy=\"2743200\"/></p:xfrm>"
                                                    "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart r:id=\"rId2\"/></a:graphicData></a:graphic></p:graphicFrame>"
                                                    "</p:spTree></p:cSld></p:sld>")}
        base-bytes (zip-bytes base-entries)
        deck {:slides/id "imported"
              :slides/slides [{:slides/id "slide-1"
                               :slides/source "ppt/slides/slide1.xml"
                               :slides/shapes [{:slides/id "Revenue Chart"
                                                :slides/shape :chart
                                                :slides/x 2.0
                                                :slides/y 3.0
                                                :slides/w 4.0
                                                :slides/h 2.5
                                                :ooxml/source {:ooxml/part "ppt/slides/slide1.xml"
                                                               :ooxml/kind :p/graphicFrame
                                                               :ooxml/index 0}}]}]}
        entries (zip-entries (pptx/update-pptx-bytes base-bytes deck))
        slide (entries "ppt/slides/slide1.xml")]
    (testing "graphicFrame uses <p:xfrm>, not <a:xfrm>/<p:spPr> -- position edits must land there"
      (is (re-find #"<p:xfrm><a:off x=\"1828800\" y=\"2743200\"/><a:ext cx=\"3657600\" cy=\"2286000\"/></p:xfrm>" slide))
      (is (not (re-find #"x=\"914400\" y=\"1371600\"" slide))))))

(def multi-run-base-entries
  {"[Content_Types].xml" "<Types/>"
   "_rels/.rels" "<Relationships/>"
   "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
   "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                 "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                                 "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                 "<p:txBody><a:bodyPr/><a:lstStyle/>"
                                 "<a:p><a:pPr algn=\"ctr\"/><a:r><a:rPr sz=\"1800\" b=\"1\"/><a:t>Hello </a:t></a:r><a:r><a:rPr sz=\"1800\"/><a:t>world</a:t></a:r></a:p>"
                                 "</p:txBody></p:sp>"
                                 "</p:spTree></p:cSld></p:sld>")})

(defn- shape-for [text]
  {:slides/id "Title"
   :slides/shape :text
   :slides/text text
   :slides/x 1 :slides/y 1 :slides/w 2 :slides/h 1
   :ooxml/source {:ooxml/part "ppt/slides/slide1.xml"
                  :ooxml/kind :p/sp
                  :ooxml/index 0}})

(deftest update-pptx-collapses-multi-run-paragraph-without-leaving-stale-runs
  (let [base-bytes (zip-bytes multi-run-base-entries)
        deck {:slides/id "imported"
              :slides/slides [{:slides/id "slide-1" :slides/shapes [(shape-for "Bonjour")]}]}
        slide (get (zip-entries (pptx/update-pptx-bytes base-bytes deck)) "ppt/slides/slide1.xml")]
    (testing "old first-<a:t>-only patch left the second run (\"world\") stale; now the whole paragraph is rebuilt"
      (is (re-find #"Bonjour" slide))
      (is (not (re-find #"world" slide)))
      (is (= 1 (count (re-seq #"<a:r>" slide)))))
    (testing "the paragraph's own <a:pPr> (alignment) survives untouched"
      (is (re-find #"<a:pPr algn=\"ctr\"/>" slide)))
    (testing "the first run's formatting (bold) is reused as the style template"
      (is (re-find #"b=\"1\"" slide)))))

(def multi-paragraph-base-entries
  {"[Content_Types].xml" "<Types/>"
   "_rels/.rels" "<Relationships/>"
   "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
   "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                 "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                                 "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                 "<p:txBody><a:bodyPr/><a:lstStyle/>"
                                 "<a:p><a:pPr algn=\"ctr\"/><a:r><a:rPr sz=\"1800\"/><a:t>Old</a:t></a:r></a:p>"
                                 "</p:txBody></p:sp>"
                                 "</p:spTree></p:cSld></p:sld>")})

(deftest update-pptx-grows-and-shrinks-paragraph-count-to-match-lines
  (let [base-bytes (zip-bytes multi-paragraph-base-entries)]
    (testing "more newlines than existing paragraphs appends plain paragraphs"
      (let [deck {:slides/id "imported"
                  :slides/slides [{:slides/id "slide-1" :slides/shapes [(shape-for "Line1\nLine2\nLine3")]}]}
            slide (get (zip-entries (pptx/update-pptx-bytes base-bytes deck)) "ppt/slides/slide1.xml")]
        (is (= 3 (count (re-seq #"<a:p>" slide))))
        (is (= 1 (count (re-seq #"<a:pPr" slide))))
        (is (re-find #"Line1" slide))
        (is (re-find #"Line2" slide))
        (is (re-find #"Line3" slide))))
    (testing "fewer newlines than existing paragraphs drops the extras"
      (let [three-paragraph-bytes (zip-bytes (assoc multi-paragraph-base-entries
                                                     "ppt/slides/slide1.xml"
                                                     (str "<p:sld><p:cSld><p:spTree>"
                                                          "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                                                          "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                                          "<p:txBody><a:bodyPr/><a:lstStyle/>"
                                                          "<a:p><a:r><a:t>A</a:t></a:r></a:p><a:p><a:r><a:t>B</a:t></a:r></a:p><a:p><a:r><a:t>C</a:t></a:r></a:p>"
                                                          "</p:txBody></p:sp>"
                                                          "</p:spTree></p:cSld></p:sld>")))
            deck {:slides/id "imported"
                  :slides/slides [{:slides/id "slide-1" :slides/shapes [(shape-for "OnlyLine")]}]}
            slide (get (zip-entries (pptx/update-pptx-bytes three-paragraph-bytes deck)) "ppt/slides/slide1.xml")]
        (is (= 1 (count (re-seq #"<a:p>" slide))))
        (is (re-find #"OnlyLine" slide))))))

(def table-base-entries
  {"[Content_Types].xml" "<Types/>"
   "_rels/.rels" "<Relationships/>"
   "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
   "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                 "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"9\" name=\"Table 1\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>"
                                 "<p:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"4572000\" cy=\"1828800\"/></p:xfrm>"
                                 "<a:graphic><a:graphicData><a:tbl>"
                                 "<a:tr><a:tc><a:txBody><a:p><a:r><a:t>Q1</a:t></a:r></a:p></a:txBody></a:tc>"
                                 "<a:tc><a:txBody><a:p><a:r><a:t>10</a:t></a:r></a:p></a:txBody></a:tc></a:tr>"
                                 "<a:tr><a:tc><a:txBody><a:p><a:r><a:t>Q2</a:t></a:r></a:p></a:txBody></a:tc>"
                                 "<a:tc><a:txBody><a:p><a:r><a:t>20</a:t></a:r></a:p></a:txBody></a:tc></a:tr>"
                                 "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>"
                                 "</p:spTree></p:cSld></p:sld>")})

(deftest update-pptx-patches-one-table-cell-without-disturbing-siblings
  (let [base-bytes (zip-bytes table-base-entries)
        deck {:slides/id "imported"
              :slides/slides [{:slides/id "slide-1"
                               :slides/shapes [{:slides/id "Table 1"
                                                :slides/shape :table
                                                :slides/rows [["Q1" "10"] ["Q2" "999"]]
                                                :slides/x 1 :slides/y 1 :slides/w 5 :slides/h 2
                                                :ooxml/source {:ooxml/part "ppt/slides/slide1.xml"
                                                               :ooxml/kind :p/graphicFrame
                                                               :ooxml/index 0}}]}]}
        slide (get (zip-entries (pptx/update-pptx-bytes base-bytes deck)) "ppt/slides/slide1.xml")]
    (testing "the edited cell changes, siblings are untouched -- not smashed into one run"
      (is (re-find #"999" slide))
      (is (not (re-find #"20<" slide)))
      (is (re-find #"Q1" slide))
      (is (re-find #"10" slide))
      (is (re-find #"Q2" slide))
      (is (= 4 (count (re-seq #"<a:tc>" slide)))))))

(deftest update-pptx-leaves-table-untouched-without-a-cell-grid
  (let [base-bytes (zip-bytes table-base-entries)
        deck {:slides/id "imported"
              :slides/slides [{:slides/id "slide-1"
                               :slides/shapes [{:slides/id "Table 1"
                                                :slides/shape :table
                                                :slides/text "Q1\n10\nQ2\n20"
                                                :slides/x 1 :slides/y 1 :slides/w 5 :slides/h 2
                                                :ooxml/source {:ooxml/part "ppt/slides/slide1.xml"
                                                               :ooxml/kind :p/graphicFrame
                                                               :ooxml/index 0}}]}]}
        slide (get (zip-entries (pptx/update-pptx-bytes base-bytes deck)) "ppt/slides/slide1.xml")]
    (testing "no :slides/rows means we can't safely align text to cells, so the table XML is left as-is"
      (is (= (table-base-entries "ppt/slides/slide1.xml") slide)))))

(deftest update-pptx-appends-new-shapes-without-ooxml-source
  (let [base-bytes (zip-bytes {"[Content_Types].xml" "<Types/>"
                               "_rels/.rels" "<Relationships/>"
                               "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                               "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                                             "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                                                             "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                                             "<p:txBody><a:p><a:r><a:t>Existing</a:t></a:r></a:p></p:txBody></p:sp>"
                                                             "</p:spTree></p:cSld></p:sld>")})
        deck {:slides/id "imported"
              :slides/slides [{:slides/id "slide-1"
                               :slides/source "ppt/slides/slide1.xml"
                               :slides/shapes [{:slides/id "Title"
                                                :slides/shape :text
                                                :slides/text "Existing"
                                                :slides/x 1 :slides/y 1 :slides/w 2 :slides/h 1
                                                :ooxml/source {:ooxml/part "ppt/slides/slide1.xml"
                                                               :ooxml/kind :p/sp
                                                               :ooxml/index 0}}
                                               {:slides/id "brand-new"
                                                :slides/shape :text
                                                :slides/text "Brand new note"
                                                :slides/x 1 :slides/y 3 :slides/w 4 :slides/h 1
                                                :slides/font-size 18}]}]}
        slide (get (zip-entries (pptx/update-pptx-bytes base-bytes deck)) "ppt/slides/slide1.xml")]
    (is (re-find #"Existing" slide))
    (is (re-find #"Brand new note" slide))
    (is (= 2 (count (re-seq #"<p:sp>" slide))))))

(deftest update-pptx-adds-new-image-shape-with-media-and-relationship
  (let [png-bytes (.getBytes "PNGDATA" "UTF-8")
        b64 (.encodeToString (java.util.Base64/getEncoder) png-bytes)
        base-bytes (zip-bytes
                    {"[Content_Types].xml"
                     (str "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                          "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                          "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                          "<Override PartName=\"/ppt/slides/slide1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
                          "</Types>")
                     "_rels/.rels" "<Relationships/>"
                     "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\"/></p:presentation>"
                     "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                                   "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                                                   "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                                   "<p:txBody><a:p><a:r><a:t>Existing</a:t></a:r></a:p></p:txBody></p:sp>"
                                                   "</p:spTree></p:cSld></p:sld>")
                     "ppt/slides/_rels/slide1.xml.rels"
                     (str "<Relationships>"
                          "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                          "</Relationships>")})
        deck {:slides/id "imported"
              :slides/slides [{:slides/id "slide-1"
                               :slides/source "ppt/slides/slide1.xml"
                               :slides/shapes [{:slides/id "Title" :slides/shape :text :slides/text "Existing"
                                                :slides/x 1 :slides/y 1 :slides/w 2 :slides/h 1
                                                :ooxml/source {:ooxml/part "ppt/slides/slide1.xml" :ooxml/kind :p/sp :ooxml/index 0}}
                                               {:slides/id "new-pic" :slides/shape :image
                                                :slides/image-data b64 :slides/media-type "image/png"
                                                :slides/x 1 :slides/y 3 :slides/w 3 :slides/h 2}]}]}
        entries (zip-entries (pptx/update-pptx-bytes base-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")
        rels-xml (entries "ppt/slides/_rels/slide1.xml.rels")
        ct-xml (entries "[Content_Types].xml")]
    (testing "the new shape renders as a real <p:pic>, not a text fallback"
      (is (re-find #"<p:pic>" slide-xml)))
    (testing "a NEW media part was added"
      (is (some #(str/starts-with? % "ppt/media/image") (keys entries))))
    (testing "the slide's .rels gained an image relationship, continuing past the pre-existing layout rId1"
      (is (re-find #"Id=\"rId1\"[^>]*slideLayout" rels-xml))
      (is (re-find #"Id=\"rId2\"[^>]*relationships/image" rels-xml)))
    (testing "Content_Types.xml gained the png extension default"
      (is (re-find #"Extension=\"png\"" ct-xml)))))

(deftest update-pptx-adds-new-notes-with-notes-master-wiring-when-deck-had-none
  (let [base-bytes (zip-bytes
                    {"[Content_Types].xml"
                     (str "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                          "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                          "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                          "<Override PartName=\"/ppt/slides/slide1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
                          "</Types>")
                     "_rels/.rels" "<Relationships/>"
                     "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\"/></p:presentation>"
                     "ppt/_rels/presentation.xml.rels"
                     "<Relationships><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide1.xml\"/></Relationships>"
                     "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                                   "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                                                   "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                                   "<p:txBody><a:p><a:r><a:t>Existing</a:t></a:r></a:p></p:txBody></p:sp>"
                                                   "</p:spTree></p:cSld></p:sld>")
                     "ppt/slides/_rels/slide1.xml.rels"
                     (str "<Relationships>"
                          "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                          "</Relationships>")})
        deck {:slides/id "imported"
              :slides/slides [{:slides/id "slide-1"
                               :slides/source "ppt/slides/slide1.xml"
                               :slides/notes "New speaker notes, deck previously had none"
                               :slides/shapes [{:slides/id "Title" :slides/shape :text :slides/text "Existing"
                                                :slides/x 1 :slides/y 1 :slides/w 2 :slides/h 1
                                                :ooxml/source {:ooxml/part "ppt/slides/slide1.xml" :ooxml/kind :p/sp :ooxml/index 0}}
                                               {:slides/id "trigger" :slides/shape :text :slides/text "trigger new-shape pass"
                                                :slides/x 1 :slides/y 3 :slides/w 3 :slides/h 1}]}]}
        entries (zip-entries (pptx/update-pptx-bytes base-bytes deck))]
    (testing "a notesSlide part was added and wired from the slide's own .rels"
      (is (contains? entries "ppt/notesSlides/notesSlide1.xml"))
      (is (re-find #"New speaker notes" (entries "ppt/notesSlides/notesSlide1.xml")))
      (is (re-find #"Type=\"[^\"]*notesSlide\"" (entries "ppt/slides/_rels/slide1.xml.rels"))))
    (testing "the notesMaster + presentation.xml.rels wiring were added even though the deck had NO notes before"
      (is (contains? entries "ppt/notesMasters/notesMaster1.xml"))
      (is (re-find #"notesMaster" (entries "ppt/_rels/presentation.xml.rels"))))))

(deftest update-pptx-removes-shapes-deleted-from-the-deck
  (let [base-bytes (zip-bytes {"[Content_Types].xml" "<Types/>"
                               "_rels/.rels" "<Relationships/>"
                               "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                               "ppt/theme/theme1.xml" "<a:theme><a:clrScheme/></a:theme>"
                               "ppt/slideLayouts/slideLayout1.xml" "<p:sldLayout/>"
                               "ppt/slideMasters/slideMaster1.xml" "<p:sldMaster/>"
                               "ppt/slides/slide1.xml" (str "<p:sld><p:cSld><p:spTree>"
                                                             "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Keep\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                                                             "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                                             "<p:txBody><a:p><a:r><a:t>Keep me</a:t></a:r></a:p></p:txBody></p:sp>"
                                                             "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Drop\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                                                             "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"1828800\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                                                             "<p:txBody><a:p><a:r><a:t>Delete me</a:t></a:r></a:p></p:txBody></p:sp>"
                                                             "</p:spTree></p:cSld></p:sld>")})
        imported (office/deck-from-office-bytes base-bytes {:source "delete.pptx"})
        edited (update-in imported [:slides/slides 0 :slides/shapes]
                          (fn [shapes] (vec (remove #(= "Delete me" (:slides/text %)) shapes))))
        slide (get (zip-entries (pptx/update-pptx-bytes base-bytes edited)) "ppt/slides/slide1.xml")]
    (is (re-find #"Keep me" slide))
    (is (not (re-find #"Delete me" slide)))
    (is (= 1 (count (re-seq #"<p:sp>" slide))))))

(deftest writes-and-round-trips-slide-transition
  (let [transition {:type "wipe" :attrs {"dir" "l"} :speed "slow"
                     :advance-on-click false :advance-after-time 3000}
        deck (-> (m/deck "deck" {:slides/title "Transitions"})
                 (m/add-slide (-> (m/slide "s1" {:slides/transition transition})
                                  (m/add-shape (m/rect "r")))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "a real <p:transition> is written as a sibling of <p:cSld>, with the effect element's own attrs"
      (is (re-find #"</p:clrMapOvr><p:transition spd=\"slow\" advClick=\"0\" advTm=\"3000\"><p:wipe dir=\"l\" /></p:transition></p:sld>"
                    slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            slide (first (:slides/slides reimported))]
        (is (= transition (:slides/transition slide))))))
  (testing "no :slides/transition -- no <p:transition> element at all, matching PowerPoint's own default"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"})
                   (m/add-slide (-> (m/slide "s1") (m/add-shape (m/rect "r")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (not (re-find #"<p:transition" slide-xml)))))
  (testing "a transition with only timing attrs, no effect element"
    (let [deck (-> (m/deck "deck" {:slides/title "Timing only"})
                   (m/add-slide (-> (m/slide "s1" {:slides/transition {:speed "fast"}})
                                    (m/add-shape (m/rect "r")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<p:transition spd=\"fast\"></p:transition>" slide-xml)))))

(deftest writes-and-round-trips-doc-properties
  (let [props {:slides/author "Jun Kawasaki" :slides/subject "Q3 Review"
               :slides/keywords "quarterly, review" :slides/category "Business"
               :slides/last-modified-by "Jun Kawasaki"
               :slides/created "2026-01-01T00:00:00Z" :slides/modified "2026-07-02T00:00:00Z"
               :slides/company "GFTD" :slides/manager "Someone"}
        deck (m/deck "deck" (merge {:slides/title "Metadata deck"} props
                                    {:slides/slides [(m/slide "s1")]}))
        entries (zip-entries (pptx/pptx-bytes deck))
        core-xml (entries "docProps/core.xml")
        app-xml (entries "docProps/app.xml")]
    (testing "every extended field is written"
      (is (re-find #"<dc:creator>Jun Kawasaki</dc:creator>" core-xml))
      (is (re-find #"<dc:subject>Q3 Review</dc:subject>" core-xml))
      (is (re-find #"<cp:keywords>quarterly, review</cp:keywords>" core-xml))
      (is (re-find #"<cp:category>Business</cp:category>" core-xml))
      (is (re-find #"<cp:lastModifiedBy>Jun Kawasaki</cp:lastModifiedBy>" core-xml))
      (is (re-find #"<dcterms:created xsi:type=\"dcterms:W3CDTF\">2026-01-01T00:00:00Z</dcterms:created>" core-xml))
      (is (re-find #"<dcterms:modified xsi:type=\"dcterms:W3CDTF\">2026-07-02T00:00:00Z</dcterms:modified>" core-xml))
      (is (re-find #"<Company>GFTD</Company>" app-xml))
      (is (re-find #"<Manager>Someone</Manager>" app-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})]
        (is (= props (select-keys reimported (keys props))))))))

(deftest core-props-defaults-author-when-deck-has-none
  (let [deck (m/deck "deck" {:slides/title "Plain" :slides/slides [(m/slide "s1")]})
        entries (zip-entries (pptx/pptx-bytes deck))
        core-xml (entries "docProps/core.xml")]
    (is (re-find #"<dc:creator>kotoba-lang/slides</dc:creator>" core-xml))
    (is (not (re-find #"<dc:subject>" core-xml)))
    (is (not (re-find #"<cp:keywords>" core-xml)))))

(deftest writes-date-and-slide-number-field-placeholders
  (let [deck (-> (m/deck "deck" {:slides/title "Field placeholders"
                                 :slides/master {:slides/date {:slides/enabled true
                                                               :slides/x 0.7 :slides/y 5.0 :slides/w 1.5 :slides/h 0.18}
                                                 :slides/slide-number {:slides/enabled true
                                                                       :slides/x 7.5 :slides/y 5.0 :slides/w 1.0 :slides/h 0.18}}})
                 (m/add-slide (m/slide "s1"))
                 (m/add-slide (m/slide "s2")))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide1 (entries "ppt/slides/slide1.xml")
        slide2 (entries "ppt/slides/slide2.xml")]
    (testing "the date placeholder is a real <p:ph type=\"dt\"> whose run is a field, not plain text"
      (is (re-find #"<p:cNvPr id=\"12\" name=\"master-date\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"dt\"/></p:nvPr>" slide1))
      (is (re-find #"<a:fld id=\"\{[^}]+\}\" type=\"datetime1\">" slide1)))
    (testing "the slide-number placeholder is a real <p:ph type=\"sldNum\">, and its text is the slide's OWN 1-based position"
      (is (re-find #"<p:cNvPr id=\"13\" name=\"master-slide-number\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"sldNum\"/></p:nvPr>" slide1))
      (is (re-find #"<a:fld id=\"[^\"]*\" type=\"slidenum\"><a:rPr[\s\S]*?</a:rPr><a:t>1</a:t></a:fld>" slide1))
      (is (re-find #"<a:fld id=\"[^\"]*\" type=\"slidenum\"><a:rPr[\s\S]*?</a:rPr><a:t>2</a:t></a:fld>" slide2)
          "slide 2's own field shows \"2\", not stale/copy-pasted \"1\""))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shapes-of (fn [idx] (-> reimported :slides/slides (nth idx) :slides/shapes))
            by-ph-type (fn [shapes t] (some #(when (= t (get-in % [:slides/placeholder :type])) %) shapes))]
        (is (= "1" (:slides/text (by-ph-type (shapes-of 0) "sldNum"))))
        (is (= "2" (:slides/text (by-ph-type (shapes-of 1) "sldNum"))))
        (is (some? (by-ph-type (shapes-of 0) "dt"))))))
  (testing "no :slides/date/:slides/slide-number in the design -- no field placeholders at all, matching the pre-feature default"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"}) (m/add-slide (m/slide "s1")))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (not (re-find #"<a:fld" slide-xml)))
      (is (not (re-find #"type=\"dt\"" slide-xml)))
      (is (not (re-find #"type=\"sldNum\"" slide-xml))))))

(deftest round-trips-per-slide-layout-ref-through-import
  (let [deck (m/deck "deck" {:slides/title "Layout diversity"
                             :slides/layouts [{:slides/id "title-slide" :slides/layout-type "title"}
                                              {:slides/id "two-content" :slides/layout-type "twoObj"}]})
        deck (-> deck
                (m/add-slide (-> (m/slide "s1" {:slides/layout-ref "title-slide"}) (m/add-shape (m/text-box "t" "Title"))))
                (m/add-slide (-> (m/slide "s2" {:slides/layout-ref "two-content"}) (m/add-shape (m/text-box "t" "Content"))))
                (m/add-slide (-> (m/slide "s3") (m/add-shape (m/text-box "t" "Default")))))
        reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
        slides (:slides/slides reimported)]
    (testing "previously lost entirely on reimport -- every slide fell back to its master's implicit default layout"
      (is (= 3 (count (:slides/layouts reimported))))
      (is (some? (:slides/layout-ref (nth slides 0))))
      (is (some? (:slides/layout-ref (nth slides 1))))
      (is (not= (:slides/layout-ref (nth slides 0)) (:slides/layout-ref (nth slides 1))))
      (is (= "title" (:slides/layout-type
                      (some #(when (= (:slides/layout-ref (nth slides 0)) (:slides/id %)) %)
                            (:slides/layouts reimported))))))))

(deftest writes-and-round-trips-paragraph-indent-level
  (let [paragraphs [{:text "Top level" :bullet {:type :char :char "•"}}
                    {:text "Sub bullet" :bullet {:type :char :char "•"} :level 1}
                    {:text "Sub-sub, explicit margin" :bullet {:type :char :char "•"} :level 2 :margin-left 1.0}]
        deck (-> (m/deck "deck" {:slides/title "Nested bullets"})
                 (m/add-slide (-> (m/slide "s1")
                                  (m/add-shape (m/text-box "t" "" {:slides/paragraphs paragraphs})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "lvl and marL are written as <a:pPr> attributes, in schema order (lvl, marL, algn)"
      (is (re-find #"<a:pPr lvl=\"1\">" slide-xml))
      (is (re-find #"<a:pPr lvl=\"2\" marL=\"914400\">" slide-xml)))
    (testing "no level on a paragraph -- no lvl attribute at all (level 0, the implicit default)"
      (is (re-find #"<a:pPr><a:buChar char=\"•\"/></a:pPr><a:r><a:rPr[^>]*><a:latin[^>]*/><a:solidFill[^>]*>[\s\S]*?</a:solidFill></a:rPr><a:t>Top level</a:t></a:r>"
                    slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            reimported-paras (:slides/paragraphs (first (-> reimported :slides/slides first :slides/shapes)))]
        (is (not (contains? (nth reimported-paras 0) :level)))
        (is (= 1 (:level (nth reimported-paras 1))))
        (is (= 2 (:level (nth reimported-paras 2))))
        (is (= 1.0 (:margin-left (nth reimported-paras 2))))))))

(deftest writes-and-round-trips-text-body-properties
  (let [body-props {:wrap :none :anchor :center :anchor-center true
                    :margin-left 0.05 :margin-top 0.025 :margin-right 0.05 :margin-bottom 0.025
                    :autofit :shrink :font-scale 90.0 :line-spacing-reduction 10.0}
        deck (-> (m/deck "deck" {:slides/title "Autofit text"})
                 (m/add-slide (-> (m/slide "s1")
                                  (m/add-shape (m/text-box "t" "Autofit text" {:slides/body-props body-props})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "wrap/anchor/anchorCtr/margins are all written as <a:bodyPr> attributes, normAutofit as its child"
      (is (re-find #"<a:bodyPr wrap=\"none\" lIns=\"45720\" tIns=\"22860\" rIns=\"45720\" bIns=\"22860\" anchor=\"ctr\" anchorCtr=\"1\">" slide-xml))
      (is (re-find #"<a:normAutofit fontScale=\"90000\" lnSpcReduction=\"10000\"/>" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shape (first (-> reimported :slides/slides first :slides/shapes))]
        (is (= body-props (:slides/body-props shape))))))
  (testing "no :slides/body-props -- a bare <a:bodyPr></a:bodyPr>, semantically identical to the historical wrap=\"square\" default"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"}) (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Plain")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (re-find #"<a:bodyPr></a:bodyPr>" slide-xml))
      (is (not (re-find #"<a:bodyPr [^>]" slide-xml))))))

(deftest writes-and-round-trips-text-vertical-direction
  (let [deck (-> (m/deck "deck" {:slides/title "Vertical text"})
                 (m/add-slide (-> (m/slide "s1")
                                  (m/add-shape (m/text-box "t" "Vertical" {:slides/body-props {:vertical :vert270}})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "vert=\"vert270\" is written on <a:bodyPr>'s own opening tag"
      (is (re-find #"<a:bodyPr vert=\"vert270\">" slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            shape (first (-> reimported :slides/slides first :slides/shapes))]
        (is (= :vert270 (:vertical (:slides/body-props shape))))))))

(deftest writes-and-round-trips-gradient-fill
  (let [gradient {:stops [{:position 0.0 :color "336699"}
                          {:position 50.0 :color "88AACC"}
                          {:position 100.0 :color "AABBCC"}]
                  :angle 90.0}
        deck (-> (m/deck "deck" {:slides/title "Gradient rect"})
                 (m/add-slide (-> (m/slide "s1")
                                  (m/add-shape (m/rect "r" {:slides/gradient gradient})))))
        entries (zip-entries (pptx/pptx-bytes deck))
        slide-xml (entries "ppt/slides/slide1.xml")]
    (testing "a real multi-stop <a:gradFill> is written, not a flattened <a:solidFill>"
      (is (re-find #"<a:gradFill><a:gsLst><a:gs pos=\"0\"><a:srgbClr val=\"336699\"/></a:gs><a:gs pos=\"50000\"><a:srgbClr val=\"88AACC\"/></a:gs><a:gs pos=\"100000\"><a:srgbClr val=\"AABBCC\"/></a:gs></a:gsLst><a:lin ang=\"5400000\" scaled=\"1\"/></a:gradFill>"
                    slide-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})
            rect (first (filter #(= :rect (:slides/shape %)) (-> reimported :slides/slides first :slides/shapes)))]
        (is (= gradient (:slides/gradient rect))))))
  (testing "no :slides/gradient -- plain <a:solidFill>, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"}) (m/add-slide (-> (m/slide "s1") (m/add-shape (m/rect "r")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          slide-xml (entries "ppt/slides/slide1.xml")]
      (is (not (re-find #"<a:gradFill" slide-xml))))))

(deftest writes-and-round-trips-slide-sections
  (let [sections [{:name "Intro" :slide-indices [0 1]} {:name "Summary" :slide-indices [2]}]
        deck (-> (m/deck "deck" {:slides/title "Sectioned" :slides/sections sections})
                 (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "One"))))
                 (m/add-slide (-> (m/slide "s2") (m/add-shape (m/text-box "t" "Two"))))
                 (m/add-slide (-> (m/slide "s3") (m/add-shape (m/text-box "t" "Three")))))
        entries (zip-entries (pptx/pptx-bytes deck))
        presentation-xml (entries "ppt/presentation.xml")]
    (testing "a real <p14:sectionLst> is written, each section's own sldIds matching <p:sldIdLst>'s 256+idx formula"
      (is (re-find #"<p14:sectionLst xmlns:p14=\"http://schemas.microsoft.com/office/powerpoint/2010/main\">" presentation-xml))
      (is (re-find #"<p14:section name=\"Intro\"[^>]*><p14:sldIdLst><p14:sldId id=\"256\"/><p14:sldId id=\"257\"/></p14:sldIdLst></p14:section>" presentation-xml))
      (is (re-find #"<p14:section name=\"Summary\"[^>]*><p14:sldIdLst><p14:sldId id=\"258\"/></p14:sldIdLst></p14:section>" presentation-xml)))
    (testing "round-trips through import"
      (let [reimported (office/deck-from-office-bytes (pptx/pptx-bytes deck) {})]
        (is (= sections (:slides/sections reimported))))))
  (testing "no :slides/sections -- no <p:extLst>/<p14:sectionLst> at all, unchanged"
    (let [deck (-> (m/deck "deck" {:slides/title "Plain"}) (m/add-slide (-> (m/slide "s1") (m/add-shape (m/text-box "t" "Hi")))))
          entries (zip-entries (pptx/pptx-bytes deck))
          presentation-xml (entries "ppt/presentation.xml")]
      (is (not (re-find #"sectionLst" presentation-xml))))))
