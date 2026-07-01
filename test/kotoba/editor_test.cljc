(ns kotoba.editor-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.editor :as editor]))

(deftest selection-test
  (is (= #{3} (editor/selected-set {:selected-shape 3})))
  (is (= {:selected-shape 1 :selected-shapes #{1}}
         (editor/select-one {} 1)))
  (is (= {:primary 1 :items #{1}}
         (editor/select-one {} 1 :items :primary)))
  (is (= {:selected-shape nil :selected-shapes #{}}
         (editor/select-one {} nil)))
  (is (= {:selected-shape nil :selected-shapes #{}}
         (editor/clear-selection {:selected-shape 1 :selected-shapes #{1}})))
  (is (= {:selected-shape 1 :selected-shapes #{1 2}}
         (editor/toggle-selection {:selected-shape 1 :selected-shapes #{1}} 2)))
  (is (= {:selected-shape 2 :selected-shapes #{2}}
         (editor/toggle-selection {:selected-shape 1 :selected-shapes #{1 2}} 1))))

(deftest history-test
  (let [db {:deck {:id 1} :selected-shape 0 :selected-shapes #{0}}
        with-undo (editor/push-undo db (editor/snapshot db) 80)
        duplicate (editor/push-undo with-undo (editor/snapshot db) 80)
        edited (assoc with-undo :deck {:id 2})
        undone (editor/undo edited editor/snapshot editor/restore)
        redone (editor/redo undone editor/snapshot editor/restore)]
    (is (= (:undo-stack with-undo) (:undo-stack duplicate)))
    (is (editor/can-undo? with-undo))
    (is (editor/can-redo? undone))
    (is (= {:id 1} (:deck undone)))
    (is (= {:id 2} (:deck redone))))
  (is (= {:deck {:id 1}} (editor/undo {:deck {:id 1}})))
  (is (= {:deck {:id 1}} (editor/redo {:deck {:id 1}})))
  (let [limited (-> {:deck {:id 1}}
                    (editor/push-undo {:deck {:id 1}} 1)
                    (editor/push-undo {:deck {:id 2}} 1))]
    (is (= [{:deck {:id 2}}] (:undo-stack limited)))))

(deftest with-history-test
  (let [handler (editor/with-history
                 (fn [db [_ id]] (assoc db :deck {:id id}))
                 :deck
                 editor/snapshot
                 80)
        next-db (handler {:deck {:id 1}} [:set 2])]
    (is (= {:id 2} (:deck next-db)))
    (is (= [{:deck {:id 1}}] (:undo-stack next-db))))
  (let [handler (editor/with-history
                 (fn [db _] (assoc db :mode :visual))
                 :deck
                 editor/snapshot
                 80)]
    (is (nil? (:undo-stack (handler {:deck {:id 1}} [:noop]))))))

(deftest align-rects-test
  (is (= {} (editor/align-rects [[0 {:x 1 :y 1 :w 2 :h 1}]] :x :start)))
  (let [updates (editor/align-rects [[0 {:x 1 :y 1 :w 2 :h 1}]
                                     [1 {:x 4 :y 2 :w 1 :h 1}]]
                                    :x
                                    :start)]
    (is (= {0 {:x 1} 1 {:x 1}} updates)))
  (let [updates (editor/align-rects [[0 {:x 1 :y 1 :w 2 :h 1}]
                                     [1 {:x 4 :y 2 :w 1 :h 1}]]
                                    :x
                                    :end)]
    (is (= {0 {:x 3} 1 {:x 4}} updates)))
  (let [updates (editor/align-rects [[0 {:x 1 :y 1 :w 2 :h 1}]
                                     [1 {:x 4 :y 2 :w 1 :h 3}]]
                                    :y
                                    :center)]
    (is (= {0 {:y 5/2} 1 {:y 3/2}} updates)))
  (let [updates (editor/align-rects [[0 {:x 1 :y 1 :w 2 :h 1}]
                                     [1 {:x 4 :y 2 :w 1 :h 3}]]
                                    :y
                                    :end)]
    (is (= {0 {:y 4} 1 {:y 2}} updates))))

(deftest nudge-selected-test
  (let [db {:items {0 {:x 1} 1 {:x 2}}}
        next-db (editor/nudge-selected
                 db
                 #{0 1}
                 (fn [acc id f] (update-in acc [:items id] f))
                 #(update % :x inc))]
    (is (= {0 {:x 2} 1 {:x 3}} (:items next-db)))))

(deftest normalize-selected-ids-test
  (is (= #{1 3} (editor/normalize-selected-ids #{1 2 3} [1 3 4]))))
