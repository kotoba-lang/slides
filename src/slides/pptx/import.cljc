(ns slides.pptx.import
  "PPTX PresentationML/DrawingML to slides EDN adapter."
  (:require [clojure.string :as str]
            [presentationml.parse :as pml-parse]))

(defn- file-title [file-name]
  (some-> file-name (str/replace #"\.pptx$" "")))

(defn- slides-color-key [[k v]]
  [(keyword "office-style.color" (name k)) v])

(defn- slides-font-key [[k v]]
  [(keyword "office-style.font" (name k)) v])

(defn- theme->slides [theme]
  (when (seq theme)
    (cond-> {}
      (:presentationml/colors theme)
      (assoc :slides/colors (into {} (map slides-color-key) (:presentationml/colors theme)))

      (:presentationml/fonts theme)
      (assoc :slides/fonts (into {} (map slides-font-key) (:presentationml/fonts theme)))

      (:presentationml/source theme)
      (assoc :slides/source (:presentationml/source theme)))))

(defn- shape-kind [kind]
  (case kind
    :rect :rect
    :pic :image
    :table :table
    :chart :chart
    :connector :connector
    :text))

(defn- shape->slides [shape]
  (cond-> {:slides/id (:drawingml/id shape)
           :slides/shape (shape-kind (:drawingml/kind shape))
           :slides/x (:drawingml/x shape)
           :slides/y (:drawingml/y shape)
           :slides/w (:drawingml/w shape)
           :slides/h (:drawingml/h shape)}
    (:drawingml/text shape) (assoc :slides/text (:drawingml/text shape))
    (:drawingml/paragraphs shape) (assoc :slides/paragraphs (:drawingml/paragraphs shape))
    (:drawingml/rows shape) (assoc :slides/rows (:drawingml/rows shape))
    (:drawingml/geometry shape) (assoc :slides/geometry (:drawingml/geometry shape))
    (:drawingml/rotation shape) (assoc :slides/rotation (:drawingml/rotation shape))
    (:drawingml/flip-h shape) (assoc :slides/flip-h (:drawingml/flip-h shape))
    (:drawingml/flip-v shape) (assoc :slides/flip-v (:drawingml/flip-v shape))
    (:drawingml/font-size shape) (assoc :slides/font-size (:drawingml/font-size shape))
    (:drawingml/color shape) (assoc :slides/color (:drawingml/color shape))
    (:drawingml/fill shape) (assoc :slides/fill (:drawingml/fill shape))
    (:drawingml/line shape) (assoc :slides/line (:drawingml/line shape))
    (:drawingml/line-dash shape) (assoc :slides/line-dash (:drawingml/line-dash shape))
    (:drawingml/bold shape) (assoc :slides/bold (:drawingml/bold shape))
    (:drawingml/italic shape) (assoc :slides/italic (:drawingml/italic shape))
    (:drawingml/underline shape) (assoc :slides/underline (:drawingml/underline shape))
    (:drawingml/strikethrough shape) (assoc :slides/strikethrough (:drawingml/strikethrough shape))
    (:drawingml/baseline shape) (assoc :slides/baseline (:drawingml/baseline shape))
    (:drawingml/hyperlink shape) (assoc :slides/hyperlink (:drawingml/hyperlink shape))
    (:drawingml/source-kind shape) (assoc :slides/source-kind (:drawingml/source-kind shape))
    (:drawingml/group shape) (assoc :slides/group (:drawingml/group shape))
    (:drawingml/placeholder shape) (assoc :slides/placeholder (:drawingml/placeholder shape))
    (:drawingml/chart-rel-id shape) (assoc :slides/chart-rel-id (:drawingml/chart-rel-id shape))
    (:drawingml/chart-part shape) (assoc :slides/chart-part (:drawingml/chart-part shape))
    (:drawingml/workbook-part shape) (assoc :slides/workbook-part (:drawingml/workbook-part shape))
    (:ooxml/source shape) (assoc :ooxml/source (:ooxml/source shape))))

(defn- shape-inventory
  "The [kind index] locator of every shape the slide's source XML actually
  held at import time, regardless of whether it survives later EDN edits.
  update-pptx-bytes diffs this against the deck's current shapes to detect
  deletions (a shape absent from the deck but present here) safely, without
  requiring editors to leave tombstones behind."
  [shapes]
  (into #{}
        (keep (fn [shape]
                (when-let [source (:ooxml/source shape)]
                  [(:ooxml/kind source) (:ooxml/index source)])))
        shapes))

(defn- slide->slides [slide]
  (let [shapes (mapv shape->slides (:presentationml/shapes slide))]
    (cond-> {:slides/id (:presentationml/id slide)
             :slides/title (:presentationml/title slide)
             :slides/source (:presentationml/source slide)
             :slides/shapes shapes
             :slides/shape-inventory (shape-inventory shapes)}
      (:presentationml/notes slide) (assoc :slides/notes (:presentationml/notes slide)))))

(defn deck-from-entries
  ([entries file-name] (deck-from-entries entries file-name {}))
  ([entries file-name opts]
   (let [parsed (pml-parse/deck entries opts)
         title (:presentationml/title parsed (or (file-title file-name) "Imported deck"))
         theme (theme->slides (:presentationml/theme parsed))]
     (merge {:slides/id (:presentationml/id parsed "imported-pptx")
             :slides/title title
             :slides/width (:presentationml/width parsed)
             :slides/height (:presentationml/height parsed)
             :slides/import {:slides/source file-name
                             :slides/format :pptx
                             :slides/text-extraction :drawingml-xml}
             :slides/slides (mapv slide->slides (:presentationml/slides parsed))}
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
