(ns slides.validate
  (:require [slides.model :as model]
            [slides.routes :as routes]))

(defn problem [severity code id msg]
  {:slides/severity severity
   :slides/code code
   :slides/id id
   :slides/msg msg})

(defn item-problems [ws]
  (mapcat
   (fn [[id it]]
     (cond-> []
       (not= id (:slides/id it))
       (conj (problem :error :item/id-key-mismatch id "item key must equal :slides/id"))

       (not (contains? model/item-kinds (:slides/kind it)))
       (conj (problem :error :item/unknown-kind id "unknown item kind"))

       (or (nil? (:slides/title it)) (= "" (:slides/title it)))
       (conj (problem :warning :item/missing-title id "item has no title"))))
   (:slides/items ws)))

(defn link-problems [ws]
  (let [ids (set (keys (:slides/items ws)))]
    (mapcat
     (fn [{:slides/keys [from to link-kind]}]
       (cond-> []
         (not (contains? ids from))
         (conj (problem :error :link/dangling-from from "link source does not exist"))

         (not (contains? ids to))
         (conj (problem :error :link/dangling-to to "link target does not exist"))

         (not (contains? model/link-kinds link-kind))
         (conj (problem :error :link/unknown-kind (str from "->" to) "unknown link kind"))))
     (:slides/links ws))))

(defn route-problems []
  (let [required #{"kotoba-lang.github.io/slides"
                   "kotoba-lang.github.io/docs"
                   "kotoba-lang.github.io/drive"
                   "kotoba-lang.github.io/sheets"}
        present (set (keys routes/hosts))]
    (for [host (sort (remove present required))]
      (problem :error :route/missing-host host "required host route is missing"))))

(defn problems [ws]
  (vec (concat (item-problems ws)
               (link-problems ws)
               (route-problems))))

(defn valid? [ws]
  (not-any? #(= :error (:slides/severity %)) (problems ws)))
