(ns slides.pptx
  "EDN to minimal PowerPoint Open XML package writer.

  The public surface is data-first: pass a deck map with :slides/slides and
  receive a .pptx byte array or write it to disk on the JVM."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [drawingml.core :as dml]
            [ooxml.core :as ooxml]
            [presentationml.core :as pml]
            [slides.design :as design]
            [xml.core :as xcore])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream FileOutputStream]
                   [java.util.regex Matcher]
                   [java.util.zip ZipEntry ZipInputStream ZipOutputStream])))

(def emu-per-inch 914400)
(def default-width-in 10)
(def default-height-in 5.625)
(def rel-slide-master "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster")
(def rel-slide "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide")
(def rel-slide-layout "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout")
(def rel-theme "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme")
(def rel-image "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image")
(def rel-chart "http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart")
(def rel-package "http://schemas.openxmlformats.org/officeDocument/2006/relationships/package")
(def rel-notes-slide "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide")
(def rel-notes-master "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster")
(def rel-comments "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments")
(def rel-handout-master "http://schemas.openxmlformats.org/officeDocument/2006/relationships/handoutMaster")
(def rel-custom-xml "http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXml")
(def rel-custom-xml-props "http://schemas.openxmlformats.org/officeDocument/2006/relationships/customXmlProps")
(def rel-hyperlink "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink")
(def rel-core-props "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties")
(def rel-app-props "http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties")

(def media-extensions
  {"image/png" "png" "image/jpeg" "jpg" "image/jpg" "jpg" "image/gif" "gif"
   "image/bmp" "bmp" "image/svg+xml" "svg" "image/tiff" "tiff" "image/webp" "webp"})

(defn- media-extension [media-type]
  (get media-extensions media-type "png"))

(defn- decode-base64
  "nil (not an exception) on malformed input -- callers treat a shape whose
  image data doesn't decode as if it had none, falling back to a plain text
  box instead of embedding corrupt bytes or crashing the export. CLJS always
  returns nil: byte-producing export (pptx-bytes) is JVM-only already (see
  below), so there is no host zip to embed decoded bytes into there yet."
  [s]
  #?(:clj (try
            (.decode (java.util.Base64/getDecoder) (str s))
            (catch Exception _ nil))
     :cljs nil))

(defn- esc [x]
  (-> (str (or x ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- hidden-attr
  "A shape's own :slides/hidden (from drawingml.parse/shape-hidden? on
  import) into <p:cNvPr>'s own hidden=\"1\" attribute -- \"\" (no
  attribute at all) when absent, unchanged from before this feature
  existed."
  [shape]
  (when (:slides/hidden shape) " hidden=\"1\""))

(defn- replacement-literal [s]
  #?(:clj (Matcher/quoteReplacement (str s))
     :cljs (str s)))

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

(defn- content-types
  ([slide-count] (content-types slide-count [] [] [] 1 1 [] false 0))
  ([slide-count media-extensions-used chart-paths notes-slide-paths]
   (content-types slide-count media-extensions-used chart-paths notes-slide-paths 1 1 [] false 0))
  ([slide-count media-extensions-used chart-paths notes-slide-paths master-count]
   (content-types slide-count media-extensions-used chart-paths notes-slide-paths master-count master-count [] false 0))
  ([slide-count media-extensions-used chart-paths notes-slide-paths master-count layout-count]
   (content-types slide-count media-extensions-used chart-paths notes-slide-paths master-count layout-count [] false 0))
  ([slide-count media-extensions-used chart-paths notes-slide-paths master-count layout-count comment-paths]
   (content-types slide-count media-extensions-used chart-paths notes-slide-paths master-count layout-count comment-paths false 0))
  ([slide-count media-extensions-used chart-paths notes-slide-paths master-count layout-count comment-paths has-handout-master?]
   (content-types slide-count media-extensions-used chart-paths notes-slide-paths master-count layout-count comment-paths has-handout-master? 0))
  ([slide-count media-extensions-used chart-paths notes-slide-paths master-count layout-count comment-paths has-handout-master? custom-xml-count]
   (ooxml/content-types-xml
    (concat
     [(ooxml/default-content-type "rels" (:rels ooxml/content-types))
      (ooxml/default-content-type "xml" (:xml ooxml/content-types))
      (ooxml/override-content-type "/docProps/app.xml" "application/vnd.openxmlformats-officedocument.extended-properties+xml")
      (ooxml/override-content-type "/docProps/core.xml" "application/vnd.openxmlformats-package.core-properties+xml")
      (ooxml/override-content-type "/ppt/presentation.xml" (:pptx ooxml/content-types))
      (ooxml/override-content-type "/ppt/theme/theme1.xml" "application/vnd.openxmlformats-officedocument.theme+xml")]
     (for [idx (range 1 (inc master-count))]
       (ooxml/override-content-type (str "/ppt/slideMasters/slideMaster" idx ".xml")
                                    "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"))
     (for [idx (range 1 (inc layout-count))]
       (ooxml/override-content-type (str "/ppt/slideLayouts/slideLayout" idx ".xml")
                                    "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"))
     (for [idx (range 1 (inc slide-count))]
       (ooxml/override-content-type (str "/ppt/slides/slide" idx ".xml")
                                    "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"))
     (for [[extension media-type] (into {} (map (fn [mt] [(media-extension mt) mt])) media-extensions-used)]
       (ooxml/default-content-type extension media-type))
     (when (seq chart-paths)
       [(ooxml/default-content-type "xlsx" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")])
     (for [path chart-paths]
       (ooxml/override-content-type (str "/" path) "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"))
     (when (seq notes-slide-paths)
       [(ooxml/override-content-type "/ppt/notesMasters/notesMaster1.xml" "application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml")])
     (for [path notes-slide-paths]
       (ooxml/override-content-type (str "/" path) "application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"))
     (when (seq comment-paths)
       [(ooxml/override-content-type "/ppt/commentAuthors.xml" "application/vnd.openxmlformats-officedocument.presentationml.commentAuthors+xml")])
     (for [path comment-paths]
       (ooxml/override-content-type (str "/" path) "application/vnd.openxmlformats-officedocument.presentationml.comments+xml"))
     (when has-handout-master?
       [(ooxml/override-content-type "/ppt/handoutMasters/handoutMaster1.xml" "application/vnd.openxmlformats-officedocument.presentationml.handoutMaster+xml")])
     (for [idx (range 1 (inc custom-xml-count))]
       (ooxml/override-content-type (str "/customXml/itemProps" idx ".xml")
                                    "application/vnd.openxmlformats-officedocument.customXmlProperties+xml"))))))

(def root-rels
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type ooxml/office-document-rel :target "ppt/presentation.xml"})
    (ooxml/relationship {:id "rId2" :type rel-core-props :target "docProps/core.xml"})
    (ooxml/relationship {:id "rId3" :type rel-app-props :target "docProps/app.xml"})]))

(defn- core-props
  "docProps/core.xml. Beyond dc:title/dc:creator (always present -- the
  latter defaults to this package's own name, unchanged from before, but
  now yields to a deck-supplied :slides/author when present so a re-
  exported imported deck keeps its original author instead of losing it
  to the tool-branding default), every other Dublin Core field is OPTIONAL
  and simply omitted when the deck doesn't carry it -- a deck with no
  extended metadata (this package's own synthetic decks, the common case)
  produces byte-for-byte the same core.xml as before this feature."
  [deck]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
       "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
       "xmlns:dcterms=\"http://purl.org/dc/terms/\" "
       "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" "
       "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
       "<dc:title>" (esc (:slides/title deck (:slides/id deck "slides"))) "</dc:title>"
       "<dc:creator>" (esc (:slides/author deck "kotoba-lang/slides")) "</dc:creator>"
       (when-let [subject (:slides/subject deck)] (str "<dc:subject>" (esc subject) "</dc:subject>"))
       (when-let [keywords (:slides/keywords deck)] (str "<cp:keywords>" (esc keywords) "</cp:keywords>"))
       (when-let [category (:slides/category deck)] (str "<cp:category>" (esc category) "</cp:category>"))
       (when-let [lmb (:slides/last-modified-by deck)] (str "<cp:lastModifiedBy>" (esc lmb) "</cp:lastModifiedBy>"))
       (when-let [created (:slides/created deck)] (str "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" (esc created) "</dcterms:created>"))
       (when-let [modified (:slides/modified deck)] (str "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" (esc modified) "</dcterms:modified>"))
       "</cp:coreProperties>"))

(defn- app-props
  "docProps/app.xml. :slides/company/:slides/manager are optional and
  omitted when the deck doesn't carry them, same as core-props' fields."
  [deck slide-count]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" "
       "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
       "<Application>kotoba-lang/slides</Application>"
       (when-let [company (:slides/company deck)] (str "<Company>" (esc company) "</Company>"))
       (when-let [manager (:slides/manager deck)] (str "<Manager>" (esc manager) "</Manager>"))
       "<PresentationFormat>On-screen Show (16:9)</PresentationFormat>"
       "<Slides>" slide-count "</Slides>"
       "</Properties>"))

(defn- section-xml
  "One <p14:section> from a deck's own :slides/sections entry ({:name ...
  :slide-indices [...]}, the same shape presentationml.parse/sections
  already produces on import) -- id is a fixed-but-distinct placeholder
  GUID (PowerPoint tolerates any well-formed GUID; its own refresh cycle
  doesn't depend on a globally-unique value here, same convention as
  slides.pptx/field-id), slide-indices convert back to the same 256+idx
  sldId formula presentation's own <p:sldIdLst> uses."
  [idx {:keys [name slide-indices]}]
  (str "<p14:section name=\"" (esc (or name (str "Section " (inc idx))))
       "\" id=\"{00000000-0000-0000-0000-" (format "%012d" (inc idx)) "}\">"
       "<p14:sldIdLst>"
       (apply str (for [slide-idx slide-indices] (str "<p14:sldId id=\"" (+ 256 slide-idx) "\"/>")))
       "</p14:sldIdLst></p14:section>"))

(defn- sections-ext-xml
  "A deck's own :slides/sections (Insert > Section in PowerPoint's UI, a
  common organizational/navigation aid for longer decks) into
  <p:extLst>'s PowerPoint-2010 <p14:sectionLst> extension -- \"\" (no
  element at all) when the deck has no sections, the common case.
  Previously a sectioned deck always round-tripped losing that
  organization completely; slide content itself was unaffected either
  way."
  [sections]
  (if (seq sections)
    (str "<p:extLst><p:ext uri=\"{521415D9-36F7-43E2-AB2F-B90AF26B5E84}\">"
         "<p14:sectionLst xmlns:p14=\"http://schemas.microsoft.com/office/powerpoint/2010/main\">"
         (apply str (map-indexed section-xml sections))
         "</p14:sectionLst></p:ext></p:extLst>")
    ""))

(defn- presentation
  ([slide-count width height] (presentation slide-count width height 1 nil))
  ([slide-count width height master-count] (presentation slide-count width height master-count nil))
  ([slide-count width height master-count sections]
   (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        "<p:presentation xmlns:a=\"" dml/ns-a "\" "
        "xmlns:r=\"" pml/ns-r "\" "
        "xmlns:p=\"" pml/ns-p "\">"
        "<p:sldMasterIdLst>"
        (apply str
               (for [idx (range 1 (inc master-count))]
                 (str "<p:sldMasterId id=\"" (+ 2147483648 idx -1) "\" r:id=\"rId" idx "\"/>")))
        "</p:sldMasterIdLst>"
        "<p:sldIdLst>"
        (apply str
               (for [idx (range 1 (inc slide-count))]
                 (str "<p:sldId id=\"" (+ 255 idx) "\" r:id=\"rId" (+ master-count idx) "\"/>")))
        "</p:sldIdLst>"
        "<p:sldSz cx=\"" (emu width) "\" cy=\"" (emu height) "\" type=\"wide\"/>"
        "<p:notesSz cx=\"6858000\" cy=\"9144000\"/>"
        (sections-ext-xml sections)
        "</p:presentation>")))

(def rel-comment-authors "http://schemas.openxmlformats.org/officeDocument/2006/relationships/commentAuthors")

(defn- presentation-rels
  ([slide-count] (presentation-rels slide-count false 1 false false 0))
  ([slide-count has-notes?] (presentation-rels slide-count has-notes? 1 false false 0))
  ([slide-count has-notes? master-count] (presentation-rels slide-count has-notes? master-count false false 0))
  ([slide-count has-notes? master-count has-comments?]
   (presentation-rels slide-count has-notes? master-count has-comments? false 0))
  ([slide-count has-notes? master-count has-comments? has-handout-master?]
   (presentation-rels slide-count has-notes? master-count has-comments? has-handout-master? 0))
  ([slide-count has-notes? master-count has-comments? has-handout-master? custom-xml-count]
   (let [base-rid (+ master-count slide-count 1 (if has-notes? 1 0) (if has-comments? 1 0) (if has-handout-master? 1 0))]
     (ooxml/relationships-xml
      (concat
       (for [idx (range 1 (inc master-count))]
         (ooxml/relationship {:id (str "rId" idx) :type rel-slide-master
                              :target (str "slideMasters/slideMaster" idx ".xml")}))
       (for [idx (range 1 (inc slide-count))]
         (ooxml/relationship {:id (str "rId" (+ master-count idx))
                              :type rel-slide
                              :target (str "slides/slide" idx ".xml")}))
       (when has-notes?
         [(ooxml/relationship {:id (str "rId" (+ master-count slide-count 1))
                               :type rel-notes-master
                               :target "notesMasters/notesMaster1.xml"})])
       (when has-comments?
         [(ooxml/relationship {:id (str "rId" (+ master-count slide-count 1 (if has-notes? 1 0)))
                               :type rel-comment-authors
                               :target "commentAuthors.xml"})])
       (when has-handout-master?
         [(ooxml/relationship {:id (str "rId" (+ master-count slide-count 1 (if has-notes? 1 0) (if has-comments? 1 0)))
                               :type rel-handout-master
                               :target "handoutMasters/handoutMaster1.xml"})])
       (for [idx (range 1 (inc custom-xml-count))]
         (ooxml/relationship {:id (str "rId" (+ base-rid idx -1))
                              :type rel-custom-xml
                              :target (str "../customXml/item" idx ".xml")})))))))

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
         "<a:fontScheme name=\"kotoba\">"
         "<a:majorFont><a:latin typeface=\"" (theme-font fonts :office-style.font/majorFont "Aptos Display") "\"/>"
         "<a:ea typeface=\"" (esc (get fonts :office-style.font/majorFont-ea "")) "\"/>"
         "<a:cs typeface=\"" (esc (get fonts :office-style.font/majorFont-cs "")) "\"/></a:majorFont>"
         "<a:minorFont><a:latin typeface=\"" (theme-font fonts :office-style.font/minorFont "Aptos") "\"/>"
         "<a:ea typeface=\"" (esc (get fonts :office-style.font/minorFont-ea "")) "\"/>"
         "<a:cs typeface=\"" (esc (get fonts :office-style.font/minorFont-cs "")) "\"/></a:minorFont>"
         "</a:fontScheme>\n"
         "<a:fmtScheme name=\"kotoba\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>"
         "<a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>"
         "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>"
         "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst>"
         "</a:fmtScheme>\n"
         "</a:themeElements>\n"
         "</a:theme>")))

(defn- master-background [master-map]
  (:slides/background master-map "FFFFFF"))

(defn- background-fill-xml
  "The <p:bg>'s fill content. :slides/background is either the historical
  flat hex string (solid fill) or a map {:stops [[pct hex] ...] :angle deg}
  for a linear gradient background (a stripe/wash effect is a common real-
  deck master background that previously had no way to round-trip at all --
  only a flat solid color was ever written). Takes the MASTER map directly
  (design/master deck, or design/master-for-slide deck slide for a deck
  using multiple named masters) rather than the deck, so a slide's own
  master's background is used, not always the deck's single default one.
  `slide-override`, when present (a slide's own :slides/slide-background,
  from presentationml.parse/slide-background on import), takes precedence
  over the master's -- a common real-deck pattern (a differently-colored
  title/section-divider slide). Previously there was no such override
  path at all -- every slide's own <p:bg> always derived from its master,
  silently losing any per-slide background a source deck actually had."
  ([master-map] (background-fill-xml master-map nil))
  ([master-map slide-override]
   (let [bg (if (some? slide-override) slide-override (master-background master-map))]
     (if (map? bg)
      (let [stops (or (seq (:stops bg)) [[0 "FFFFFF"] [100 "F0F0F0"]])
            angle (numeric (:angle bg) 90)]
        (str "<a:gradFill rotWithShape=\"1\"><a:gsLst>"
             (apply str (map (fn [[pos hex]]
                               (str "<a:gs pos=\"" (long (* (numeric pos 0) 1000)) "\">"
                                    "<a:srgbClr val=\"" (hex-color hex "FFFFFF") "\"/></a:gs>"))
                             stops))
             "</a:gsLst><a:lin ang=\"" (long (* angle 60000)) "\" scaled=\"1\"/></a:gradFill>"))
       (str "<a:solidFill><a:srgbClr val=\"" (hex-color bg "FFFFFF") "\"/></a:solidFill>")))))

(defn- slide-master
  "A single <p:sldMaster> part for `master-map`, referencing EVERY layout
  (global file index) in `layout-indices` that belongs to it -- a master
  can now own multiple distinct layouts (Title Slide, Title and Content,
  Blank, ...), not just one."
  [master-map layout-indices]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">
  <p:cSld name=\"kotoba\"><p:bg><p:bgPr>" (background-fill-xml master-map) "</p:bgPr></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>
  <p:sldLayoutIdLst>"
       (apply str (map-indexed (fn [i layout-idx]
                                 (str "<p:sldLayoutId id=\"" (+ 2147483649 i) "\" r:id=\"rId" (inc i) "\"/>"))
                               layout-indices))
       "</p:sldLayoutIdLst>
  <p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>
</p:sldMaster>"))

(defn- slide-master-rels [layout-indices]
  (ooxml/relationships-xml
   (concat
    (map-indexed (fn [i layout-idx]
                   (ooxml/relationship {:id (str "rId" (inc i)) :type rel-slide-layout
                                        :target (str "../slideLayouts/slideLayout" layout-idx ".xml")}))
                 layout-indices)
    [(ooxml/relationship {:id (str "rId" (inc (count layout-indices))) :type rel-theme :target "../theme/theme1.xml"})])))

(defn- layout-placeholder-shape-xml
  [n {:keys [type idx x y w h]}]
  (str "<p:sp><p:nvSpPr><p:cNvPr id=\"" (+ 10 n) "\" name=\"Placeholder " n "\"/>"
       "<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>"
       "<p:nvPr><p:ph"
       (when type (str " type=\"" (esc type) "\""))
       (when idx (str " idx=\"" (esc (str idx)) "\""))
       "/></p:nvPr></p:nvSpPr>"
       "<p:spPr>"
       (when (and x y w h) (str "<a:xfrm><a:off x=\"" (emu x) "\" y=\"" (emu y) "\"/><a:ext cx=\"" (emu w) "\" cy=\"" (emu h) "\"/></a:xfrm>"))
       "</p:spPr>"
       "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody>"
       "</p:sp>"))

(defn- slide-layout
  "A single <p:sldLayout> part. `layout-map` (design/layout-by-ref, nil for
  the historical implicit default) supplies its own :slides/layout-type
  (the type=\"...\" attribute PowerPoint's New Slide/Reset Layout gallery
  keys off of -- \"title\"/\"obj\"/\"twoObj\"/\"blank\"/...) and optional
  :slides/placeholders (positioned <p:ph> shape templates, the same
  {:type ... :idx ... :x ... :y ... :w ... :h ...} shape drawingml's
  placeholder produces on import) -- previously every layout was an
  identical, content-free \"blank\" template regardless of what the deck
  actually wanted a slide to look like when reset to its layout."
  ([] (slide-layout nil))
  ([layout-map]
   (let [layout-type (or (:slides/layout-type layout-map) "blank")
         placeholders (:slides/placeholders layout-map)]
     (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"" (esc layout-type) "\" preserve=\"1\">
  <p:cSld name=\"" (esc (or (:slides/id layout-map) "Blank")) "\"><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
          (apply str (map-indexed layout-placeholder-shape-xml placeholders))
          "</p:spTree></p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>"))))

(defn- notes-master-xml []
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<p:notesMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">
  <p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>
  <p:notesStyle><a:lvl1pPr><a:defRPr sz=\"1200\"/></a:lvl1pPr></p:notesStyle>
</p:notesMaster>"))

(def notes-master-rels
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type rel-theme :target "../theme/theme1.xml"})]))

(defn- handout-master-xml []
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
<p:handoutMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">
  <p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>
</p:handoutMaster>"))

(def handout-master-rels
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type rel-theme :target "../theme/theme1.xml"})]))

(defn- custom-xml-item-rels-xml [idx]
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type rel-custom-xml-props :target (str "itemProps" idx ".xml")})]))

(defn- custom-xml-parts-entries
  "The full set of package entries for a deck's own :slides/custom-xml-parts
  ({:content ... :props-content ...}, the same shape presentationml.parse/
  custom-xml-parts already produces on import -- both preserved verbatim
  as opaque raw XML strings, this package doesn't reinterpret custom XML
  content) -- customXml/itemN.xml always, plus itemPropsN.xml + item's own
  .rels only when the source part actually had props-content."
  [custom-xml-parts]
  (apply concat
         (map-indexed
          (fn [i {:keys [content props-content]}]
            (let [idx (inc i)]
              (concat
               [[(str "customXml/item" idx ".xml") content]]
               (when props-content
                 [[(str "customXml/itemProps" idx ".xml") props-content]
                  [(str "customXml/_rels/item" idx ".xml.rels") (custom-xml-item-rels-xml idx)]]))))
          custom-xml-parts)))

(defn- notes-paragraphs-xml [notes-text]
  (apply str (map (fn [line] (str "<a:p><a:r><a:t>" (esc line) "</a:t></a:r></a:p>"))
                   (str/split (str notes-text) #"\n" -1))))

(defn- notes-slide-xml [notes-text]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<p:notes xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
       "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
       "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
       "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
       "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
       "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes Placeholder\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>"
       "<p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>"
       "<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>"
       (notes-paragraphs-xml notes-text)
       "</p:txBody></p:sp>"
       "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"))

(defn- notes-slide-rels-xml []
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type rel-notes-master :target "../notesMasters/notesMaster1.xml"})]))

(defn- author-initials [author-name]
  (->> (str/split (str author-name) #"\s+")
       (remove str/blank?)
       (map #(str/upper-case (subs % 0 1)))
       (apply str)))

(defn- comment-authors-xml
  "ppt/commentAuthors.xml -- the deck-wide, shared author table every
  <p:cm>'s own authorId references (legacy <p:cmLst> comment format never
  carries an author's name inline). `author-names` is the deck's own
  distinct comment authors, in first-appearance order -- assigned ids
  0, 1, 2..., matching author-id-by-name's own assignment (see
  deck-comment-authors)."
  [author-names]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<p:cmAuthorLst xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
       (apply str (map-indexed
                   (fn [idx author-name]
                     (str "<p:cmAuthor id=\"" idx "\" name=\"" (esc author-name) "\" initials=\""
                          (esc (author-initials author-name)) "\" lastIdx=\"1\" clrIdx=\"" idx "\"/>"))
                   author-names))
       "</p:cmAuthorLst>"))

(defn- comment-xml
  "One <p:cm> from a :slides/comments entry ({:author ... :text ...
  :date ... :x ... :y ...}, the same shape presentationml.parse/slide-
  comments already produces on import) -- authorId resolved through the
  deck-wide author-id-by-name map, <p:pos> only when the comment carries
  its own x/y (a comment need not be pinned to a specific point)."
  [idx {:keys [author text date x y]} author-id-by-name]
  (str "<p:cm authorId=\"" (get author-id-by-name author 0) "\""
       (when date (str " dt=\"" (esc date) "\""))
       " idx=\"" (inc idx) "\">"
       (when (and x y) (str "<p:pos x=\"" (emu x) "\" y=\"" (emu y) "\"/>"))
       "<p:text>" (esc text) "</p:text>"
       "</p:cm>"))

(defn- comments-part-xml
  "One slide's own ppt/comments/commentN.xml, holding all of ITS comments
  (each slide with comments gets its own part -- comments don't span
  slides). Needs no .rels of its own: a comment references its author by
  a plain integer id resolved against the shared commentAuthors.xml, not
  through an OPC relationship."
  [comments author-id-by-name]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<p:cmLst xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
       "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
       (apply str (map-indexed #(comment-xml %1 %2 author-id-by-name) comments))
       "</p:cmLst>"))

(defn- slide-layout-rels [master-idx]
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type rel-slide-master :target (str "../slideMasters/slideMaster" master-idx ".xml")})]))

(defn- xfrm-attrs
  "rot/flipH/flipV attributes for an <a:xfrm> opening tag. rot is stored on
  the shape as plain degrees (:slides/rotation) and converted to OOXML's
  60,000ths-of-a-degree unit here."
  [{:slides/keys [rotation flip-h flip-v]}]
  (str (when rotation (str " rot=\"" (long (Math/round (* (double rotation) 60000.0))) "\""))
       (when flip-h " flipH=\"1\"")
       (when flip-v " flipV=\"1\"")))

(defn- shape-xfrm [{:slides/keys [x y w h] :as shape}]
  (str "<a:xfrm" (xfrm-attrs shape) "><a:off x=\"" (emu (numeric x 0)) "\" y=\"" (emu (numeric y 0)) "\"/>"
       "<a:ext cx=\"" (emu (positive-numeric w 1)) "\" cy=\"" (emu (positive-numeric h 1)) "\"/></a:xfrm>"))

(defn- connector-xfrm
  "Like shape-xfrm, but allows a zero width or height -- a perfectly
  horizontal or vertical connector line legitimately has one, and
  shape-xfrm's positive-numeric would silently substitute a 1-inch fallback,
  turning a horizontal connector into a visibly diagonal one."
  [{:slides/keys [x y w h] :as shape}]
  (str "<a:xfrm" (xfrm-attrs shape) "><a:off x=\"" (emu (numeric x 0)) "\" y=\"" (emu (numeric y 0)) "\"/>"
       "<a:ext cx=\"" (emu (numeric w 1)) "\" cy=\"" (emu (numeric h 0)) "\"/></a:xfrm>"))

(defn- font-face [deck major?]
  (get (design/fonts deck)
       (if major? :office-style.font/majorFont :office-style.font/minorFont)
       (if major? "Aptos Display" "Aptos")))

(defn- font-face-ea
  "The theme's East Asian typeface for this role, or nil if the deck never
  captured one (e.g. a theme with no CJK font configured at all) -- callers
  only emit <a:ea> when this is non-nil, since a made-up font name would be
  worse than PowerPoint's own fallback."
  [deck major?]
  (get (design/fonts deck)
       (if major? :office-style.font/majorFont-ea :office-style.font/minorFont-ea)))

(defn- cjk-lang
  "A best-effort BCP-47 language tag from the run's own text -- Hiragana/
  Katakana implies Japanese, Hangul implies Korean, bare CJK ideographs
  (no kana) default to Chinese. Latin/other text keeps the historical
  \"en-US\". This is a heuristic (a document can legitimately mix scripts
  per run), not a real language detector, but it's strictly better than
  always claiming en-US for CJK content -- PowerPoint's own font
  substitution/proofing keys off this attribute."
  [text]
  (cond
    (re-find #"[぀-ヿ]" (str text)) "ja-JP"
    (re-find #"[가-힯]" (str text)) "ko-KR"
    (re-find #"[一-鿿㐀-䶿]" (str text)) "zh-CN"
    :else "en-US"))

(defn- tab-stop-xml
  "One <a:tab> from a paragraph's own :tab-stops entry ({:position inches
  :align :left/:center/:right/:decimal}, from drawingml.parse/paragraph-
  tab-stops on import) -- :left (the schema default) isn't written as
  algn=\"l\" at all, matching how it was captured as absent on import."
  [{:keys [position align]}]
  (str "<a:tab pos=\"" (emu position) "\""
       (when align (str " algn=\"" (case align :center "ctr" :right "r" :decimal "dec") "\""))
       "/>"))

(defn- tab-stops-xml
  "A paragraph's own :tab-stops into <a:pPr>'s own <a:tabLst>, schema-
  ordered after the bullet and before <a:defRPr> (this writer never emits
  defRPr, so tabLst is simply the last child). \"\" (no element at all)
  when the paragraph has no explicit tab stops, unchanged from before
  this feature existed."
  [tab-stops]
  (when (seq tab-stops)
    (str "<a:tabLst>" (apply str (map tab-stop-xml tab-stops)) "</a:tabLst>")))

(defn- paragraph-ppr-xml [{:keys [align bullet line-spacing level margin-left tab-stops]}]
  (when (or align bullet line-spacing level margin-left tab-stops)
    (str "<a:pPr"
         (when level (str " lvl=\"" (long level) "\""))
         (when margin-left (str " marL=\"" (emu margin-left) "\""))
         (when align (str " algn=\"" (case align :center "ctr" :right "r" :justify "just" "l") "\""))
         ">"
         (when line-spacing
           (str "<a:lnSpc><a:spcPct val=\"" (long (* line-spacing 100000)) "\"/></a:lnSpc>"))
         (case (:type bullet)
           :char (str "<a:buChar char=\"" (esc (:char bullet)) "\"/>")
           :auto-num (str "<a:buAutoNum type=\"" (esc (or (:scheme bullet) "arabicPeriod")) "\""
                          (when (:start-at bullet) (str " startAt=\"" (long (:start-at bullet)) "\""))
                          "/>")
           :none "<a:buNone/>"
           nil nil)
         (tab-stops-xml tab-stops)
         "</a:pPr>")))

(def field-placeholder-types
  "The two placeholder types whose content PowerPoint auto-computes at
  render time (today's date, the slide's own position) rather than storing
  as static text -- their <p:ph type> maps directly onto <a:fld>'s own
  type attribute (\"datetime1\"/\"slidenum\"), so a date/slide-number
  shape's placeholder type alone is enough to know its runs must be
  <a:fld>, not <a:r> -- no separate field-vs-plain-text flag needed."
  {"dt" "datetime1" "sldNum" "slidenum"})

(def field-id
  "A fixed <a:fld id=\"...\"> GUID. PowerPoint tolerates any well-formed
  GUID here (its own refresh cycle re-derives the field's actual value at
  open time regardless) -- a single constant keeps output deterministic,
  matching this package's other hardcoded-but-valid identifiers (e.g.
  default-table-style-id)."
  "{5C7C1F09-0F5C-4B8A-9C1E-8A5F5D7B3E11}")

(def ^:private ppaction-jump-queries
  "The reverse of drawingml.parse/ppaction-jumps -- each :drawingml/
  hyperlink-action keyword back to its own ppaction://hlinkshowjump
  query value."
  {:first-slide "firstslide" :last-slide "lastslide" :next-slide "nextslide"
   :previous-slide "previousslide" :last-viewed-slide "lastslideviewed" :end-show "endshow"})

(defn- paragraph-run-xml [deck {:slides/keys [font-size color bold italic underline strikethrough baseline placeholder hyperlink-action] :as shape} text major? ea-font hlink-rel-id]
  (let [field-type (field-placeholder-types (:type placeholder))
        open-tag (if field-type (str "<a:fld id=\"" field-id "\" type=\"" field-type "\">") "<a:r>")
        close-tag (if field-type "</a:fld>" "</a:r>")]
    (str open-tag "<a:rPr lang=\"" (cjk-lang text) "\" sz=\"" (* 100 (long (positive-numeric font-size 24))) "\""
         (when bold " b=\"1\"")
         (when italic " i=\"1\"")
         (when underline " u=\"sng\"")
         (when strikethrough " strike=\"sngStrike\"")
         (when baseline (str " baseline=\"" (long (* baseline 1000)) "\""))
         "><a:latin typeface=\"" (esc (font-face deck major?)) "\"/>"
         (when ea-font (str "<a:ea typeface=\"" (esc ea-font) "\"/>"))
         "<a:solidFill><a:srgbClr val=\"" (hex-color color "17202A") "\"/></a:solidFill>"
         (cond
           hyperlink-action (str "<a:hlinkClick action=\"ppaction://hlinkshowjump?jump="
                                 (get ppaction-jump-queries hyperlink-action) "\"/>")
           hlink-rel-id (str "<a:hlinkClick r:id=\"" hlink-rel-id "\"/>"))
         "</a:rPr><a:t>" (esc text) "</a:t>" close-tag)))

(defn- paragraph-xml [deck shape major? ea-font hlink-rel-id {:keys [text] :as para}]
  (str "<a:p>" (or (paragraph-ppr-xml para) "")
       (paragraph-run-xml deck shape text major? ea-font hlink-rel-id)
       "</a:p>"))

(defn- shape-paragraphs
  "Structured paragraphs when the shape carries them (from a PPTX import,
  see drawingml.parse/paragraphs -- bullets/alignment/line-spacing survive),
  else one plain paragraph per newline in :slides/text. Splitting on \\n is
  the historical behavior improved: previously the whole (possibly
  multi-line) text was written into a SINGLE <a:p>, which real renderers
  don't break into visual lines on an embedded newline -- multi-line text
  boxes silently lost their line breaks on export."
  [{:slides/keys [text paragraphs]}]
  (if (seq paragraphs)
    paragraphs
    (mapv (fn [line] {:text line}) (str/split (str text) #"\n" -1))))

(def ^:private geometry-preset-pattern #"^[A-Za-z][A-Za-z0-9]*$")

(defn- geometry-preset
  "The shape's <a:prstGeom> preset name, defaulting to \"rect\". Validated
  against a conservative charset (OOXML preset names are always bare
  alphanumeric identifiers, e.g. \"roundRect\"/\"ellipse\") rather than
  trusted verbatim, since a hand-authored deck could set :slides/geometry to
  anything."
  [shape]
  (let [v (some-> (:slides/geometry shape) name)]
    (if (and v (re-matches geometry-preset-pattern v)) v "rect")))

(defn- line-dash-xml [shape]
  (when-let [dash (:slides/line-dash shape)]
    (str "<a:prstDash val=\"" (esc (name dash)) "\"/>")))

(defn- line-cap-attr
  "The <a:ln>'s own cap=\"...\" attribute from :slides/line-cap (:round ->
  \"rnd\", :square -> \"sq\"), or \"\" when absent -- PowerPoint's own
  default (flat) needs no explicit attribute at all."
  [shape]
  (case (:slides/line-cap shape)
    :round " cap=\"rnd\""
    :square " cap=\"sq\""
    ""))

(defn- line-join-xml
  "The <a:ln>'s one join child from :slides/line-join ({:type :round}/
  {:type :bevel}/{:type :miter :limit pct}, from drawingml.parse/line-join
  on import), or \"\" when absent -- PowerPoint's own default (round)
  needs no explicit child at all. :limit (a plain percentage) converts
  back to OOXML's own thousandths-of-a-percent lim attribute."
  [shape]
  (let [{:keys [type limit]} (:slides/line-join shape)]
    (case type
      :round "<a:round/>"
      :bevel "<a:bevel/>"
      :miter (str "<a:miter" (when limit (str " lim=\"" (long (Math/round (* (double limit) 1000.0))) "\"")) "/>")
      "")))

(defn- avlst-xml
  "A shape's <a:avLst> with its actual adjustment handle values
  (:slides/adjustments, the same {:name ... :fmla ...} shape drawingml's
  shape-adjustments already produces on import), or the historical empty
  <a:avLst/> when absent -- a re-exported shape with a customized
  adjustment (a roundRect's non-default corner radius, a custom arrowhead
  ratio) previously always collapsed to the geometry's default."
  [shape]
  (if-let [adjustments (seq (:slides/adjustments shape))]
    (str "<a:avLst>"
         (apply str (map (fn [{:keys [name fmla]}]
                           (str "<a:gd name=\"" (esc name) "\" fmla=\"" (esc fmla) "\"/>"))
                         adjustments))
         "</a:avLst>")
    "<a:avLst/>"))

(defn- path-command-xml
  [{:keys [cmd pts w-radius h-radius start-angle swing-angle]}]
  (case cmd
    :close "<a:close/>"
    :arcTo (str "<a:arcTo wR=\"" (long w-radius) "\" hR=\"" (long h-radius)
                "\" stAng=\"" (long start-angle) "\" swAng=\"" (long swing-angle) "\"/>")
    (str "<a:" (name cmd) ">"
         (apply str (map (fn [{:keys [x y]}] (str "<a:pt x=\"" (long x) "\" y=\"" (long y) "\"/>")) pts))
         "</a:" (name cmd) ">")))

(defn- custgeom-xml [custom-geometry]
  (str "<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>"
       "<a:pathLst>"
       (apply str (map (fn [{:keys [width height fill-rule commands]}]
                         (str "<a:path w=\"" (long width) "\" h=\"" (long height) "\""
                              (when fill-rule (str " fill=\"" (esc fill-rule) "\""))
                              ">"
                              (apply str (map path-command-xml commands))
                              "</a:path>"))
                       custom-geometry))
       "</a:pathLst></a:custGeom>"))

(defn- geometry-xml
  "A shape's own <a:prstGeom>/<a:avLst>, OR (when :slides/custom-geometry
  is present -- the same {:width ... :height ... :commands [...]} shape
  drawingml's custom-geometry already produces on import) a real
  <a:custGeom> instead -- previously every re-exported shape always wrote
  a plain preset regardless of the source having used a custom vector
  path."
  [shape]
  (if-let [custom (seq (:slides/custom-geometry shape))]
    (custgeom-xml custom)
    (str "<a:prstGeom prst=\"" (geometry-preset shape) "\">" (avlst-xml shape) "</a:prstGeom>")))

(defn- glow-effect-xml
  "A shape's own <a:glow .../> from :slides/glow ({:radius pt :color hex
  :alpha pct}, the same shape drawingml's shape-glow already produces on
  import), or nil when absent."
  [glow]
  (when glow
    (str "<a:glow rad=\"" (long (* 12700 (positive-numeric (:radius glow) 5))) "\">"
         "<a:srgbClr val=\"" (hex-color (:color glow) "00B0F0") "\">"
         (when (:alpha glow) (str "<a:alpha val=\"" (long (* 1000 (numeric (:alpha glow) 100))) "\"/>"))
         "</a:srgbClr></a:glow>")))

(defn- shadow-effect-xml
  "A shape's own <a:outerShdw .../> from :slides/shadow ({:blur pt
  :distance pt :angle deg :color hex :alpha pct}, the same shape
  drawingml's shape-shadow already produces on import), or nil when
  absent."
  [shadow]
  (when shadow
    (str "<a:outerShdw"
         " blurRad=\"" (long (* 12700 (positive-numeric (:blur shadow) 4))) "\""
         " dist=\"" (long (* 12700 (positive-numeric (:distance shadow) 2))) "\""
         " dir=\"" (long (* 60000 (numeric (:angle shadow) 45))) "\""
         " rotWithShape=\"0\">"
         "<a:srgbClr val=\"" (hex-color (:color shadow) "000000") "\">"
         (when (:alpha shadow) (str "<a:alpha val=\"" (long (* 1000 (numeric (:alpha shadow) 40))) "\"/>"))
         "</a:srgbClr>"
         "</a:outerShdw>")))

(defn- reflection-effect-xml
  "A shape's own <a:reflection .../> from :slides/reflection ({:blur pt
  :distance pt :angle deg :start-alpha pct :end-alpha pct}, the same
  shape drawingml's shape-reflection already produces on import), self-
  closing and carrying no color of its own (a reflection mirrors the
  shape's own fill) -- or nil when absent."
  [reflection]
  (when reflection
    (str "<a:reflection"
         (when (:blur reflection) (str " blurRad=\"" (long (* 12700 (numeric (:blur reflection) 0))) "\""))
         (when (:distance reflection) (str " dist=\"" (long (* 12700 (numeric (:distance reflection) 0))) "\""))
         (when (:angle reflection) (str " dir=\"" (long (* 60000 (numeric (:angle reflection) 0))) "\""))
         (when (:start-alpha reflection) (str " stA=\"" (long (* 1000 (numeric (:start-alpha reflection) 0))) "\""))
         (when (:end-alpha reflection) (str " endA=\"" (long (* 1000 (numeric (:end-alpha reflection) 0))) "\""))
         "/>")))

(defn- effect-lst-xml
  "A shape's own <a:effectLst>, combining whichever of :slides/glow/
  :slides/shadow/:slides/reflection it carries into ONE effect list (in
  their CT_EffectList schema order: glow, outerShdw, reflection) -- OOXML
  allows only a single <a:effectLst> per shape, containing however many
  effect children are actually present. nil (no element at all) when the
  shape has none of the three -- previously no shape ever emitted ANY
  effect regardless of the source deck."
  [shape]
  (let [children (str (glow-effect-xml (:slides/glow shape))
                       (shadow-effect-xml (:slides/shadow shape))
                       (reflection-effect-xml (:slides/reflection shape)))]
    (when (seq children)
      (str "<a:effectLst>" children "</a:effectLst>"))))

(defn- line-width-attr
  "The <a:ln>'s own w=\"...\" EMU attribute from :slides/line-width (plain
  points), defaulting to 1pt (12700 EMU) -- the writer's own historical
  hardcoded value -- when absent."
  [shape]
  (str " w=\"" (long (* 12700 (positive-numeric (:slides/line-width shape) 1))) "\""))

(defn- src-rect-xml
  "A picture's own :slides/crop ({:left/:top/:right/:bottom pct}, from
  drawingml.parse/picture-crop on import) into <a:srcRect>, schema-ordered
  right after <a:blip> and before the fill mode (<a:stretch>) -- each side
  converted back to thousandths-of-a-percent. nil (no element at all) when
  the shape carries no crop, matching how the shape round-tripped before
  this feature existed."
  [{:keys [left top right bottom]}]
  (when (or left top right bottom)
    (str "<a:srcRect"
         (when left (str " l=\"" (long (Math/round (* (double left) 1000.0))) "\""))
         (when top (str " t=\"" (long (Math/round (* (double top) 1000.0))) "\""))
         (when right (str " r=\"" (long (Math/round (* (double right) 1000.0))) "\""))
         (when bottom (str " b=\"" (long (Math/round (* (double bottom) 1000.0))) "\""))
         "/>")))

(defn- blip-recolor-children-xml
  "A picture's own :slides/recolor ({:grayscale? true :alpha-mod pct},
  from drawingml.parse/picture-recolor on import) into <a:blip>'s own
  child elements (<a:alphaModFix>/<a:grayscl>) -- \"\" (no children) when
  the shape carries no recolor effects, so <a:blip> stays self-closing,
  matching how the shape round-tripped before this feature existed."
  [{:keys [grayscale? alpha-mod]}]
  (str (when alpha-mod (str "<a:alphaModFix amt=\"" (long (Math/round (* (double alpha-mod) 1000.0))) "\"/>"))
       (when grayscale? "<a:grayscl/>")))

(defn- blip-xml
  "A picture's own <a:blip>, self-closing when it has no recolor effects
  at all (the overwhelming common case), otherwise wrapping its recolor
  children in a paired closing tag (an <a:blip> with children can't be
  self-closing)."
  [rel-id recolor]
  (let [children (blip-recolor-children-xml recolor)]
    (if (seq children)
      (str "<a:blip r:embed=\"" rel-id "\">" children "</a:blip>")
      (str "<a:blip r:embed=\"" rel-id "\"/>"))))

(defn- blip-fill-xml
  ([rel-id] (blip-fill-xml rel-id nil nil))
  ([rel-id crop] (blip-fill-xml rel-id crop nil))
  ([rel-id crop recolor]
   (str "<a:blipFill>" (blip-xml rel-id recolor) (src-rect-xml crop) "<a:stretch><a:fillRect/></a:stretch></a:blipFill>")))

(defn- gradient-fill-xml
  "A shape's own :slides/gradient ({:stops [{:position 0-100 :color
  \"hex\"} ...] :angle deg}, from drawingml.parse/gradient-fill on
  import) into a real multi-stop <a:gradFill> -- previously a gradient-
  filled shape always wrote as a flat <a:solidFill> using its own first-
  stop-only :slides/fill approximation, since no writer path ever
  reconstructed the full gradient."
  [{:keys [stops angle]}]
  (str "<a:gradFill><a:gsLst>"
       (apply str (for [{:keys [position color]} stops]
                    (str "<a:gs" (when position (str " pos=\"" (long (Math/round (* (double position) 1000.0))) "\"")) ">"
                         "<a:srgbClr val=\"" (hex-color color "336699") "\"/></a:gs>")))
       "</a:gsLst>"
       (when angle (str "<a:lin ang=\"" (long (Math/round (* (double angle) 60000.0))) "\" scaled=\"1\"/>"))
       "</a:gradFill>"))

(defn- placeholder-xml
  "A shape's <p:ph .../> from its :slides/placeholder (carried through
  unchanged from import's drawingml/placeholder -- see
  drawingml.parse/placeholder). Previously the FULL-REGEN writer never
  emitted <p:ph> at all: a re-exported title/body shape was written as a
  plain, non-placeholder textbox, invisible to PowerPoint's Outline view
  or Reset Layout (the update/patch path, which never touches <p:nvSpPr>,
  already preserved it correctly for shapes patched in place -- this only
  affects a shape re-rendered from scratch)."
  [{:keys [type idx size orient]}]
  (str "<p:ph"
       (when type (str " type=\"" (esc type) "\""))
       (when idx (str " idx=\"" (esc idx) "\""))
       (when size (str " sz=\"" (esc size) "\""))
       (when orient (str " orient=\"" (esc orient) "\""))
       "/>"))

(defn- sp-locks-xml
  "A text/rect shape's own :slides/locks ({:no-grp? true ...}, from
  drawingml.parse/shape-locks on import) as a full <a:spLocks .../>
  element, or nil when :slides/locks is absent -- the historical case,
  where <p:cNvSpPr> stays self-closing with no lock child at all
  (semantically \"no restrictions\", the OOXML default)."
  [locks]
  (when locks
    (str "<a:spLocks"
         (when (:no-grp? locks) " noGrp=\"1\"")
         (when (:no-rot? locks) " noRot=\"1\"")
         (when (:no-change-aspect? locks) " noChangeAspect=\"1\"")
         (when (:no-move? locks) " noMove=\"1\"")
         (when (:no-resize? locks) " noResize=\"1\"")
         (when (:no-select? locks) " noSelect=\"1\"")
         "/>")))

(def ^:private text-vertical-attrs
  "The reverse of drawingml.parse/text-vertical-values -- each :vertical
  keyword back to its own vert=\"...\" attribute value."
  {:vert "vert" :vert270 "vert270" :word-art-vert "wordArtVert"
   :ea-vert "eaVert" :mongolian-vert "mongolianVert" :word-art-vert-rtl "wordArtVertRtl"})

(defn- body-pr-xml
  "A shape's <a:bodyPr> from its :slides/body-props (carried through
  unchanged from import's drawingml/body-props -- see
  drawingml.parse/text-body-props). wrap/lIns/tIns/rIns/rIns/anchor/
  anchorCtr/vert are all attributes on the tag itself
  (CT_TextBodyProperties); the autofit choice (spAutoFit/noAutofit/
  normAutofit) is its one child element. nil :slides/body-props (the
  common case) still emits a bare <a:bodyPr></a:bodyPr> -- semantically
  identical to the historical hardcoded wrap=\"square\" (PowerPoint's own
  default when wrap is omitted), just without redundantly spelling out
  the default."
  [{:keys [wrap anchor anchor-center margin-left margin-top margin-right margin-bottom
           autofit font-scale line-spacing-reduction vertical]}]
  (str "<a:bodyPr"
       (when (= wrap :none) " wrap=\"none\"")
       (when margin-left (str " lIns=\"" (emu margin-left) "\""))
       (when margin-top (str " tIns=\"" (emu margin-top) "\""))
       (when margin-right (str " rIns=\"" (emu margin-right) "\""))
       (when margin-bottom (str " bIns=\"" (emu margin-bottom) "\""))
       (when (= anchor :center) " anchor=\"ctr\"")
       (when (= anchor :bottom) " anchor=\"b\"")
       (when anchor-center " anchorCtr=\"1\"")
       (when-let [v (get text-vertical-attrs vertical)] (str " vert=\"" v "\""))
       ">"
       (case autofit
         :resize-shape "<a:spAutoFit/>"
         :none "<a:noAutofit/>"
         :shrink (str "<a:normAutofit"
                      (when font-scale (str " fontScale=\"" (long (* font-scale 1000)) "\""))
                      (when line-spacing-reduction (str " lnSpcReduction=\"" (long (* line-spacing-reduction 1000)) "\""))
                      "/>")
         nil "")
       "</a:bodyPr>"))

(defn- text-shape
  ([deck idx shape] (text-shape deck idx shape {}))
  ([deck idx {:slides/keys [id fill line placeholder] :as shape} opts]
   (let [font-size (:slides/font-size shape)
         major? (>= (positive-numeric font-size 24) 30)
         ea-font (font-face-ea deck major?)
         hlink-rel-id (get-in opts [:hyperlink-rels (:slides/id shape)])
         fill-image-rel-id (when (:slides/fill-image-data shape) (get-in opts [:image-rels (:slides/id shape)]))
         locks-xml (sp-locks-xml (:slides/locks shape))]
     (str "<p:sp><p:nvSpPr><p:cNvPr id=\"" (+ 10 idx) "\" name=\"" (esc (or id (str "Text " idx))) "\"" (hidden-attr shape) "/>"
          "<p:cNvSpPr" (when-not placeholder " txBox=\"1\"")
          (if locks-xml (str ">" locks-xml "</p:cNvSpPr>") "/>")
          (if placeholder (str "<p:nvPr>" (placeholder-xml placeholder) "</p:nvPr>") "<p:nvPr/>")
          "</p:nvSpPr>"
          "<p:spPr>" (shape-xfrm shape) (geometry-xml shape)
          (cond
            (:slides/gradient shape) (gradient-fill-xml (:slides/gradient shape))
            fill-image-rel-id (blip-fill-xml fill-image-rel-id (:slides/crop shape) (:slides/recolor shape))
            fill (str "<a:solidFill><a:srgbClr val=\"" (hex-color fill "EAF0F8") "\"/></a:solidFill>")
            :else "<a:noFill/>")
          (if line
            (str "<a:ln" (line-width-attr shape) (line-cap-attr shape) "><a:solidFill><a:srgbClr val=\"" (hex-color line "496B9A") "\"/></a:solidFill>" (line-dash-xml shape) (line-join-xml shape) "</a:ln>")
            "<a:ln><a:noFill/></a:ln>")
          (effect-lst-xml shape)
          "</p:spPr>"
          "<p:txBody>" (body-pr-xml (:slides/body-props shape)) "<a:lstStyle/>"
          (apply str (map #(paragraph-xml deck shape major? ea-font hlink-rel-id %) (shape-paragraphs shape)))
          "</p:txBody></p:sp>"))))

(defn- rect-shape
  ([idx shape] (rect-shape idx shape {}))
  ([idx {:slides/keys [id fill line] :as shape} opts]
   (let [fill-image-rel-id (when (:slides/fill-image-data shape) (get-in opts [:image-rels (:slides/id shape)]))
         locks-xml (sp-locks-xml (:slides/locks shape))]
     (str "<p:sp><p:nvSpPr><p:cNvPr id=\"" (+ 10 idx) "\" name=\"" (esc (or id (str "Rect " idx))) "\"" (hidden-attr shape) "/>"
          "<p:cNvSpPr" (if locks-xml (str ">" locks-xml "</p:cNvSpPr>") "/>") "<p:nvPr/></p:nvSpPr>"
          "<p:spPr>" (shape-xfrm shape)
          (geometry-xml shape)
          (cond
            (:slides/gradient shape) (gradient-fill-xml (:slides/gradient shape))
            fill-image-rel-id (blip-fill-xml fill-image-rel-id (:slides/crop shape) (:slides/recolor shape))
            :else (str "<a:solidFill><a:srgbClr val=\"" (hex-color fill "EAF0F8") "\"/></a:solidFill>"))
          "<a:ln" (line-width-attr shape) (line-cap-attr shape) "><a:solidFill><a:srgbClr val=\"" (hex-color line "496B9A") "\"/></a:solidFill>" (line-dash-xml shape) (line-join-xml shape) "</a:ln>"
          (effect-lst-xml shape)
          "</p:spPr></p:sp>"))))

(defn- connector-connection-xml
  "One <a:stCxn>/<a:endCxn> from a connector's own :slides/connections
  entry ({:shape-id N :idx N}, from drawingml.parse/connector-connections
  on import) -- id is the OTHER shape's own shape id, idx is which of
  that shape's connection sites this end is attached to."
  [tag {:keys [shape-id idx]}]
  (when shape-id
    (str "<a:" tag " id=\"" (long shape-id) "\"" (when idx (str " idx=\"" (long idx) "\"")) "/>")))

(defn- connector-cnv-cxn-sp-pr-xml
  "A connector's own <p:cNvCxnSpPr>, carrying its :slides/connections
  ({:start {...} :end {...}}) as child <a:stCxn>/<a:endCxn> elements when
  present -- bare <p:cNvCxnSpPr/> (a free-floating connector, unchanged
  from before this feature existed) otherwise."
  [{:keys [start end]}]
  (if (or start end)
    (str "<p:cNvCxnSpPr>" (connector-connection-xml "stCxn" start) (connector-connection-xml "endCxn" end) "</p:cNvCxnSpPr>")
    "<p:cNvCxnSpPr/>"))

(defn- connector-shape [idx {:slides/keys [id line] :as shape}]
  (str "<p:cxnSp><p:nvCxnSpPr><p:cNvPr id=\"" (+ 10 idx) "\" name=\"" (esc (or id (str "Connector " idx))) "\"" (hidden-attr shape) "/>"
       (connector-cnv-cxn-sp-pr-xml (:slides/connections shape)) "<p:nvPr/></p:nvCxnSpPr>"
       "<p:spPr>" (connector-xfrm shape)
       "<a:prstGeom prst=\"" (geometry-preset (assoc shape :slides/geometry (or (:slides/geometry shape) :straightConnector1))) "\">" (avlst-xml shape) "</a:prstGeom>"
       "<a:ln" (when (:slides/line-width shape) (line-width-attr shape)) (line-cap-attr shape)
       "><a:solidFill><a:srgbClr val=\"" (hex-color line "334155") "\"/></a:solidFill>" (line-dash-xml shape) (line-join-xml shape) "</a:ln>"
       "</p:spPr></p:cxnSp>"))

;; PowerPoint's built-in "Medium Style 2 - Accent 1" table style GUID: a real
;; style id every PowerPoint/LibreOffice recognizes, giving header-row +
;; banded-row styling for free instead of an unstyled grid.
(def ^:private default-table-style-id "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}")

(def ^:private merge-markers #{:h-merge :v-merge :hv-merge})

(defn- table-cell-border-side-xml
  "One <a:tcPr> border-side child from a cell's own :borders side map
  ({:width pt :color hex}, from drawingml.parse/table-cell-borders on
  import) -- an <a:ln>-shaped element, `tag` one of lnL/lnR/lnT/lnB."
  [tag {:keys [width color]}]
  (str "<a:" tag (when width (str " w=\"" (long (Math/round (* (double width) 12700.0))) "\"")) ">"
       "<a:solidFill><a:srgbClr val=\"" (hex-color color "000000") "\"/></a:solidFill>"
       "</a:" tag ">"))

(defn- table-cell-borders-xml
  "A cell's :borders ({:left {...} :right {...} :top {...} :bottom {...}
  :diagonal-down {...} :diagonal-up {...}}, only the sides actually
  present) into <a:tcPr>'s own lnL/lnR/lnT/lnB/lnTlToBr/lnBlToRt
  children, in that schema order -- before the fill choice group. \"\"
  (no border overrides) when the cell has none; PowerPoint's own
  table-style default borders then apply, unchanged."
  [borders]
  (str (when-let [b (:left borders)] (table-cell-border-side-xml "lnL" b))
       (when-let [b (:right borders)] (table-cell-border-side-xml "lnR" b))
       (when-let [b (:top borders)] (table-cell-border-side-xml "lnT" b))
       (when-let [b (:bottom borders)] (table-cell-border-side-xml "lnB" b))
       (when-let [b (:diagonal-down borders)] (table-cell-border-side-xml "lnTlToBr" b))
       (when-let [b (:diagonal-up borders)] (table-cell-border-side-xml "lnBlToRt" b))))

(defn- table-cell-tcpr-attrs-xml
  "A cell's own margin/anchor/vert attributes (from drawingml.parse/table-
  cell-margins-and-anchor on import) into <a:tcPr>'s own OPENING tag --
  these live as attributes on <a:tcPr> itself, not child elements, unlike
  borders/fill. vert (rotated cell text) reuses the same text-vertical-
  attrs reverse map already built for <a:bodyPr>'s own vert. \"\" (no
  attrs at all) when the cell has none; PowerPoint's own default margins,
  top anchor, and horizontal text then apply, unchanged."
  [{:keys [margin-left margin-right margin-top margin-bottom anchor vertical]}]
  (str (when margin-left (str " marL=\"" (emu margin-left) "\""))
       (when margin-right (str " marR=\"" (emu margin-right) "\""))
       (when margin-top (str " marT=\"" (emu margin-top) "\""))
       (when margin-bottom (str " marB=\"" (emu margin-bottom) "\""))
       (when anchor (str " anchor=\"" (case anchor :top "t" :center "ctr" :bottom "b") "\""))
       (when-let [v (get text-vertical-attrs vertical)] (str " vert=\"" v "\""))))

(defn- table-cell-xml
  "A single <a:tc>, dispatching on the cell's shape:
  - :h-merge/:v-merge/:hv-merge -- an empty merge-continuation cell,
    hMerge=\"1\"/vMerge=\"1\"/both (see drawingml.parse/table-cells).
  - a map {:text ... :col-span N :row-span N :fill \"hex\" :borders {...}
    :margin-left/:margin-right/:margin-top/:margin-bottom :anchor} -- the
    ANCHOR cell of a merge and/or a cell with its own background fill/
    border/margin/anchor override.
  - anything else (the common case) -- a plain text cell, unchanged from
    before."
  [cell]
  (cond
    (merge-markers cell)
    (str "<a:tc"
         (when (#{:h-merge :hv-merge} cell) " hMerge=\"1\"")
         (when (#{:v-merge :hv-merge} cell) " vMerge=\"1\"")
         "><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></a:txBody><a:tcPr/></a:tc>")

    (map? cell)
    (str "<a:tc"
         (when (:col-span cell) (str " gridSpan=\"" (long (:col-span cell)) "\""))
         (when (:row-span cell) (str " rowSpan=\"" (long (:row-span cell)) "\""))
         "><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/>"
         "<a:t>" (esc (:text cell)) "</a:t></a:r></a:p></a:txBody>"
         "<a:tcPr" (table-cell-tcpr-attrs-xml cell) ">" (table-cell-borders-xml (:borders cell))
         (when (:fill cell)
           (str "<a:solidFill><a:srgbClr val=\"" (hex-color (:fill cell) "FFFFFF") "\"/></a:solidFill>"))
         "</a:tcPr></a:tc>")

    :else
    (str "<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/>"
         "<a:t>" (esc cell) "</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc>")))

(defn- table-row-xml [row-height-emu cells]
  (str "<a:tr h=\"" row-height-emu "\">" (apply str (map table-cell-xml cells)) "</a:tr>"))

(defn- table-grid-xml [col-widths-emu]
  (str "<a:tblGrid>"
       (apply str (map #(str "<a:gridCol w=\"" % "\"/>") col-widths-emu))
       "</a:tblGrid>"))

(defn- normalize-cell
  "Passes a structured cell (map/merge-marker) through unchanged; coerces
  any plain scalar (the common case, including non-string values like a
  hand-authored deck's numbers) to a string."
  [cell]
  (if (or (map? cell) (merge-markers cell)) cell (str cell)))

(defn- normalize-rows
  "Pads every row to `col-count` cells (missing cells become blank) and
  ensures at least one row/column exists, so a malformed/empty source grid
  still produces a structurally valid table instead of a corrupt one."
  [rows col-count]
  (let [rows (if (seq rows) rows [[""]])]
    (mapv (fn [row] (vec (take col-count (concat (map normalize-cell row) (repeat ""))))) rows)))

(defn- table-style-flags-xml
  "A table's own :slides/table-style-flags ({:first-row? true ...}, from
  drawingml.parse/table-style-flags on import) into <a:tblPr>'s own
  firstRow/lastRow/firstCol/lastCol/bandRow/bandCol attributes. When the
  shape carries NO table-style-flags at all (a hand-authored deck that
  never went through import, or a source table whose own <a:tblPr> set
  none of these), defaults to this writer's own historical firstRow+
  bandRow -- unchanged output for every deck built before this feature
  existed. Previously hardcoded UNCONDITIONALLY regardless of the source
  table's actual flags -- an imported table banding COLUMNS instead of
  rows, or one with no header-row emphasis at all, always had its real
  style silently overwritten on export."
  [flags]
  (if flags
    (str (when (:first-row? flags) " firstRow=\"1\"")
         (when (:last-row? flags) " lastRow=\"1\"")
         (when (:first-col? flags) " firstCol=\"1\"")
         (when (:last-col? flags) " lastCol=\"1\"")
         (when (:band-row? flags) " bandRow=\"1\"")
         (when (:band-col? flags) " bandCol=\"1\""))
    " firstRow=\"1\" bandRow=\"1\""))

(defn- graphic-frame-locks-xml
  "A table/chart's own :slides/locks ({:no-grp? true ...}, from
  drawingml.parse/graphic-frame-locks on import) into
  <a:graphicFrameLocks>'s own attributes. Falls back to this writer's
  own historical default (noGrp=\"1\") only when :slides/locks is absent
  -- unchanged output for every deck built before this feature existed."
  [locks]
  (if locks
    (str (when (:no-grp? locks) " noGrp=\"1\"")
         (when (:no-drilldown? locks) " noDrilldown=\"1\"")
         (when (:no-select? locks) " noSelect=\"1\"")
         (when (:no-change-aspect? locks) " noChangeAspect=\"1\"")
         (when (:no-move? locks) " noMove=\"1\"")
         (when (:no-resize? locks) " noResize=\"1\""))
    " noGrp=\"1\""))

(defn- table-shape
  "Writes a :table shape as a native <p:graphicFrame><a:tbl> instead of
  degrading to plain text -- table cells (:slides/cells when the table has
  a merge/span/per-cell-fill, else the plain :slides/rows text grid, from
  either a hand-authored deck or a PPTX import) round-trip through full
  PPTX regeneration, not just the source-aware `update` patch path."
  [idx {:slides/keys [id rows cells w h column-widths row-heights] :as shape}]
  (let [rows (or cells rows)
        col-count (max 1 (apply max 1 (map count rows)))
        norm-rows (normalize-rows rows col-count)
        row-count (count norm-rows)
        total-w (emu (positive-numeric w 8.4))
        total-h (emu (positive-numeric h 2.0))
        col-w (quot total-w col-count)
        row-h (quot total-h row-count)
        col-widths-emu (if (= (count column-widths) col-count)
                          (map emu column-widths)
                          (repeat col-count col-w))
        row-heights-emu (if (= (count row-heights) row-count)
                           (map emu row-heights)
                           (repeat row-count row-h))]
    (str "<p:graphicFrame>"
         "<p:nvGraphicFramePr><p:cNvPr id=\"" (+ 10 idx) "\" name=\"" (esc (or id (str "Table " idx))) "\"" (hidden-attr shape) "/>"
         "<p:cNvGraphicFramePr><a:graphicFrameLocks" (graphic-frame-locks-xml (:slides/locks shape)) "/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>"
         "<p:xfrm><a:off x=\"" (emu (numeric (:slides/x shape) 0)) "\" y=\"" (emu (numeric (:slides/y shape) 0)) "\"/>"
         "<a:ext cx=\"" total-w "\" cy=\"" total-h "\"/></p:xfrm>"
         "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">"
         "<a:tbl><a:tblPr" (table-style-flags-xml (:slides/table-style-flags shape)) "><a:tableStyleId>" default-table-style-id "</a:tableStyleId></a:tblPr>"
         (table-grid-xml col-widths-emu)
         (apply str (map table-row-xml row-heights-emu norm-rows))
         "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")))

(defn- pic-locks-xml
  "A picture's own :slides/locks ({:no-change-aspect? true ...}, from
  drawingml.parse/picture-locks on import) into <a:picLocks>'s own
  attributes. Falls back to this writer's own historical default
  (noChangeAspect=\"1\") only when :slides/locks is absent -- unchanged
  output for every deck built before this feature existed."
  [locks]
  (if locks
    (str (when (:no-change-aspect? locks) " noChangeAspect=\"1\"")
         (when (:no-move? locks) " noMove=\"1\"")
         (when (:no-resize? locks) " noResize=\"1\"")
         (when (:no-rot? locks) " noRot=\"1\""))
    " noChangeAspect=\"1\""))

(defn- pic-shape
  "Writes an :image shape as a native <p:pic> referencing an already-embedded
  media part via `rel-id` (see `slide-image-rels`/`pptx-files`). Callers must
  only reach this when a rel-id actually exists for the shape -- with no
  rel-id, `render-shape` falls back to a plain text box instead of emitting a
  dangling r:embed that would corrupt the package."
  [idx {:slides/keys [id] :as shape} rel-id]
  (str "<p:pic><p:nvPicPr><p:cNvPr id=\"" (+ 10 idx) "\" name=\"" (esc (or id (str "Picture " idx))) "\"" (hidden-attr shape) "/>"
       "<p:cNvPicPr><a:picLocks" (pic-locks-xml (:slides/locks shape)) "/></p:cNvPicPr><p:nvPr/></p:nvPicPr>"
       "<p:blipFill>" (blip-xml rel-id (:slides/recolor shape)) (src-rect-xml (:slides/crop shape)) "<a:stretch><a:fillRect/></a:stretch></p:blipFill>"
       "<p:spPr>" (shape-xfrm shape) "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>"
       "</p:pic>"))

(defn- chart-shape
  "Writes a :chart shape as a native <p:graphicFrame><c:chart> referencing an
  already-generated chart part via `rel-id` (see `slide-chart-entries`/
  `pptx-files`). Like `pic-shape`, callers must only reach this when a rel-id
  actually exists -- otherwise render-shape falls back to plain text."
  [idx {:slides/keys [id] :as shape} rel-id]
  (str "<p:graphicFrame>"
       "<p:nvGraphicFramePr><p:cNvPr id=\"" (+ 10 idx) "\" name=\"" (esc (or id (str "Chart " idx))) "\"" (hidden-attr shape) "/>"
       "<p:cNvGraphicFramePr><a:graphicFrameLocks" (graphic-frame-locks-xml (:slides/locks shape)) "/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>"
       "<p:xfrm><a:off x=\"" (emu (numeric (:slides/x shape) 0)) "\" y=\"" (emu (numeric (:slides/y shape) 0)) "\"/>"
       "<a:ext cx=\"" (emu (positive-numeric (:slides/w shape) 4)) "\" cy=\"" (emu (positive-numeric (:slides/h shape) 3)) "\"/></p:xfrm>"
       "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">"
       "<c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"" rel-id "\"/>"
       "</a:graphicData></a:graphic></p:graphicFrame>"))

(defn- render-shape
  ([deck idx shape] (render-shape deck idx shape {}))
  ([deck idx shape opts]
   (let [shape (design/resolve-shape deck shape)
         image-rel-id (get-in opts [:image-rels (:slides/id shape)])
         chart-rel-id (get-in opts [:chart-rels (:slides/id shape)])]
     (case (:slides/shape shape)
       :rect (rect-shape idx shape opts)
       :text (text-shape deck idx shape opts)
       :table (table-shape idx shape)
       :connector (connector-shape idx shape)
       :image (if image-rel-id
                (pic-shape idx shape image-rel-id)
                (text-shape deck idx (assoc shape :slides/text (or (:slides/text shape) "Image")) opts))
       :chart (if chart-rel-id
                (chart-shape idx shape chart-rel-id)
                (text-shape deck idx (assoc shape :slides/text (or (:slides/text shape) "Chart")) opts))
       (text-shape deck idx (assoc shape :slides/text (or (:slides/text shape) (:slides/title shape) "")) opts)))))

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

(defn- master-footer-shape
  "Previously a plain synthetic textbox (positioned/styled like a footer
  but carrying no :slides/placeholder) -- invisible to PowerPoint's own
  footer semantics (Insert > Header & Footer, Outline view, Reset Layout
  all key off a real <p:ph type=\"ftr\"> placeholder, not a shape that
  merely LOOKS like one). Now a genuine footer placeholder, same as
  text-shape already does for title/body shapes carrying :slides/placeholder
  (see placeholder-xml)."
  [deck slide]
  (let [footer (:slides/footer (design/master-for-slide deck slide))]
    (when (:slides/enabled footer)
      (assoc footer
             :slides/id "master-footer"
             :slides/shape :text
             :slides/placeholder {:type "ftr"}
             :slides/text (or (:slides/text footer) (:slides/title deck ""))))))

(defn- master-date-shape
  "A design's own :slides/date ({:slides/enabled true, positioning/style
  same shape as :slides/footer, :slides/text an OPTIONAL fixed date string
  -- PowerPoint recomputes today's date at open time regardless, so a
  fixed string is only the DISPLAYED-until-refresh value, same as
  PowerPoint's own behavior when authoring one by hand). Off (absent
  entirely) unless the design opts in -- unlike the footer, no default
  design ships with it enabled, so existing decks' output is unaffected."
  [deck slide]
  (let [date-cfg (:slides/date (design/master-for-slide deck slide))]
    (when (:slides/enabled date-cfg)
      (assoc date-cfg
             :slides/id "master-date"
             :slides/shape :text
             :slides/placeholder {:type "dt"}
             :slides/text (or (:slides/text date-cfg) "")))))

(defn- slide-number
  "slide's 1-based position within deck's own slide sequence -- derived
  rather than threaded as an extra parameter through slide-shapes' several
  call sites (image/chart/hyperlink scanning, none of which otherwise need
  a slide index at all)."
  [deck slide]
  (inc (or (first (keep-indexed (fn [i s] (when (= s slide) i)) (:slides/slides deck))) 0)))

(defn- master-slide-number-shape
  "A design's own :slides/slide-number, same shape/opt-in convention as
  master-date-shape -- :slides/text is always overridden to the slide's
  own computed position (PowerPoint recomputes it at open time regardless;
  a stale hand-authored number would be actively wrong)."
  [deck slide]
  (let [cfg (:slides/slide-number (design/master-for-slide deck slide))]
    (when (:slides/enabled cfg)
      (assoc cfg
             :slides/id "master-slide-number"
             :slides/shape :text
             :slides/placeholder {:type "sldNum"}
             :slides/text (str (slide-number deck slide))))))

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
                (when-let [footer (master-footer-shape deck slide)] [footer])
                (when-let [date (master-date-shape deck slide)] [date])
                (when-let [n (master-slide-number-shape deck slide)] [n])))))

(defn- transition-xml
  "A slide's own :slides/transition ({:type ... :attrs {...} :speed ...
  :advance-on-click bool :advance-after-time ms}) into <p:transition>, a
  sibling of <p:cSld> in CT_Slide's own schema order (cSld, clrMapOvr?,
  transition?, timing?, extLst?) -- NOT one of cSld's descendants. :type/
  :attrs are the transition-effect element's raw tag/attrs (fade, wipe,
  push, ... -- see presentationml.parse/transition, the read side this
  mirrors); xml.core emits that child so its attrs are correctly escaped.
  \"\" (no element at all) when the slide has no transition, matching
  PowerPoint's own default of omitting <p:transition> entirely."
  [{:keys [type attrs speed advance-on-click advance-after-time] :as transition}]
  (if-not transition
    ""
    (str "<p:transition"
         (when speed (str " spd=\"" speed "\""))
         (when (false? advance-on-click) " advClick=\"0\"")
         (when advance-after-time (str " advTm=\"" advance-after-time "\""))
         ">"
         (when type (xcore/xml [(keyword "p" type) (or attrs {})]))
         "</p:transition>")))

(defn- slide-xml
  ([deck slide] (slide-xml deck slide {}))
  ([deck slide opts]
   (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
        "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
        "<p:cSld><p:bg><p:bgPr>" (background-fill-xml (design/master-for-slide deck slide) (:slides/slide-background slide)) "</p:bgPr></p:bg>"
        "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
        (let [shapes (slide-shapes deck slide)]
          (if (seq shapes)
            (apply str (map-indexed (fn [idx shape] (render-shape deck idx shape opts)) shapes))
            (render-shape deck 0 {:slides/shape :text :slides/id "title" :slides/text (:slides/title slide) :slides/x 0.8 :slides/y 0.8 :slides/w 8.4 :slides/h 1.0 :slides/font-size 32} opts)))
        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>"
        (transition-xml (:slides/transition slide))
        "</p:sld>")))

(defn- slide-rels [layout-idx]
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type rel-slide-layout :target (str "../slideLayouts/slideLayout" layout-idx ".xml")})]))

(defn- slide-image-entries
  "Decodes every :image shape's :slides/image-data on `slide` INTO a media
  part, assigning each a rel-id local to that slide's own .rels (rId1 is
  reserved for the layout relationship) starting at `rid-start`, and a media
  part path built from the running `next-index` (shared across the whole
  deck so two slides' images never collide on the same ppt/media/imageN
  path). Also picks up any :rect/:text shape's :slides/fill-image-data --
  a shape whose own FILL is a picture (<a:blipFill> in <p:spPr>, distinct
  from a <p:pic> :image shape) needs the exact same media-part/relationship
  wiring, just rendered differently (see render-shape)."
  [deck slide next-index rid-start]
  (let [images (->> (slide-shapes deck slide)
                    (filterv #(and (:slides/id %)
                                   (or (and (= :image (:slides/shape %)) (:slides/image-data %))
                                       (:slides/fill-image-data %)))))]
    (vec
     (keep-indexed
      (fn [i shape]
        (when-let [bytes (decode-base64 (or (:slides/image-data shape) (:slides/fill-image-data shape)))]
          (let [media-type (or (:slides/media-type shape) "image/png")]
            {:shape-id (:slides/id shape)
             :rel-id (str "rId" (+ rid-start i))
             :media-type media-type
             :filename (str "image" (+ next-index i) "." (media-extension media-type))
             :bytes bytes})))
      images))))

(declare slide-chart-entries)

(defn- slide-notes-entry
  "The notesSlide part for one slide's :slides/notes, or nil. `rid` is this
  slide's own rels id for the notesSlide relationship, continuing on from
  wherever image/chart rIds left off (2 + images + charts)."
  [slide next-index rid]
  (when-let [notes-text (:slides/notes slide)]
    (let [filename (str "notesSlide" next-index ".xml")]
      {:rel-id (str "rId" rid)
       :notes-filename filename
       :notes-path (str "ppt/notesSlides/" filename)
       :notes-xml (notes-slide-xml notes-text)
       :notes-rels-path (str "ppt/notesSlides/_rels/" filename ".rels")
       :notes-rels-xml (notes-slide-rels-xml)})))

(defn- deck-comment-authors
  "Every slide's :slides/comments' own :author, deduplicated, in first-
  appearance order across the whole deck -- comment authors are assigned
  a stable integer id shared by the whole package (commentAuthors.xml),
  so this has to be computed once up front, before any single slide's
  comments part can be written."
  [slides]
  (into [] (comp (mapcat :slides/comments) (keep :author) (distinct)) slides))

(defn- slide-comments-entry
  "The comments part for one slide's :slides/comments, or nil. `rid` is
  this slide's own rels id for the comments relationship, continuing on
  from wherever image/chart/notes rIds left off."
  [slide next-index rid author-id-by-name]
  (when-let [comments (seq (:slides/comments slide))]
    (let [filename (str "comment" next-index ".xml")]
      {:rel-id (str "rId" rid)
       :comments-filename filename
       :comments-path (str "ppt/comments/" filename)
       :comments-xml (comments-part-xml comments author-id-by-name)})))

(defn- hyperlink-relationship-xml
  "One hyperlink relationship entry, dispatching on whether it carries
  :url (external, TargetMode=\"External\") or :slide-part (an internal
  same-deck \"jump to slide\" link, from :slides/hyperlink-slide-part --
  Target relative to the CURRENT slide's own .rels directory; since both
  live under ppt/slides/ as siblings, that's just the target's bare
  filename, no TargetMode attribute at all, Internal being the schema
  default). Previously ALL hyperlinks were written as external
  regardless of shape -- an internal slide-jump link's raw package path
  (e.g. \"ppt/slides/slide3.xml\") would have been written back as an
  invalid external relationship pointing at that same bare internal
  path, a broken link in the output file."
  [{:keys [rel-id url slide-part]}]
  (if slide-part
    (ooxml/relationship {:id rel-id :type rel-hyperlink :target (last (str/split slide-part #"/"))})
    (ooxml/relationship {:id rel-id :type rel-hyperlink :target url :target-mode "External"})))

(defn- slide-hyperlink-entries
  "Every hyperlink-bearing shape on `slide`, assigned a rel-id local to the
  slide's own .rels, continuing on from wherever image/chart/notes rIds
  left off (2 + images + charts + (if notes 1 0)). Covers both
  :slides/hyperlink (an external URL) and :slides/hyperlink-slide-part (an
  internal same-deck \"jump to slide\" link, from drawingml.parse/
  hyperlink-slide-part on import) -- a shape carries at most one of the
  two, mirroring how the source XML's hlinkClick has exactly one relationship."
  [deck slide rid-start]
  (let [shapes (->> (slide-shapes deck slide)
                    (filterv #(and (:slides/id %) (or (:slides/hyperlink %) (:slides/hyperlink-slide-part %)))))]
    (vec
     (map-indexed
      (fn [i shape]
        (cond-> {:shape-id (:slides/id shape)
                 :rel-id (str "rId" (+ rid-start i))}
          (:slides/hyperlink shape) (assoc :url (:slides/hyperlink shape))
          (:slides/hyperlink-slide-part shape) (assoc :slide-part (:slides/hyperlink-slide-part shape))))
      shapes))))

(defn- hyperlink-rels-map [hyperlink-entries]
  (into {} (map (juxt :shape-id :rel-id)) hyperlink-entries))

(defn- slide-rels-xml [layout-idx image-entries chart-entries notes-entry hyperlink-entries comments-entry]
  (if (or (seq image-entries) (seq chart-entries) notes-entry (seq hyperlink-entries) comments-entry)
    (ooxml/relationships-xml
     (into [(ooxml/relationship {:id "rId1" :type rel-slide-layout :target (str "../slideLayouts/slideLayout" layout-idx ".xml")})]
           (concat
            (map (fn [{:keys [rel-id filename]}]
                   (ooxml/relationship {:id rel-id :type rel-image :target (str "../media/" filename)}))
                 image-entries)
            (map (fn [{:keys [rel-id chart-filename]}]
                   (ooxml/relationship {:id rel-id :type rel-chart :target (str "../charts/" chart-filename)}))
                 chart-entries)
            (when notes-entry
              [(ooxml/relationship {:id (:rel-id notes-entry) :type rel-notes-slide
                                    :target (str "../notesSlides/" (:notes-filename notes-entry))})])
            (map hyperlink-relationship-xml hyperlink-entries)
            (when comments-entry
              [(ooxml/relationship {:id (:rel-id comments-entry) :type rel-comments
                                    :target (str "../comments/" (:comments-filename comments-entry))})]))))
    (slide-rels layout-idx)))

(defn- image-rels-map [image-entries]
  (into {} (map (juxt :shape-id :rel-id)) image-entries))

(defn- chart-rels-map [chart-entries]
  (into {} (map (juxt :shape-id :rel-id)) chart-entries))

(defn deck-slides [deck]
  (let [slides (:slides/slides deck)
        valid-slides (when (sequential? slides)
                       (filterv map? slides))]
    (if (seq valid-slides)
      valid-slides
      [{:slides/id "slide-1"
        :slides/title (:slides/title deck (:slides/id deck "Slides"))
        :slides/shapes []}])))

(defn- deck-master-refs
  "The distinct :slides/master-ref values used across the deck's slides, in
  order of first appearance, always with a leading nil (the deck's single
  default master) -- so a deck where NO slide sets an explicit ref still
  gets exactly one master (:master-idx 1 for every slide, identical output
  to before multiple masters existed), while one that DOES use named
  masters gets one master part per distinct ref used, in addition to the
  default."
  [slides]
  (into [nil] (distinct) (keep :slides/master-ref slides)))

(defn- slide-master-idx [master-refs slide]
  (inc (or (first (keep-indexed (fn [i r] (when (= r (:slides/master-ref slide)) i)) master-refs)) 0)))

(defn- deck-layout-entries
  "One entry per DISTINCT (master-idx, layout-ref) pair actually needed:
  every master always gets its own implicit default (nil layout-ref, the
  historical blank layout) entry first, PLUS one additional entry per
  distinct non-nil :slides/layout-ref a slide belonging to that master
  actually uses -- so a deck where no slide sets an explicit layout-ref
  still gets exactly one layout per master (identical output to before
  layout diversity existed), while one that DOES use named layouts gets
  one layout part per distinct (master, layout) combination used. Order
  (and therefore each entry's GLOBAL 1-based file index) is master-major:
  all of master 1's layouts, then all of master 2's, ..."
  [slides master-refs]
  (let [by-master (group-by #(slide-master-idx master-refs %) slides)]
    (vec
     (mapcat (fn [master-idx]
               (let [master-slides (get by-master master-idx [])
                     layout-refs (into [nil] (distinct) (keep :slides/layout-ref master-slides))]
                 (map (fn [layout-ref] {:master-idx master-idx :layout-ref layout-ref}) layout-refs)))
             (range 1 (inc (count master-refs)))))))

(defn- slide-layout-idx [layout-entries master-idx layout-ref]
  (inc (or (first (keep-indexed (fn [i e]
                                  (when (and (= master-idx (:master-idx e)) (= layout-ref (:layout-ref e)))
                                    i))
                                layout-entries))
           0)))

(defn- master-layout-indices [layout-entries master-idx]
  (vec (keep-indexed (fn [i e] (when (= master-idx (:master-idx e)) (inc i))) layout-entries)))

(defn pptx-files [deck]
  (let [slides (vec (deck-slides deck))
        width (positive-numeric (:slides/width deck) default-width-in)
        height (positive-numeric (:slides/height deck) default-height-in)
        master-refs (deck-master-refs slides)
        master-count (count master-refs)
        layout-entries (deck-layout-entries slides master-refs)
        layout-count (count layout-entries)
        author-names (deck-comment-authors slides)
        author-id-by-name (into {} (map-indexed (fn [idx name] [name idx])) author-names)
        per-slide (reduce (fn [{:keys [acc media-index chart-index notes-index comments-index]} slide]
                            (let [master-idx (slide-master-idx master-refs slide)
                                  layout-idx (slide-layout-idx layout-entries master-idx (:slides/layout-ref slide))
                                  images (slide-image-entries deck slide media-index 2)
                                  charts (slide-chart-entries deck slide chart-index (+ 2 (count images)))
                                  notes (slide-notes-entry slide notes-index (+ 2 (count images) (count charts)))
                                  hyperlinks (slide-hyperlink-entries deck slide (+ 2 (count images) (count charts) (if notes 1 0)))
                                  comments (slide-comments-entry slide comments-index
                                                                 (+ 2 (count images) (count charts) (if notes 1 0) (count hyperlinks))
                                                                 author-id-by-name)]
                              {:acc (conj acc {:slide slide :images images :charts charts :notes notes :hyperlinks hyperlinks
                                               :comments comments :master-idx master-idx :layout-idx layout-idx})
                               :media-index (+ media-index (count images))
                               :chart-index (+ chart-index (count charts))
                               :notes-index (+ notes-index (if notes 1 0))
                               :comments-index (+ comments-index (if comments 1 0))}))
                          {:acc [] :media-index 1 :chart-index 1 :notes-index 1 :comments-index 1}
                          slides)
        slide-plans (:acc per-slide)
        all-media-types (mapcat (fn [{:keys [images]}] (map :media-type images)) slide-plans)
        all-chart-paths (mapcat (fn [{:keys [charts]}] (map :chart-path charts)) slide-plans)
        all-notes-paths (keep (fn [{:keys [notes]}] (:notes-path notes)) slide-plans)
        all-comment-paths (keep (fn [{:keys [comments]}] (:comments-path comments)) slide-plans)
        has-notes? (boolean (seq all-notes-paths))
        has-comments? (boolean (seq all-comment-paths))
        has-handout-master? (boolean (:slides/handout-master? deck))
        custom-xml-parts (:slides/custom-xml-parts deck)
        custom-xml-count (count custom-xml-parts)]
    (vec
     (concat
      [["[Content_Types].xml" (content-types (count slides) all-media-types all-chart-paths all-notes-paths master-count layout-count all-comment-paths has-handout-master? custom-xml-count)]
       ["_rels/.rels" root-rels]
       ["docProps/core.xml" (core-props deck)]
       ["docProps/app.xml" (app-props deck (count slides))]
       ["ppt/presentation.xml" (presentation (count slides) width height master-count (:slides/sections deck))]
       ["ppt/_rels/presentation.xml.rels" (presentation-rels (count slides) has-notes? master-count has-comments? has-handout-master? custom-xml-count)]
       ["ppt/theme/theme1.xml" (theme-xml (design/theme deck))]]
      (mapcat (fn [master-idx]
                (let [layout-indices (master-layout-indices layout-entries master-idx)
                      master-ref (nth master-refs (dec master-idx))]
                  [[(str "ppt/slideMasters/slideMaster" master-idx ".xml") (slide-master (design/master-by-ref deck master-ref) layout-indices)]
                   [(str "ppt/slideMasters/_rels/slideMaster" master-idx ".xml.rels") (slide-master-rels layout-indices)]]))
              (range 1 (inc master-count)))
      (mapcat (fn [[idx {:keys [master-idx layout-ref]}]]
                (let [n (inc idx)]
                  [[(str "ppt/slideLayouts/slideLayout" n ".xml") (slide-layout (design/layout-by-ref deck layout-ref))]
                   [(str "ppt/slideLayouts/_rels/slideLayout" n ".xml.rels") (slide-layout-rels master-idx)]]))
              (map-indexed vector layout-entries))
      (when has-notes?
        [["ppt/notesMasters/notesMaster1.xml" (notes-master-xml)]
         ["ppt/notesMasters/_rels/notesMaster1.xml.rels" notes-master-rels]])
      (when has-comments?
        [["ppt/commentAuthors.xml" (comment-authors-xml author-names)]])
      (when has-handout-master?
        [["ppt/handoutMasters/handoutMaster1.xml" (handout-master-xml)]
         ["ppt/handoutMasters/_rels/handoutMaster1.xml.rels" handout-master-rels]])
      (custom-xml-parts-entries custom-xml-parts)
      (mapcat (fn [[idx {:keys [slide images charts notes hyperlinks comments layout-idx]}]]
                (let [n (inc idx)
                      opts {:image-rels (image-rels-map images) :chart-rels (chart-rels-map charts)
                            :hyperlink-rels (hyperlink-rels-map hyperlinks)}]
                  (concat
                   [[(str "ppt/slides/slide" n ".xml") (slide-xml deck slide opts)]
                    [(str "ppt/slides/_rels/slide" n ".xml.rels") (slide-rels-xml layout-idx images charts notes hyperlinks comments)]]
                   (map (fn [{:keys [filename bytes]}] [(str "ppt/media/" filename) bytes]) images)
                   (mapcat (fn [{:keys [chart-path chart-xml chart-rels-path chart-rels-xml embed-path embed-bytes]}]
                             [[chart-path chart-xml]
                              [chart-rels-path chart-rels-xml]
                              [embed-path embed-bytes]])
                           charts)
                   (when notes
                     [[(:notes-path notes) (:notes-xml notes)]
                      [(:notes-rels-path notes) (:notes-rels-xml notes)]])
                   (when comments
                     [[(:comments-path comments) (:comments-xml comments)]]))))
              (map-indexed vector slide-plans))))))

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

(defn- parse-long-safe [s]
  #?(:clj (try (Long/parseLong (str s)) (catch Exception _ nil))
     :cljs (let [n (js/parseInt (str s) 10)] (when-not (js/isNaN n) n))))

(defn- existing-rels-max-rid
  "The highest numeric rId already used in a slide's own .rels XML (0 if
  none/blank) -- new relationships added by the update path (for brand-new
  images/charts/notes/hyperlinks) must continue past this, not restart at
  the full-regen path's usual rId2, or they'd collide with rIds the source
  deck already assigned."
  [rels-xml]
  (->> (re-seq #"\bId=\"rId(\d+)\"" (or rels-xml ""))
       (keep (comp parse-long-safe second))
       (reduce max 0)))

(defn- max-numbered-entry-index
  "The highest N already used by entries whose path matches `pattern`
  (capturing N), 0 if none -- so new media/chart/notes parts the update path
  adds get filenames that can't collide with ones the source deck already
  has (ppt/media/imageN.*, ppt/charts/chartN.xml, ppt/notesSlides/
  notesSlideN.xml all number independently of one another)."
  [entries pattern]
  (->> (keys entries)
       (keep (fn [path] (some->> (re-find pattern (str path)) second parse-long-safe)))
       (reduce max 0)))

(defn- append-relationships-into-rels-xml [rels-xml new-rels]
  (if (seq new-rels)
    (let [fragment (apply str (map ooxml/relationship-xml new-rels))]
      (if (str/blank? (or rels-xml ""))
        (ooxml/relationships-xml new-rels)
        (str/replace-first rels-xml "</Relationships>" (str fragment "</Relationships>"))))
    rels-xml))

(defn- append-content-type-overrides [ct-xml new-overrides]
  (if (seq new-overrides)
    (let [fragment (apply str (map ooxml/content-type-xml new-overrides))]
      (str/replace-first ct-xml "</Types>" (str fragment "</Types>")))
    ct-xml))

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
      (str/replace-first sheet-xml cell-pattern (replacement-literal cell-xml))

      (and row (re-find row-pattern sheet-xml))
      (str/replace-first sheet-xml row-pattern
                         (fn [row-xml]
                           (str/replace row-xml #"</row>\s*$"
                                        (fn [_] (str cell-xml "</row>")))))

      (str/includes? sheet-xml "</sheetData>")
      (str/replace-first sheet-xml #"</sheetData>"
                         (replacement-literal
                          (str "<row r=\"" row "\">" cell-xml "</row></sheetData>")))

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
          (str/replace-first #"<c:tx\b[\s\S]*?</c:tx>" (replacement-literal tx)))
        (cond-> (re-find #"<c:cat\b[\s\S]*?</c:cat>" block)
          (str/replace-first #"<c:cat\b[\s\S]*?</c:cat>" (replacement-literal cat)))
        (cond-> (re-find #"<c:val\b[\s\S]*?</c:val>" block)
          (str/replace-first #"<c:val\b[\s\S]*?</c:val>" (replacement-literal val))))))

(defn- patch-chart-xml [chart-xml chart-data]
  (if-let [series (seq (chart-series-from-rows chart-data))]
    (reduce (fn [xml [idx data]]
              (replace-nth-element xml "c:ser" idx #(patch-chart-series-block % data)))
            chart-xml
            (map-indexed vector series))
    chart-xml))

;; ---------------------------------------------------------------------------
;; Native chart export (full regeneration path). A chart shape's data source
;; (:slides/chart-data, the same {:rows [...]} shape `update`'s chart-data
;; patching already accepts) drives THREE new parts per chart: the chart XML
;; itself, that chart's own relationship to an embedded data workbook, and a
;; from-scratch minimal .xlsx holding the same rows so PowerPoint's "Edit
;; Data" has something real to open -- not just cached display values.
;; ---------------------------------------------------------------------------

(defn- chart-series-xml
  "One <c:ser> from scratch (unlike patch-chart-series-block, which only
  rewrites <c:tx>/<c:cat>/<c:val> inside an ALREADY-EXISTING series block).
  `series-idx` is this series' 0-based position among all of the chart's
  series, which is also how its data column is derived: category data always
  lives in column A, so the first series is column B, the second C, etc."
  [series-idx {:keys [name categories values]}]
  (let [col (index->col (+ 2 series-idx))
        last-row (+ 1 (max 1 (count categories) (count values)))]
    (str "<c:ser>"
         "<c:idx val=\"" series-idx "\"/><c:order val=\"" series-idx "\"/>"
         "<c:tx><c:strRef><c:f>Sheet1!$" col "$1</c:f><c:strCache><c:ptCount val=\"1\"/>"
         (cache-pt 0 name false) "</c:strCache></c:strRef></c:tx>"
         "<c:cat><c:strRef><c:f>Sheet1!$A$2:$A$" last-row "</c:f>" (str-cache categories) "</c:strRef></c:cat>"
         "<c:val><c:numRef><c:f>Sheet1!$" col "$2:$" col "$" last-row "</c:f>" (num-cache values) "</c:numRef></c:val>"
         "</c:ser>")))

(defn- bar-chart-body-xml [series]
  (str "<c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/>"
       (apply str (map-indexed chart-series-xml series))
       "<c:axId val=\"111111111\"/><c:axId val=\"222222222\"/></c:barChart>"))

(defn- line-chart-body-xml [series]
  (str "<c:lineChart><c:grouping val=\"standard\"/><c:varyColors val=\"0\"/>"
       (apply str (map-indexed chart-series-xml series))
       "<c:marker val=\"1\"/><c:axId val=\"111111111\"/><c:axId val=\"222222222\"/></c:lineChart>"))

(defn- pie-chart-body-xml
  "A pie chart plots exactly one series and has no value/category axes."
  [series]
  (str "<c:pieChart><c:varyColors val=\"1\"/>" (chart-series-xml 0 (first series)) "</c:pieChart>"))

(defn- area-chart-body-xml [series]
  (str "<c:areaChart><c:grouping val=\"standard\"/><c:varyColors val=\"0\"/>"
       (apply str (map-indexed chart-series-xml series))
       "<c:axId val=\"111111111\"/><c:axId val=\"222222222\"/></c:areaChart>"))

(defn- doughnut-chart-body-xml
  "A doughnut chart plots exactly one series and has no value/category
  axes, same as pie -- its one structural difference is holeSize, the
  ring's inner-hole diameter as a percentage of the outer diameter."
  [series]
  (str "<c:doughnutChart><c:varyColors val=\"1\"/>" (chart-series-xml 0 (first series))
       "<c:holeSize val=\"50\"/></c:doughnutChart>"))

(defn- scatter-series-xml
  "One <c:ser> for a scatter chart -- unlike bar/line/area's cat+val (a
  category axis + a value axis), a scatter series is X-Y value PAIRS
  (<c:xVal>/<c:yVal>, BOTH value axes; there's no category axis at all).
  Reuses chart-series-from-rows' {:name :categories :values} shape,
  treating :categories as the X values -- via num-cache, not str-cache,
  since scatter's X axis holds numbers, not category labels."
  [series-idx {:keys [name categories values]}]
  (let [col (index->col (+ 2 series-idx))
        last-row (+ 1 (max 1 (count categories) (count values)))]
    (str "<c:ser>"
         "<c:idx val=\"" series-idx "\"/><c:order val=\"" series-idx "\"/>"
         "<c:tx><c:strRef><c:f>Sheet1!$" col "$1</c:f><c:strCache><c:ptCount val=\"1\"/>"
         (cache-pt 0 name false) "</c:strCache></c:strRef></c:tx>"
         "<c:xVal><c:numRef><c:f>Sheet1!$A$2:$A$" last-row "</c:f>" (num-cache categories) "</c:numRef></c:xVal>"
         "<c:yVal><c:numRef><c:f>Sheet1!$" col "$2:$" col "$" last-row "</c:f>" (num-cache values) "</c:numRef></c:yVal>"
         "</c:ser>")))

(defn- scatter-chart-body-xml [series]
  (str "<c:scatterChart><c:scatterStyle val=\"lineMarker\"/><c:varyColors val=\"0\"/>"
       (apply str (map-indexed scatter-series-xml series))
       "<c:axId val=\"111111111\"/><c:axId val=\"222222222\"/></c:scatterChart>"))

(def ^:private axisless-chart-types
  "Chart types with no value/category axes at all -- a pie/doughnut plots
  proportions of a whole, not points against two scaled axes."
  #{:pie :doughnut})

(defn- chart-axis-title-xml
  "One axis' own <c:title>, schema-ordered after <c:axPos> and before
  <c:crossAx> -- nil (no element at all) when the axis has no title, the
  overwhelming common case (this package's own charts previously never
  had ANY axis title option at all)."
  [title]
  (when title
    (str "<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>" (esc title) "</a:t></a:r></a:p></c:rich></c:tx>"
         "<c:overlay val=\"0\"/></c:title>")))

(defn- category-value-axes-xml
  ([] (category-value-axes-xml nil))
  ([{:keys [category value]}]
   (str "<c:catAx><c:axId val=\"111111111\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling>"
        "<c:delete val=\"0\"/><c:axPos val=\"b\"/>" (chart-axis-title-xml category) "<c:crossAx val=\"222222222\"/></c:catAx>"
        "<c:valAx><c:axId val=\"222222222\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling>"
        "<c:delete val=\"0\"/><c:axPos val=\"l\"/>" (chart-axis-title-xml value) "<c:crossAx val=\"111111111\"/></c:valAx>")))

(defn- value-value-axes-xml
  "A scatter chart's own two axes, BOTH value axes (<c:valAx>) -- unlike
  bar/line/area's one category + one value axis, scatter has no category
  axis at all; X is itself a plotted value, not a discrete label. Reuses
  the same {:category :value} axis-titles shape as category-value-axes-
  xml -- :category names the X (first) value axis' own title, matching
  how scatter-series-xml already treats :categories as X data."
  ([] (value-value-axes-xml nil))
  ([{:keys [category value]}]
   (str "<c:valAx><c:axId val=\"111111111\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling>"
        "<c:delete val=\"0\"/><c:axPos val=\"b\"/>" (chart-axis-title-xml category) "<c:crossAx val=\"222222222\"/></c:valAx>"
        "<c:valAx><c:axId val=\"222222222\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling>"
        "<c:delete val=\"0\"/><c:axPos val=\"l\"/>" (chart-axis-title-xml value) "<c:crossAx val=\"111111111\"/></c:valAx>")))

(defn- chart-legend-xml
  "A chart's own :legend-position (:top/:bottom/:left/:right/:top-right/
  :none, from :slides/chart-legend-position on the shape) into <c:legend>
  -- :none omits the element entirely (no legend at all). Defaults to
  :bottom (this writer's own historical hardcoded position) when absent,
  unchanged output for every chart built before this feature existed."
  [position]
  (when-not (= position :none)
    (str "<c:legend><c:legendPos val=\""
         (case position :top "t" :left "l" :right "r" :top-right "tr" "b")
         "\"/><c:overlay val=\"0\"/></c:legend>")))

(defn- chart-space-xml
  "`legend-position` and `axis-titles` are write-only configuration (from
  :slides/chart-legend-position/:slides/chart-axis-titles on the shape) --
  like this chart subsystem's own :chart-type/:series, there is no chart-
  XML reader anywhere in this package (chart import is reference-metadata
  only: rel-id + resolved chart-part/workbook-part path, never the
  chart's own visual configuration), so these are settable only when
  hand-authoring or programmatically building a deck, not round-tripped."
  [{:keys [chart-type series legend-position axis-titles]}]
  (let [axisless? (axisless-chart-types chart-type)
        scatter? (= :scatter chart-type)
        body (case chart-type
               :line (line-chart-body-xml series)
               :area (area-chart-body-xml series)
               :pie (pie-chart-body-xml series)
               :doughnut (doughnut-chart-body-xml series)
               :scatter (scatter-chart-body-xml series)
               (bar-chart-body-xml series))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
         "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" "
         "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
         "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
         "<c:chart><c:plotArea><c:layout/>"
         body
         (cond
           axisless? nil
           scatter? (value-value-axes-xml axis-titles)
           :else (category-value-axes-xml axis-titles))
         "</c:plotArea>"
         (chart-legend-xml (or legend-position :bottom))
         "<c:plotVisOnly val=\"1\"/></c:chart></c:chartSpace>")))

;; -- a minimal, valid .xlsx (itself an OPC package) embedded as the chart's
;;    editable data source, reusing the same cell-writing primitives
;;    patch-workbook-bytes uses so initial generation and later `update`-path
;;    edits stay consistent.

(defn- xlsx-content-types-xml []
  (ooxml/content-types-xml
   [(ooxml/default-content-type "rels" (:rels ooxml/content-types))
    (ooxml/default-content-type "xml" (:xml ooxml/content-types))
    (ooxml/override-content-type "/xl/workbook.xml" (:xlsx ooxml/content-types))
    (ooxml/override-content-type "/xl/worksheets/sheet1.xml" "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml")]))

(defn- xlsx-root-rels-xml []
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1" :type ooxml/office-document-rel :target "xl/workbook.xml"})]))

(defn- xlsx-workbook-xml []
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
       "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
       "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"))

(defn- xlsx-workbook-rels-xml []
  (ooxml/relationships-xml
   [(ooxml/relationship {:id "rId1"
                         :type "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                         :target "worksheets/sheet1.xml"})]))

(defn- xlsx-sheet-xml [rows]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
       "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
       (apply str
              (map-indexed
               (fn [r row]
                 (str "<row r=\"" (inc r) "\">"
                      (apply str (map-indexed (fn [c v] (cell-value-xml (offset-cell-ref "A1" r c) v)) row))
                      "</row>"))
               rows))
       "</sheetData></worksheet>"))

(defn- xlsx-bytes
  "nil in CLJS: byte-producing export is JVM-only already (pptx-bytes throws
  for :cljs), so a chart shape simply contributes no entries there -- same
  degrade-to-nil-then-fall-back-to-text pattern as decode-base64."
  [rows]
  #?(:clj (zip-bytes-from-entries
           {"[Content_Types].xml" (text-entry-bytes (xlsx-content-types-xml))
            "_rels/.rels" (text-entry-bytes (xlsx-root-rels-xml))
            "xl/workbook.xml" (text-entry-bytes (xlsx-workbook-xml))
            "xl/_rels/workbook.xml.rels" (text-entry-bytes (xlsx-workbook-rels-xml))
            "xl/worksheets/sheet1.xml" (text-entry-bytes (xlsx-sheet-xml rows))})
     :cljs nil))

(defn- slide-chart-entries
  "For every :chart shape on `slide` with usable :slides/chart-data, builds
  the chart XML part + its own relationship to a freshly generated embedded
  xlsx workbook, assigning each a rel-id local to the SLIDE's own .rels
  starting at `rid-start` (continuing on from wherever slide-image-entries
  left off, so a slide with both pictures and charts never collides on the
  same rId) and a globally-unique chart index from the running `next-index`."
  [deck slide next-index rid-start]
  (let [charts (->> (slide-shapes deck slide)
                    (filterv #(and (= :chart (:slides/shape %)) (:slides/id %) (:slides/chart-data %))))]
    (vec
     (keep-indexed
      (fn [i shape]
        (let [series (chart-series-from-rows (:slides/chart-data shape))]
          (when (seq series)
            (let [n (+ next-index i)
                  chart-filename (str "chart" n ".xml")
                  embed-bytes (xlsx-bytes (:rows (:slides/chart-data shape)))]
              (when embed-bytes
                {:shape-id (:slides/id shape)
                 :rel-id (str "rId" (+ rid-start i))
                 :chart-path (str "ppt/charts/" chart-filename)
                 :chart-filename chart-filename
                 :chart-xml (chart-space-xml {:chart-type (:slides/chart-type shape) :series series
                                              :legend-position (:slides/chart-legend-position shape)
                                              :axis-titles (:slides/chart-axis-titles shape)})
                 :chart-rels-path (str "ppt/charts/_rels/chart" n ".xml.rels")
                 :chart-rels-xml (ooxml/relationships-xml
                                  [(ooxml/relationship {:id "rId1" :type rel-package
                                                        :target (str "../embeddings/Microsoft_Excel_Sheet" n ".xlsx")})])
                 :embed-path (str "ppt/embeddings/Microsoft_Excel_Sheet" n ".xlsx")
                 :embed-bytes embed-bytes})))))
      charts))))

(defn pptx-bytes
  "Returns a JVM byte array containing a .pptx generated from an EDN deck map."
  [deck]
  #?(:clj
     (let [baos (ByteArrayOutputStream.)]
       (with-open [zip (ZipOutputStream. baos)]
         (doseq [[path content] (pptx-files deck)]
           (if (bytes? content)
             (add-entry-bytes! zip path content)
             (add-entry! zip path content))))
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

(defn- element-span
  "The [start end] byte range in `block` spanning from the start of the first
  `tag` element to the end of the last one, walked left-to-right so repeated
  identical elements (e.g. two empty paragraphs) resolve to their true,
  distinct positions rather than all collapsing onto the first match."
  [block tag]
  (loop [pos 0 remaining (xml-elements block tag) first-start nil last-end nil]
    (if (empty? remaining)
      (when first-start [first-start last-end])
      (let [el (first remaining)
            idx (str/index-of block el pos)]
        (recur (+ idx (count el)) (rest remaining)
               (or first-start idx)
               (+ idx (count el)))))))

(defn- splice-span [block span replacement]
  (if span
    (let [[start end] span]
      (str (subs block 0 start) replacement (subs block end)))
    block))

(defn- patch-or-insert-xfrm
  "Position/size patch. p:graphicFrame (tables, charts) places its transform
  in a <p:xfrm> child, not <a:xfrm> inside <p:spPr> the way p:sp/p:pic do.
  p:cxnSp (connectors) can legitimately have a zero width or height (a
  perfectly horizontal/vertical line) -- shape-xfrm's positive-numeric would
  otherwise silently substitute a 1-inch fallback and visibly skew the line."
  [block shape kind]
  (let [xfrm (if (= :p/cxnSp kind) (connector-xfrm shape) (shape-xfrm shape))]
    (if (= :p/graphicFrame kind)
      (let [frame-xfrm (str/replace xfrm "a:xfrm" "p:xfrm")]
        (if (re-find #"<p:xfrm\b[\s\S]*?</p:xfrm>" block)
          (str/replace-first block #"<p:xfrm\b[\s\S]*?</p:xfrm>"
                             (replacement-literal frame-xfrm))
          (str/replace-first block #"(</p:nvGraphicFramePr>)"
                             (str "$1" (replacement-literal frame-xfrm)))))
      (if (re-find #"<a:xfrm\b[\s\S]*?</a:xfrm>" block)
        (str/replace-first block #"<a:xfrm\b[\s\S]*?</a:xfrm>"
                           (replacement-literal xfrm))
        (str/replace-first block #"<p:spPr\b([^>]*)>"
                           (str "<p:spPr$1>" xfrm))))))

(def ^:private rpr-pattern #"<a:rPr\b[^>]*/>|<a:rPr\b[^>]*>[\s\S]*?</a:rPr>")
(def ^:private default-rpr "<a:rPr lang=\"en-US\"/>")

(defn- normalize-rpr [rpr]
  (if (re-matches #"<a:rPr\b[^>]*/>" rpr)
    (str (subs rpr 0 (- (count rpr) 2)) "></a:rPr>")
    rpr))

(defn- set-open-tag-attr [xml attr value]
  (let [close-idx (str/index-of xml ">")
        open (subs xml 0 close-idx)
        rest (subs xml close-idx)
        attr-pattern (re-pattern (str "\\b" attr "=\"[^\"]*\""))]
    (str (if (re-find attr-pattern open)
           (str/replace-first open attr-pattern (str attr "=\"" value "\""))
           (str open " " attr "=\"" value "\""))
         rest)))

(defn- remove-tag-attr [xml attr]
  (str/replace xml (re-pattern (str "\\s*\\b" attr "=\"[^\"]*\"")) ""))

(defn- set-self-closing-tag-attr
  "Like set-open-tag-attr, but for a self-closing tag string (e.g.
  <p:cNvPr id=\"2\" name=\"Box\"/>) -- set-open-tag-attr's own
  close-idx/subs split would land INSIDE the trailing \"/>\" here
  (there being no separate opening-tag-then-children shape to split on)
  and corrupt the tag, so this inserts before the closing \"/>\"
  directly instead."
  [xml attr value]
  (let [attr-pattern (re-pattern (str "\\b" attr "=\"[^\"]*\""))]
    (if (re-find attr-pattern xml)
      (str/replace-first xml attr-pattern (str attr "=\"" value "\""))
      (str/replace-first xml #"/>$" (str " " attr "=\"" value "\"/>")))))

(defn- patch-hidden-flag
  "A shape's own :slides/hidden through the source-aware update path,
  toggling <p:cNvPr>'s own hidden=\"1\" attribute in place. Only touches
  the attribute when :slides/hidden is explicitly present on the
  incoming shape map (true to hide, false to un-hide) -- absent leaves
  whatever the source already had untouched, matching every other patch
  function's \"only edit what's explicitly given\" convention.
  Previously :slides/hidden was write-only through full PPTX
  regeneration; toggling a shape's visibility via `update` silently did
  nothing."
  [block shape]
  (if (contains? shape :slides/hidden)
    (if-let [cnvpr (re-find #"<p:cNvPr\b[^>]*/>" block)]
      (str/replace-first block cnvpr
                         (replacement-literal
                          (if (:slides/hidden shape)
                            (set-self-closing-tag-attr cnvpr "hidden" "1")
                            (remove-tag-attr cnvpr "hidden"))))
      block)
    block))

(defn- patch-picture-recolor
  "A picture's own :slides/recolor through the source-aware update path.
  Rebuilds the WHOLE <a:blip> element (self-closing or paired, whichever
  the source already has) via the same blip-xml used by full regen,
  preserving its existing r:embed rel-id -- simpler and more correct than
  trying to insert/replace <a:alphaModFix>/<a:grayscl> children
  independently, since blip-xml already owns the self-closing-vs-paired
  decision in exactly one place. Shared by both <p:pic> and a picture-
  filled <p:sp>'s own <a:blipFill> (blip-xml/blip-recolor-children-xml
  don't care which shape kind they're embedded in). Previously write-
  only through full regen; recoloring an already-imported picture via
  `update` silently did nothing."
  [block shape]
  (if (:slides/recolor shape)
    (if-let [rel-id (some-> (re-find #"<a:blip\b[^>]*\br:embed=\"([^\"]*)\"" block) second)]
      (if-let [blip (re-find #"<a:blip\b[^>]*(?:/>|>[\s\S]*?</a:blip>)" block)]
        (str/replace-first block blip (replacement-literal (blip-xml rel-id (:slides/recolor shape))))
        block)
      block)
    block))

(defn- patch-picture-crop
  "A picture's own :slides/crop through the source-aware update path.
  Replaces an existing <a:srcRect .../> in place, or inserts a fresh one
  right before <a:stretch> (the schema position src-rect-xml/blip-fill-
  xml already use in full regen) when the source has none yet -- same
  insert-or-replace shape as patch-or-insert-xfrm. Previously write-only
  through full regen; cropping an already-imported picture via `update`
  silently did nothing."
  [block shape]
  (if (:slides/crop shape)
    (let [replacement (src-rect-xml (:slides/crop shape))]
      (cond
        (re-find #"<a:srcRect\b[^>]*/>" block)
        (str/replace-first block #"<a:srcRect\b[^>]*/>" (replacement-literal replacement))

        (re-find #"<a:stretch\b" block)
        (str/replace-first block #"<a:stretch\b" (replacement-literal (str replacement "<a:stretch")))

        :else block))
    block))

(defn- patch-effects
  "A shape's own glow/shadow/reflection through the source-aware update
  path. Regenerates the WHOLE <a:effectLst> from the shape's current
  :slides/glow/:slides/shadow/:slides/reflection via the same effect-
  lst-xml full regen uses (OOXML allows only one effectLst per shape, so
  patching each effect independently would risk producing two) -- only
  when at least one of the three keys is present on the incoming shape
  map, matching patch-hidden-flag's \"only touch what's explicitly
  given\" convention; contains?, not truthiness, so explicitly nilling
  out every effect (all three keys present but nil) removes an existing
  <a:effectLst> entirely rather than leaving it stale. Replaces an
  existing effectLst (self-closing or paired) in place, or inserts a
  fresh one right before </p:spPr> when the source has none yet.
  Previously write-only through full regen; adding/changing/removing a
  shape's effects via `update` silently did nothing."
  [block shape]
  (if (or (contains? shape :slides/glow) (contains? shape :slides/shadow) (contains? shape :slides/reflection))
    (let [replacement (or (effect-lst-xml shape) "")]
      (cond
        (re-find #"<a:effectLst\b[^>]*/>" block)
        (str/replace-first block #"<a:effectLst\b[^>]*/>" (replacement-literal replacement))

        (re-find #"<a:effectLst\b[^>]*>[\s\S]*?</a:effectLst>" block)
        (str/replace-first block #"<a:effectLst\b[^>]*>[\s\S]*?</a:effectLst>" (replacement-literal replacement))

        (re-find #"</p:spPr>" block)
        (str/replace-first block #"</p:spPr>" (replacement-literal (str replacement "</p:spPr>")))

        :else block))
    block))

(defn- patch-pic-locks
  "A picture's own :slides/locks through the source-aware update path.
  Replaces <a:picLocks>'s own attributes in place via the same pic-
  locks-xml full regen uses (which already falls back to the historical
  noChangeAspect=\"1\" default when :slides/locks is nil) -- <p:cNvPicPr>
  always has exactly one <a:picLocks/> child in this writer's own
  output, so no insert-when-absent branch is needed. Previously write-
  only through full regen; changing a picture's lock flags via `update`
  silently did nothing."
  [block shape]
  (if (contains? shape :slides/locks)
    (if (re-find #"<a:picLocks\b[^>]*/>" block)
      (str/replace-first block #"<a:picLocks\b[^>]*/>"
                         (replacement-literal (str "<a:picLocks" (pic-locks-xml (:slides/locks shape)) "/>")))
      block)
    block))

(defn- patch-graphic-frame-locks
  "A table/chart's own :slides/locks through the source-aware update
  path. Same shape as patch-pic-locks, sibling attribute on
  <a:graphicFrameLocks> instead of <a:picLocks> -- <p:cNvGraphicFramePr>
  always has exactly one <a:graphicFrameLocks/> child in this writer's
  own output. Previously write-only through full regen; changing a
  table/chart's lock flags via `update` silently did nothing."
  [block shape]
  (if (contains? shape :slides/locks)
    (if (re-find #"<a:graphicFrameLocks\b[^>]*/>" block)
      (str/replace-first block #"<a:graphicFrameLocks\b[^>]*/>"
                         (replacement-literal (str "<a:graphicFrameLocks" (graphic-frame-locks-xml (:slides/locks shape)) "/>")))
      block)
    block))

(defn- patch-sp-locks
  "A text/rect shape's own :slides/locks through the source-aware update
  path. Unlike picLocks/graphicFrameLocks, <p:cNvSpPr> can legitimately
  be self-closing with NO <a:spLocks> child at all (this writer's own
  \"no lock overrides\" default) -- so this rebuilds the WHOLE <p:cNvSpPr>
  element (self-closing or paired, whichever the source already has),
  preserving its own attributes (e.g. txBox=\"1\") via a captured regex
  group, and choosing self-closing vs paired based on whether sp-locks-
  xml returns anything for the shape's current :slides/locks. Previously
  write-only through full regen; changing a shape's lock flags via
  `update` silently did nothing."
  [block shape]
  (if (contains? shape :slides/locks)
    (let [locks-xml (sp-locks-xml (:slides/locks shape))
          rebuild (fn [[_ attrs]]
                    (if locks-xml
                      (str "<p:cNvSpPr" attrs ">" locks-xml "</p:cNvSpPr>")
                      (str "<p:cNvSpPr" attrs "/>")))]
      (cond
        (re-find #"<p:cNvSpPr([^>]*)>[\s\S]*?</p:cNvSpPr>" block)
        (str/replace-first block #"<p:cNvSpPr([^>]*)>[\s\S]*?</p:cNvSpPr>" rebuild)

        (re-find #"<p:cNvSpPr([^>]*)/>" block)
        (str/replace-first block #"<p:cNvSpPr([^>]*)/>" rebuild)

        :else block))
    block))

(defn- set-rpr-color [rpr color]
  (let [hex (hex-color color "17202A")]
    (if (re-find #"<a:solidFill\b[\s\S]*?</a:solidFill>" rpr)
      (str/replace-first rpr #"<a:solidFill\b[\s\S]*?</a:solidFill>"
                         (replacement-literal (str "<a:solidFill><a:srgbClr val=\"" hex "\"/></a:solidFill>")))
      (let [close-idx (inc (str/index-of rpr ">"))]
        (str (subs rpr 0 close-idx)
             "<a:solidFill><a:srgbClr val=\"" hex "\"/></a:solidFill>"
             (subs rpr close-idx))))))

(defn- set-hlink-action
  "A run's own <a:hlinkClick action=\"ppaction://...\"/> (built-in Next/
  Previous/First/Last-slide/end-show navigation, from drawingml.parse/
  hyperlink-action on import) inserted right before </a:rPr> -- schema
  order in this writer always puts hlinkClick last, after latin/ea/
  solidFill (see paragraph-run-xml). `action` nil removes an existing
  hlinkClick entirely rather than leaving it stale, matching patch-
  effects' \"contains? key with nil value clears it\" convention. Only
  the ppaction case -- an external-URL or internal-slide-jump
  hyperlink needs its own new package relationship, out of scope for a
  same-run in-place edit."
  [rpr action]
  (if action
    (let [xml (str "<a:hlinkClick action=\"ppaction://hlinkshowjump?jump=" (get ppaction-jump-queries action) "\"/>")]
      (if (re-find #"<a:hlinkClick\b[^>]*/>" rpr)
        (str/replace-first rpr #"<a:hlinkClick\b[^>]*/>" (replacement-literal xml))
        (str/replace-first rpr #"</a:rPr>$" (replacement-literal (str xml "</a:rPr>")))))
    (str/replace rpr #"<a:hlinkClick\b[^>]*/>" "")))

(defn- apply-rpr-overrides [rpr shape]
  (cond-> (normalize-rpr rpr)
    (:slides/font-size shape) (set-open-tag-attr "sz" (* 100 (long (positive-numeric (:slides/font-size shape) 24))))
    (contains? shape :slides/bold) (set-open-tag-attr "b" (if (:slides/bold shape) "1" "0"))
    (:slides/color shape) (set-rpr-color (:slides/color shape))
    (contains? shape :slides/hyperlink-action) (set-hlink-action (:slides/hyperlink-action shape))))

(defn- patch-all-rpr
  "Style-only edit (no text change): apply font-size/color/bold overrides to
  every run in the block instead of only the first, so multi-run shapes stay
  consistent."
  [block shape]
  (reduce (fn [acc rpr] (str/replace-first acc rpr (replacement-literal (apply-rpr-overrides rpr shape))))
          block
          (re-seq rpr-pattern block)))

(defn- rpr-template [p-block]
  (or (re-find rpr-pattern p-block) default-rpr))

(defn- paragraph-ppr [p-block]
  (or (re-find #"<a:pPr\b[^>]*/>" p-block)
      (re-find #"<a:pPr\b[^>]*>[\s\S]*?</a:pPr>" p-block)
      ""))

(defn- rewrite-paragraph
  "Replaces a paragraph's run content with a single new run carrying `line`,
  reusing its first run's <a:rPr> (with overrides applied) as the style
  template. The paragraph's own <a:pPr> (alignment/line-spacing/bullets/
  indent) is copied through untouched, so editing text never disturbs
  paragraph-level formatting."
  [p-block line shape]
  (str "<a:p>" (paragraph-ppr p-block) "<a:r>" (apply-rpr-overrides (rpr-template p-block) shape)
       "<a:t>" (esc line) "</a:t></a:r></a:p>"))

(defn- new-paragraph [line shape template-rpr]
  (str "<a:p><a:r>" (apply-rpr-overrides template-rpr shape) "<a:t>" (esc line) "</a:t></a:r></a:p>"))

(defn- patch-paragraphs
  "Replaces the <a:p> paragraphs found anywhere in `block` (a txBody, or a
  bare table-cell fragment) with one paragraph per newline-separated line of
  `text`. Each surviving paragraph keeps its own <a:pPr> and inherits its
  first run's formatting (overridden per `shape`); missing lines are dropped,
  extra lines are appended as new plain paragraphs. A block with no <a:p> at
  all (e.g. a picture) is left untouched."
  [block text shape]
  (let [paragraphs (xml-elements block "a:p")]
    (if (empty? paragraphs)
      block
      (let [lines (str/split (str text) #"\n" -1)
            template-rpr (rpr-template (first paragraphs))
            kept (map #(rewrite-paragraph %1 %2 shape) paragraphs lines)
            added (map #(new-paragraph % shape template-rpr) (drop (count paragraphs) lines))]
        (splice-span block (element-span block "a:p") (apply str (concat kept added)))))))

(defn- patch-text [block shape]
  (cond
    (contains? shape :slides/text)
    (patch-paragraphs block (:slides/text shape) shape)

    (or (:slides/font-size shape) (:slides/color shape) (contains? shape :slides/bold)
        (contains? shape :slides/hyperlink-action))
    (patch-all-rpr block shape)

    :else block))

(defn- remove-nth-element* [xml tag idx]
  (let [blocks (vec (xml-elements xml tag))]
    (if-let [block (get blocks idx)]
      (replace-at xml block "")
      xml)))

(defn- patch-table-rows
  "Walks <a:tr> then <a:tc> by document position and patches each cell's own
  paragraphs independently, instead of collapsing the whole table into one
  run of joined text. Cells/rows beyond the source table's own grid are
  ignored -- growing/shrinking a table's dimensions isn't supported yet."
  [block rows shape]
  (reduce
   (fn [acc [row-idx cells]]
     (if-let [row-block (get (vec (xml-elements acc "a:tr")) row-idx)]
       (let [patched-row (reduce
                          (fn [row-acc [col-idx cell-text]]
                            (if-let [cell-block (get (vec (xml-elements row-acc "a:tc")) col-idx)]
                              (replace-at row-acc cell-block (patch-paragraphs cell-block (str cell-text) shape))
                              row-acc))
                          row-block
                          (map-indexed vector cells))]
         (replace-at acc row-block patched-row))
       acc))
   block
   (map-indexed vector rows)))

(defn- patch-gradient-fill
  "Replaces whichever fill element the shape's own <p:spPr> already has
  (<a:gradFill>, <a:solidFill>, or self-closing <a:noFill/>, checked in
  that order) with a real multi-stop <a:gradFill> built from :slides/
  gradient -- same fill-element-agnostic replacement shape as patch-
  solid-fill/patch-line-fill, but a shape can carry ANY of the three
  going in (a plain shape being given its first gradient is the common
  edit this enables), not just the fill kind patch-solid-fill assumes.
  Previously :slides/gradient was write-only through full regen; the
  source-aware `update` patch path silently ignored it entirely."
  [block shape]
  (if (:slides/gradient shape)
    (let [replacement (replacement-literal (gradient-fill-xml (:slides/gradient shape)))]
      (cond
        (re-find #"<a:gradFill\b[\s\S]*?</a:gradFill>" block)
        (str/replace-first block #"<a:gradFill\b[\s\S]*?</a:gradFill>" replacement)

        (re-find #"<a:solidFill\b[\s\S]*?</a:solidFill>" block)
        (str/replace-first block #"<a:solidFill\b[\s\S]*?</a:solidFill>" replacement)

        (re-find #"<a:noFill\s*/>" block)
        (str/replace-first block #"<a:noFill\s*/>" replacement)

        :else block))
    block))

(defn- patch-solid-fill [block shape]
  (if (:slides/fill shape)
    (if (re-find #"<a:solidFill\b[\s\S]*?</a:solidFill>" block)
      (str/replace-first block #"<a:solidFill\b[\s\S]*?</a:solidFill>"
                         (replacement-literal
                          (str "<a:solidFill><a:srgbClr val=\""
                               (hex-color (:slides/fill shape) "EAF0F8")
                               "\"/></a:solidFill>")))
      block)
    block))

(defn- patch-line-fill [block shape]
  (if (:slides/line shape)
    (if (re-find #"<a:ln\b[\s\S]*?</a:ln>" block)
      (str/replace-first block #"<a:ln\b[\s\S]*?</a:ln>"
                         (replacement-literal
                          (str "<a:ln><a:solidFill><a:srgbClr val=\""
                               (hex-color (:slides/line shape) "496B9A")
                               "\"/></a:solidFill></a:ln>")))
      block)
    block))

(defn- patch-shape-block [block shape kind]
  (let [block (-> block
                  (patch-or-insert-xfrm shape kind)
                  (patch-hidden-flag shape)
                  (patch-gradient-fill shape)
                  (patch-solid-fill shape)
                  (patch-line-fill shape)
                  (patch-picture-recolor shape)
                  (patch-picture-crop shape)
                  (patch-effects shape)
                  (patch-pic-locks shape)
                  (patch-graphic-frame-locks shape)
                  (patch-sp-locks shape))
        table-like? (#{:p/graphicFrame :a/tbl} kind)]
    (cond
      ;; A table's <a:p> elements are separated by <a:tc>/<a:tr> boundaries;
      ;; patch-text's whole-block splice would delete those boundaries. Only
      ;; patch cell-by-cell when we actually have a cell grid to align
      ;; against -- a table shape with no :slides/rows is left untouched
      ;; rather than risk corrupting its structure. Chart graphicFrames have
      ;; no <a:p> at all, so patch-text is already a safe no-op for those.
      (and table-like? (:slides/rows shape))
      (patch-table-rows block (:slides/rows shape) shape)

      table-like?
      block

      :else
      (patch-text block shape))))

(defn- source-tag [kind]
  (case kind
    :p/sp "p:sp"
    :p/pic "p:pic"
    :p/cxnSp "p:cxnSp"
    :p/graphicFrame "p:graphicFrame"
    :a/tbl "a:tbl"
    :fallback/text nil
    nil))

(defn- patch-slide-xml [xml shapes]
  (reduce (fn [acc shape]
            (let [source (:ooxml/source shape)
                  kind (:ooxml/kind source)
                  tag (source-tag kind)
                  idx (:ooxml/index source)]
              (if (and tag (integer? idx))
                (replace-nth-element acc tag idx #(patch-shape-block % shape kind))
                acc)))
          xml
          shapes))

(defn- patchable-shapes [deck]
  (->> (deck-slides deck)
       (mapcat :slides/shapes)
       (filter #(get-in % [:ooxml/source :ooxml/part]))
       vec))

(defn- new-shapes-for-slide [slide]
  (->> (:slides/shapes slide)
       (filterv map?)
       (remove #(get-in % [:ooxml/source :ooxml/part]))))

(defn- append-shapes-xml
  ([xml deck new-shapes] (append-shapes-xml xml deck new-shapes {}))
  ([xml deck new-shapes opts]
   (if (seq new-shapes)
     ;; idx offset avoids colliding cNvPr ids with the slide's original shapes.
     (let [fragment (apply str (map-indexed (fn [i shape] (render-shape deck (+ 1000 i) shape opts)) new-shapes))]
       (str/replace-first xml #"</p:spTree>" (replacement-literal (str fragment "</p:spTree>"))))
     xml)))

(defn- deleted-locators [slide]
  (let [inventory (or (:slides/shape-inventory slide) #{})
        present (into #{}
                      (keep (fn [shape]
                              (when-let [source (:ooxml/source shape)]
                                [(:ooxml/kind source) (:ooxml/index source)])))
                      (:slides/shapes slide))]
    (set/difference inventory present)))

(defn- remove-shapes-xml [xml slide]
  (let [by-tag (group-by (fn [[kind _]] (source-tag kind)) (deleted-locators slide))]
    (reduce (fn [acc [tag locators]]
              (if tag
                ;; highest index first: deleting low-to-high would shift the
                ;; positions later (still-pending) indices refer to.
                (reduce (fn [acc' [_ idx]] (remove-nth-element* acc' tag idx))
                        acc
                        (sort-by second > locators))
                acc))
            xml
            by-tag)))

(defn- patch-slide-deletions
  "Applies deletions for one slide's part, after its surviving shapes have
  already been patched in place (patch-slide-xml doesn't change element
  count/order, so this can safely run afterwards against still-valid
  positions). Additions run separately (patch-new-content), since a NEW
  image/chart/notes shape needs new relationships/parts wired up too, not
  just its own <p:sp>/<p:pic>/... fragment appended."
  [xml slide]
  (remove-shapes-xml xml slide))

(defn- patch-base-entries [entries deck]
  (let [by-part (group-by #(get-in % [:ooxml/source :ooxml/part])
                          (patchable-shapes deck))
        entries (reduce (fn [acc [part shapes]]
                          (if-let [bytes (get acc part)]
                            (let [patched (patch-slide-xml (bytes->text bytes) shapes)]
                              (assoc acc part (text-entry-bytes patched)))
                            acc))
                        entries
                        by-part)]
    (reduce (fn [acc slide]
              (let [part (:slides/source slide)]
                (if-let [bytes (and part (get acc part))]
                  (assoc acc part (text-entry-bytes (patch-slide-deletions (bytes->text bytes) slide)))
                  acc)))
            entries
            (deck-slides deck))))

(defn- new-content-relationships [images charts notes hyperlinks]
  (concat
   (map (fn [{:keys [rel-id filename]}]
          (ooxml/relationship {:id rel-id :type rel-image :target (str "../media/" filename)}))
        images)
   (map (fn [{:keys [rel-id chart-filename]}]
          (ooxml/relationship {:id rel-id :type rel-chart :target (str "../charts/" chart-filename)}))
        charts)
   (when notes
     [(ooxml/relationship {:id (:rel-id notes) :type rel-notes-slide
                           :target (str "../notesSlides/" (:notes-filename notes))})])
   (map hyperlink-relationship-xml hyperlinks)))

(defn- new-content-parts [images charts notes]
  (concat
   (map (fn [{:keys [filename bytes]}] [(str "ppt/media/" filename) bytes]) images)
   (mapcat (fn [{:keys [chart-path chart-xml chart-rels-path chart-rels-xml embed-path embed-bytes]}]
             [[chart-path (text-entry-bytes chart-xml)]
              [chart-rels-path (text-entry-bytes chart-rels-xml)]
              [embed-path embed-bytes]])
           charts)
   (when notes
     [[(:notes-path notes) (text-entry-bytes (:notes-xml notes))]
      [(:notes-rels-path notes) (text-entry-bytes (:notes-rels-xml notes))]])))

(defn- ensure-notes-master-entries [entries]
  (if (contains? entries "ppt/notesMasters/notesMaster1.xml")
    entries
    (assoc entries
           "ppt/notesMasters/notesMaster1.xml" (text-entry-bytes (notes-master-xml))
           "ppt/notesMasters/_rels/notesMaster1.xml.rels" (text-entry-bytes notes-master-rels))))

(defn- ensure-presentation-notes-master-rel
  "Wires ppt/_rels/presentation.xml.rels to the notesMaster part, the one
  piece of global (not per-slide) wiring a brand-new notesSlide needs when
  the source deck previously had NO notes anywhere."
  [entries]
  (let [path "ppt/_rels/presentation.xml.rels"
        rels-xml (some-> (get entries path) bytes->text)]
    (if (or (nil? rels-xml) (str/includes? rels-xml rel-notes-master))
      entries
      (assoc entries path
             (text-entry-bytes
              (append-relationships-into-rels-xml
               rels-xml
               [(ooxml/relationship {:id (str "rId" (inc (existing-rels-max-rid rels-xml)))
                                     :type rel-notes-master
                                     :target "notesMasters/notesMaster1.xml"})]))))))

(defn- patch-new-content
  "Adds brand-new (no :ooxml/source) images/charts/notes/hyperlinks that a
  patch-path edit introduced -- previously these silently fell back to a
  plain text box (images/charts, via render-shape's opts-less call) or were
  dropped entirely (notes/hyperlinks with no rId ever assigned), since
  append-shapes-xml only ever emitted the shape's own fragment with no
  accompanying relationship/media/chart-part/notesSlide-part wiring."
  [entries deck]
  (let [start-media (max-numbered-entry-index entries #"ppt/media/image(\d+)\.")
        start-chart (max-numbered-entry-index entries #"ppt/charts/chart(\d+)\.xml")
        start-notes (max-numbered-entry-index entries #"ppt/notesSlides/notesSlide(\d+)\.xml")
        result
        (reduce
         (fn [{:keys [entries media-idx chart-idx notes-idx] :as acc} slide]
           (let [part (:slides/source slide)
                 new-shapes (new-shapes-for-slide slide)]
             (if (or (nil? part) (not (contains? entries part)) (empty? new-shapes))
               acc
               (let [rels-part (rels-path part)
                     rels-xml (some-> (get entries rels-part) bytes->text)
                     rid-start (inc (existing-rels-max-rid rels-xml))
                     already-has-notes-rel? (str/includes? (or rels-xml "") rel-notes-slide)
                     synthetic-slide {:slides/shapes new-shapes
                                      :slides/notes (when-not already-has-notes-rel? (:slides/notes slide))}
                     images (slide-image-entries deck synthetic-slide (inc media-idx) rid-start)
                     charts (slide-chart-entries deck synthetic-slide (inc chart-idx)
                                                 (+ rid-start (count images)))
                     notes (slide-notes-entry synthetic-slide (inc notes-idx)
                                              (+ rid-start (count images) (count charts)))
                     hyperlinks (slide-hyperlink-entries deck synthetic-slide
                                                         (+ rid-start (count images) (count charts) (if notes 1 0)))
                     opts {:image-rels (image-rels-map images)
                           :chart-rels (chart-rels-map charts)
                           :hyperlink-rels (hyperlink-rels-map hyperlinks)}
                     patched-slide-xml (-> (bytes->text (get entries part))
                                           (append-shapes-xml deck new-shapes opts))
                     new-rels (new-content-relationships images charts notes hyperlinks)
                     updated-rels-xml (when (seq new-rels)
                                       (append-relationships-into-rels-xml rels-xml new-rels))]
                 {:entries (into (cond-> (assoc entries part (text-entry-bytes patched-slide-xml))
                                   updated-rels-xml (assoc rels-part (text-entry-bytes updated-rels-xml)))
                                 (new-content-parts images charts notes))
                  :media-idx (+ media-idx (count images))
                  :chart-idx (+ chart-idx (count charts))
                  :notes-idx (+ notes-idx (if notes 1 0))
                  :media-types (into (:media-types acc) (map :media-type images))
                  :chart-paths (into (:chart-paths acc) (map :chart-path charts))
                  :notes-paths (into (:notes-paths acc) (when notes [(:notes-path notes)]))}))))
         {:entries entries :media-idx start-media :chart-idx start-chart :notes-idx start-notes
          :media-types [] :chart-paths [] :notes-paths []}
         (deck-slides deck))
        any-notes? (boolean (seq (:notes-paths result)))
        entries' (cond-> (:entries result)
                   any-notes? ensure-notes-master-entries
                   any-notes? ensure-presentation-notes-master-rel)
        ct-path "[Content_Types].xml"
        ct-xml (some-> (get entries' ct-path) bytes->text)]
    (if (str/blank? ct-xml)
      entries'
      (let [ct-xml (reduce (fn [xml media-type]
                             (ooxml/ensure-content-type-extension xml (media-extension media-type) media-type))
                           ct-xml
                           (distinct (:media-types result)))
            ct-xml (cond-> ct-xml
                     (seq (:chart-paths result))
                     (ooxml/ensure-content-type-extension "xlsx" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            chart-overrides (for [path (:chart-paths result)
                                  :when (not (str/includes? ct-xml path))]
                              (ooxml/override-content-type (str "/" path) "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"))
            notes-overrides (concat
                             (when (and any-notes? (not (str/includes? ct-xml "notesMaster1.xml")))
                               [(ooxml/override-content-type "/ppt/notesMasters/notesMaster1.xml"
                                                             "application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml")])
                             (for [path (:notes-paths result)
                                   :when (not (str/includes? ct-xml path))]
                               (ooxml/override-content-type (str "/" path) "application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml")))
            ct-xml (append-content-type-overrides ct-xml (concat chart-overrides notes-overrides))]
        (assoc entries' ct-path (text-entry-bytes ct-xml))))))

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
             (patch-new-content deck)
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
