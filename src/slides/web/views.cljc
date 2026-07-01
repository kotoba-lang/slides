(ns slides.web.views
  "Pure-hiccup views for the slides web editor (.cljc, no reagent import).

  The SAME hiccup data is rendered live by reagent in the browser (cljs) and to
  HTML by shitsuke.hiccup/->html for SSR (clj) — the dual-render contract. Views
  take the app-db map and derive; they carry no side-effects.

  Interaction is via stable data-attributes (not cljs callbacks) so the SSR HTML
  is identical to the live DOM and a single enhancer (slides.web.app) can drive
  re-frame dispatch for both:
    :data-act      button actions (new-deck, add-slide, ...)
    :data-slide    slide thumbnail selection (idx)
    :data-shape    shape selection (idx)
    :data-field    property input (prefixed: shape.x / slide.title / ...)
  This mirrors the legacy document-level dispatch model (so behaviour is
  preserved) while the rendering moves from innerHTML strings to hiccup data."
  (:require [clojure.string :as str]
            [slides.design :as design]
            [shitsuke.style :as sstyle]))

;; ---------------------------------------------------------------------------
;; pure helpers
;; ---------------------------------------------------------------------------

(defn numeric [x fallback]
  (if (number? x) x fallback))

(defn positive [x fallback]
  (if (and (number? x) (pos? x)) x fallback))

(defn valid-hex [x fallback]
  (let [s (-> (or x fallback) str (str/replace #"^#" "") str/upper-case)]
    (if (re-matches #"[0-9A-F]{6}" s) s fallback)))

(defn slide-index [db]
  (let [idx (:selected-slide db)
        slides (vec (:slides/slides (:deck db)))
        max-idx (max 0 (dec (count slides)))]
    (min max-idx (max 0 idx))))

(defn selected-slide [db]
  (let [ss (vec (:slides/slides (:deck db)))]
    (get ss (slide-index db))))

(defn selected-shape [db]
  (when-let [idx (:selected-shape db)]
    (let [slide (selected-slide db)]
      (get (:slides/shapes slide) idx))))

;; ---------------------------------------------------------------------------
;; slide list / canvas
;; ---------------------------------------------------------------------------

(defn thumb-shape [deck idx shape]
  (let [resolved (design/resolve-shape deck shape)
        width (positive (:slides/width deck) 10)
        height (positive (:slides/height deck) 5.625)
        style {:left (str (/ (* 100 (numeric (:slides/x resolved) 0)) width) "%")
               :top (str (/ (* 100 (numeric (:slides/y resolved) 0)) height) "%")
               :width (str (/ (* 100 (positive (:slides/w resolved) 1)) width) "%")
               :height (str (/ (* 100 (positive (:slides/h resolved) 1)) height) "%")}]
    [:i {:class (str "thumb-shape " (name (:slides/shape resolved :text)))
         :style (cond-> style
                  (= :rect (:slides/shape resolved))
                  (assoc :background (str "#" (valid-hex (:slides/fill resolved) "EAF0F8")))
                  (not= :rect (:slides/shape resolved))
                  (assoc :background (if (zero? idx) "#17202A" "#526170")))}]))

(defn thumb-preview [deck slide]
  [:div.thumb-preview
   (map-indexed (fn [i shape] (thumb-shape deck i shape))
                (take 5 (:slides/shapes slide)))])

(defn slide-thumb [deck idx slide selected?]
  [:button.thumb {:class (when selected? "active") :data-slide idx :type "button"}
   [:small (inc idx)]
   (thumb-preview deck slide)
   [:span (:slides/title slide (:slides/id slide))]
   [:em (str (count (:slides/shapes slide)))]])

(defn slide-list [db]
  (let [deck (:deck db)
        ss (vec (:slides/slides deck))
        sel (slide-index db)]
    (into [:div] (map-indexed (fn [i s] (slide-thumb deck i s (= i sel))) ss))))

(defn shape-style [deck-width deck-height shape selected?]
  (let [x (/ (* 100 (numeric (:slides/x shape) 0)) deck-width)
        y (/ (* 100 (numeric (:slides/y shape) 0)) deck-height)
        w (/ (* 100 (positive (:slides/w shape) 1)) deck-width)
        h (/ (* 100 (positive (:slides/h shape) 1)) deck-height)]
    (cond-> {:left (str x "%")
             :top (str y "%")
             :width (str w "%")
             :height (str h "%")
             :font-size (str (positive (:slides/font-size shape) 24) "px")
             :color (str "#" (valid-hex (:slides/color shape) "17202A"))}
      selected? (assoc :outline "2px solid #111827" :outline-offset "2px"))))

(defn shape-node [db idx shape]
  (let [d (:deck db)
        resolved (design/resolve-shape d shape)
        width (positive (:slides/width d) 10)
        height (positive (:slides/height d) 5.625)
        selected? (= idx (:selected-shape db))
        base (str "shape " (name (:slides/shape resolved :text)) (when selected? " selected"))
        style (shape-style width height resolved selected?)]
    (case (:slides/shape resolved)
      :rect
      [:button {:class base :data-shape idx :type "button"
                :style (assoc style
                              :background (str "#" (valid-hex (:slides/fill resolved) "EAF0F8"))
                              :border-color (str "#" (valid-hex (:slides/line resolved) "496B9A")))}
       (when selected?
         [:span.resize-handles
          [:span.resize-handle.nw {:data-resize "nw"}]
          [:span.resize-handle.ne {:data-resize "ne"}]
          [:span.resize-handle.sw {:data-resize "sw"}]
          [:span.resize-handle.se {:id "resize-se" :data-resize "se"}]])]
      [:button {:class base :data-shape idx :type "button" :style style}
       (:slides/text resolved "")
       (when selected?
         [:span.resize-handles
          [:span.resize-handle.nw {:data-resize "nw"}]
          [:span.resize-handle.ne {:data-resize "ne"}]
          [:span.resize-handle.sw {:data-resize "sw"}]
          [:span.resize-handle.se {:id "resize-se" :data-resize "se"}]])])))

(defn canvas [db]
  (let [d (:deck db)
        width (positive (:slides/width d) 10)
        height (positive (:slides/height d) 5.625)
        slide (selected-slide db)]
    [:div#canvas.canvas {:style {:aspect-ratio (str width "/" height)}}
     (map-indexed (fn [i shape] (shape-node db i shape)) (:slides/shapes slide))]))

;; ---------------------------------------------------------------------------
;; properties panel
;; ---------------------------------------------------------------------------

(defn property-input [label id field value opts]
  (let [{:keys [type step]} opts]
    [:label [:span label]
     [:input {:id id
              :value (if (nil? value) "" (str value))
              :data-field field
              :type (or type "text")
              :step step}]]))

(defn select-options [values selected]
  (cons [:option {:value ""}]
        (for [value values
              :let [s (name value)]]
          [:option {:value s :selected (= value selected)} s])))

(defn shape-properties [db]
  (let [d (:deck db)
        raw-shape (selected-shape db)
        shape (design/resolve-shape d raw-shape)
        dd (design/deck-design d)]
    [:div
     [:div.panel-title "Shape"]
     (property-input "ID" "shape-id" "shape.id" (:slides/id raw-shape (:slides/id shape "")) {})
     [:label [:span "Component"]
      [:select#shape-component {:data-field "shape.component"}
       (select-options (keys (:slides/components dd)) (:slides/component raw-shape))]]
     [:label [:span "Text style"]
      [:select#shape-text-style {:data-field "shape.text-style"}
       (select-options (keys (:slides/text-styles dd)) (:slides/text-style raw-shape))]]
     [:label [:span "Kind"]
      [:select#shape-kind {:data-field "shape.kind"}
       [:option {:value "text" :selected (= :text (:slides/shape shape))} "Text"]
       [:option {:value "rect" :selected (= :rect (:slides/shape shape))} "Rect"]]]
     (when (not= :rect (:slides/shape shape))
       [:label [:span "Text"]
        [:textarea#shape-text {:data-field "shape.text"} (:slides/text shape "")]])
     [:div.grid2
      (property-input "X" "shape-x" "shape.x" (:slides/x shape 0) {:type "number" :step "0.1"})
      (property-input "Y" "shape-y" "shape.y" (:slides/y shape 0) {:type "number" :step "0.1"})
      (property-input "W" "shape-w" "shape.w" (:slides/w shape 1) {:type "number" :step "0.1"})
      (property-input "H" "shape-h" "shape.h" (:slides/h shape 1) {:type "number" :step "0.1"})]
     (if (= :rect (:slides/shape shape))
       [:div.grid2
        (property-input "Fill" "shape-fill" "shape.fill" (:slides/fill shape "EAF0F8") {})
        (property-input "Line" "shape-line" "shape.line" (:slides/line shape "496B9A") {})]
       [:div.grid2
        (property-input "Font" "shape-font-size" "shape.font-size" (:slides/font-size shape 24) {:type "number" :step "1"})
        (property-input "Color" "shape-color" "shape.color" (:slides/color shape "17202A") {})])
     [:div.inspector-actions
      [:button#duplicate-shape {:data-act "duplicate-shape" :type "button"} "Duplicate"]
      [:button#delete-shape.danger {:data-act "delete-shape" :type "button"} "Delete"]]]))

(defn slide-properties [db]
  (let [slide (selected-slide db)]
    [:div
     [:div.panel-title "Slide"]
     (property-input "ID" "slide-id" "slide.id" (:slides/id slide "") {})
     (property-input "Title" "slide-title" "slide.title" (:slides/title slide "") {})
     [:button#delete-slide.danger {:data-act "delete-slide" :type "button"} "Delete Slide"]]))

(defn properties-panel [db]
  (if (some? (:selected-shape db))
    (shape-properties db)
    (slide-properties db)))

;; ---------------------------------------------------------------------------
;; workspace / toolbar / rail / root
;; ---------------------------------------------------------------------------

(defn mode-tabs [mode]
  [:div.mode-tabs
   [:button#mode-visual {:class (when (= :visual mode) "active") :data-act "mode-visual" :type "button"} "Visual"]
   [:button#mode-edn {:class (when (= :edn mode) "active") :data-act "mode-edn" :type "button"} "EDN"]])

(defn insert-bar []
  [:div.insert-bar
   [:button#add-text {:data-act "add-text" :type "button"} "Text"]
   [:button#add-rect {:data-act "add-rect" :type "button"} "Rect"]
   [:button#add-title {:data-act "add-title" :type "button"} "Title"]
   [:button#add-panel {:data-act "add-panel" :type "button"} "Panel"]])

(defn zoom-controls [zoom]
  [:div.zoom-controls
   [:button#zoom-out {:data-act "zoom-out" :type "button"} "-"]
   [:button#zoom-reset {:data-act "zoom-reset" :type "button"} (str (long (* 100 (positive zoom 1))) "%")]
   [:button#zoom-in {:data-act "zoom-in" :type "button"} "+"]])

(defn workspace [db]
  (let [mode (:mode db)
        deck (:deck db)
        slide (selected-slide db)
        zoom (positive (:zoom db) 1)]
    [:section.workspace
     [:div.workspace-head
      [:div
       [:h2 (:slides/title deck "Untitled deck")]
       [:p (str (:slides/title slide (:slides/id slide "Slide")))]]
      [:div.workspace-tools
       (mode-tabs mode)
       (insert-bar)
       (zoom-controls zoom)]]
     [:div#visual-pane.stage {:hidden (not= :visual mode)}
      [:div.canvas-shell {:style {:transform (str "scale(" zoom ")")}}
       (canvas db)]]
     [:div#edn-pane {:hidden (not= :edn mode)}
      ;; Uncontrolled textarea keyed by deck content: React won't revert user
      ;; edits (no :on-change), and it remounts with the current deck's pr-str
      ;; whenever the deck changes — mirroring the legacy imperative
      ;; `set! (.-value …)`. Apply EDN reads the live DOM value via the enhancer.
      [:textarea#deck-edn {:spellcheck "false"
                           :key (hash deck)
                           :default-value (pr-str deck)}]
      [:div.edn-actions
       [:button#apply-edn.primary {:data-act "apply-edn" :type "button"} "Apply EDN"]]]
     [:div#error (:error db)]]))

(defn rail [db]
  (let [ss (vec (:slides/slides (:deck db)))
        n (count ss)
        slide (selected-slide db)
        shape-count (count (:slides/shapes slide))]
    [:aside
     [:div.aside-title "Slides"]
     [:div.rail-actions
      [:button#add-slide {:data-act "add-slide" :type "button"} "Add"]
      [:button#duplicate-slide {:data-act "duplicate-slide" :type "button"} "Copy"]]
     (slide-list db)
     [:div#status.status
      [:strong (str n)]
      [:span "slides"]
      [:strong (str shape-count)]
      [:span "shapes"]]]))

(defn toolbar [db]
  (let [deck (:deck db)
        slide-count (count (:slides/slides deck))]
  [:div.toolbar
   [:div.brand
    [:h1 "kotoba-lang/slides"]
    [:p (:slides/title deck "Untitled deck")]]
   [:div.deck-meta
    [:span (:slides/id deck "deck")]
    [:span (str slide-count " slides")]
    [:span "causal PPTX"]]
   [:div.toolbar-actions
    [:button#new-deck {:data-act "new-deck" :type "button"} "New"]
    [:button#undo {:data-act "undo" :type "button" :disabled (empty? (:undo-stack db))} "Undo"]
    [:button#redo {:data-act "redo" :type "button" :disabled (empty? (:redo-stack db))} "Redo"]
    [:label.file-label "Open EDN"
     [:input#edn-file {:type "file" :accept ".edn,text/plain"}]]
    [:label.file-label "Open PPTX"
     [:input#pptx-file {:type "file" :accept ".pptx,application/vnd.openxmlformats-officedocument.presentationml.presentation"}]]
    [:button#download-edn {:data-act "download-edn" :type "button"} "EDN"]
    [:button#download-svgraph {:data-act "download-svgraph" :type "button"} "SVGraph"]
    [:button#download-pptx.primary {:data-act "download-pptx" :type "button"} "PPTX + causal"]
    [:a.github {:href "https://github.com/kotoba-lang/slides"} "GitHub"]]]))

(defn root [db]
  [:div {:class (sstyle/class-name :app)}
   [:header.top (toolbar db)]
   [:main (rail db) (workspace db)]
   [:section#properties.props (properties-panel db)]])
