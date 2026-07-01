(ns slides.web.events-test
  "Exercises the slides web re-frame events/subs on the JVM mini runtime
  (shitsuke.re-frame.core). The SAME registrations run on real re-frame in the
  browser; staying within the portable 7-fn subset keeps this test faithful."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [shitsuke.re-frame.core :as rf]
            [slides.design :as design]
            [slides.web.events :as events]
            [slides.web.sample :as sample]))

(use-fixtures :each
  (fn [t]
    (rf/clear!)
    (events/register!)
    (rf/dispatch [:slides/init sample/sample-deck])
    (t)
    (rf/clear!)))

(deftest init-and-subs-test
  (is (= sample/sample-deck @(rf/subscribe [:slides/deck])))
  (is (= :visual @(rf/subscribe [:slides/mode])))
  (is (= 0 @(rf/subscribe [:slides/selected-slide-index])))
  (is (nil? @(rf/subscribe [:slides/selected-shape-index])))
  (is (nil? @(rf/subscribe [:slides/error])))
  (is (= 1.0 @(rf/subscribe [:slides/zoom])))
  (is (nil? @(rf/subscribe [:slides/can-undo])))
  (is (nil? @(rf/subscribe [:slides/can-redo])))
  (is (= {:slides/width 10 :slides/height 5.625}
         @(rf/subscribe [:slides/canvas-size])))
  (is (seq (:slides/components @(rf/subscribe [:slides/deck-design])))))

(deftest new-deck-and-edn-mode-snapshot-test
  (rf/dispatch [:slides/select-slide 1])
  (rf/dispatch [:slides/set-error "stale"])
  (rf/dispatch [:slides/new-deck])
  (let [db @(rf/subscribe [:slides/db])]
    (is (= sample/sample-deck (:deck db)))
    (is (= 0 (:selected-slide db)))
    (is (nil? (:selected-shape db)))
    (is (= :visual (:mode db)))
    (is (nil? (:error db)))
    (is (= 1.0 (:zoom db)))
    (is (= (pr-str sample/sample-deck) (:edn-text db))))
  (rf/dispatch [:slides/set-mode :edn])
  (let [db @(rf/subscribe [:slides/db])]
    (is (= :edn (:mode db)))
    (is (= (pr-str (:deck db)) (:edn-text db)))
    (is (= 1 (:edn-key db)))))

(deftest select-slide-and-shape-test
  (rf/dispatch [:slides/select-slide 1])
  (is (= 1 @(rf/subscribe [:slides/selected-slide-index])))
  (is (nil? @(rf/subscribe [:slides/selected-shape-index]))) ; selecting a slide clears shape
  (rf/dispatch [:slides/select-shape 0])
  (is (= 0 @(rf/subscribe [:slides/selected-shape-index])))
  (is (some? @(rf/subscribe [:slides/selected-shape]))))

(deftest add-slide-test
  (let [before (count @(rf/subscribe [:slides/slides]))]
    (rf/dispatch [:slides/add-slide])
    (is (= (inc before) (count @(rf/subscribe [:slides/slides]))))
    (is (= before @(rf/subscribe [:slides/selected-slide-index]))) ; new slide selected

    (rf/dispatch [:slides/select-slide 0])
    (rf/dispatch [:slides/duplicate-slide])
    (is (= (inc (inc before)) (count @(rf/subscribe [:slides/slides]))))))

(deftest delete-slide-keeps-at-least-one-test
  (rf/dispatch [:slides/select-slide 0])
  (dotimes [_ 5] (rf/dispatch [:slides/delete-slide]))
  (is (>= (count @(rf/subscribe [:slides/slides])) 1)))

(deftest add-and-delete-shape-test
  (rf/dispatch [:slides/select-slide 0])
  (let [before (count (:slides/shapes @(rf/subscribe [:slides/selected-slide])))]
    (rf/dispatch [:slides/add-shape :text])
    (is (= (inc before) (count (:slides/shapes @(rf/subscribe [:slides/selected-slide])))))
    (is (= before @(rf/subscribe [:slides/selected-shape-index]))) ; new shape selected
    (rf/dispatch [:slides/delete-shape])
    (is (= before (count (:slides/shapes @(rf/subscribe [:slides/selected-slide])))))
    (is (nil? @(rf/subscribe [:slides/selected-shape-index])))))

(deftest duplicate-and-nudge-shape-test
  (rf/dispatch [:slides/select-slide 0])
  (rf/dispatch [:slides/select-shape 0])
  (let [before (count (:slides/shapes @(rf/subscribe [:slides/selected-slide])))]
    (rf/dispatch [:slides/duplicate-shape])
    (is (= (inc before) (count (:slides/shapes @(rf/subscribe [:slides/selected-slide])))))
    (is (= before @(rf/subscribe [:slides/selected-shape-index])))
    (let [x (:slides/x @(rf/subscribe [:slides/selected-shape]))]
      (rf/dispatch [:slides/nudge-shape 0.1 -0.1])
      (is (= (+ x 0.1) (:slides/x @(rf/subscribe [:slides/selected-shape])))))))

(deftest add-rect-shape-test
  (rf/dispatch [:slides/select-slide 0])
  (rf/dispatch [:slides/add-shape :rect])
  (let [shape @(rf/subscribe [:slides/selected-shape])]
    (is (= :rect (:slides/shape shape)))
    (is (= "rect-7" (:slides/id shape)))
    (is (= 4.0 (:slides/w shape)))))

(deftest add-component-test
  (rf/dispatch [:slides/select-slide 0])
  (let [before (count (:slides/shapes @(rf/subscribe [:slides/selected-slide])))]
    (rf/dispatch [:slides/add-component :title])
    (is (= (inc before) (count (:slides/shapes @(rf/subscribe [:slides/selected-slide])))))))

(deftest update-shape-field-test
  (rf/dispatch [:slides/select-slide 0])
  (rf/dispatch [:slides/select-shape 0])
  (rf/dispatch [:slides/update-shape-field :slides/x 2.5])
  (let [shape @(rf/subscribe [:slides/selected-shape])]
    (is (= 2.5 (:slides/x (design/resolve-shape @(rf/subscribe [:slides/deck]) shape))))))

(deftest set-shape-position-test
  (rf/dispatch [:slides/select-slide 0])
  (rf/dispatch [:slides/select-shape 0])
  (rf/dispatch [:slides/set-shape-position 0 1.25 2.5])
  (let [shape @(rf/subscribe [:slides/selected-shape])]
    (is (= 1.25 (:slides/x shape)))
    (is (= 2.5 (:slides/y shape)))))

(deftest set-shape-frame-test
  (rf/dispatch [:slides/select-slide 0])
  (rf/dispatch [:slides/select-shape 0])
  (rf/dispatch [:slides/set-shape-frame 0 1.25 2.5 3.5 1.5])
  (let [shape @(rf/subscribe [:slides/selected-shape])]
    (is (= 1.25 (:slides/x shape)))
    (is (= 2.5 (:slides/y shape)))
    (is (= 3.5 (:slides/w shape)))
    (is (= 1.5 (:slides/h shape)))))

(deftest undo-redo-shape-edit-test
  (rf/dispatch [:slides/select-slide 0])
  (rf/dispatch [:slides/select-shape 0])
  (let [before @(rf/subscribe [:slides/selected-shape])]
    (rf/dispatch [:slides/update-shape-field :slides/text "Undoable"])
    (is (seq @(rf/subscribe [:slides/can-undo])))
    (is (= "Undoable" (:slides/text @(rf/subscribe [:slides/selected-shape]))))
    (rf/dispatch [:slides/undo])
    (is (= (:slides/text before) (:slides/text @(rf/subscribe [:slides/selected-shape]))))
    (is (seq @(rf/subscribe [:slides/can-redo])))
    (rf/dispatch [:slides/redo])
    (is (= "Undoable" (:slides/text @(rf/subscribe [:slides/selected-shape]))))))

(deftest undo-redo-direct-drag-test
  (rf/dispatch [:slides/select-slide 0])
  (rf/dispatch [:slides/select-shape 0])
  (let [before @(rf/subscribe [:slides/selected-shape])]
    (rf/dispatch [:slides/mark-undo])
    (rf/dispatch [:slides/set-shape-position 0 1.25 2.5])
    (rf/dispatch [:slides/set-shape-position 0 1.5 2.75])
    (is (= 1.5 (:slides/x @(rf/subscribe [:slides/selected-shape]))))
    (rf/dispatch [:slides/undo])
    (is (= (:slides/x before) (:slides/x @(rf/subscribe [:slides/selected-shape]))))
    (rf/dispatch [:slides/redo])
    (is (= 1.5 (:slides/x @(rf/subscribe [:slides/selected-shape]))))))

(deftest selected-shape-noop-transitions-test
  (let [before @(rf/subscribe [:slides/db])]
    (rf/dispatch [:slides/delete-shape])
    (is (= before @(rf/subscribe [:slides/db])))
    (rf/dispatch [:slides/update-shape-field :slides/x 9])
    (is (= before @(rf/subscribe [:slides/db])))
    (rf/dispatch [:slides/duplicate-shape])
    (is (= before @(rf/subscribe [:slides/db])))
    (rf/dispatch [:slides/nudge-shape 1 1])
    (is (= before @(rf/subscribe [:slides/db])))
    (rf/dispatch [:slides/set-shape-kind :rect])
    (is (= before @(rf/subscribe [:slides/db])))))

(deftest set-shape-kind-swap-test
  (rf/dispatch [:slides/select-slide 0])
  (rf/dispatch [:slides/select-shape 0])
  (rf/dispatch [:slides/set-shape-kind :rect])
  (let [shape @(rf/subscribe [:slides/selected-shape])]
    (is (= :rect (:slides/shape shape)))
    (is (not (contains? shape :slides/text))))
  (rf/dispatch [:slides/set-shape-kind :text])
  (let [shape @(rf/subscribe [:slides/selected-shape])]
    (is (= :text (:slides/shape shape)))
    (is (not (contains? shape :slides/fill)))))

(deftest apply-edn-and-error-test
  (rf/dispatch [:slides/set-error "boom"])
  (is (= "boom" @(rf/subscribe [:slides/error])))
  (rf/dispatch [:slides/apply-edn {:slides/id "x" :slides/slides []}])
  (is (nil? @(rf/subscribe [:slides/error])))
  (is (= {:slides/id "x" :slides/slides []} @(rf/subscribe [:slides/deck])))
  (is (= (pr-str {:slides/id "x" :slides/slides []})
         (:edn-text @(rf/subscribe [:slides/db])))))

(deftest import-deck-clear-error-and-update-slide-test
  (let [deck {:slides/id "imported"
              :slides/width 12
              :slides/height 7
              :slides/slides [{:slides/id "slide-a"
                               :slides/title "Before"
                               :slides/shapes []}]}]
    (rf/dispatch [:slides/set-error "import warning"])
    (rf/dispatch [:slides/import-deck deck])
    (is (= deck @(rf/subscribe [:slides/deck])))
    (is (nil? @(rf/subscribe [:slides/error])))
    (is (= {:slides/width 12 :slides/height 7}
           @(rf/subscribe [:slides/canvas-size])))
    (rf/dispatch [:slides/update-slide-field :slides/title "After"])
    (is (= "After" (:slides/title @(rf/subscribe [:slides/selected-slide]))))
    (rf/dispatch [:slides/set-error "temporary"])
    (rf/dispatch [:slides/clear-error])
    (is (nil? @(rf/subscribe [:slides/error])))))

(deftest set-zoom-clamps-test
  (rf/dispatch [:slides/set-zoom 1.2])
  (is (= 1.2 @(rf/subscribe [:slides/zoom])))
  (rf/dispatch [:slides/set-zoom 9])
  (is (= 1.5 @(rf/subscribe [:slides/zoom])))
  (rf/dispatch [:slides/set-zoom 0])
  (is (= 0.5 @(rf/subscribe [:slides/zoom]))))
