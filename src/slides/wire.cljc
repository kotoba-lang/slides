(ns slides.wire
  "Transit wire helpers for Kotoba Slides decks."
  (:require [transit.core :as transit]))

(defn deck-envelope
  ([deck] (deck-envelope deck {}))
  ([deck opts]
   (transit/office-envelope :slides/deck deck opts)))

(defn read-deck-envelope [body]
  (let [envelope (transit/read-office-envelope-body body)]
    (when-not (= :slides/deck (:kotoba.resource/kind envelope))
      (throw (ex-info "not a Slides deck Transit envelope"
                      {:kind (:kotoba.resource/kind envelope)})))
    (:kotoba.resource/payload envelope)))
