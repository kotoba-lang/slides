(ns slides.web.effects
  "Browser side-effects for the slides web editor: localStorage persistence,
  file download, and PPTX (OOXML ZIP) import/export. cljs-only — the pure state
  transitions live in slides.web.events (portable .cljc, testable on the JVM).

  Ported verbatim from the legacy slides.web.cljs so EDN/PPTX import-export and
  the localStorage key are behaviourally identical. The dead in-tree PPTX-XML
  emission (content-types/presentation/slide-xml/...) is dropped — slides.pptx
  is the canonical emitter via pptx/pptx-files."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [slides.pptx :as pptx]
            [slides.web.sample :as sample]))

(declare read-pptx-entries pptx-entries->deck)

(def storage-key "kotoba-lang/slides.deck")

(declare pptx-blob read-pptx-entries pptx-entries->deck)

;; ---------------------------------------------------------------------------
;; persistence
;; ---------------------------------------------------------------------------

(defn save-deck! [deck]
  (when deck
    (.setItem js/localStorage storage-key (pr-str deck))))

(defn load-deck []
  (try
    (if-let [raw (.getItem js/localStorage storage-key)]
      (reader/read-string raw)
      sample/sample-deck)
    (catch :default _
      sample/sample-deck)))

;; ---------------------------------------------------------------------------
;; download
;; ---------------------------------------------------------------------------

(defn download! [name mime content]
  (let [blob (js/Blob. #js [content] #js {:type mime})
        url (.createObjectURL js/URL blob)
        a (.createElement js/document "a")]
    (set! (.-href a) url)
    (set! (.-download a) name)
    (.click a)
    (js/setTimeout #(.revokeObjectURL js/URL url) 1000)))

(defn download-pptx! [deck]
  (let [blob (pptx-blob deck)
        url (.createObjectURL js/URL blob)
        a (.createElement js/document "a")]
    (set! (.-href a) url)
    (set! (.-download a) "deck.pptx")
    (.click a)
    (js/setTimeout #(.revokeObjectURL js/URL url) 1000)))

;; ---------------------------------------------------------------------------
;; ZIP writer (stored + deflate-raw via DecompressionStream for read)
;; ---------------------------------------------------------------------------

(defn u8 [xs]
  (js/Uint8Array. (clj->js xs)))

(defn u16 [n]
  [(bit-and n 255) (bit-and (bit-shift-right n 8) 255)])

(defn u32 [n]
  [(bit-and n 255)
   (bit-and (bit-shift-right n 8) 255)
   (bit-and (bit-shift-right n 16) 255)
   (bit-and (bit-shift-right n 24) 255)])

(def crc-table
  (delay
    (vec
     (for [n (range 256)]
       (loop [c n k 0]
         (if (= k 8)
           c
           (recur (if (pos? (bit-and c 1))
                    (bit-xor 0xEDB88320 (unsigned-bit-shift-right c 1))
                    (unsigned-bit-shift-right c 1))
                  (inc k))))))))

(defn crc32 [bytes]
  (loop [c 0xFFFFFFFF
         i 0]
    (if (= i (.-length bytes))
      (bit-and (bit-not c) 0xFFFFFFFF)
      (recur (bit-xor (get @crc-table (bit-and (bit-xor c (aget bytes i)) 255))
                      (unsigned-bit-shift-right c 8))
             (inc i)))))

(def encoder (js/TextEncoder.))

(defn encode [s]
  (.encode encoder (str s)))

(defn byte-length [chunks]
  (reduce + 0 (map #(.-length %) chunks)))

(defn zip-blob [files]
  (let [chunks (array)
        central (array)
        offset (atom 0)]
    (doseq [[name text] files]
      (let [data (encode text)
            name-bytes (encode name)
            crc (crc32 data)
            local (u8 (concat [0x50 0x4b 3 4 20 0 0 0 0 0 0 0 0 0]
                              (u32 crc) (u32 (.-length data)) (u32 (.-length data))
                              (u16 (.-length name-bytes)) [0 0]))
            dir (u8 (concat [0x50 0x4b 1 2 20 0 20 0 0 0 0 0 0 0 0 0]
                            (u32 crc) (u32 (.-length data)) (u32 (.-length data))
                            (u16 (.-length name-bytes)) [0 0 0 0 0 0 0 0 0 0 0 0]
                            (u32 @offset)))]
        (.push chunks local name-bytes data)
        (.push central dir name-bytes)
        (swap! offset + (.-length local) (.-length name-bytes) (.-length data))))
    (let [central-size (byte-length (array-seq central))
          end (u8 (concat [0x50 0x4b 5 6 0 0 0 0]
                          (u16 (count files)) (u16 (count files))
                          (u32 central-size) (u32 @offset) [0 0]))]
      (js/Blob. (clj->js (concat (array-seq chunks) (array-seq central) [end]))
                #js {:type "application/vnd.openxmlformats-officedocument.presentationml.presentation"}))))

(defn pptx-blob [deck]
  (zip-blob (pptx/pptx-files deck)))

;; ---------------------------------------------------------------------------
;; file import wrappers (read file → parsed deck | error message)
;; ---------------------------------------------------------------------------

(defn import-edn-file
  "Read a .edn file and call (on-success deck) or (on-error msg)."
  [file on-success on-error]
  (let [r (js/FileReader.)]
    (set! (.-onload r)
          (fn [ev]
            (try
              (on-success (reader/read-string (.. ev -target -result)))
              (catch :default e
                (on-error (.-message e))))))
    (.readAsText r file)))

(defn import-pptx-file
  "Read a .pptx file and call (on-success deck) or (on-error msg)."
  [file on-success on-error]
  (let [r (js/FileReader.)]
    (set! (.-onload r)
          (fn [ev]
            (try
              (-> (read-pptx-entries (.. ev -target -result))
                  (.then (fn [entries]
                           (on-success (pptx-entries->deck entries (.-name file)))))
                  (.catch (fn [e]
                            (on-error (.-message e)))))
              (catch :default e
                (on-error (.-message e))))))
    (.readAsArrayBuffer r file)))

;; ---------------------------------------------------------------------------
;; PPTX import (OOXML ZIP read)
;; ---------------------------------------------------------------------------

(def decoder (js/TextDecoder. "utf-8"))

(defn decode-bytes [bytes]
  (.decode decoder bytes))

(defn u16-at [view pos]
  (.getUint16 view pos true))

(defn u32-at [view pos]
  (.getUint32 view pos true))

(defn find-eocd [view]
  (loop [pos (- (.-byteLength view) 22)]
    (cond
      (neg? pos) nil
      (= 0x06054b50 (u32-at view pos)) pos
      :else (recur (dec pos)))))

(defn central-directory [buffer]
  (let [view (js/DataView. buffer)
        eocd (find-eocd view)]
    (when-not eocd
      (throw (js/Error. "PPTX ZIP end record was not found")))
    (let [total (u16-at view (+ eocd 10))
          dir-offset (u32-at view (+ eocd 16))]
      (loop [idx 0
             pos dir-offset
             out []]
        (if (= idx total)
          out
          (do
            (when-not (= 0x02014b50 (u32-at view pos))
              (throw (js/Error. "PPTX central directory is malformed")))
            (let [method (u16-at view (+ pos 10))
                  compressed-size (u32-at view (+ pos 20))
                  uncompressed-size (u32-at view (+ pos 24))
                  name-len (u16-at view (+ pos 28))
                  extra-len (u16-at view (+ pos 30))
                  comment-len (u16-at view (+ pos 32))
                  local-offset (u32-at view (+ pos 42))
                  name (decode-bytes (js/Uint8Array. buffer (+ pos 46) name-len))]
              (recur (inc idx)
                     (+ pos 46 name-len extra-len comment-len)
                     (conj out {:name name
                                :method method
                                :compressed-size compressed-size
                                :uncompressed-size uncompressed-size
                                :local-offset local-offset})))))))))

(defn entry-bytes [buffer entry]
  (let [view (js/DataView. buffer)
        local-offset (:local-offset entry)]
    (when-not (= 0x04034b50 (u32-at view local-offset))
      (throw (js/Error. (str "PPTX local file header is malformed: " (:name entry)))))
    (let [name-len (u16-at view (+ local-offset 26))
          extra-len (u16-at view (+ local-offset 28))
          data-offset (+ local-offset 30 name-len extra-len)]
      (js/Uint8Array. buffer data-offset (:compressed-size entry)))))

(defn inflate-raw [bytes]
  (if (exists? js/DecompressionStream)
    (let [stream (.stream (js/Blob. #js [bytes]))
          inflated (.pipeThrough stream (js/DecompressionStream. "deflate-raw"))]
      (-> (js/Response. inflated)
          (.arrayBuffer)
          (.then #(js/Uint8Array. %))))
    (js/Promise.reject
     (js/Error. "This browser cannot import compressed PPTX files because DecompressionStream is unavailable"))))

(defn inflate-entry [buffer entry]
  (let [bytes (entry-bytes buffer entry)]
    (case (:method entry)
      0 (js/Promise.resolve bytes)
      8 (inflate-raw bytes)
      (js/Promise.reject
       (js/Error. (str "Unsupported ZIP compression method " (:method entry) " for " (:name entry)))))))

(defn text-entry? [name]
  (or (str/ends-with? name ".xml")
      (str/ends-with? name ".rels")
      (str/ends-with? name ".edn")
      (str/ends-with? name ".json")))

(defn read-pptx-entries [buffer]
  (let [entries (filter #(text-entry? (:name %)) (central-directory buffer))
        tasks (map (fn [entry]
                     (-> (inflate-entry buffer entry)
                         (.then (fn [bytes]
                                  #js [(:name entry) (decode-bytes bytes)]))))
                   entries)]
    (-> (js/Promise.all (clj->js tasks))
        (.then (fn [pairs]
                 (into {}
                       (map (fn [pair]
                              [(aget pair 0) (aget pair 1)]))
                       (array-seq pairs)))))))

(defn xml-unescape [s]
  (-> (str (or s ""))
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'")
      (str/replace "&amp;" "&")))

(defn xml-attr [xml attr]
  (when-let [[_ v] (re-find (js/RegExp. (str attr "=\"([^\"]+)\"")) xml)]
    v))

(defn xml-texts [xml tag]
  (->> (re-seq (js/RegExp. (str "<" tag "[^>]*>([\\s\\S]*?)</" tag ">") "g") xml)
       (map second)
       (map xml-unescape)
       (remove str/blank?)))

(defn first-xml-text [xml tag]
  (first (xml-texts xml tag)))

(def emu-per-inch 914400)

(defn emu->inch [n]
  (/ (js/parseFloat n) emu-per-inch))

(defn slide-size-from-presentation [xml]
  (let [sld (first (re-find #"<p:sldSz[^>]*>" (or xml "")))]
    (if sld
      {:slides/width (or (some-> (xml-attr sld "cx") emu->inch) 10)
       :slides/height (or (some-> (xml-attr sld "cy") emu->inch) 5.625)}
      {:slides/width 10
       :slides/height 5.625})))

(defn theme-colors [theme-xml]
  (let [roles ["dk1" "lt1" "dk2" "lt2" "accent1" "accent2" "accent3" "accent4" "accent5" "accent6"]]
    (into {}
          (keep (fn [role]
                  (when-let [[_ block] (re-find (js/RegExp. (str "<a:" role "[^>]*>([\\s\\S]*?)</a:" role ">")) (or theme-xml ""))]
                    (when-let [color (or (xml-attr block "val")
                                         (second (re-find #"lastClr=\"([0-9A-Fa-f]{6})\"" block)))]
                      [(keyword "office-style.color" role) (str/upper-case color)]))))
          roles)))

(defn theme-fonts [theme-xml]
  (let [major (or (some-> (second (re-find #"<a:majorFont>[\s\S]*?<a:latin[^>]*typeface=\"([^\"]+)\"" (or theme-xml ""))) xml-unescape)
                  "Aptos Display")
        minor (or (some-> (second (re-find #"<a:minorFont>[\s\S]*?<a:latin[^>]*typeface=\"([^\"]+)\"" (or theme-xml ""))) xml-unescape)
                  "Aptos")]
    {:office-style.font/major major
     :office-style.font/minor minor}))

(defn imported-design [entries]
  (let [theme (entries "ppt/theme/theme1.xml")
        colors (theme-colors theme)
        fonts (theme-fonts theme)]
    (cond-> {}
      (seq colors) (assoc-in [:slides/theme :office-style.theme/colors] colors)
      (seq fonts) (assoc-in [:slides/theme :office-style.theme/fonts] fonts)
      theme (assoc-in [:slides/theme :office-style.theme/source] "ppt/theme/theme1.xml"))))

(defn slide-number [name]
  (or (some-> (second (re-find #"slide(\d+)\.xml$" name)) (js/parseInt 10))
      0))

(defn slide-title [texts idx]
  (or (first texts) (str "Slide " (inc idx))))

(defn text-shapes-from-slide [texts]
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

(defn pptx-entries->deck [entries file-name]
  (let [presentation (entries "ppt/presentation.xml")
        core (entries "docProps/core.xml")
        size (slide-size-from-presentation presentation)
        slide-paths (->> (keys entries)
                         (filter #(re-matches #"ppt/slides/slide\d+\.xml" %))
                         (sort-by slide-number))
        slides (vec
                (map-indexed
                 (fn [idx path]
                   (let [texts (vec (xml-texts (entries path) "a:t"))]
                     {:slides/id (str "slide-" (inc idx))
                      :slides/title (slide-title texts idx)
                      :slides/shapes (text-shapes-from-slide
                                      (if (seq texts)
                                        texts
                                        [(str "Slide " (inc idx))]))}))
                 slide-paths))
        title (or (first-xml-text core "dc:title")
                  (some-> file-name (str/replace #"\.pptx$" ""))
                  "Imported deck")]
    (merge {:slides/id "imported-pptx"
            :slides/title title
            :slides/import {:slides/source file-name
                            :slides/format :pptx
                            :slides/text-extraction :drawingml-runs}
            :slides/slides (if (seq slides)
                             slides
                             [{:slides/id "slide-1"
                               :slides/title title
                               :slides/shapes (text-shapes-from-slide [title])}])}
           size
           (let [d (imported-design entries)]
             (when (seq d)
               {:slides/design d})))))
