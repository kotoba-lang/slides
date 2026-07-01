(ns slides.web.events
  "re-frame events + subscriptions for the slides web editor.

  Pure state transitions (portable .cljc): registered against
  `shitsuke.re-frame.core` so the SAME events/subs run on the JVM mini runtime
  (tests, SSR) and on real re-frame in the browser. App code stays within the
  portable 7-fn subset — no effects/cofx/interceptors. Side-effects (localStorage,
  PPTX import/export) live in slides.web.effects (cljs) and are invoked from
  slides.web.app around dispatch, not inside event handlers."
  (:require #?(:cljs [re-frame.core :as rf]
               :clj [shitsuke.re-frame.core :as rf])
            [slides.design :as design]
            [slides.model :as model]
            [slides.web.sample :as sample]))

;; ---------------------------------------------------------------------------
;; pure helpers (operate on the app-db map)
;; ---------------------------------------------------------------------------

(defn slide-index
  "Clamp :selected-slide into a valid index for the deck's slides."
  [db]
  (let [idx (:selected-slide db)
        slides (vec (:slides/slides (:deck db)))
        max-idx (max 0 (dec (count slides)))]
    (min max-idx (max 0 idx))))

(defn- next-id [prefix xs]
  (str prefix "-" (inc (count xs))))

(defn- clamp [lo hi x]
  (min hi (max lo x)))

(defn- replace-slide [db idx f]
  (update-in db [:deck :slides/slides]
             (fn [xs] (vec (map-indexed (fn [i x] (if (= i idx) (f x) x)) xs)))))

(defn- replace-shape [db slide-idx shape-idx f]
  (replace-slide db slide-idx
                 (fn [slide]
                   (update slide :slides/shapes
                           (fn [xs]
                             (vec (map-indexed (fn [i x] (if (= i shape-idx) (f x) x)) xs)))))))

;; ---------------------------------------------------------------------------
;; event handlers (top-level fns so the call graph is trivial to read)
;; ---------------------------------------------------------------------------

(defn init-handler [_ [_ deck]]
  {:deck deck :selected-slide 0 :selected-shape nil :mode :visual :error nil
   :zoom 1.0 :edn-text (pr-str deck) :edn-key 0})

(defn new-deck-handler [_ _]
  {:deck sample/sample-deck :selected-slide 0 :selected-shape nil :mode :visual :error nil
   :zoom 1.0 :edn-text (pr-str sample/sample-deck) :edn-key 0})

(defn select-slide-handler [db [_ idx]]
  (assoc db :selected-slide idx :selected-shape nil))

(defn select-shape-handler [db [_ idx]]
  (assoc db :selected-shape idx))

(defn set-mode-handler [db [_ mode]]
  (cond-> (assoc db :mode mode)
    (= :edn mode)
    (assoc :edn-text (pr-str (:deck db))
           :edn-key (inc (or (:edn-key db) 0)))))

(defn add-slide-handler [db _]
  (let [ss (:slides/slides (:deck db))
        id (next-id "slide" ss)
        new-slide (-> (model/slide id {:slides/title (str "Slide " (inc (count ss)))})
                      (model/add-shape (model/text-box "title" "New slide" {:slides/font-size 34})))]
    (-> db
        (update-in [:deck :slides/slides] conj new-slide)
        (assoc :selected-slide (count ss) :selected-shape nil :error nil))))

(defn duplicate-slide-handler [db _]
  (let [idx (slide-index db)
        ss (vec (:slides/slides (:deck db)))
        cur (get ss idx)
        copy (assoc cur
                    :slides/id (next-id "slide" ss)
                    :slides/title (str (:slides/title cur "Slide") " copy"))]
    (-> db
        (update-in [:deck :slides/slides]
                   (fn [xs] (vec (concat (subvec xs 0 (inc idx)) [copy] (subvec xs (inc idx))))))
        (assoc :selected-slide (inc idx) :selected-shape nil :error nil))))

(defn delete-slide-handler [db _]
  (let [ss (vec (:slides/slides (:deck db)))]
    (if (<= (count ss) 1)
      db
      (let [idx (slide-index db)]
        (-> db
            (update-in [:deck :slides/slides]
                       (fn [xs] (vec (concat (subvec xs 0 idx) (subvec xs (inc idx))))))
            (assoc :selected-slide (max 0 (dec idx)) :selected-shape nil :error nil))))))

(defn add-shape-handler [db [_ kind]]
  (let [idx (slide-index db)
        shapes (:slides/shapes (get (:slides/slides (:deck db)) idx))
        shape (if (= kind :rect)
                (model/rect (next-id "rect" shapes)
                            {:slides/x 1.0 :slides/y 2.8 :slides/w 4.0 :slides/h 1.2})
                (model/text-box (next-id "text" shapes) "Text"
                                {:slides/x 1.0 :slides/y 1.0 :slides/w 5.0 :slides/h 0.8
                                 :slides/font-size 28}))]
    (-> db
        (replace-slide idx #(update % :slides/shapes conj shape))
        (assoc :selected-shape (count shapes) :error nil))))

(defn add-component-handler [db [_ component-id]]
  (let [idx (slide-index db)
        shapes (:slides/shapes (get (:slides/slides (:deck db)) idx))
        shape {:slides/id (str (name component-id) "-" (inc (count shapes)))
               :slides/component component-id
               :slides/text (case component-id
                              :title "Title"
                              :subtitle "Subtitle"
                              :eyebrow "SECTION"
                              :body "Body text"
                              "")}]
    (-> db
        (replace-slide idx #(update % :slides/shapes conj shape))
        (assoc :selected-shape (count shapes) :error nil))))

(defn duplicate-shape-handler [db _]
  (if-let [shape-idx (:selected-shape db)]
    (let [slide-idx (slide-index db)
          shapes (vec (get-in db [:deck :slides/slides slide-idx :slides/shapes]))
          shape (get shapes shape-idx)
          copy (-> shape
                   (assoc :slides/id (next-id (or (some-> (:slides/shape shape) name) "shape") shapes))
                   (update :slides/x (fnil + 0) 0.18)
                   (update :slides/y (fnil + 0) 0.18))]
      (-> db
          (replace-slide slide-idx #(update % :slides/shapes conj copy))
          (assoc :selected-shape (count shapes) :error nil)))
    db))

(defn delete-shape-handler [db _]
  (if-let [shape-idx (:selected-shape db)]
    (let [idx (slide-index db)]
      (-> db
          (replace-slide idx
                         (fn [slide]
                           (let [xs (:slides/shapes slide)]
                             (assoc slide :slides/shapes
                                    (vec (concat (subvec xs 0 shape-idx)
                                                 (subvec xs (inc shape-idx))))))))
          (assoc :selected-shape nil :error nil)))
    db))

(defn update-shape-field-handler [db [_ field value]]
  (if-let [shape-idx (:selected-shape db)]
    (replace-shape db (slide-index db) shape-idx #(assoc % field value))
    db))

(defn set-shape-position-handler [db [_ shape-idx x y]]
  (replace-shape db (slide-index db) shape-idx
                 #(assoc % :slides/x x :slides/y y)))

(defn set-shape-frame-handler [db [_ shape-idx x y w h]]
  (replace-shape db (slide-index db) shape-idx
                 #(assoc % :slides/x x :slides/y y :slides/w w :slides/h h)))

(defn nudge-shape-handler [db [_ dx dy]]
  (if-let [shape-idx (:selected-shape db)]
    (replace-shape db (slide-index db) shape-idx
                   (fn [shape]
                     (-> shape
                         (update :slides/x (fnil + 0) dx)
                         (update :slides/y (fnil + 0) dy))))
    db))

(defn set-shape-kind-handler [db [_ kind]]
  (if-let [shape-idx (:selected-shape db)]
    (replace-shape db (slide-index db) shape-idx
                   (fn [shape]
                     (if (= kind :rect)
                       (-> shape (assoc :slides/shape :rect)
                           (dissoc :slides/text :slides/font-size :slides/color))
                       (-> shape (assoc :slides/shape :text)
                           (dissoc :slides/fill :slides/line)))))
    db))

(defn update-slide-field-handler [db [_ field value]]
  (replace-slide db (slide-index db) #(assoc % field value)))

(defn apply-edn-handler [db [_ deck]]
  (assoc db :deck deck :selected-slide 0 :selected-shape nil :error nil :mode :visual
         :edn-text (pr-str deck) :edn-key (inc (or (:edn-key db) 0))))

(defn import-deck-handler [db [_ deck]]
  (assoc db :deck deck :selected-slide 0 :selected-shape nil :error nil :mode :visual
         :edn-text (pr-str deck) :edn-key (inc (or (:edn-key db) 0))))

(defn set-zoom-handler [db [_ zoom]]
  (assoc db :zoom (clamp 0.5 1.5 zoom)))

(defn set-error-handler [db [_ msg]]
  (assoc db :error msg))

(defn clear-error-handler [db _]
  (assoc db :error nil))

;; ---------------------------------------------------------------------------
;; subscription handlers
;; ---------------------------------------------------------------------------

(defn db-sub [db _] db)
(defn deck-sub [db _] (:deck db))
(defn slides-sub [db _] (vec (:slides/slides (:deck db))))
(defn selected-slide-index-sub [db _] (slide-index db))

(defn selected-slide-sub [db _]
  (let [ss (vec (:slides/slides (:deck db)))]
    (get ss (slide-index db))))

(defn selected-shape-index-sub [db _] (:selected-shape db))

(defn selected-shape-sub [db _]
  (when-let [idx (:selected-shape db)]
    (let [slide (selected-slide-sub db nil)]
      (get (:slides/shapes slide) idx))))

(defn mode-sub [db _] (:mode db))
(defn error-sub [db _] (:error db))
(defn zoom-sub [db _] (:zoom db))

(defn deck-design-sub [db _]
  (design/deck-design (:deck db)))

(defn canvas-size-sub [db _]
  (let [d (:deck db)]
    {:slides/width  (or (:slides/width d) 10)
     :slides/height (or (:slides/height d) 5.625)}))

;; ---------------------------------------------------------------------------
;; registration
;; ---------------------------------------------------------------------------

(defn register!
  "Register all slides web events + subs against the active re-frame host
  (mini runtime on JVM, real re-frame on cljs). Idempotent."
  []
  (rf/reg-event-db :slides/init init-handler)
  (rf/reg-event-db :slides/new-deck new-deck-handler)
  (rf/reg-event-db :slides/select-slide select-slide-handler)
  (rf/reg-event-db :slides/select-shape select-shape-handler)
  (rf/reg-event-db :slides/set-mode set-mode-handler)
  (rf/reg-event-db :slides/add-slide add-slide-handler)
  (rf/reg-event-db :slides/duplicate-slide duplicate-slide-handler)
  (rf/reg-event-db :slides/delete-slide delete-slide-handler)
  (rf/reg-event-db :slides/add-shape add-shape-handler)
  (rf/reg-event-db :slides/add-component add-component-handler)
  (rf/reg-event-db :slides/duplicate-shape duplicate-shape-handler)
  (rf/reg-event-db :slides/delete-shape delete-shape-handler)
  (rf/reg-event-db :slides/update-shape-field update-shape-field-handler)
  (rf/reg-event-db :slides/set-shape-position set-shape-position-handler)
  (rf/reg-event-db :slides/set-shape-frame set-shape-frame-handler)
  (rf/reg-event-db :slides/nudge-shape nudge-shape-handler)
  (rf/reg-event-db :slides/set-shape-kind set-shape-kind-handler)
  (rf/reg-event-db :slides/update-slide-field update-slide-field-handler)
  (rf/reg-event-db :slides/apply-edn apply-edn-handler)
  (rf/reg-event-db :slides/import-deck import-deck-handler)
  (rf/reg-event-db :slides/set-zoom set-zoom-handler)
  (rf/reg-event-db :slides/set-error set-error-handler)
  (rf/reg-event-db :slides/clear-error clear-error-handler)
  (rf/reg-sub :slides/db db-sub)
  (rf/reg-sub :slides/deck deck-sub)
  (rf/reg-sub :slides/slides slides-sub)
  (rf/reg-sub :slides/selected-slide-index selected-slide-index-sub)
  (rf/reg-sub :slides/selected-slide selected-slide-sub)
  (rf/reg-sub :slides/selected-shape-index selected-shape-index-sub)
  (rf/reg-sub :slides/selected-shape selected-shape-sub)
  (rf/reg-sub :slides/mode mode-sub)
  (rf/reg-sub :slides/error error-sub)
  (rf/reg-sub :slides/zoom zoom-sub)
  (rf/reg-sub :slides/deck-design deck-design-sub)
  (rf/reg-sub :slides/canvas-size canvas-size-sub)
  nil)
