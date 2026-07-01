(ns slides.office-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [office.graph :as office-graph]
            [office-style.style :as office-style]
            [slides.causal :as causal]
            [slides.model :as model]
            [slides.office :as office]
            [slides.pptx :as pptx])
  (:import (java.util.zip ZipInputStream)))

(defn zip-bytes [entries]
  (let [out (java.io.ByteArrayOutputStream.)]
    (with-open [zip (java.util.zip.ZipOutputStream. out)]
      (doseq [[path text] entries]
        (.putNextEntry zip (java.util.zip.ZipEntry. path))
        (.write zip (.getBytes text "UTF-8"))
        (.closeEntry zip)))
    (.toByteArray out)))

(defn entries-from-bytes [bytes]
  (with-open [zip (ZipInputStream. (java.io.ByteArrayInputStream. bytes))]
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

(deftest imports-pptx-to-slides-deck
  (let [bytes (zip-bytes
               {"[Content_Types].xml" "<Types/>"
                "_rels/.rels" "<Relationships/>"
                "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                "ppt/theme/theme1.xml" "<a:theme><a:clrScheme><a:accent1><a:srgbClr val=\"112233\"/></a:accent1><a:accent2><a:srgbClr val=\"445566\"/></a:accent2><a:dk1><a:srgbClr val=\"010101\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"020202\"/></a:dk2><a:lt2><a:srgbClr val=\"030303\"/></a:lt2><a:accent3><a:srgbClr val=\"030303\"/></a:accent3><a:accent4><a:srgbClr val=\"040404\"/></a:accent4><a:accent5><a:srgbClr val=\"050505\"/></a:accent5><a:accent6><a:srgbClr val=\"060606\"/></a:accent6><a:hlink><a:srgbClr val=\"070707\"/></a:hlink><a:folHlink><a:srgbClr val=\"080808\"/></a:folHlink></a:clrScheme><a:fontScheme><a:majorFont><a:latin typeface=\"Aptos Display\"/></a:majorFont><a:minorFont><a:latin typeface=\"Aptos\"/></a:minorFont></a:fontScheme></a:theme>"
                "ppt/slides/slide1.xml" "<p:sld><a:t>Alpha</a:t><a:t>Beta</a:t></p:sld>"
                "ppt/slides/slide2.xml" "<p:sld><a:t>Gamma</a:t></p:sld>"
                "ppt/slideLayouts/slideLayout1.xml" "<p:sldLayout/>"
                "ppt/slideMasters/slideMaster1.xml" "<p:sldMaster/>"})
        deck (office/deck-from-office-bytes bytes {:title "Imported Deck"})
        entries (entries-from-bytes (pptx/pptx-bytes deck))]
    (is (= "Imported Deck" (:slides/title deck)))
    (is (= 2 (count (:slides/slides deck))))
    (is (= 2 (count (filter #(= :text (:slides/shape %))
                           (get-in (first (:slides/slides deck)) [:slides/shapes])))))
    (is (= "Alpha" (-> deck :slides/slides first :slides/shapes first :slides/text)))
    (is (= "slide-1" (:slides/id (first (:slides/slides deck)))))
    (is (= "112233" (get-in deck [:slides/theme :slides/colors :office-style.color/accent1])))
    (is (contains? entries "[Content_Types].xml"))
    (is (contains? entries "ppt/slides/slide1.xml"))
    (is (contains? entries "ppt/theme/theme1.xml"))))

(deftest imports-pptx-without-text-keeps-empty-slides
  (let [bytes (zip-bytes
               {"[Content_Types].xml" "<Types/>"
                "_rels/.rels" "<Relationships/>"
                "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                "ppt/theme/theme1.xml" "<a:theme><a:clrScheme/></a:theme>"
                "ppt/slides/slide1.xml" "<p:sld><p:cSld/></p:sld>"
                "ppt/slides/slide2.xml" "<p:sld><p:cSld/></p:sld>"
                "ppt/slideLayouts/slideLayout1.xml" "<p:sldLayout/>"
                "ppt/slideMasters/slideMaster1.xml" "<p:sldMaster/>"})
        deck (office/deck-from-office-bytes bytes {:slides/title "Empty deck"})
        entries (entries-from-bytes (pptx/pptx-bytes deck))]
    (is (= "Empty deck" (:slides/title deck)))
    (is (= 2 (count (:slides/slides deck))))
    (is (every? #(empty? (:slides/shapes %)) (:slides/slides deck)))
    (is (= "slide-1" (:slides/id (first (:slides/slides deck)))))
    (is (contains? entries "ppt/slides/slide2.xml"))))

(deftest imports-pptx-with-empty-style-uses-default-theme
  (let [bytes (zip-bytes
               {"[Content_Types].xml" "<Types/>"
                "_rels/.rels" "<Relationships/>"
                "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                "ppt/slides/slide1.xml" "<p:sld><a:t>Only one</a:t></p:sld>"
                "ppt/slideLayouts/slideLayout1.xml" "<p:sldLayout/>"
                "ppt/slideMasters/slideMaster1.xml" "<p:sldMaster/>"})
        deck (office/deck-from-office-bytes bytes)
        entries (entries-from-bytes (pptx/pptx-bytes deck))]
    (is (= 1 (count (:slides/slides deck))))
    (is (= "imported-deck" (:slides/id deck)))
    (is (= 10.0 (:slides/width deck)))
    (is (= 5.625 (:slides/height deck)))
    (is (contains? (:slides/theme deck) :slides/colors))
    (is (re-find #"496B9A" (get entries "ppt/theme/theme1.xml")))))

(deftest imports-empty-pptx-still-produces-empty-deck-slide
  (let [bytes (zip-bytes
               {"[Content_Types].xml" "<Types/>"
                "_rels/.rels" "<Relationships/>"
                "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                "ppt/slideLayouts/slideLayout1.xml" "<p:sldLayout/>"
                "ppt/slideMasters/slideMaster1.xml" "<p:sldMaster/>"})
        deck (office/deck-from-office-bytes bytes)
        entries (entries-from-bytes (pptx/pptx-bytes deck))]
    (is (= 1 (count (:slides/slides deck))))
    (is (= "slide-1" (:slides/id (first (:slides/slides deck)))))
    (is (empty? (:slides/shapes (first (:slides/slides deck)))))
    (is (contains? entries "ppt/slides/slide1.xml"))))

(deftest ordered-slide-sources-follows-style-order
  (let [graph {:office/nodes [{:office/id "package" :office/kind :package}
                             {:office/id "ppt/slides/slide1.xml" :office/kind :slide}
                             {:office/id "ppt/slides/slide2.xml" :office/kind :slide}
                             {:office/id "ppt/slides/slide3.xml" :office/kind :slide}]}
        style-ir {:office-style/slides ["ppt/slides/slide2.xml"
                                       "ppt/slides/missing.xml"
                                       "ppt/slides/slide1.xml"]}
        ordered-sources (ns-resolve 'slides.office 'ordered-slide-sources)]
    (is (some? ordered-sources))
    (let [ordered (@ordered-sources graph style-ir)]
      (is (= ["ppt/slides/slide2.xml"
              "ppt/slides/slide1.xml"
              "ppt/slides/slide3.xml"]
             ordered)))))

(deftest ordered-slide-sources-removes-duplicates
  (let [graph {:office/nodes [{:office/id "ppt/slides/slide1.xml" :office/kind :slide}
                             {:office/id "ppt/slides/slide2.xml" :office/kind :slide}]}
        style-ir {:office-style/slides ["ppt/slides/slide1.xml"
                                       "ppt/slides/slide1.xml"
                                       "ppt/slides/slide2.xml"
                                       "ppt/slides/slide2.xml"
                                       "ppt/slides/slide3.xml"]}
        ordered-sources (ns-resolve 'slides.office 'ordered-slide-sources)]
    (is (some? ordered-sources))
      (let [ordered (@ordered-sources graph style-ir)]
        (is (= ["ppt/slides/slide1.xml"
                "ppt/slides/slide2.xml"]
               ordered)))))

(deftest ordered-slide-sources-falls-back-to-observed-order
  (let [graph {:office/nodes [{:office/id "ppt/slides/slide10.xml" :office/kind :slide}
                             {:office/id "ppt/slides/slide2.xml" :office/kind :slide}
                             {:office/id "ppt/slides/slide1.xml" :office/kind :slide}]}
        ordered-sources (ns-resolve 'slides.office 'ordered-slide-sources)]
    (is (some? ordered-sources))
    (let [ordered (@ordered-sources graph {})]
      (is (= ["ppt/slides/slide1.xml"
              "ppt/slides/slide2.xml"
              "ppt/slides/slide10.xml"]
             ordered)))))

(deftest ordered-slide-sources-does-not-parse-huge-slide-numbers
  (let [graph {:office/nodes [{:office/id "ppt/slides/slide99999999999999999999999999999999999999.xml" :office/kind :slide}
                             {:office/id "ppt/slides/slide2.xml" :office/kind :slide}]}
        ordered-sources (ns-resolve 'slides.office 'ordered-slide-sources)]
    (is (some? ordered-sources))
    (is (= ["ppt/slides/slide2.xml"
            "ppt/slides/slide99999999999999999999999999999999999999.xml"]
           (@ordered-sources graph {})))))

(deftest office-import-and-export-can-stay-on-edn-boundary
  (let [bytes (zip-bytes
               {"[Content_Types].xml" "<Types/>"
                "_rels/.rels" "<Relationships/>"
                "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                "ppt/theme/theme1.xml" "<a:theme><a:clrScheme><a:accent1><a:srgbClr val=\"112233\"/></a:accent1></a:clrScheme></a:theme>"
                "ppt/slides/slide1.xml" "<p:sld><a:t>Alpha</a:t></p:sld>"
                "ppt/slideLayouts/slideLayout1.xml" "<p:sldLayout/>"
                "ppt/slideMasters/slideMaster1.xml" "<p:sldMaster/>"})
        deck-edn (office/deck-edn-from-office-bytes bytes {:title "EDN Bridge"})
        deck (edn/read-string deck-edn)
        out-entries (entries-from-bytes (office/pptx-bytes-from-deck-edn deck-edn))]
    (is (= "EDN Bridge" (:slides/title deck)))
    (is (= "Alpha" (-> deck :slides/slides first :slides/shapes first :slides/text)))
    (is (contains? out-entries "ppt/presentation.xml"))
    (is (contains? out-entries "ppt/slides/slide1.xml"))
    (is (re-find #"EDN Bridge" (get out-entries "docProps/core.xml")))))

(deftest imports-drawingml-shape-geometry-and-style
  (let [bytes (zip-bytes
               {"[Content_Types].xml" "<Types/>"
                "_rels/.rels" "<Relationships/>"
                "docProps/core.xml" "<cp:coreProperties><dc:title>Styled deck</dc:title></cp:coreProperties>"
                "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                "ppt/slides/slide1.xml"
                (str "<p:sld><p:cSld><p:spTree>"
                     "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Headline\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                     "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"457200\"/><a:ext cx=\"2743200\" cy=\"914400\"/></a:xfrm>"
                     "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>"
                     "<p:txBody><a:p><a:r><a:rPr sz=\"3200\"><a:solidFill><a:srgbClr val=\"123456\"/></a:solidFill></a:rPr><a:t>Moved title</a:t></a:r></a:p></p:txBody></p:sp>"
                     "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Panel\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
                     "<p:spPr><a:xfrm><a:off x=\"1828800\" y=\"1828800\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>"
                     "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"ABCDEF\"/></a:solidFill>"
                     "<a:ln><a:solidFill><a:srgbClr val=\"112233\"/></a:solidFill></a:ln></p:spPr></p:sp>"
                     "</p:spTree></p:cSld></p:sld>")
                "ppt/slideLayouts/slideLayout1.xml" "<p:sldLayout/>"
                "ppt/slideMasters/slideMaster1.xml" "<p:sldMaster/>"})
        deck (office/deck-from-office-bytes bytes)
        [text rect] (-> deck :slides/slides first :slides/shapes)]
    (is (= "Styled deck" (:slides/title deck)))
    (is (= "Moved title" (:slides/text text)))
    (is (= 1.0 (:slides/x text)))
    (is (= 0.5 (:slides/y text)))
    (is (= 3.0 (:slides/w text)))
    (is (= 32.0 (:slides/font-size text)))
    (is (= "123456" (:slides/color text)))
    (is (= :rect (:slides/shape rect)))
    (is (= "ABCDEF" (:slides/fill rect)))
    (is (= "112233" (:slides/line rect)))))

(deftest reconciles-causal-sidecar-with-current-pptx-xml
  (let [sidecar-deck (-> (model/deck "sidecar-deck" {:slides/title "Sidecar title"})
                         (model/add-slide
                          (-> (model/slide "s1" {:slides/title "Old"})
                              (model/add-shape (model/text-box "title" "Old sidecar text")))))
        entries (entries-from-bytes (causal/embed-deck-bytes sidecar-deck))
        changed (assoc entries "ppt/slides/slide1.xml"
                       (str "<p:sld><p:cSld><p:spTree>"
                            "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Edited\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                            "<p:spPr><a:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"1828800\" cy=\"914400\"/></a:xfrm></p:spPr>"
                            "<p:txBody><a:p><a:r><a:t>Edited in PowerPoint</a:t></a:r></a:p></p:txBody></p:sp>"
                            "</p:spTree></p:cSld></p:sld>"))
        deck (office/deck-from-office-bytes (zip-bytes changed))]
    (is (= "sidecar-deck" (:slides/id deck)))
    (is (= "Sidecar title" (:slides/title deck)))
    (is (= "Edited in PowerPoint" (-> deck :slides/slides first :slides/shapes first :slides/text)))
    (is (= :reconciled-pptx (get-in deck [:slides/import :slides/text-extraction])))))

(deftest office-import-does-not-parse-huge-text-node-numbers
  (let [graph {:office/nodes [{:office/id "ppt/slides/slide1.xml"
                               :office/kind :slide}
                              {:office/id "ppt/slides/slide1.xml#text-999999999999999999999999999999"
                               :office/kind :text
                               :office/source "ppt/slides/slide1.xml"
                               :office/text "Huge"}
                              {:office/id "ppt/slides/slide1.xml#text-2"
                               :office/kind :text
                               :office/source "ppt/slides/slide1.xml"
                               :office/text "Two"}]
               :office/edges []}]
    (with-redefs [office-graph/analyze-bytes (fn [_] graph)
                  office-style/extract-bytes (fn [_] {})]
      (let [deck (office/deck-from-office-bytes (.getBytes "ignored" "UTF-8"))
            texts (map :slides/text (-> deck :slides/slides first :slides/shapes))]
        (is (= ["Two" "Huge"] texts))))))

(deftest preserves-default-size-on-invalid-slide-size
  (let [bytes (zip-bytes
               {"[Content_Types].xml" "<Types/>"
                "_rels/.rels" "<Relationships/>"
                "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"-100\" cy=\"0\" type=\"wide\"/></p:presentation>"
                "ppt/slides/slide1.xml" "<p:sld><a:t>Only one</a:t></p:sld>"
                "ppt/slideLayouts/slideLayout1.xml" "<p:sldLayout/>"
                "ppt/slideMasters/slideMaster1.xml" "<p:sldMaster/>"})]
    (with-redefs [office-style/extract-bytes (fn [_] {:office-style/slide-size
                                                     {:office-style/cx -100
                                                      :office-style/cy 0}})]
      (let [deck (office/deck-from-office-bytes bytes)]
        (is (= 10.0 (:slides/width deck)))
        (is (= 5.625 (:slides/height deck)))))))

(deftest falls-back-when-style-extraction-fails
  (let [bytes (zip-bytes
               {"[Content_Types].xml" "<Types/>"
                "_rels/.rels" "<Relationships/>"
                "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                "ppt/slides/slide1.xml" "<p:sld><a:t>Only one</a:t></p:sld>"
                "ppt/slideLayouts/slideLayout1.xml" "<p:sldLayout/>"
                "ppt/slideMasters/slideMaster1.xml" "<p:sldMaster/>"})]
    (with-redefs [office-style/extract-bytes (fn [_] (throw (ex-info "boom" {})))]
      (let [deck (office/deck-from-office-bytes bytes)]
        (is (= 1 (count (:slides/slides deck))))
        (is (= 10.0 (:slides/width deck)))
        (is (= 5.625 (:slides/height deck)))
        (is (= "imported-deck" (:slides/id deck)))
        (is (contains? (:slides/theme deck) :slides/colors))))))

(deftest deck-title-precedence
  (let [bytes (zip-bytes
               {"[Content_Types].xml" "<Types/>"
                "_rels/.rels" "<Relationships/>"
                "ppt/presentation.xml" "<p:presentation><p:sldSz cx=\"9144000\" cy=\"5143500\" type=\"wide\"/></p:presentation>"
                "ppt/slides/slide1.xml" "<p:sld><a:t>Only one</a:t></p:sld>"
                "ppt/slideLayouts/slideLayout1.xml" "<p:sldLayout/>"
                "ppt/slideMasters/slideMaster1.xml" "<p:sldMaster/>"})]
    (is (= "CLI title" (:slides/title (office/deck-from-office-bytes bytes {:title "CLI title"
                                                                          :slides/title "Attr title"}))))
    (is (= "Attr title" (:slides/title (office/deck-from-office-bytes bytes {:slides/title "Attr title"}))))
    (is (= "Imported deck" (:slides/title (office/deck-from-office-bytes bytes {:title "" :slides/title ""}))))
    (is (= "Imported deck" (:slides/title (office/deck-from-office-bytes bytes))))))
