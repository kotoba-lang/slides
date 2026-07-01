(ns slides.wire-test
  (:require [clojure.test :refer [deftest is]]
            [slides.wire :as wire]))

(deftest deck-envelope-round-trips-through-transit
  (let [deck {:slides/id "deck"
              :slides/slides [{:slides/id "s1"}]}
        envelope (wire/deck-envelope deck {:request-id "req-1"})
        decoded (wire/read-deck-envelope (:body envelope))]
    (is (= "application/transit+json" (:content-type envelope)))
    (is (= deck decoded))))

(deftest deck-envelope-defaults-and-kind-check
  (let [deck {:slides/id "deck"}
        envelope (wire/deck-envelope deck)]
    (is (= deck (wire/read-deck-envelope (:body envelope))))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs cljs.core.ExceptionInfo)
                 (wire/read-deck-envelope
                  {"~:kotoba.protocol/family" "~:kotoba.protocol/office"
                   "~:kotoba.protocol/version" 1
                   "~:kotoba.resource/kind" "~:docs/document"
                   "~:kotoba.resource/payload" {}})))))
