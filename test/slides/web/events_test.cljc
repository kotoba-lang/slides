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
  (is (nil? @(rf/subscribe [:slides/error]))))

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
  (is (= {:slides/id "x" :slides/slides []} @(rf/subscribe [:slides/deck]))))
