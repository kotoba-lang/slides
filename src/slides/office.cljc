(ns slides.office
  "Office PPTX to slides deck bridge (EDN/CLJC only)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [office.graph :as office-graph]
            [office-style.style :as office-style]
            [slides.model :as model]
            [slides.pptx :as pptx]))

(def emu-per-inch 914400)

(defn- sanitize-id [x]
  (let [base (-> (or x "")
                 str
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"-{2,}" "-")
                 (str/replace #"(^-)|(-$)" ""))]
    (if (str/blank? base) "imported" base)))

(defn- emu->inches [value fallback]
  (if (and value (pos? value))
    (double (/ value emu-per-inch))
    (double fallback)))

(defn- title->id [title]
  (-> title sanitize-id))

(defn- natural-sort-key [s]
  (->> (re-seq #"\d+|\D+" (str s))
       (mapv (fn [part]
               (if (re-matches #"\d+" part)
                 [0 (count part) part]
                 [1 part])))))

(defn- slide-sources-from-graph [graph]
  (->> (:office/nodes graph)
       (filter #(= :slide (:office/kind %)))
       (map :office/id)
       (sort-by natural-sort-key)
       vec))

(defn- ordered-slide-sources [graph style-ir]
  (let [declared (seq (:office-style/slides style-ir))
        observed (slide-sources-from-graph graph)]
    (if (seq declared)
      (let [observed->set (set observed)
            declared-visible (vec (dedupe (filter observed->set declared)))
            declared-visible->set (set declared-visible)
            unseen-observed (vec (dedupe (remove declared-visible->set observed)))]
        (if (seq declared-visible)
          (vec (concat declared-visible unseen-observed))
          observed))
      observed)))

(defn- slide-title [idx source]
  (str "Slide " (inc idx) (when source (str " · " (sanitize-id source)))))

(defn- slide-id [source idx]
  (str "slide-" (inc idx)))

(defn- text-shape-for-node [text-node idx text-color]
  (model/text-box
   (str "text-" (inc idx))
   (or (:office/text text-node) "")
   {:slides/x 0.8
    :slides/y (+ 0.8 (* 1.1 idx))
    :slides/w 8.4
    :slides/h 0.9
    :slides/font-size 20
    :slides/color (or text-color "17202A")}))

(defn- build-slide [source idx text-nodes colors]
  (let [text-color (or (get colors :office-style.color/accent1) "17202A")
        shapes (->> text-nodes
                    (sort-by (fn [node]
                               (or (some-> (re-find #"#text-(\\d+)$" (:office/id node))
                                           second
                                           Long/parseLong)
                                   0)))
                    (map-indexed (fn [i node] (text-shape-for-node node i text-color)))
                    vec)]
    (-> (model/slide (slide-id source idx)
                     {:slides/title (slide-title idx source)
                      :slides/source source})
        (assoc :slides/source source)
        (as-> slide (reduce model/add-shape slide shapes)))))

(defn- build-deck [style-title style-ir]
  (let [colors (or (:office-style/colors style-ir) {})
        fonts (or (:office-style/fonts style-ir) {})
        slide-size (:office-style/slide-size style-ir)]
    (let [base-width 10
          base-height 5.625
          width (emu->inches (:office-style/cx slide-size) base-width)
          height (emu->inches (:office-style/cy slide-size) base-height)]
      (-> (model/deck "imported-deck"
                      {:slides/title style-title
                       :slides/width width
                       :slides/height height
                       :slides/theme {:slides/format "office-style"
                                     :slides/colors colors
                                     :slides/fonts fonts
                                     :slides/slide-size slide-size}})
          (assoc :slides/id (title->id style-title))))))

(defn- preferred-title [options]
  (let [candidate (or (:title options)
                      (:slides/title options))]
    (when-not (str/blank? (str candidate))
      candidate)))

(defn deck-from-office-bytes
  "Build a slides deck from Office package bytes using EDN-only readers."
  ([bytes]
   (deck-from-office-bytes bytes {}))
  ([bytes options]
   (let [graph (office-graph/analyze-bytes bytes)
         style-ir (try (office-style/extract-bytes bytes)
                       (catch Exception _ {}))
         sources (ordered-slide-sources graph style-ir)
         effective-sources (if (seq sources) sources ["imported"])
         text-by-source (->> (:office/nodes graph)
                             (filter #(= :text (:office/kind %)))
                             (group-by :office/source))
         style-title (or (preferred-title options) "Imported deck")
         deck (build-deck style-title style-ir)]
     (reduce
      (fn [acc idx]
        (let [source (nth effective-sources idx)
              shapes (get text-by-source source [])]
          (if (seq shapes)
            (model/add-slide acc (build-slide source idx shapes (:office-style/colors style-ir {})))
            (model/add-slide acc (build-slide source idx [] (:office-style/colors style-ir {}))))))
      deck
      (range (count effective-sources))))))

(defn deck-edn-from-office-bytes
  "Build printable deck EDN from Office package bytes."
  ([bytes]
   (deck-edn-from-office-bytes bytes {}))
  ([bytes options]
   (pr-str (deck-from-office-bytes bytes options))))

(defn pptx-bytes-from-deck-edn
  "Build PPTX bytes from printable deck EDN."
  [deck-edn]
  (pptx/pptx-bytes (edn/read-string deck-edn)))
