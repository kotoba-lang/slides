(ns slides.wire-test
  (:require [clojure.test :refer [deftest is]]
            [slides.model :as model]
            [slides.validate :as validate]
            [slides.wire :as wire]))

(deftest deck-envelope-round-trips-as-plain-json
  (let [deck {:slides/id "deck"
              :slides/slides [{:slides/id "s1"}]}
        envelope (wire/deck-envelope deck {:request-id "req-1"})
        decoded (wire/read-deck-envelope (:body envelope))]
    (is (= "application/json" (:content-type envelope)))
    (is (= {"slides/id" "deck" "slides/slides" [{"slides/id" "s1"}]}
           decoded))))

(deftest deck-envelope-defaults-and-kind-check
  (let [deck {:slides/id "deck"}
        envelope (wire/deck-envelope deck)]
    (is (= {"slides/id" "deck"} (wire/read-deck-envelope (:body envelope))))
    ;; The kind it refused, out of the thrown value's `ex-data`, rather than
    ;; `(thrown? ExceptionInfo …)`. Two reasons, and the second is why this
    ;; changed: naming the class means naming it per host, and the `:cljs`
    ;; branch said `cljs.core.ExceptionInfo`, which sci cannot resolve — the
    ;; branch had never been read, because nothing ran this file anywhere but
    ;; the JVM. Asking what was thrown is portable and says more than that
    ;; something was.
    (is (= :docs/document
           (try (wire/read-deck-envelope
                 {"kotoba.protocol/family" "kotoba.protocol/office"
                  "kotoba.protocol/version" 1
                  "kotoba.resource/kind" "docs/document"
                  "kotoba.resource/payload" {}})
                nil
                (catch #?(:clj Exception :cljs :default) e
                  (:kind (ex-data e))))))))

(deftest a-deck-survives-the-projection-and-comes-back
  (let [deck (-> (model/deck "d1" {:slides/title "四半期"})
                 (model/add-slide
                  (-> (model/slide "s1" {:slides/title "表紙"})
                      (model/add-shape (model/text-box "t1" "売上"))
                      (model/add-shape (model/rect "r1")))))
        projected (wire/read-deck-envelope (:body (wire/deck-envelope deck)))]
    ;; What the wire carries: the keywords have become bare strings.
    (is (= "slides/deck" (get projected "slides/kind")))
    (is (= "gftd" (get projected "slides/theme")))
    (is (= ["text" "rect"]
           (mapv #(get % "slides/shape")
                 (get-in projected ["slides/slides" 0 "slides/shapes"]))))
    ;; And closed again by a reader that knows which of them were.
    (is (= deck (wire/rehydrate-deck projected)))
    (is (= deck (wire/deck-of-envelope (:body (wire/deck-envelope deck)))))))

(deftest a-design-override-theme-stays-a-map
  (let [deck (model/deck "d1" {:slides/theme {:slides/accent "112233"}})
        back (wire/deck-of-envelope (:body (wire/deck-envelope deck)))]
    (is (= {:slides/accent "112233"} (:slides/theme back)))))

(deftest validate-cannot-see-a-deck-that-was-not-rehydrated
  ;; `slides.validate` matches `:slides/kind` against a set of keywords, so a
  ;; projected deck is not recognised as a deck at all — its slides and
  ;; shapes are never looked at, and a broken one comes back unremarked.
  (let [broken (-> (model/deck "d1" {:slides/title "壊れ"})
                   (model/add-slide {:slides/id "s1" :slides/title "no shapes key"
                                     :slides/shapes "not-a-vector"}))
        workspace-of (fn [d] (model/add-item (model/workspace "ws") d))
        projected (wire/read-deck-envelope (:body (wire/deck-envelope broken)))]
    (is (empty? (validate/deck-problems (workspace-of projected)))
        "vacuously, having not recognised it as a deck")
    (is (= [:slide/shapes-not-sequential]
           (->> (validate/deck-problems (workspace-of (wire/rehydrate-deck projected)))
                (filter #(= :error (:slides/severity %)))
                (mapv :slides/code)))
        "and wrong, once it is a deck again")))

(deftest a-malformed-payload-is-handed-on-rather-than-thrown-at
  ;; The converter's job is to give `slides.validate` something to look at.
  ;; One that throws is one the validator never gets to answer, and the
  ;; caller gets a crash instead of the list of what is wrong.
  (doseq [payload [{"slides/slides" "nope"}
                   {"slides/slides" [{"slides/shapes" "nope"}]}
                   {"slides/slides" ["not-a-slide"]}
                   {"slides/theme" 7}
                   {"slides/kind" 7}
                   "not-a-deck-at-all"]]
    (is (some? (wire/rehydrate-deck payload))
        (str "survived: " (pr-str payload)))))
