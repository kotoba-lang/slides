(ns slides.pptx-test
  (:require [clojure.test :refer [deftest is]]
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

(defn zip-bytes [entries]
  (let [out (java.io.ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[path text] entries]
        (.putNextEntry zip (ZipEntry. path))
        (.write zip (.getBytes text "UTF-8"))
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
