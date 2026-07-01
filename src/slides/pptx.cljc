(ns slides.pptx
  "EDN to minimal PowerPoint Open XML package writer.

  The public surface is data-first: pass a deck map with :slides/slides and
  receive a .pptx byte array or write it to disk on the JVM."
  (:require [clojure.string :as str]
            [drawingml.core :as dml]
            [ooxml.core :as ooxml]
            [presentationml.core :as pml]
            [slides.design :as design])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream FileOutputStream]
                   [java.util.zip ZipEntry ZipInputStream ZipOutputStream])))

(def emu-per-inch 914400)
(def default-width-in 10)
(def default-height-in 5.625)
(def rel-slide-master "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster")
(def rel-slide "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide")
(def rel-slide-layout "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout")
(def rel-theme "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme")
(def rel-core-props "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties")
(def rel-app-props "http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties")

(defn- esc [x]
  (-> (str (or x ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- finite-number? [x]
  (and (number? x)
       #?(:clj (Double/isFinite (double x))
          :cljs (js/isFinite x))))

(defn- numeric [x fallback]
  (if (finite-number? x) x fallback))

(defn- positive-numeric [x fallback]
  (if (and (finite-number? x) (pos? x)) x fallback))

(defn- emu [inches]
  (long (Math/round (* emu-per-inch (double (numeric inches 0))))))

(defn- hex-color [x fallback]
  (let [s (-> (or x fallback) str (str/replace #"^#" "") str/upper-case)]
    (if (re-matches #"[0-9A-F]{6}" s) s fallback)))

(defn- content-types [slide-count]
  (ooxml/content-types-xml
   (concat
    [(ooxml/default-content-type "rels" (:rels ooxml/content-types))
     (ooxml/default-content-type "xml" (:xml ooxml/content-types))
     (ooxml/override-content-type "/docProps/app.xml" "application/vnd.openxmlformats-officedocument.extended-properties+xml")
     (ooxml/override-content-type "/docProps/core.xml" "application/vnd.openxmlformats-package.core-properties+xml")
     (ooxml/override-content-type "/ppt/presentation.xml" (:pptx ooxml/content-types))
     (ooxml/override-content-type "/ppt/slideMasters/slideMaster1.xml" "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml")
     (ooxml/override-content-type "/ppt/slideLayouts/slideLayout1.xml" "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml")
     (ooxml/override-content-type "/ppt/theme/theme1.xml" "application/vnd.openxmlformats-officedocument.theme+xml")]
    (for [idx (range 1 (inc slide-count))]
      (ooxml/override-content-type (str "/ppt/slides/slide" idx ".xml")
                                   "application/vnd.openxmlformats-officedocument.presentationml.slide+xml")))))

(def root-rels
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type ooxml/office-document-rel :target "ppt/presentation.xml"})
    (ooxml/relationship {:id "rId2" :type rel-core-props :target "docProps/core.xml"})
    (ooxml/relationship {:id "rId3" :type rel-app-props :target "docProps/app.xml"})]))

(defn- core-props [deck]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
       "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
       "xmlns:dcterms=\"http://purl.org/dc/terms/\" "
       "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" "
       "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
       "<dc:title>" (esc (:slides/title deck (:slides/id deck "slides"))) "</dc:title>"
       "<dc:creator>kotoba-lang/slides</dc:creator>"
       "</cp:coreProperties>"))

(defn- app-props [slide-count]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" "
       "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
       "<Application>kotoba-lang/slides</Application>"
       "<PresentationFormat>On-screen Show (16:9)</PresentationFormat>"
       "<Slides>" slide-count "</Slides>"
       "</Properties>"))

(defn- presentation [slide-count width height]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<p:presentation xmlns:a=\"" dml/ns-a "\" "
       "xmlns:r=\"" pml/ns-r "\" "
       "xmlns:p=\"" pml/ns-p "\">"
       "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>"
       "<p:sldIdLst>"
       (apply str
              (for [idx (range 1 (inc slide-count))]
                (str "<p:sldId id=\"" (+ 255 idx) "\" r:id=\"rId" (inc idx) "\"/>")))
       "</p:sldIdLst>"
       "<p:sldSz cx=\"" (emu width) "\" cy=\"" (emu height) "\" type=\"wide\"/>"
       "<p:notesSz cx=\"6858000\" cy=\"9144000\"/>"
       "</p:presentation>"))

(defn- presentation-rels [slide-count]
  (ooxml/relationships-xml
   (concat
    [(ooxml/relationship {:id "rId1" :type rel-slide-master :target "slideMasters/slideMaster1.xml"})]
    (for [idx (range 1 (inc slide-count))]
      (ooxml/relationship {:id (str "rId" (inc idx))
                           :type rel-slide
                           :target (str "slides/slide" idx ".xml")})))))

(def default-theme (:slides/theme design/default-design))

(defn- normalize-theme [value]
  (cond
    (nil? value) default-theme
    (and (map? value) (:slides/theme value)) (:slides/theme value)
    (map? value) value
    :else default-theme))

(defn- theme-colors [value]
  (let [theme (normalize-theme value)]
    (or (:office-style/colors theme)
        (:slides/colors theme)
        (:colors theme)
        (:slides/colors default-theme))))

(defn- theme-fonts [value]
  (let [theme (normalize-theme value)]
    (or (:office-style/fonts theme)
        (:slides/fonts theme)
        (:fonts theme)
        (:slides/fonts default-theme))))

(defn- theme-color [colors role fallback]
  (hex-color (or (get colors role) fallback) fallback))

(defn- theme-font [fonts role fallback]
  (esc (or (get fonts role) fallback)))

(defn theme-xml [theme]
  (let [colors (theme-colors theme)
        fonts (theme-fonts theme)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
         "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"kotoba\">\n"
         "<a:themeElements>\n"
         "<a:clrScheme name=\"kotoba\">"
         "<a:dk1><a:srgbClr val=\"" (theme-color colors :office-style.color/dk1 "17202A") "\"/></a:dk1>"
         "<a:lt1><a:srgbClr val=\"" (theme-color colors :office-style.color/lt1 "FFFFFF") "\"/></a:lt1>"
         "<a:dk2><a:srgbClr val=\"" (theme-color colors :office-style.color/dk2 "334155") "\"/></a:dk2>"
         "<a:lt2><a:srgbClr val=\"" (theme-color colors :office-style.color/lt2 "F7F8FB") "\"/></a:lt2>"
         "<a:accent1><a:srgbClr val=\"" (theme-color colors :office-style.color/accent1 "496B9A") "\"/></a:accent1>"
         "<a:accent2><a:srgbClr val=\"" (theme-color colors :office-style.color/accent2 "7C9A4B") "\"/></a:accent2>"
         "<a:accent3><a:srgbClr val=\"" (theme-color colors :office-style.color/accent3 "B46A55") "\"/></a:accent3>"
         "<a:accent4><a:srgbClr val=\"" (theme-color colors :office-style.color/accent4 "5C6F7E") "\"/></a:accent4>"
         "<a:accent5><a:srgbClr val=\"" (theme-color colors :office-style.color/accent5 "8A6F3D") "\"/></a:accent5>"
         "<a:accent6><a:srgbClr val=\"" (theme-color colors :office-style.color/accent6 "6A5A8E") "\"/></a:accent6>"
         "<a:hlink><a:srgbClr val=\"" (theme-color colors :office-style.color/hlink "315D8C") "\"/></a:hlink>"
         "<a:folHlink><a:srgbClr val=\"" (theme-color colors :office-style.color/folHlink "6A5A8E") "\"/></a:folHlink>"
         "</a:clrScheme>\n"
         "<a:fontScheme name=\"kotoba\"><a:majorFont><a:latin typeface=\""
         (theme-font fonts :office-style.font/majorFont "Aptos Display")
         "\"/></a:majorFont><a:minorFont><a:latin typeface=\""
         (theme-font fonts :office-style.font/minorFont "Aptos")
         "\"/></a:minorFont></a:fontScheme>\n"
         "<a:fmtScheme name=\"kotoba\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>"
         "<a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>"
         "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>"
         "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst>"
         "</a:fmtScheme>\n"
         "</a:themeElements>\n"
         "</a:theme>")))

(defn- master-background [deck]
  (hex-color (:slides/background (design/master deck)) "FFFFFF"))

(defn- slide-master [deck]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">
  <p:cSld name=\"kotoba\"><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"" (master-background deck) "\"/></a:solidFill></p:bgPr></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>
  <p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>
  <p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>
</p:sldMaster>"))

(def slide-master-rels
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type rel-slide-layout :target "../slideLayouts/slideLayout1.xml"})
    (ooxml/relationship {:id "rId2" :type rel-theme :target "../theme/theme1.xml"})]))

(defn- slide-layout [deck]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" preserve=\"1\">
  <p:cSld name=\"Blank\"><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>"))

(def slide-layout-rels
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type rel-slide-master :target "../slideMasters/slideMaster1.xml"})]))

(defn- shape-xfrm [{:slides/keys [x y w h]}]
  (str "<a:xfrm><a:off x=\"" (emu (numeric x 0)) "\" y=\"" (emu (numeric y 0)) "\"/>"
       "<a:ext cx=\"" (emu (positive-numeric w 1)) "\" cy=\"" (emu (positive-numeric h 1)) "\"/></a:xfrm>"))

(defn- font-face [deck major?]
  (get (design/fonts deck)
       (if major? :office-style.font/majorFont :office-style.font/minorFont)
       (if major? "Aptos Display" "Aptos")))

(defn- text-shape [deck idx {:slides/keys [id text font-size color bold] :as shape}]
  (str "<p:sp><p:nvSpPr><p:cNvPr id=\"" (+ 10 idx) "\" name=\"" (esc (or id (str "Text " idx))) "\"/>"
       "<p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
       "<p:spPr>" (shape-xfrm shape) "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>"
       "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>"
       "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"" (* 100 (long (positive-numeric font-size 24))) "\""
       (when bold " b=\"1\"")
       "><a:latin typeface=\"" (esc (font-face deck (>= (positive-numeric font-size 24) 30))) "\"/>"
       "<a:solidFill><a:srgbClr val=\"" (hex-color color "17202A") "\"/></a:solidFill>"
       "</a:rPr><a:t>" (esc text) "</a:t></a:r></a:p>"
       "</p:txBody></p:sp>"))

(defn- rect-shape [idx {:slides/keys [id fill line] :as shape}]
  (str "<p:sp><p:nvSpPr><p:cNvPr id=\"" (+ 10 idx) "\" name=\"" (esc (or id (str "Rect " idx))) "\"/>"
       "<p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
       "<p:spPr>" (shape-xfrm shape)
       "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>"
       "<a:solidFill><a:srgbClr val=\"" (hex-color fill "EAF0F8") "\"/></a:solidFill>"
       "<a:ln w=\"12700\"><a:solidFill><a:srgbClr val=\"" (hex-color line "496B9A") "\"/></a:solidFill></a:ln>"
       "</p:spPr></p:sp>"))

(defn- render-shape [deck idx shape]
  (let [shape (design/resolve-shape deck shape)]
    (case (:slides/shape shape)
    :rect (rect-shape idx shape)
    :text (text-shape deck idx shape)
    (text-shape deck idx (assoc shape :slides/text (or (:slides/text shape) (:slides/title shape) ""))))))

(defn- guide-shapes [deck]
  (when (:slides/show-guides deck)
    (let [guides (design/guides deck)
          margin (get guides :slides/margin)
          w (positive-numeric (:slides/width deck) default-width-in)
          h (positive-numeric (:slides/height deck) default-height-in)
          left (:slides/x margin 0.65)
          top (:slides/y margin 0.55)
          right (- w (:slides/right margin 0.65))
          bottom (- h (:slides/bottom margin 0.48))]
      [{:slides/shape :rect :slides/id "guide-frame"
        :slides/x left :slides/y top :slides/w (- right left) :slides/h (- bottom top)
        :slides/fill "FFFFFF" :slides/line "D8DEE8"}])))

(defn- master-footer-shape [deck]
  (let [footer (:slides/footer (design/master deck))]
    (when (:slides/enabled footer)
      (assoc footer
             :slides/id "master-footer"
             :slides/shape :text
             :slides/text (or (:slides/text footer) (:slides/title deck ""))))))

(defn- slide-shapes [deck slide]
  (let [valid-shapes (when (sequential? (:slides/shapes slide))
                       (filterv map? (:slides/shapes slide)))
        own-shapes (if (seq valid-shapes)
                     valid-shapes
                     [{:slides/shape :text
                       :slides/id "title"
                       :slides/text (:slides/title slide)
                       :slides/x 0.8 :slides/y 0.8 :slides/w 8.4 :slides/h 1.0
                       :slides/font-size 32}])]
    (vec (concat (guide-shapes deck)
                own-shapes
                (when-let [footer (master-footer-shape deck)] [footer])))))

(defn- slide-xml [deck slide]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
       "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
       "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
       "<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"" (master-background deck) "\"/></a:solidFill></p:bgPr></p:bg>"
       "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
       "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
       (let [shapes (slide-shapes deck slide)]
         (if (seq shapes)
           (apply str (map-indexed (partial render-shape deck) shapes))
           (render-shape deck 0 {:slides/shape :text :slides/id "title" :slides/text (:slides/title slide) :slides/x 0.8 :slides/y 0.8 :slides/w 8.4 :slides/h 1.0 :slides/font-size 32})))
       "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"))

(def slide-rels
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type rel-slide-layout :target "../slideLayouts/slideLayout1.xml"})]))

(defn deck-slides [deck]
  (let [slides (:slides/slides deck)
        valid-slides (when (sequential? slides)
                       (filterv map? slides))]
    (if (seq valid-slides)
      valid-slides
      [{:slides/id "slide-1"
        :slides/title (:slides/title deck (:slides/id deck "Slides"))
        :slides/shapes []}])))

(defn pptx-files [deck]
  (let [slides (vec (deck-slides deck))
        width (positive-numeric (:slides/width deck) default-width-in)
        height (positive-numeric (:slides/height deck) default-height-in)]
    (vec
     (concat
      [["[Content_Types].xml" (content-types (count slides))]
       ["_rels/.rels" root-rels]
       ["docProps/core.xml" (core-props deck)]
       ["docProps/app.xml" (app-props (count slides))]
       ["ppt/presentation.xml" (presentation (count slides) width height)]
       ["ppt/_rels/presentation.xml.rels" (presentation-rels (count slides))]
       ["ppt/theme/theme1.xml" (theme-xml (design/theme deck))]
       ["ppt/slideMasters/slideMaster1.xml" (slide-master deck)]
       ["ppt/slideMasters/_rels/slideMaster1.xml.rels" slide-master-rels]
       ["ppt/slideLayouts/slideLayout1.xml" (slide-layout deck)]
       ["ppt/slideLayouts/_rels/slideLayout1.xml.rels" slide-layout-rels]]
      (mapcat (fn [[idx slide]]
                (let [n (inc idx)]
                  [[(str "ppt/slides/slide" n ".xml") (slide-xml deck slide)]
                   [(str "ppt/slides/_rels/slide" n ".xml.rels") slide-rels]]))
              (map-indexed vector slides))))))

#?(:clj
   (defn- add-entry! [^ZipOutputStream zip path content]
     (.putNextEntry zip (ZipEntry. path))
     (.write zip (.getBytes (str content) "UTF-8"))
     (.closeEntry zip)))

#?(:clj
   (defn- add-entry-bytes! [^ZipOutputStream zip path bytes]
     (.putNextEntry zip (ZipEntry. path))
     (.write zip ^bytes bytes)
     (.closeEntry zip)))

#?(:clj
   (defn- zip-entries-bytes [bytes]
     (with-open [zip (ZipInputStream. (ByteArrayInputStream. bytes))]
       (loop [entries {}]
         (if-let [entry (.getNextEntry zip)]
           (let [buf (byte-array 8192)
                 out (ByteArrayOutputStream.)]
             (loop []
               (let [n (.read zip buf)]
                 (when (pos? n)
                   (.write out buf 0 n)
                   (recur))))
             (recur (assoc entries (.getName entry) (.toByteArray out))))
           entries)))))

#?(:clj
   (defn- zip-bytes-from-entries [entries]
     (let [baos (ByteArrayOutputStream.)]
       (with-open [zip (ZipOutputStream. baos)]
         (doseq [[path content] entries]
           (add-entry-bytes! zip path content)))
       (.toByteArray baos))))

(defn- text-entry-bytes [s]
  #?(:clj (.getBytes (str s) "UTF-8")
     :cljs s))

(defn- bytes->text [bytes]
  #?(:clj (String. ^bytes bytes "UTF-8")
     :cljs bytes))

(declare replace-nth-element)

(defn- xml-attr [xml attr]
  (second (re-find (re-pattern (str "\\b" attr "=\"([^\"]*)\"")) (or xml ""))))

(defn- dirname [path]
  (if-let [idx (str/last-index-of (str path) "/")]
    (subs (str path) 0 idx)
    ""))

(defn- normalize-part-path [path]
  (->> (str/split (str/replace-first (str path) #"^/" "") #"/")
       (reduce (fn [parts part]
                 (case part
                   "" parts
                   "." parts
                   ".." (vec (butlast parts))
                   (conj parts part)))
               [])
       (str/join "/")))

(defn- resolve-part-target [source-part target]
  (let [target (str target)]
    (cond
      (str/blank? target) target
      (str/starts-with? target "/") (normalize-part-path target)
      (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" target) target
      :else (normalize-part-path (str (dirname source-part) "/" target)))))

(defn- rels-path [part-path]
  (let [path (str part-path)
        idx (str/last-index-of path "/")]
    (if idx
      (str (subs path 0 idx) "/_rels/" (subs path (inc idx)) ".rels")
      (str "_rels/" path ".rels"))))

(defn- relationships-from-entries [entries part-path]
  (let [rels-xml (some-> (entries (rels-path part-path)) bytes->text)]
    (into {}
          (keep (fn [tag]
                  (when-let [id (xml-attr tag "Id")]
                    (let [target (xml-attr tag "Target")]
                      [id {:id id
                           :type (xml-attr tag "Type")
                           :target target
                           :target-path (resolve-part-target part-path target)}]))))
          (re-seq #"<Relationship\b[^>]*/?>" (or rels-xml "")))))

(defn- workbook-sheet-paths [workbook-entries]
  (let [workbook-xml (bytes->text (get workbook-entries "xl/workbook.xml"))
        rels (relationships-from-entries workbook-entries "xl/workbook.xml")]
    (into {}
          (keep (fn [tag]
                  (let [name (xml-attr tag "name")
                        rel-id (xml-attr tag "r:id")
                        target (get-in rels [rel-id :target-path])]
                    (when (and name target)
                      [name target]))))
          (re-seq #"<sheet\b[^>]*/?>" (or workbook-xml "")))))

(defn- col->index [col]
  (reduce (fn [acc ch]
            (+ (* acc 26) (- (int ch) 64)))
          0
          (str/upper-case (str col))))

(defn- index->col [idx]
  (loop [n idx
         out ""]
    (if (pos? n)
      (let [n' (dec n)
            ch (char (+ 65 (mod n' 26)))]
        (recur (quot n' 26) (str ch out)))
      out)))

(defn- cell-ref-parts [ref]
  (when-let [[_ col row] (re-matches #"([A-Za-z]+)(\d+)" (str ref))]
    {:col col
     :col-index (col->index col)
     :row #?(:clj (Long/parseLong row)
             :cljs (js/parseInt row 10))}))

(defn- offset-cell-ref [anchor row-idx col-idx]
  (let [{:keys [col-index row]} (cell-ref-parts anchor)]
    (str (index->col (+ col-index col-idx)) (+ row row-idx))))

(defn- cell-value-xml [ref value]
  (cond
    (nil? value)
    (str "<c r=\"" ref "\"/>")

    (number? value)
    (str "<c r=\"" ref "\"><v>" value "</v></c>")

    (boolean? value)
    (str "<c r=\"" ref "\" t=\"b\"><v>" (if value 1 0) "</v></c>")

    :else
    (str "<c r=\"" ref "\" t=\"inlineStr\"><is><t>" (esc value) "</t></is></c>")))

(defn- patch-sheet-cell [sheet-xml ref value]
  (let [cell-xml (cell-value-xml ref value)
        row (some-> (cell-ref-parts ref) :row)
        cell-pattern (re-pattern (str "<c\\b(?=[^>]*\\br=\"" ref "\")[\\s\\S]*?</c>"))
        row-pattern (re-pattern (str "<row\\b(?=[^>]*\\br=\"" row "\")[\\s\\S]*?</row>"))]
    (cond
      (re-find cell-pattern sheet-xml)
      (str/replace-first sheet-xml cell-pattern cell-xml)

      (and row (re-find row-pattern sheet-xml))
      (str/replace-first sheet-xml row-pattern
                         (fn [row-xml]
                           (str/replace row-xml #"</row>\s*$" (str cell-xml "</row>"))))

      (str/includes? sheet-xml "</sheetData>")
      (str/replace-first sheet-xml #"</sheetData>"
                         (str "<row r=\"" row "\">" cell-xml "</row></sheetData>"))

      :else sheet-xml)))

(defn- chart-data-cells [{:keys [sheet anchor rows cells]}]
  (let [sheet (or sheet "Sheet1")
        anchor (or anchor "A1")
        row-cells (for [[r row] (map-indexed vector rows)
                        [c value] (map-indexed vector row)]
                    {:sheet sheet
                     :ref (offset-cell-ref anchor r c)
                     :value value})
        explicit-cells (for [[ref value] cells
                             :let [[sheet-name cell-ref] (if (str/includes? (str ref) "!")
                                                           (str/split (str ref) #"!" 2)
                                                           [sheet (str ref)])]]
                         {:sheet sheet-name
                          :ref cell-ref
                          :value value})]
    (concat row-cells explicit-cells)))

#?(:clj
   (defn- patch-workbook-bytes [workbook-bytes chart-data]
     (let [workbook-entries (zip-entries-bytes workbook-bytes)
           sheet-paths (workbook-sheet-paths workbook-entries)
           by-sheet (group-by :sheet (chart-data-cells chart-data))
           patched (reduce (fn [entries [sheet cells]]
                             (if-let [path (get sheet-paths sheet)]
                               (if-let [sheet-bytes (get entries path)]
                                 (let [patched-xml (reduce (fn [xml {:keys [ref value]}]
                                                             (patch-sheet-cell xml ref value))
                                                           (bytes->text sheet-bytes)
                                                           cells)]
                                   (assoc entries path (text-entry-bytes patched-xml)))
                                 entries)
                               entries))
                           workbook-entries
                           by-sheet)]
       (zip-bytes-from-entries patched))))

(defn- cache-pt [idx value numeric?]
  (str "<c:pt idx=\"" idx "\">"
       (when numeric? "<c:v>")
       (if numeric? value (str "<c:v>" (esc value) "</c:v>"))
       (when numeric? "</c:v>")
       "</c:pt>"))

(defn- str-cache [values]
  (str "<c:strCache><c:ptCount val=\"" (count values) "\"/>"
       (apply str (map-indexed #(cache-pt %1 %2 false) values))
       "</c:strCache>"))

(defn- num-cache [values]
  (str "<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"" (count values) "\"/>"
       (apply str (map-indexed #(cache-pt %1 %2 true) values))
       "</c:numCache>"))

(defn- chart-series-from-rows [{:keys [rows]}]
  (when (and (seq rows) (> (count (first rows)) 1))
    (let [headers (vec (first rows))
          body (vec (rest rows))
          categories (mapv first body)]
      (mapv (fn [idx]
              {:name (nth headers idx)
               :categories categories
               :values (mapv #(nth % idx nil) body)})
            (range 1 (count headers))))))

(defn- patch-chart-series-block [block {:keys [name categories values]}]
  (let [tx (str "<c:tx><c:v>" (esc name) "</c:v></c:tx>")
        cat (str "<c:cat><c:strRef>" (str-cache categories) "</c:strRef></c:cat>")
        val (str "<c:val><c:numRef>" (num-cache values) "</c:numRef></c:val>")]
    (-> block
        (cond-> (re-find #"<c:tx\b[\s\S]*?</c:tx>" block)
          (str/replace-first #"<c:tx\b[\s\S]*?</c:tx>" tx))
        (cond-> (re-find #"<c:cat\b[\s\S]*?</c:cat>" block)
          (str/replace-first #"<c:cat\b[\s\S]*?</c:cat>" cat))
        (cond-> (re-find #"<c:val\b[\s\S]*?</c:val>" block)
          (str/replace-first #"<c:val\b[\s\S]*?</c:val>" val)))))

(defn- patch-chart-xml [chart-xml chart-data]
  (if-let [series (seq (chart-series-from-rows chart-data))]
    (reduce (fn [xml [idx data]]
              (replace-nth-element xml "c:ser" idx #(patch-chart-series-block % data)))
            chart-xml
            (map-indexed vector series))
    chart-xml))

(defn pptx-bytes
  "Returns a JVM byte array containing a .pptx generated from an EDN deck map."
  [deck]
  #?(:clj
     (let [baos (ByteArrayOutputStream.)]
       (with-open [zip (ZipOutputStream. baos)]
         (doseq [[path content] (pptx-files deck)]
           (add-entry! zip path content)))
       (.toByteArray baos))
     :cljs
     (throw (ex-info "pptx byte writing requires a host zip implementation" {:feature :slides/pptx}))))

(defn- xml-elements [xml tag]
  (re-seq (re-pattern (str "<" tag "\\b[\\s\\S]*?</" tag ">")) (or xml "")))

(defn- replace-at [s old new]
  (let [idx (str/index-of s old)]
    (if (nil? idx)
      s
      (str (subs s 0 idx) new (subs s (+ idx (count old)))))))

(defn- replace-nth-element [xml tag idx f]
  (let [blocks (vec (xml-elements xml tag))]
    (if-let [block (get blocks idx)]
      (replace-at xml block (f block))
      xml)))

(defn- patch-or-insert-xfrm [block shape]
  (let [xfrm (shape-xfrm shape)]
    (if (re-find #"<a:xfrm\b[\s\S]*?</a:xfrm>" block)
      (str/replace-first block #"<a:xfrm\b[\s\S]*?</a:xfrm>" xfrm)
      (str/replace-first block #"<p:spPr\b([^>]*)>"
                         (str "<p:spPr$1>" xfrm)))))

(defn- patch-text [block shape]
  (if (contains? shape :slides/text)
    (if (re-find #"<a:t\b[^>]*>[\s\S]*?</a:t>" block)
      (str/replace-first block #"<a:t\b[^>]*>[\s\S]*?</a:t>"
                         (str "<a:t>" (esc (:slides/text shape)) "</a:t>"))
      block)
    block))

(defn- patch-font-size [block shape]
  (if-let [size (:slides/font-size shape)]
    (let [sz (* 100 (long (positive-numeric size 24)))]
      (if (re-find #"<a:rPr\b[^>]*\bsz=\"[^\"]*\"" block)
        (str/replace-first block #"(<a:rPr\b[^>]*\bsz=\")[^\"]*(\")"
                           (str "$1" sz "$2"))
        block))
    block))

(defn- patch-color-val [block color]
  (if color
    (if (re-find #"<a:srgbClr\b[^>]*\bval=\"[0-9A-Fa-f]{6}\"" block)
      (str/replace-first block #"(<a:srgbClr\b[^>]*\bval=\")[0-9A-Fa-f]{6}(\")"
                         (str "$1" (hex-color color "17202A") "$2"))
      block)
    block))

(defn- patch-text-color [block shape]
  (if (:slides/color shape)
    (patch-color-val block (:slides/color shape))
    block))

(defn- patch-solid-fill [block shape]
  (if (:slides/fill shape)
    (if (re-find #"<a:solidFill\b[\s\S]*?</a:solidFill>" block)
      (str/replace-first block #"<a:solidFill\b[\s\S]*?</a:solidFill>"
                         (str "<a:solidFill><a:srgbClr val=\""
                              (hex-color (:slides/fill shape) "EAF0F8")
                              "\"/></a:solidFill>"))
      block)
    block))

(defn- patch-line-fill [block shape]
  (if (:slides/line shape)
    (if (re-find #"<a:ln\b[\s\S]*?</a:ln>" block)
      (str/replace-first block #"<a:ln\b[\s\S]*?</a:ln>"
                         (str "<a:ln><a:solidFill><a:srgbClr val=\""
                              (hex-color (:slides/line shape) "496B9A")
                              "\"/></a:solidFill></a:ln>"))
      block)
    block))

(defn- patch-shape-block [block shape]
  (-> block
      (patch-or-insert-xfrm shape)
      (patch-text shape)
      (patch-font-size shape)
      (patch-text-color shape)
      (patch-solid-fill shape)
      (patch-line-fill shape)))

(defn- source-tag [kind]
  (case kind
    :p/sp "p:sp"
    :p/pic "p:pic"
    :p/graphicFrame "p:graphicFrame"
    :a/tbl "a:tbl"
    :fallback/text nil
    nil))

(defn- patch-slide-xml [xml shapes]
  (reduce (fn [acc shape]
            (let [source (:ooxml/source shape)
                  tag (source-tag (:ooxml/kind source))
                  idx (:ooxml/index source)]
              (if (and tag (integer? idx))
                (replace-nth-element acc tag idx #(patch-shape-block % shape))
                acc)))
          xml
          shapes))

(defn- patchable-shapes [deck]
  (->> (deck-slides deck)
       (mapcat :slides/shapes)
       (filter #(get-in % [:ooxml/source :ooxml/part]))
       vec))

(defn- patch-base-entries [entries deck]
  (let [by-part (group-by #(get-in % [:ooxml/source :ooxml/part])
                          (patchable-shapes deck))]
    (reduce (fn [acc [part shapes]]
              (if-let [bytes (get acc part)]
                (let [patched (patch-slide-xml (bytes->text bytes) shapes)]
                  (assoc acc part (text-entry-bytes patched)))
                acc))
            entries
            by-part)))

(defn- chart-data-shapes [deck]
  (->> (deck-slides deck)
       (mapcat :slides/shapes)
       (filter #(and (:slides/chart-data %)
                     (:slides/chart-part %)))
       vec))

#?(:clj
   (defn- patch-chart-data-entries [entries deck]
     (reduce (fn [acc shape]
               (let [chart-data (:slides/chart-data shape)
                     chart-part (:slides/chart-part shape)
                     workbook-part (:slides/workbook-part shape)
                     acc (if-let [chart-bytes (get acc chart-part)]
                           (assoc acc chart-part
                                  (text-entry-bytes
                                   (patch-chart-xml (bytes->text chart-bytes) chart-data)))
                           acc)]
                 (if-let [workbook-bytes (get acc workbook-part)]
                   (assoc acc workbook-part (patch-workbook-bytes workbook-bytes chart-data))
                   acc)))
             entries
             (chart-data-shapes deck))))

(defn update-pptx-bytes
  "Returns .pptx bytes for deck EDN.

  If imported shapes carry :ooxml/source locators, this patches the matching
  source slide XML parts in the base package and preserves unrelated OOXML
  entries. Decks without locators still fall back to normalized regeneration."
  [base-bytes deck]
  #?(:clj
     (let [patches (patchable-shapes deck)]
       (if (seq patches)
         (-> (zip-entries-bytes base-bytes)
             (patch-base-entries deck)
             (patch-chart-data-entries deck)
             zip-bytes-from-entries)
         (pptx-bytes deck)))
     :cljs
     (pptx-bytes deck)))

(defn write-pptx!
  "Writes a .pptx generated from an EDN deck map. JVM only."
  [path deck]
  #?(:clj
     (let [bytes (pptx-bytes deck)]
       (with-open [out (FileOutputStream. (str path))]
         (.write out bytes))
       {:slides/path (str path)
        :slides/bytes (alength bytes)
        :slides/slides (count (deck-slides deck))})
     :cljs
     (throw (ex-info "write-pptx! requires a host file implementation" {:feature :slides/pptx}))))

(defn update-pptx!
  "Writes an updated .pptx from base path and deck EDN. JVM only."
  [in-path out-path deck]
  #?(:clj
     (let [base (java.nio.file.Files/readAllBytes
                 (java.nio.file.Path/of (str in-path) (into-array String [])))
           bytes (update-pptx-bytes base deck)]
       (with-open [out (FileOutputStream. (str out-path))]
         (.write out bytes))
       {:slides/path (str out-path)
        :slides/bytes (alength bytes)
        :slides/slides (count (deck-slides deck))})
     :cljs
     (throw (ex-info "update-pptx! requires a host file implementation" {:feature :slides/pptx}))))
