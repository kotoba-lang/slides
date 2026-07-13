(ns slides.web.dispatch-test
  "The data-act / data-field → event-vector contract, exercised end-to-end on
  the JVM against the real handlers via the shitsuke re-frame mini runtime."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [shitsuke.re-frame.core :as rf])
            [slides.web.dispatch :as dispatch]
            #?(:clj [slides.web.events :as events])
            #?(:clj [slides.web.sample :as sample])))

(deftest parse-number-test
  (is (= 1.5 (dispatch/parse-number "1.5")))
  (is (= -2.0 (dispatch/parse-number "-2")))
  (is (= 0.5 (dispatch/parse-number ".5")))
  (is (= 150.0 (dispatch/parse-number "1.5e2")))
  (is (= 24.0 (dispatch/parse-number " 24 ")))
  (is (nil? (dispatch/parse-number "")))
  (is (nil? (dispatch/parse-number "1.")))
  (is (nil? (dispatch/parse-number "-")))
  (is (nil? (dispatch/parse-number "abc")))
  (is (nil? (dispatch/parse-number "1abc"))))

(deftest field-value-coercion-test
  (testing "numeric fields parse; in-progress text is kept raw (typing continuity)"
    (is (= 1.5 (dispatch/field-value "x" "1.5")))
    (is (= 24.0 (dispatch/field-value "font-size" "24")))
    (is (= "1." (dispatch/field-value "x" "1.")))
    (is (= "" (dispatch/field-value "y" ""))))
  (testing "string fields pass through"
    (is (= "EAF0F8" (dispatch/field-value "fill" "EAF0F8")))
    (is (= "hello" (dispatch/field-value "text" "hello")))))

(deftest field->event-shape-test
  (is (= [:slides/update-shape-field :slides/x 1.5]
         (dispatch/field->event "shape.x" "1.5")))
  (is (= [:slides/update-shape-field :slides/text "Hi"]
         (dispatch/field->event "shape.text" "Hi")))
  (is (= [:slides/update-shape-field :slides/id "s1"]
         (dispatch/field->event "shape.id" "s1")))
  (is (= [:slides/update-shape-field :slides/font-size 28.0]
         (dispatch/field->event "shape.font-size" "28")))
  (is (= [:slides/set-shape-kind :rect]
         (dispatch/field->event "shape.kind" "rect")))
  (is (= [:slides/update-shape-field :slides/component :title]
         (dispatch/field->event "shape.component" "title")))
  (is (= [:slides/update-shape-field :slides/component nil]
         (dispatch/field->event "shape.component" "")))
  (is (= [:slides/update-shape-field :slides/text-style :headline]
         (dispatch/field->event "shape.text-style" "headline")))
  (is (= [:slides/update-shape-field :slides/text-style nil]
         (dispatch/field->event "shape.text-style" ""))))

(deftest field->event-slide-and-unknown-test
  (is (= [:slides/update-slide-field :slides/title "T"]
         (dispatch/field->event "slide.title" "T")))
  (is (= [:slides/update-slide-field :slides/id "slide-9"]
         (dispatch/field->event "slide.id" "slide-9")))
  (is (nil? (dispatch/field->event "bogus.thing" "v")))
  (is (nil? (dispatch/field->event "shape" "v")))
  (is (nil? (dispatch/field->event "slide" "v")))
  (is (nil? (dispatch/field->event nil "v"))))

(deftest act->event-test
  (is (= [:slides/new-deck] (dispatch/act->event "new-deck" {})))
  (is (= [:slides/undo] (dispatch/act->event "undo" {})))
  (is (= [:slides/redo] (dispatch/act->event "redo" {})))
  (is (= [:slides/add-slide] (dispatch/act->event "add-slide" {})))
  (is (= [:slides/duplicate-slide] (dispatch/act->event "duplicate-slide" {})))
  (is (= [:slides/delete-slide] (dispatch/act->event "delete-slide" {})))
  (is (= [:slides/duplicate-shape] (dispatch/act->event "duplicate-shape" {})))
  (is (= [:slides/delete-shape] (dispatch/act->event "delete-shape" {})))
  (is (= [:slides/add-shape :text] (dispatch/act->event "add-text" {})))
  (is (= [:slides/add-shape :rect] (dispatch/act->event "add-rect" {})))
  (is (= [:slides/add-component :title] (dispatch/act->event "add-title" {})))
  (is (= [:slides/add-component :panel] (dispatch/act->event "add-panel" {})))
  (is (= [:slides/set-mode :visual] (dispatch/act->event "mode-visual" {})))
  (is (= [:slides/set-mode :edn] (dispatch/act->event "mode-edn" {}))))

(deftest act->event-zoom-test
  (testing "zoom acts are relative to the current app-db zoom"
    (is (= [:slides/set-zoom 1.1] (dispatch/act->event "zoom-in" {:zoom 1.0})))
    (is (= [:slides/set-zoom 0.9] (dispatch/act->event "zoom-out" {:zoom 1.0})))
    (is (= [:slides/set-zoom 1.0] (dispatch/act->event "zoom-reset" {:zoom 0.7}))))
  (testing "missing zoom defaults to 1.0"
    (is (= [:slides/set-zoom 1.1] (dispatch/act->event "zoom-in" {})))))

(deftest act->event-align-test
  (is (= [:slides/align-selected :x :start] (dispatch/act->event "align-left" {})))
  (is (= [:slides/align-selected :x :center] (dispatch/act->event "align-center" {})))
  (is (= [:slides/align-selected :x :end] (dispatch/act->event "align-right" {})))
  (is (= [:slides/align-selected :y :start] (dispatch/act->event "align-top" {})))
  (is (= [:slides/align-selected :y :center] (dispatch/act->event "align-middle" {})))
  (is (= [:slides/align-selected :y :end] (dispatch/act->event "align-bottom" {}))))

(deftest act->event-host-effect-acts-are-nil-test
  ;; apply-edn / downloads / file opens are host effects the browser adapter
  ;; owns directly — the pure mapper must not claim them.
  (is (nil? (dispatch/act->event "apply-edn" {})))
  (is (nil? (dispatch/act->event "download-edn" {})))
  (is (nil? (dispatch/act->event "download-svgraph" {})))
  (is (nil? (dispatch/act->event "download-pptx" {})))
  (is (nil? (dispatch/act->event "unknown" {}))))

#?(:clj
   (deftest dispatch-contract-drives-real-handlers-test
     ;; End-to-end on the mini runtime: the event vectors this ns produces are
     ;; accepted by the registered slides.web.events handlers.
     (rf/clear!)
     (events/register!)
     (rf/dispatch-sync [:slides/init sample/sample-deck])
     (rf/dispatch-sync (dispatch/act->event "add-slide" @rf/app-db))
     (is (= 3 (count (:slides/slides (:deck @rf/app-db)))))
     (rf/dispatch-sync (dispatch/act->event "add-rect" @rf/app-db))
     (is (some? (:selected-shape @rf/app-db)))
     (rf/dispatch-sync (dispatch/field->event "shape.x" "2.5"))
     (let [db @rf/app-db
           slide-idx (events/slide-index db)
           shape (get-in db [:deck :slides/slides slide-idx :slides/shapes
                             (:selected-shape db)])]
       (is (= 2.5 (:slides/x shape))))
     (rf/dispatch-sync (dispatch/act->event "zoom-in" @rf/app-db))
     (is (= 1.1 (:zoom @rf/app-db)))
     (rf/dispatch-sync (dispatch/act->event "undo" @rf/app-db))
     (rf/dispatch-sync (dispatch/act->event "undo" @rf/app-db))
     (is (= 3 (count (:slides/slides (:deck @rf/app-db)))))
     (rf/clear!)))
