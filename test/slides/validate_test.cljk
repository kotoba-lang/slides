(ns slides.validate-test
  (:require [clojure.test :refer [deftest is]]
            [slides.model :as model]
            [slides.validate :as validate]
            [slides.routes :as routes]))

(deftest valid-seed-workspace-has-no-errors
  (let [problems (validate/problems (model/seed-workspace))]
    (is (empty? (filter #(= :error (:slides/severity %)) problems)))
    (is (empty? (validate/route-problems)))))

(deftest detects-item-problems
  (let [ws (-> (model/workspace "ws")
               (assoc :slides/items
                      {"wrong-id" (model/item :slides/deck "id2" {:slides/title ""})
                       "doc-1" (model/item :slides/doc "doc-1" {:slides/title "Doc"})}))]
    (let [problems (validate/problems ws)]
      (is (some #(= :item/id-key-mismatch (:slides/code %)) problems))
      (is (some #(= :item/missing-title (:slides/code %)) problems))
      (is (some #(= :error (:slides/severity %)) problems)))))

(deftest detects-workspace-structure-problems
  (let [problems (validate/problems {:slides/id "bad"
                                     :slides/items []
                                     :slides/links {}})]
    (is (some #(= :workspace/items-not-map (:slides/code %)) problems))
    (is (some #(= :workspace/links-not-sequential (:slides/code %)) problems))
    (is (not (validate/valid? {:slides/id "bad"
                               :slides/items []
                               :slides/links {}})))))

(deftest malformed-items-with-sequential-links-does-not-throw
  (let [ws {:slides/id "bad"
            :slides/items []
            :slides/links [{:slides/from "missing"
                            :slides/to "also-missing"
                            :slides/link-kind :uses}]}
        problems (validate/problems ws)]
    (is (some #(= :workspace/items-not-map (:slides/code %)) problems))
    (is (not (some #(= :link/dangling-from (:slides/code %)) problems)))
    (is (not (validate/valid? ws)))))

(deftest detects-non-map-items-and-links
  (let [ws {:slides/id "ws"
            :slides/type :workspace
            :slides/items {"bad-item" "not a map"}
            :slides/links ["not a map"]}
        problems (validate/problems ws)]
    (is (some #(= :item/not-map (:slides/code %)) problems))
    (is (some #(= :link/not-map (:slides/code %)) problems))
    (is (not (validate/valid? ws)))))

(deftest detects-deck-slide-and-shape-structure-problems
  (let [ws {:slides/id "ws"
            :slides/type :workspace
            :slides/items {"deck-a" {:slides/id "deck-a"
                                     :slides/kind :slides/deck
                                     :slides/title "Deck"
                                     :slides/slides "not slides"}
                           "deck-b" {:slides/id "deck-b"
                                     :slides/kind :slides/deck
                                     :slides/title "Deck"
                                     :slides/slides ["not a slide"
                                                     {:slides/id "s2"
                                                      :slides/title "Slide"
                                                      :slides/shapes "not shapes"}
                                                     {:slides/id "s3"
                                                      :slides/title "Slide"
                                                      :slides/shapes ["not a shape"]}]}}
            :slides/links []}
        problems (validate/problems ws)]
    (is (some #(= :deck/slides-not-sequential (:slides/code %)) problems))
    (is (some #(= :slide/not-map (:slides/code %)) problems))
    (is (some #(= :slide/shapes-not-sequential (:slides/code %)) problems))
    (is (some #(= :shape/not-map (:slides/code %)) problems))
    (is (not (validate/valid? ws)))))

(deftest warns-on-shape-semantic-problems
  (let [ws {:slides/id "ws"
            :slides/type :workspace
            :slides/items {"deck-a" {:slides/id "deck-a"
                                     :slides/kind :slides/deck
                                     :slides/title "Deck"
                                     :slides/slides [{:slides/id "s1"
                                                      :slides/title "Slide"
                                                      :slides/shapes [{:slides/shape :unknown}
                                                                      {:slides/id "ok"
                                                                       :slides/shape :text
                                                                       :slides/text "OK"}]}]}}
            :slides/links []}
        problems (validate/problems ws)]
    (is (some #(= :shape/missing-id (:slides/code %)) problems))
    (is (some #(= :shape/unknown-kind (:slides/code %)) problems))
    (is (every? #(= :warning (:slides/severity %))
                (filter #(contains? #{:shape/missing-id :shape/unknown-kind}
                                    (:slides/code %))
                        problems)))
    (is (validate/valid? ws))))

(deftest component-only-shapes-are-valid-when-component-exists
  (let [ws {:slides/id "ws"
            :slides/type :workspace
            :slides/items {"deck-a" {:slides/id "deck-a"
                                     :slides/kind :slides/deck
                                     :slides/title "Deck"
                                     :slides/components {:hero {:slides/shape :text
                                                                :slides/text "Hero"}}
                                     :slides/slides [{:slides/id "s1"
                                                      :slides/title "Slide"
                                                      :slides/shapes [{:slides/id "hero-1"
                                                                      :slides/component :hero}
                                                                      {:slides/id "missing"
                                                                       :slides/component :missing}]}]}}
            :slides/links []}
        problems (validate/problems ws)]
    (is (not (some #(= :shape/unknown-kind (:slides/code %)) problems)))
    (is (some #(= :shape/unknown-component (:slides/code %)) problems))
    (is (validate/valid? ws))))

(deftest warns-on-slide-semantic-problems
  (let [ws {:slides/id "ws"
            :slides/type :workspace
            :slides/items {"deck-a" {:slides/id "deck-a"
                                     :slides/kind :slides/deck
                                     :slides/title "Deck"
                                     :slides/slides [{:slides/shapes []}
                                                     {:slides/id "s2"
                                                      :slides/title ""
                                                      :slides/shapes []}]}}
            :slides/links []}
        problems (validate/problems ws)]
    (is (some #(= :slide/missing-id (:slides/code %)) problems))
    (is (some #(= :slide/missing-title (:slides/code %)) problems))
    (is (every? #(= :warning (:slides/severity %))
                (filter #(contains? #{:slide/missing-id :slide/missing-title}
                                    (:slides/code %))
                        problems)))
    (is (validate/valid? ws))))

(deftest warns-on-malformed-design-overrides
  (let [ws {:slides/id "ws"
            :slides/type :workspace
            :slides/items {"deck-a" {:slides/id "deck-a"
                                     :slides/kind :slides/deck
                                     :slides/title "Deck"
                                     :slides/theme "not a theme"
                                     :slides/master "not a master"
                                     :slides/guides "not guides"
                                     :slides/text-styles "not styles"
                                     :slides/components "not components"
                                     :slides/design "not a design"
                                     :slides/slides []}}
            :slides/links []}
        problems (validate/problems ws)
        design-warnings (filter #(= :design/override-not-map (:slides/code %)) problems)]
    (is (= 5 (count design-warnings)))
    (is (every? #(= :warning (:slides/severity %)) design-warnings))
    (is (some #(= :design/theme-override-invalid (:slides/code %)) problems))
    (is (validate/valid? ws))))

(deftest keyword-theme-shorthand-is-valid
  (let [ws (-> (model/workspace "ws")
               (model/add-item (model/deck "deck-a" {:slides/title "Deck"
                                                     :slides/theme :gftd})))
        problems (validate/problems ws)]
    (is (not (some #(= :design/theme-override-invalid (:slides/code %)) problems)))
    (is (validate/valid? ws))))

(deftest default-design-component-shapes-are-valid
  (let [ws {:slides/id "ws"
            :slides/type :workspace
            :slides/items {"deck-a" {:slides/id "deck-a"
                                     :slides/kind :slides/deck
                                     :slides/title "Deck"
                                     :slides/slides [{:slides/id "s1"
                                                      :slides/title "Slide"
                                                      :slides/shapes [{:slides/id "title"
                                                                       :slides/component :title
                                                                       :slides/text "Title"}]}]}}
            :slides/links []}
        problems (validate/problems ws)]
    (is (not (some #(= :shape/unknown-component (:slides/code %)) problems)))
    (is (not (some #(= :shape/unknown-kind (:slides/code %)) problems)))
    (is (validate/valid? ws))))

(deftest detects-link-problems
  (let [ws (-> (model/workspace "ws")
               (model/add-item (model/deck "d1" {:slides/title "Deck"}))
               (assoc :slides/links [{:slides/from "missing"
                                     :slides/to "d1"
                                     :slides/link-kind :uses}]))]
    (let [problems (validate/problems ws)]
      (is (some #(= :link/dangling-from (:slides/code %)) problems))
      (is (some #(= :error (:slides/severity %)) problems)))))

(deftest detects-invalid-route-configuration
  (let [required #{"kotoba-lang.github.io/slides"
                   "kotoba-lang.github.io/docs"
                   "kotoba-lang.github.io/drive"
                   "kotoba-lang.github.io/sheets"}]
    (with-redefs [routes/hosts {"kotoba-lang.github.io/slides" (routes/apps :slides)}]
      (let [problems (validate/route-problems)]
        (is (= 3 (count problems)))
        (is (every? #(= :error (:slides/severity %)) problems))
        (is (every? #(contains? required (:slides/id %)) problems))))))
