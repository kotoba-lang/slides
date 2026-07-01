(ns slides.web.app
  "Browser mount + enhancer for the slides web editor.

  State lives in re-frame (slides.web.events, portable). Views are pure hiccup
  (slides.web.views) rendered by reagent. Side-effects (localStorage, PPTX/EDN
  file import-export) live in slides.web.effects and are invoked HERE around
  dispatch — never inside event handlers (which stay pure for JVM/SSR testing).

  Interaction uses a single document-level enhancer over stable data-attributes
  (:data-act / :data-slide / :data-shape / :data-field), mirroring the legacy
  dispatch model so the SSR HTML equals the live DOM and behaviour is preserved."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [slides.design :as design]
            [slides.web.events :as events]
            [slides.web.views :as views]
            [slides.web.effects :as effects]))

;; ---------------------------------------------------------------------------
;; persistence (re-render → localStorage, same as legacy rerender-save!)
;; ---------------------------------------------------------------------------

(defn- install-persistence! []
  (add-watch rfdb/app-db ::persist
             (fn [_ _ _ new] (effects/save-deck! (:deck new)))))

;; ---------------------------------------------------------------------------
;; enhancer: data-attribute → re-frame dispatch
;; ---------------------------------------------------------------------------

(defn- kw-or-nil [v]
  (when-not (str/blank? v) (keyword v)))

(defn- parse-number [v]
  (let [n (js/parseFloat v)]
    (if (js/isNaN n) 0 n)))

(def ^:private numeric-fields
  #{:slides/x :slides/y :slides/w :slides/h :slides/font-size})

(def ^:private keyword-fields
  #{:slides/component :slides/text-style})

(defn- dispatch-field [field value]
  (cond
    (= field "shape.kind")
    (rf/dispatch [:slides/set-shape-kind (keyword value)])

    (str/starts-with? field "shape.")
    (let [kw (keyword "slides" (subs field 6))]
      (rf/dispatch
       [:slides/update-shape-field
        kw
        (cond
          (numeric-fields kw) (parse-number value)
          (keyword-fields kw) (kw-or-nil value)
          :else value)]))

    (str/starts-with? field "slide.")
    (let [kw (keyword "slides" (subs field 6))]
      (rf/dispatch [:slides/update-slide-field kw value]))))

(defn- deck-sub []
  @(rf/subscribe [:slides/deck]))

(defn- zoom-sub []
  @(rf/subscribe [:slides/zoom]))

(defonce ^:private drag-state (atom nil))

(defn- round2 [x]
  (/ (js/Math.round (* x 100)) 100))

(defn- clamp-number [lo hi x]
  (min hi (max lo x)))

(defn- selected-slide []
  (let [deck (deck-sub)
        idx (or (:selected-slide @rfdb/app-db) 0)]
    (get (vec (:slides/slides deck)) idx)))

(defn- shape-at [idx]
  (get (vec (:slides/shapes (selected-slide))) idx))

(defn- start-drag! [event shape-el resize-handle]
  (when (= 0 (.-button event))
    (let [shape-idx (js/parseInt (.getAttribute shape-el "data-shape") 10)
          canvas (.getElementById js/document "canvas")
          rect (.getBoundingClientRect canvas)
          deck (deck-sub)
          shape (shape-at shape-idx)
          resolved (design/resolve-shape deck shape)
          deck-width (or (:slides/width deck) 10)
          deck-height (or (:slides/height deck) 5.625)]
      (.preventDefault event)
      (rf/dispatch [:slides/select-shape shape-idx])
      (rf/dispatch [:slides/mark-undo])
      (reset! drag-state
              {:interaction (if resize-handle :resize :move)
               :handle resize-handle
               :shape-idx shape-idx
               :start-client-x (.-clientX event)
               :start-client-y (.-clientY event)
               :start-x (or (:slides/x resolved) 0)
               :start-y (or (:slides/y resolved) 0)
               :shape-w (or (:slides/w resolved) 1)
               :shape-h (or (:slides/h resolved) 1)
               :deck-width deck-width
               :deck-height deck-height
               :canvas-width (.-width rect)
               :canvas-height (.-height rect)}))))

(defn- drag! [event]
  (when-let [{:keys [interaction handle shape-idx start-client-x start-client-y start-x start-y
                     shape-w shape-h deck-width deck-height canvas-width canvas-height]} @drag-state]
    (let [dx (* (- (.-clientX event) start-client-x) (/ deck-width canvas-width))
          dy (* (- (.-clientY event) start-client-y) (/ deck-height canvas-height))
          max-x (max 0 (- deck-width shape-w))
          max-y (max 0 (- deck-height shape-h))]
      (.preventDefault event)
      (if (= :resize interaction)
        (let [right (+ start-x shape-w)
              bottom (+ start-y shape-h)
              min-w 0.25
              min-h 0.25
              west? (str/includes? handle "w")
              east? (str/includes? handle "e")
              north? (str/includes? handle "n")
              south? (str/includes? handle "s")
              raw-x (if west? (clamp-number 0 (- right min-w) (+ start-x dx)) start-x)
              raw-y (if north? (clamp-number 0 (- bottom min-h) (+ start-y dy)) start-y)
              raw-w (cond
                      east? (clamp-number min-w (- deck-width start-x) (+ shape-w dx))
                      west? (- right raw-x)
                      :else shape-w)
              raw-h (cond
                      south? (clamp-number min-h (- deck-height start-y) (+ shape-h dy))
                      north? (- bottom raw-y)
                      :else shape-h)]
          (rf/dispatch [:slides/set-shape-frame shape-idx
                        (round2 raw-x) (round2 raw-y) (round2 raw-w) (round2 raw-h)]))
        (let [x (min max-x (max 0 (+ start-x dx)))
              y (min max-y (max 0 (+ start-y dy)))]
          (rf/dispatch [:slides/set-shape-position shape-idx (round2 x) (round2 y)]))))))

(defn- act-handler [act]
  (case act
    "new-deck"        (rf/dispatch [:slides/new-deck])
    "add-slide"       (rf/dispatch [:slides/add-slide])
    "duplicate-slide" (rf/dispatch [:slides/duplicate-slide])
    "add-text"        (rf/dispatch [:slides/add-shape :text])
    "add-rect"        (rf/dispatch [:slides/add-shape :rect])
    "add-title"       (rf/dispatch [:slides/add-component :title])
    "add-panel"       (rf/dispatch [:slides/add-component :panel])
    "duplicate-shape" (rf/dispatch [:slides/duplicate-shape])
    "align-left"      (rf/dispatch [:slides/align-selected :x :start])
    "align-center"    (rf/dispatch [:slides/align-selected :x :center])
    "align-right"     (rf/dispatch [:slides/align-selected :x :end])
    "align-top"       (rf/dispatch [:slides/align-selected :y :start])
    "align-middle"    (rf/dispatch [:slides/align-selected :y :center])
    "align-bottom"    (rf/dispatch [:slides/align-selected :y :end])
    "undo"            (rf/dispatch [:slides/undo])
    "redo"            (rf/dispatch [:slides/redo])
    "delete-slide"    (rf/dispatch [:slides/delete-slide])
    "delete-shape"    (rf/dispatch [:slides/delete-shape])
    "zoom-out"        (rf/dispatch [:slides/set-zoom (- (zoom-sub) 0.1)])
    "zoom-in"         (rf/dispatch [:slides/set-zoom (+ (zoom-sub) 0.1)])
    "zoom-reset"      (rf/dispatch [:slides/set-zoom 1.0])
    "mode-visual"     (rf/dispatch [:slides/set-mode :visual])
    "mode-edn"        (rf/dispatch [:slides/set-mode :edn])
    "download-edn"    (effects/download! "deck.edn" "application/edn;charset=utf-8" (pr-str (deck-sub)))
    "download-pptx"   (effects/download-pptx! (deck-sub))
    "download-svgraph" (effects/download-svgraph! (deck-sub))
    "apply-edn"
    (try
      (let [parsed (reader/read-string (.-value (.getElementById js/document "deck-edn")))]
        (rf/dispatch [:slides/apply-edn parsed]))
      (catch :default e
        (rf/dispatch [:slides/set-error (.-message e)])))
    nil))

(defn- install-enhancer! []
  (.addEventListener js/document "click"
                     (fn [event]
                       (let [target (.-target event)]
                         (when-let [act-el (.closest target "[data-act]")]
                           (act-handler (.getAttribute act-el "data-act")))
                         (when-let [slide-el (.closest target "[data-slide]")]
                           (rf/dispatch [:slides/select-slide
                                         (js/parseInt (.getAttribute slide-el "data-slide") 10)]))
                         (when-let [shape-el (.closest target "[data-shape]")]
                           (.stopPropagation event)
                           (let [shape-idx (js/parseInt (.getAttribute shape-el "data-shape") 10)]
                             (if (.-shiftKey event)
                               (rf/dispatch [:slides/toggle-shape-selection shape-idx])
                               (rf/dispatch [:slides/select-shape shape-idx]))))
                         (when (= "canvas" (.-id target))
                           (rf/dispatch [:slides/select-shape nil])))))
  (.addEventListener js/document "pointerdown"
                     (fn [event]
                       (let [target (.-target event)
                             resize-el (.closest target "[data-resize]")
                             shape-el (.closest target "[data-shape]")]
                         (when (and shape-el (not (.-shiftKey event)))
                           (start-drag! event shape-el (some-> resize-el (.getAttribute "data-resize")))))))
  (.addEventListener js/document "pointermove" drag!)
  (.addEventListener js/document "pointerup" #(reset! drag-state nil))
  (.addEventListener js/document "pointercancel" #(reset! drag-state nil))
  (.addEventListener js/document "change"
                     (fn [event]
                       (let [target (.-target event)]
                         (cond
                           (= "edn-file" (.-id target))
                           (when-let [file (aget (.-files target) 0)]
                             (effects/import-edn-file
                              file
                              #(rf/dispatch [:slides/import-deck %])
                              #(rf/dispatch [:slides/set-error %])))

                           (= "pptx-file" (.-id target))
                           (when-let [file (aget (.-files target) 0)]
                             (effects/import-pptx-file
                              file
                              #(rf/dispatch [:slides/import-deck %])
                              #(rf/dispatch [:slides/set-error %])))

                           (.closest target "#properties")
                           (when-let [field (.getAttribute target "data-field")]
                             (dispatch-field field (.-value target)))))))
  (.addEventListener js/document "input"
                     (fn [event]
                       (let [target (.-target event)]
                         (when (and (.closest target "#properties")
                                    (.getAttribute target "data-field"))
                           (dispatch-field (.getAttribute target "data-field")
                                           (.-value target)))))))
  (.addEventListener js/document "keydown"
                     (fn [event]
                       (let [target (.-target event)
                             tag (some-> (.-tagName target) str/lower-case)
                             editing? (#{"input" "textarea" "select"} tag)
                             key (.-key event)]
                         (when-not editing?
                           (case key
                             "Delete" (do (.preventDefault event)
                                          (rf/dispatch [:slides/delete-shape]))
                             "Backspace" (do (.preventDefault event)
                                             (rf/dispatch [:slides/delete-shape]))
                             "ArrowLeft" (do (.preventDefault event)
                                             (rf/dispatch [:slides/nudge-shape -0.1 0]))
                             "ArrowRight" (do (.preventDefault event)
                                              (rf/dispatch [:slides/nudge-shape 0.1 0]))
                             "ArrowUp" (do (.preventDefault event)
                                           (rf/dispatch [:slides/nudge-shape 0 -0.1]))
                             "ArrowDown" (do (.preventDefault event)
                                             (rf/dispatch [:slides/nudge-shape 0 0.1]))
                             nil))
                         (when (and (not editing?)
                                    (or (.-metaKey event) (.-ctrlKey event))
                                    (= "z" (str/lower-case key)))
                           (.preventDefault event)
                           (if (.-shiftKey event)
                             (rf/dispatch [:slides/redo])
                             (rf/dispatch [:slides/undo])))
                         (when (and (not editing?)
                                    (or (.-metaKey event) (.-ctrlKey event))
                                    (= "d" (str/lower-case key)))
                           (.preventDefault event)
                           (rf/dispatch [:slides/duplicate-shape])))))

;; ---------------------------------------------------------------------------
;; mount
;; ---------------------------------------------------------------------------

(defn root-component []
  (let [db @(rf/subscribe [:slides/db])]
    [views/root db]))

(defn mount! []
  (when-let [el (.getElementById js/document "app")]
    (rdom/render [root-component] el)))

(defn init! []
  (events/register!)
  (rf/dispatch [:slides/init (effects/load-deck)])
  (install-persistence!)
  (install-enhancer!)
  (mount!))
