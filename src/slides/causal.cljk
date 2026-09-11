(ns slides.causal
  "Causal/provenance payload adapter for slides PPTX packages."
  (:require [office.embed :as embed]
            [office.opc :as opc]
            [slides.pptx :as pptx])
  #?(:clj (:import [java.io FileOutputStream])))

(def payload-version 1)

(defn deck-graph
  "Returns the slides causal graph embedded under office's ocz/causal.edn part."
  ([deck] (deck-graph deck {}))
  ([deck attrs]
   (merge
    {:slides-causal/version payload-version
     :slides-causal/generator "kotoba-lang/slides"
     :slides-causal/deck-id (:slides/id deck)
     :slides-causal/title (:slides/title deck)
     :slides-causal/deck deck
     :slides-causal/slides
     (mapv (fn [idx slide]
             {:slides-causal/index idx
              :slides-causal/id (:slides/id slide)
              :slides-causal/title (:slides/title slide)
              :slides-causal/shape-count (count (filter map? (:slides/shapes slide)))})
           (range)
           (pptx/deck-slides deck))}
    attrs)))

(defn embed-deck-bytes
  "Returns PPTX bytes with an embedded ocz/causal.edn slides payload."
  ([deck] (embed-deck-bytes deck {}))
  ([deck attrs]
   #?(:clj
      (-> (pptx/pptx-bytes deck)
          opc/open-package
          (embed/embed-graph (deck-graph deck attrs))
          opc/package-bytes)
      :cljs
      (throw (ex-info "embed-deck-bytes requires host zip support"
                      {:feature :slides/causal})))))

(defn read-payload-bytes
  "Reads the raw office causal payload from PPTX bytes, when present."
  [bytes]
  #?(:clj
     (-> bytes opc/open-package embed/read-payload)
     :cljs
     (throw (ex-info "read-payload-bytes requires host zip support"
                     {:feature :slides/causal}))))

(defn read-graph-bytes
  "Reads the embedded slides causal graph, or office's fallback package graph."
  [bytes]
  #?(:clj
     (-> bytes opc/open-package embed/read-graph)
     :cljs
     (throw (ex-info "read-graph-bytes requires host zip support"
                     {:feature :slides/causal}))))

(defn read-deck-bytes
  "Reads a deck previously embedded by embed-deck-bytes."
  [bytes]
  (:slides-causal/deck (read-graph-bytes bytes)))

(defn write-pptx!
  "Writes PPTX bytes with an embedded slides causal payload. JVM only."
  ([path deck] (write-pptx! path deck {}))
  ([path deck attrs]
   #?(:clj
      (let [bytes (embed-deck-bytes deck attrs)]
        (with-open [out (FileOutputStream. (str path))]
          (.write out bytes))
        {:slides/path (str path)
         :slides/bytes (alength bytes)
         :slides/slides (count (pptx/deck-slides deck))
         :slides/causal true})
      :cljs
      (throw (ex-info "write-pptx! requires a host file implementation"
                      {:feature :slides/causal})))))
