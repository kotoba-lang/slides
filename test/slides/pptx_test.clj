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
