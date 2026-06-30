(ns slides.pptx
  "EDN to minimal PowerPoint Open XML package writer.

  The public surface is data-first: pass a deck map with :slides/slides and
  receive a .pptx byte array or write it to disk on the JVM."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.io ByteArrayOutputStream FileOutputStream]
                   [java.util.zip ZipEntry ZipOutputStream])))

(def emu-per-inch 914400)
(def default-width-in 10)
(def default-height-in 5.625)

(defn- esc [x]
  (-> (str (or x ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- emu [inches]
  (long (Math/round (* emu-per-inch (double (or inches 0))))))

(defn- hex-color [x fallback]
  (let [s (-> (or x fallback) str (str/replace #"^#" "") str/upper-case)]
    (if (re-matches #"[0-9A-F]{6}" s) s fallback)))

(defn- content-types [slide-count]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
       "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
       "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
       "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
       "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
       "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>"
       "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>"
       "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>"
       "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>"
       (apply str
              (for [idx (range 1 (inc slide-count))]
                (str "<Override PartName=\"/ppt/slides/slide" idx ".xml\" "
                     "ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")))
       "</Types>"))

(def root-rels
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">
  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>
  <Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>
  <Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>
</Relationships>")

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
       "<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
       "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
       "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
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
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
       "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>"
       (apply str
              (for [idx (range 1 (inc slide-count))]
                (str "<Relationship Id=\"rId" (inc idx) "\" "
                     "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" "
                     "Target=\"slides/slide" idx ".xml\"/>")))
       "</Relationships>"))

(def theme
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"kotoba\">
  <a:themeElements>
    <a:clrScheme name=\"kotoba\"><a:dk1><a:srgbClr val=\"17202A\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"334155\"/></a:dk2><a:lt2><a:srgbClr val=\"F7F8FB\"/></a:lt2><a:accent1><a:srgbClr val=\"496B9A\"/></a:accent1><a:accent2><a:srgbClr val=\"7C9A4B\"/></a:accent2><a:accent3><a:srgbClr val=\"B46A55\"/></a:accent3><a:accent4><a:srgbClr val=\"5C6F7E\"/></a:accent4><a:accent5><a:srgbClr val=\"8A6F3D\"/></a:accent5><a:accent6><a:srgbClr val=\"6A5A8E\"/></a:accent6><a:hlink><a:srgbClr val=\"315D8C\"/></a:hlink><a:folHlink><a:srgbClr val=\"6A5A8E\"/></a:folHlink></a:clrScheme>
    <a:fontScheme name=\"kotoba\"><a:majorFont><a:latin typeface=\"Aptos Display\"/></a:majorFont><a:minorFont><a:latin typeface=\"Aptos\"/></a:minorFont></a:fontScheme>
    <a:fmtScheme name=\"kotoba\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>
  </a:themeElements>
</a:theme>")

(def slide-master
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">
  <p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></p:bgPr></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>
  <p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>
  <p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>
</p:sldMaster>")

(def slide-master-rels
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">
  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>
  <Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>
</Relationships>")

(def slide-layout
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" preserve=\"1\">
  <p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>")

(def slide-layout-rels
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">
  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>
</Relationships>")

(defn- shape-xfrm [{:slides/keys [x y w h]}]
  (str "<a:xfrm><a:off x=\"" (emu (or x 0)) "\" y=\"" (emu (or y 0)) "\"/>"
       "<a:ext cx=\"" (emu (or w 1)) "\" cy=\"" (emu (or h 1)) "\"/></a:xfrm>"))

(defn- text-shape [idx {:slides/keys [id text font-size color] :as shape}]
  (str "<p:sp><p:nvSpPr><p:cNvPr id=\"" (+ 10 idx) "\" name=\"" (esc (or id (str "Text " idx))) "\"/>"
       "<p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
       "<p:spPr>" (shape-xfrm shape) "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>"
       "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>"
       "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"" (* 100 (long (or font-size 24))) "\">"
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

(defn- render-shape [idx shape]
  (case (:slides/shape shape)
    :rect (rect-shape idx shape)
    :text (text-shape idx shape)
    (text-shape idx (assoc shape :slides/text (or (:slides/text shape) (:slides/title shape) "")))))

(defn- slide-xml [slide]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
       "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
       "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
       "<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></p:bgPr></p:bg>"
       "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
       "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
       (if (seq (:slides/shapes slide))
         (apply str (map-indexed render-shape (:slides/shapes slide)))
         (render-shape 0 {:slides/shape :text :slides/id "title" :slides/text (:slides/title slide) :slides/x 0.8 :slides/y 0.8 :slides/w 8.4 :slides/h 1.0 :slides/font-size 32}))
       "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"))

(def slide-rels
  "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">
  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>
</Relationships>")

(defn- deck-slides [deck]
  (let [slides (:slides/slides deck)]
    (if (seq slides)
      slides
      [{:slides/id "slide-1"
        :slides/title (:slides/title deck (:slides/id deck "Slides"))
        :slides/shapes []}])))

#?(:clj
   (defn- add-entry! [^ZipOutputStream zip path content]
     (.putNextEntry zip (ZipEntry. path))
     (.write zip (.getBytes (str content) "UTF-8"))
     (.closeEntry zip)))

(defn pptx-bytes
  "Returns a JVM byte array containing a .pptx generated from an EDN deck map."
  [deck]
  #?(:clj
     (let [slides (vec (deck-slides deck))
           width (:slides/width deck default-width-in)
           height (:slides/height deck default-height-in)
           baos (ByteArrayOutputStream.)]
       (with-open [zip (ZipOutputStream. baos)]
         (add-entry! zip "[Content_Types].xml" (content-types (count slides)))
         (add-entry! zip "_rels/.rels" root-rels)
         (add-entry! zip "docProps/core.xml" (core-props deck))
         (add-entry! zip "docProps/app.xml" (app-props (count slides)))
         (add-entry! zip "ppt/presentation.xml" (presentation (count slides) width height))
         (add-entry! zip "ppt/_rels/presentation.xml.rels" (presentation-rels (count slides)))
         (add-entry! zip "ppt/theme/theme1.xml" theme)
         (add-entry! zip "ppt/slideMasters/slideMaster1.xml" slide-master)
         (add-entry! zip "ppt/slideMasters/_rels/slideMaster1.xml.rels" slide-master-rels)
         (add-entry! zip "ppt/slideLayouts/slideLayout1.xml" slide-layout)
         (add-entry! zip "ppt/slideLayouts/_rels/slideLayout1.xml.rels" slide-layout-rels)
         (doseq [[idx slide] (map-indexed vector slides)]
           (let [n (inc idx)]
             (add-entry! zip (str "ppt/slides/slide" n ".xml") (slide-xml slide))
             (add-entry! zip (str "ppt/slides/_rels/slide" n ".xml.rels") slide-rels))))
       (.toByteArray baos))
     :cljs
     (throw (ex-info "pptx byte writing requires a host zip implementation" {:feature :slides/pptx}))))

(defn update-pptx-bytes
  "Returns .pptx bytes for deck EDN.

  The current writer emits a complete normalized PPTX package from the supplied
  deck data. The base bytes are accepted so callers can use the same command
  shape for create/update workflows while the CLJC writer grows richer patch
  support."
  [_base-bytes deck]
  (pptx-bytes deck))

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
