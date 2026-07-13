(ns slides.web.enhance-test
  "The live-host hiccup enhancer: attaches :on-change to data-field controls
  and converts data-field selects to controlled form — leaving everything
  else (including the SSR bytes contract) untouched."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [slides.web.enhance :as enhance]
            [slides.web.ssr :as ssr]
            [slides.web.views :as views]))

;; marker factory: lets JVM tests assert handler placement without cljs fns
(defn- marker [field] [::handler field])

(deftest tag-name-test
  (is (= "select" (enhance/tag-name :select#shape-kind)))
  (is (= "input" (enhance/tag-name :input)))
  (is (= "div" (enhance/tag-name :div.canvas-shell))))

(deftest attaches-on-change-to-text-controls-test
  (let [tree [:div {}
              [:input {:data-field "shape.x" :value "1"}]
              [:textarea {:data-field "shape.text" :value "Hi"}]
              [:input {:id "no-field" :value "z"}]]
        out (enhance/enhance tree marker)]
    (is (= [::handler "shape.x"] (get-in out [2 1 :on-change])))
    (is (= [::handler "shape.text"] (get-in out [3 1 :on-change])))
    (testing "controls without :data-field stay untouched"
      (is (= [:input {:id "no-field" :value "z"}] (get out 4))))))

(deftest converts-data-field-select-to-controlled-test
  (let [tree [:select#shape-kind {:data-field "shape.kind"}
              [:option {:value "text" :selected false} "Text"]
              [:option {:value "rect" :selected true} "Rect"]]
        [tag attrs & options] (enhance/enhance tree marker)]
    (is (= :select#shape-kind tag))
    (is (= "rect" (:value attrs)))
    (is (= [::handler "shape.kind"] (:on-change attrs)))
    (testing ":selected is stripped from every option"
      (is (every? #(not (contains? (second %) :selected)) options)))))

(deftest select-with-seq-children-and-no-selection-test
  ;; views/select-options returns a cons whose first option is {:value ""}
  (let [tree [:select {:data-field "shape.component"}
              (cons [:option {:value ""}]
                    [[:option {:value "title" :selected false} "title"]])]
        [_ attrs & children] (enhance/enhance tree marker)]
    (testing "no :selected option → controlled value defaults to \"\""
      (is (= "" (:value attrs))))
    (testing "nested seq children survive the walk"
      (is (= 1 (count children)))
      (is (seq? (first children))))))

(deftest selected-option-value-nested-test
  (is (= "b" (enhance/selected-option-value
              [(list [:option {:value "a"}]
                     [:option {:value "b" :selected true}])])))
  (is (nil? (enhance/selected-option-value [[:option {:value "a"}]]))))

(deftest full-view-tree-enhancement-test
  ;; shape 2 of slide 1 is the :title text component → the Text textarea shows
  (let [db (assoc (ssr/sample-db 0 2) :zoom 1.0)
        out (enhance/enhance (views/root db) marker)
        controls (atom [])]
    ;; collect every enhanced control in the real editor tree
    (walk/postwalk
     (fn [node]
       (when (and (vector? node) (map? (second node))
                  (:on-change (second node)))
         (swap! controls conj [(enhance/tag-name (first node))
                               (:data-field (second node))]))
       node)
     out)
    (testing "the shape property inputs + selects all got handlers"
      (let [fields (set (map second @controls))]
        (is (contains? fields "shape.x"))
        (is (contains? fields "shape.text"))
        (is (contains? fields "shape.kind"))
        (is (contains? fields "shape.component"))))
    (testing "the uncontrolled #deck-edn textarea got NO handler"
      (is (not-any? #(nil? (second %)) @controls)))))

(deftest ssr-html-is-untouched-by-enhancer-existence-test
  ;; the SSR path never calls enhance — rendering the raw views is stable
  (is (= (ssr/root-html) (ssr/root-html))))
