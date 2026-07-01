(ns kotoba.editor
  "Portable editor kernel shared by document-like Kotoba tools.

  This namespace intentionally knows nothing about slides, PPTX, DOM, re-frame,
  or a concrete document schema. Callers provide small access/update functions
  for their model. The same code can back slides, sheets/docs canvases, or KAMI
  scene editors."
  (:require [clojure.set :as set]))

(def default-history-limit 80)

(defn selected-set
  "Return the canonical multi-selection set for an editor db.
  Falls back to `primary-key` so older single-selection dbs keep working."
  ([db] (selected-set db :selected-shapes :selected-shape))
  ([db selected-key primary-key]
   (set (or (get db selected-key)
            (when-let [idx (get db primary-key)] #{idx})
            #{}))))

(defn select-one
  "Select exactly one item, or clear selection when `idx` is nil."
  ([db idx] (select-one db idx :selected-shapes :selected-shape))
  ([db idx selected-key primary-key]
   (assoc db primary-key idx selected-key (if (some? idx) #{idx} #{}))))

(defn clear-selection
  ([db] (clear-selection db :selected-shapes :selected-shape))
  ([db selected-key primary-key]
   (assoc db primary-key nil selected-key #{})))

(defn toggle-selection
  "Toggle `idx` in the selection set while keeping a stable primary selection."
  ([db idx] (toggle-selection db idx :selected-shapes :selected-shape))
  ([db idx selected-key primary-key]
   (let [selected (selected-set db selected-key primary-key)
         next-selected (if (contains? selected idx)
                         (disj selected idx)
                         (conj selected idx))
         primary (or (when (contains? next-selected (get db primary-key))
                       (get db primary-key))
                     (first (sort next-selected)))]
     (assoc db primary-key primary selected-key next-selected))))

(defn snapshot
  "Take a compact undo snapshot from an editor db."
  ([db] (snapshot db [:deck :selected-slide :selected-shape :selected-shapes]))
  ([db keys]
   (select-keys db keys)))

(defn push-undo
  "Push an undo snapshot and clear redo. Duplicate consecutive snapshots collapse."
  ([db] (push-undo db (snapshot db) default-history-limit))
  ([db snap limit]
   (let [stack (vec (:undo-stack db))]
     (if (= snap (peek stack))
       db
       (assoc db
              :undo-stack (->> (conj stack snap)
                               (take-last (or limit default-history-limit))
                               vec)
              :redo-stack [])))))

(defn can-undo? [db] (seq (:undo-stack db)))
(defn can-redo? [db] (seq (:redo-stack db)))

(defn restore
  "Restore `snap` while preserving non-snap UI state."
  [db snap]
  (merge db snap))

(defn undo
  "Undo using the supplied snapshot and restore functions."
  ([db] (undo db snapshot restore))
  ([db snapshot-fn restore-fn]
   (if-let [snap (peek (:undo-stack db))]
     (-> db
         (assoc :undo-stack (pop (vec (:undo-stack db)))
                :redo-stack (conj (vec (:redo-stack db)) (snapshot-fn db)))
         (restore-fn snap))
     db)))

(defn redo
  "Redo using the supplied snapshot and restore functions."
  ([db] (redo db snapshot restore))
  ([db snapshot-fn restore-fn]
   (if-let [snap (peek (:redo-stack db))]
     (-> db
         (assoc :redo-stack (pop (vec (:redo-stack db)))
                :undo-stack (conj (vec (:undo-stack db)) (snapshot-fn db)))
         (restore-fn snap))
     db)))

(defn with-history
  "Wrap a pure handler so document changes push undo and clear redo."
  ([handler] (with-history handler :deck snapshot default-history-limit))
  ([handler document-key snapshot-fn limit]
   (fn [db event]
     (let [next-db (handler db event)]
       (if (= (get db document-key) (get next-db document-key))
         next-db
         (let [tracked (push-undo db (snapshot-fn db) limit)]
           (assoc next-db
                  :undo-stack (:undo-stack tracked)
                  :redo-stack [])))))))

(defn nudge-selected
  "Apply `nudge-fn` to every selected item through caller supplied `update-fn`."
  [db selected-ids update-fn nudge-fn]
  (reduce (fn [acc id] (update-fn acc id nudge-fn))
          db
          selected-ids))

(defn align-rects
  "Align selected rectangle-like items.

  `rects` is a seq of [id {:x .. :y .. :w .. :h ..}]. Returns {id {:x ? :y ?}}
  with only the changed coordinate for each id. Axis is :x or :y; position is
  :start, :center, or :end."
  [rects axis position]
  (let [rs (vec rects)]
    (if (< (count rs) 2)
      {}
      (let [left (apply min (map (comp :x second) rs))
            top (apply min (map (comp :y second) rs))
            right (apply max (map (fn [[_ r]] (+ (:x r 0) (:w r 1))) rs))
            bottom (apply max (map (fn [[_ r]] (+ (:y r 0) (:h r 1))) rs))
            center-x (/ (+ left right) 2)
            center-y (/ (+ top bottom) 2)]
        (into {}
              (map (fn [[id r]]
                     [id (case axis
                           :x {:x (case position
                                    :start left
                                    :center (- center-x (/ (:w r 1) 2))
                                    :end (- right (:w r 1)))}
                           :y {:y (case position
                                    :start top
                                    :center (- center-y (/ (:h r 1) 2))
                                    :end (- bottom (:h r 1)))})]))
              rs)))))

(defn normalize-selected-ids
  "Drop selected ids that no longer exist."
  [selected ids]
  (set/intersection (set selected) (set ids)))
