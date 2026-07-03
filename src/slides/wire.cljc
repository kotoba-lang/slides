(ns slides.wire
  "Plain JSON wire helpers for Kotoba Slides decks.

  `read-deck-envelope` returns the plain JSON payload as written
  (string-keyed maps, arrays), not the original EDN deck shape — see
  ADR-kotoba-json-wire-protocol.md. Callers that need the EDN deck model
  back convert it explicitly with `slides.model`."
  (:require [transit.core :as transit]))

(defn deck-envelope
  ([deck] (deck-envelope deck {}))
  ([deck opts]
   (transit/office-envelope :slides/deck deck opts)))

(defn read-deck-envelope [body]
  (let [envelope (transit/read-office-envelope-body body)]
    (when-not (= :slides/deck (:kotoba.resource/kind envelope))
      (throw (ex-info "not a Slides deck JSON envelope"
                      {:kind (:kotoba.resource/kind envelope)})))
    (:kotoba.resource/payload envelope)))
