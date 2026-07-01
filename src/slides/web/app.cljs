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
                           (rf/dispatch [:slides/select-shape
                                         (js/parseInt (.getAttribute shape-el "data-shape") 10)]))
                         (when (= "canvas" (.-id target))
                           (rf/dispatch [:slides/select-shape nil])))))
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
