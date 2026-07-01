(ns slides.pptx.import
  "PPTX DrawingML/PresentationML to slides EDN projection."
  (:require [clojure.string :as str]))

(def emu-per-inch 914400)
(def default-width 10.0)
(def default-height 5.625)

(defn xml-unescape [s]
  (-> (str (or s ""))
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'")
      (str/replace "&amp;" "&")))

(defn xml-attr [xml attr]
  (some-> (second (re-find (re-pattern (str "\\b" attr "=\"([^\"]*)\"")) (or xml "")))
          xml-unescape))

(defn xml-texts [xml tag]
  (->> (re-seq (re-pattern (str "<" tag "\\b[^>]*>([\\s\\S]*?)</" tag ">")) (or xml ""))
       (map second)
       (map xml-unescape)
       (remove str/blank?)))

(defn first-xml-text [xml tag]
  (first (xml-texts xml tag)))

(defn xml-elements [xml tag]
  (re-seq (re-pattern (str "<" tag "\\b[\\s\\S]*?</" tag ">")) (or xml "")))

(defn parse-long-safe [x]
  (when (and x (re-matches #"-?\d+" (str x)))
    #?(:clj (Long/parseLong (str x))
       :cljs (js/parseInt (str x) 10))))

(defn parse-double-safe [x]
  (when-not (str/blank? (str x))
    (let [n #?(:clj (try (Double/parseDouble (str x))
                         (catch Exception _ nil))
               :cljs (js/parseFloat (str x)))]
      (when #?(:clj (and (some? n) (Double/isFinite n))
               :cljs (js/isFinite n))
        n))))

(defn emu->inch [n fallback]
  (if-let [value (parse-double-safe n)]
    (if (pos? value)
      (double (/ value emu-per-inch))
      fallback)
    fallback))

(defn slide-size-from-presentation [xml]
  (if-let [sld (re-find #"<p:sldSz\b[^>]*>" (or xml ""))]
    {:slides/width (emu->inch (xml-attr sld "cx") default-width)
     :slides/height (emu->inch (xml-attr sld "cy") default-height)}
    {:slides/width default-width
     :slides/height default-height}))

(defn slide-number [name]
  (or (some-> (second (re-find #"slide(\d+)\.xml$" (str name))) parse-long-safe)
      0))

(defn shape-name [block idx fallback]
  (or (xml-attr (or (re-find #"<p:cNvPr\b[^>]*>" (or block "")) "") "name")
      (str fallback "-" (inc idx))))

(defn xfrm [block]
  (let [body (or (second (re-find #"<a:xfrm\b[^>]*>([\s\S]*?)</a:xfrm>" (or block ""))) "")
        off (or (re-find #"<a:off\b[^>]*>" body) "")
        ext (or (re-find #"<a:ext\b[^>]*>" body) "")]
    {:slides/x (emu->inch (xml-attr off "x") 0.8)
     :slides/y (emu->inch (xml-attr off "y") 0.8)
     :slides/w (emu->inch (xml-attr ext "cx") 8.4)
     :slides/h (emu->inch (xml-attr ext "cy") 0.7)}))

(defn first-color [xml]
  (some-> (or (second (re-find #"<a:srgbClr\b[^>]*\bval=\"([0-9A-Fa-f]{6})\"" (or xml "")))
              (second (re-find #"\blastClr=\"([0-9A-Fa-f]{6})\"" (or xml ""))))
          str/upper-case))

(defn solid-fill [block]
  (some-> (second (re-find #"<a:solidFill\b[^>]*>([\s\S]*?)</a:solidFill>" (or block "")))
          first-color))

(defn line-fill [block]
  (some-> (second (re-find #"<a:ln\b[^>]*>([\s\S]*?)</a:ln>" (or block "")))
          first-color))

(defn font-size [block fallback]
  (if-let [sz (some-> (re-find #"<a:rPr\b[^>]*>" (or block "")) (xml-attr "sz") parse-double-safe)]
    (double (/ sz 100))
    fallback))

(defn geometry [block]
  (some-> (re-find #"<a:prstGeom\b[^>]*>" (or block ""))
          (xml-attr "prst")
          keyword))

(defn text-shape [idx block]
  (let [texts (vec (xml-texts block "a:t"))
        text (str/join "\n" texts)]
    (when-not (str/blank? text)
      (cond-> (merge {:slides/id (shape-name block idx "text")
                      :slides/shape :text
                      :slides/text text
                      :slides/font-size (font-size block 20)
                      :slides/color (or (solid-fill block) "17202A")}
                     (xfrm block))
        (> (count texts) 1) (assoc :slides/source-kind :drawingml/text-runs)))))

(defn rect-shape [idx block]
  (when (= :rect (geometry block))
    (cond-> (merge {:slides/id (shape-name block idx "rect")
                    :slides/shape :rect
                    :slides/fill (or (solid-fill block) "EAF0F8")}
                   (xfrm block))
      (line-fill block) (assoc :slides/line (line-fill block)))))

(defn pic-shape [idx block]
  (merge {:slides/id (shape-name block idx "pic")
          :slides/shape :text
          :slides/text (shape-name block idx "Picture")
          :slides/source-kind :drawingml/pic
          :slides/font-size 12
          :slides/color "334155"}
         (xfrm block)))

(defn table-shape [idx block]
  (let [texts (vec (xml-texts block "a:t"))]
    (when (seq texts)
      (merge {:slides/id (shape-name block idx "table")
              :slides/shape :text
              :slides/text (str/join "\n" texts)
              :slides/source-kind :drawingml/table
              :slides/font-size 14
              :slides/color "17202A"}
             (xfrm block)))))

(defn fallback-text-shapes [texts]
  (vec
   (map-indexed
    (fn [idx text]
      {:slides/id (str "text-" (inc idx))
       :slides/shape :text
       :slides/text text
       :slides/x 0.8
       :slides/y (+ 0.75 (* idx 0.72))
       :slides/w 8.4
       :slides/h 0.6
       :slides/font-size (if (zero? idx) 30 20)
       :slides/color (if (zero? idx) "17202A" "334155")})
    texts)))

(defn slide-shapes [xml idx]
  (let [shape-blocks (vec (xml-elements xml "p:sp"))
        shapes (vec (keep-indexed (fn [shape-idx block]
                                    (or (text-shape shape-idx block)
                                        (rect-shape shape-idx block)))
                                  shape-blocks))
        pics (vec (map-indexed pic-shape (xml-elements xml "p:pic")))
        tables (vec (keep-indexed table-shape (xml-elements xml "a:tbl")))
        parsed (vec (concat shapes pics tables))]
    (if (seq parsed)
      parsed
      (let [texts (vec (xml-texts xml "a:t"))]
        (if (seq texts)
          (fallback-text-shapes texts)
          [])))))

(defn theme-colors [theme-xml]
  (let [roles ["dk1" "lt1" "dk2" "lt2" "accent1" "accent2" "accent3" "accent4" "accent5" "accent6" "hlink" "folHlink"]]
    (into {}
          (keep (fn [role]
                  (when-let [[_ block] (re-find (re-pattern (str "<a:" role "\\b[^>]*>([\\s\\S]*?)</a:" role ">"))
                                                (or theme-xml ""))]
                    (when-let [color (first-color block)]
                      [(keyword "office-style.color" role) color]))))
          roles)))

(defn theme-fonts [theme-xml]
  (let [major (or (some-> (second (re-find #"<a:majorFont>[\s\S]*?<a:latin\b[^>]*typeface=\"([^\"]+)\"" (or theme-xml ""))) xml-unescape)
                  "Aptos Display")
        minor (or (some-> (second (re-find #"<a:minorFont>[\s\S]*?<a:latin\b[^>]*typeface=\"([^\"]+)\"" (or theme-xml ""))) xml-unescape)
                  "Aptos")]
    {:office-style.font/majorFont major
     :office-style.font/minorFont minor}))

(defn theme [entries]
  (let [theme-xml (entries "ppt/theme/theme1.xml")
        colors (theme-colors theme-xml)
        fonts (theme-fonts theme-xml)]
    (if theme-xml
      (cond-> {:slides/source "ppt/theme/theme1.xml"}
        (seq colors) (assoc :slides/colors colors)
        (seq fonts) (assoc :slides/fonts fonts))
      {})))

(defn slide-title [shapes idx]
  (or (some :slides/text shapes)
      (str "Slide " (inc idx))))

(defn deck-from-entries
  ([entries file-name] (deck-from-entries entries file-name {}))
  ([entries file-name opts]
   (let [presentation (entries "ppt/presentation.xml")
         core (entries "docProps/core.xml")
         size (slide-size-from-presentation presentation)
         slide-paths (->> (keys entries)
                          (filter #(re-matches #"ppt/slides/slide\d+\.xml" %))
                          (sort-by slide-number))
         slides (vec
                 (map-indexed
                  (fn [idx path]
                    (let [shapes (slide-shapes (entries path) idx)]
                      {:slides/id (str "slide-" (inc idx))
                       :slides/title (slide-title shapes idx)
                       :slides/source path
                       :slides/shapes shapes}))
                  slide-paths))
         title (or (:title opts)
                   (:slides/title opts)
                   (first-xml-text core "dc:title")
                   (some-> file-name (str/replace #"\.pptx$" ""))
                   "Imported deck")
         theme (theme entries)]
     (merge {:slides/id "imported-pptx"
             :slides/title title
             :slides/import {:slides/source file-name
                             :slides/format :pptx
                             :slides/text-extraction :drawingml-xml}
             :slides/slides (if (seq slides)
                              slides
                              [{:slides/id "slide-1"
                                :slides/title title
                                :slides/shapes []}])}
            size
            (when (seq theme) {:slides/theme theme})))))

(defn useful-deck? [deck]
  (boolean (seq (:slides/slides deck))))

(defn reconcile-decks [sidecar actual]
  (cond
    (and sidecar (useful-deck? actual))
    (-> (merge sidecar actual)
        (assoc :slides/id (:slides/id sidecar (:slides/id actual))
               :slides/title (:slides/title sidecar (:slides/title actual)))
        (update :slides/import merge {:slides/text-extraction :reconciled-pptx}))

    sidecar
    (assoc-in sidecar [:slides/import :slides/text-extraction] :causal-edn)

    :else actual))
