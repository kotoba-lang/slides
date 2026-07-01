(ns slides.roundtrip-matrix-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [slides.causal :as causal]
            [slides.office :as office]
            [slides.pptx :as pptx])
  (:import (java.util.zip ZipInputStream ZipOutputStream ZipEntry)))

(def matrix-path "test/slides/fixtures/pptx_roundtrip_matrix.edn")

(defn- read-matrix []
  (edn/read-string (slurp matrix-path)))

(defn- zip-bytes [entries]
  (let [out (java.io.ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[path text] entries]
        (.putNextEntry zip (ZipEntry. path))
        (.write zip (.getBytes text "UTF-8"))
        (.closeEntry zip)))
    (.toByteArray out)))

(defn- entries-from-bytes [bytes]
  (with-open [zip (ZipInputStream. (java.io.ByteArrayInputStream. bytes))]
    (loop [entries {}]
      (if-let [entry (.getNextEntry zip)]
        (let [buf (byte-array 8192)
              out (java.io.ByteArrayOutputStream.)]
          (loop []
            (let [n (.read zip buf)]
              (when (pos? n)
                (.write out buf 0 n)
                (recur))))
          (recur (assoc entries (.getName entry) (.toString out "UTF-8"))))
        entries))))

(defn- input-bytes [{:keys [kind deck entries]}]
  (case kind
    :deck (pptx/pptx-bytes deck)
    :entries (zip-bytes entries)))

(defn- import-case [case]
  (office/deck-from-office-bytes (input-bytes (:input case))
                                 {:source (str (name (:id case)) ".pptx")}))

(defn- causal-roundtrip [deck]
  (office/deck-from-office-bytes (causal/embed-deck-bytes deck)))

(defn- shapes [deck]
  (mapcat :slides/shapes (:slides/slides deck)))

(defn- texts [deck]
  (vec (keep :slides/text (shapes deck))))

(defn- source-kinds [deck]
  (set (keep :slides/source-kind (shapes deck))))

(defn- rects [deck]
  (filter #(= :rect (:slides/shape %)) (shapes deck)))

(defn- includes-frequencies? [actual expected]
  (every? (fn [[value n]]
            (<= n (get actual value 0)))
          expected))

(defn- assert-expected! [case imported roundtripped]
  (let [expect (:expect case)]
    (is (= (:slides expect) (count (:slides/slides roundtripped))))
    (when-let [expected-text (:text expect)]
      (is (= (frequencies expected-text) (frequencies (texts roundtripped)))))
    (when-let [expected-kinds (:source-kinds expect)]
      (is (every? (source-kinds imported) expected-kinds)))
    (when-let [expected-rects (:rects expect)]
      (is (<= expected-rects (count (rects roundtripped)))))
    (when-let [expected-fills (:fills expect)]
      (is (includes-frequencies?
           (frequencies (keep :slides/fill (rects roundtripped)))
           (frequencies expected-fills))))
    (when-let [expected-lines (:lines expect)]
      (is (includes-frequencies?
           (frequencies (keep :slides/line (rects roundtripped)))
           (frequencies expected-lines))))
    (doseq [[path value] (:theme-colors expect)]
      (is (= value (get-in roundtripped [:slides/theme :slides/colors path]))))
    (is (contains? (entries-from-bytes (causal/embed-deck-bytes roundtripped))
                   "ocz/causal.edn"))))

(deftest roundtrip-matrix-has-explicit-support-status
  (let [matrix (read-matrix)
        cases (:cases matrix)]
    (is (= 1 (:version matrix)))
    (is (seq cases))
    (is (some #(= :guarded (:support %)) cases))
    (is (some #(= :target (:support %)) cases))
    (doseq [case cases]
      (testing (:id case)
        (is (:id case))
        (is (contains? #{:guarded :target} (:support case)))
        (is (seq (:features case)))
        (if (= :guarded (:support case))
          (is (contains? (:input case) :kind))
          (is (seq (:gap case))))))))

(deftest guarded-pptx-fixtures-roundtrip-through-office-and-causal
  (doseq [case (filter #(= :guarded (:support %)) (:cases (read-matrix)))]
    (testing (:id case)
      (let [imported (import-case case)
            roundtripped (causal-roundtrip imported)]
        (is (= :reconciled-pptx
               (get-in roundtripped [:slides/import :slides/text-extraction])))
        (assert-expected! case imported roundtripped)))))
