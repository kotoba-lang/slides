(ns slides.svg-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [slides.model :as m]
            [slides.svg :as svg]))

(defn- one [& shapes]
  (svg/slide (m/deck "d" {})
             (reduce m/add-shape (m/slide "s1" {:slides/title "見出し"}) shapes)))

(deftest a-slide-is-drawn-in-the-units-it-is-measured-in
  ;; The model measures in inches and a deck is 10 × 5.625 unless it says
  ;; otherwise. Using them directly as the viewBox means a shape's numbers
  ;; and its position are the same thing — nothing to convert to find out
  ;; where something is.
  (is (str/includes? (one) "viewBox=\"0 0 10 5.625\""))
  (is (str/includes? (svg/slide (m/deck "d" {:slides/width 13.333 :slides/height 7.5})
                                (m/slide "s" {}))
                     "viewBox=\"0 0 13.333 7.5\"")))

(deftest each-shape-kind-draws
  (is (str/includes? (one (m/text-box "t" "本文")) "<text"))
  (is (str/includes? (one (m/rect "r" {})) "<rect x=\"0.8\""))
  (is (str/includes? (one (m/image "i" "aGVsbG8=")) "<image"))
  ;; A kind this does not know draws nothing. A placeholder box says "here
  ;; is a shape" when what is true is "this does not know how to draw it",
  ;; and the two look identical on screen.
  (is (nil? (svg/shape {:slides/shape :smartart :slides/x 1 :slides/y 1}))))

(deftest a-colour-gets-its-hash
  ;; The model stores what OOXML stores — six hex digits, no hash — and
  ;; passing that to SVG unchanged names no colour, which draws the shape
  ;; black and looks deliberate.
  (is (str/includes? (one (m/rect "r" {:slides/fill "FF0000"})) "fill=\"#FF0000\""))
  ;; One that already has a hash, and a named colour, are left alone.
  (is (str/includes? (one (m/rect "r" {:slides/fill "#00FF00"})) "fill=\"#00FF00\""))
  (is (str/includes? (one (m/rect "r" {:slides/fill "red"})) "fill=\"red\""))
  ;; And a missing one falls back rather than drawing nothing.
  (is (str/includes? (one (m/rect "r" {:slides/fill nil})) "fill=\"#EAF0F8\"")))

(deftest a-font-size-is-points-and-everything-else-is-inches
  ;; A 72pt line is one inch tall. Mixing the two units silently gives a
  ;; slide whose text is 28 inches high.
  (is (str/includes? (one (m/text-box "t" "本文" {:slides/font-size 72}))
                     "font-size=\"1.0\"")))

(deftest a-newline-is-a-line
  ;; SVG text does not wrap and a newline inside it renders as a space, so a
  ;; two-line title would come out as one long one.
  (let [out (one (m/text-box "t" "一行目\n二行目"))]
    (is (= 2 (count (re-seq #"<tspan" out))))
    (is (str/includes? out "一行目"))
    (is (str/includes? out "二行目"))))

(deftest an-image-with-no-data-draws-its-frame
  ;; So an editor can still find and move it.
  (let [out (one (m/image "i" ""))]
    (is (not (str/includes? out "<image")))
    (is (str/includes? out "stroke-dasharray"))))

(deftest text-is-escaped
  (is (str/includes? (one (m/text-box "t" "<script>")) "&lt;script&gt;"))
  (is (str/includes? (svg/slide (m/deck "d" {}) (m/slide "s" {:slides/title "a & b"}))
                     "a &amp; b")))

(deftest an-empty-slide-is-still-a-slide
  ;; Unlike an empty chart. An empty slide is a real thing to be looking at,
  ;; and drawing the page is how you see it is empty rather than broken.
  (let [out (svg/slide (m/deck "d" {}) (m/slide "s" {}))]
    (is (str/includes? out "<svg"))
    (is (str/includes? out "fill=\"#ffffff\""))))

(deftest a-deck-draws-in-its-own-order
  (let [d (-> (m/deck "d" {})
              (m/add-slide (m/slide "s1" {:slides/title "一"}))
              (m/add-slide (m/slide "s2" {:slides/title "二"})))
        drawn (svg/deck d)]
    (is (= ["s1" "s2"] (mapv :id drawn)))
    (is (= ["一" "二"] (mapv :title drawn)))
    (is (every? #(str/includes? (:svg %) "<svg") drawn))))

(deftest the-outline-is-only-drawn-when-asked
  ;; A text box has no outline on a slide, and its height is what an editor
  ;; is moving — worth seeing while editing and wrong in a preview.
  (let [s (reduce m/add-shape (m/slide "s" {}) [(m/text-box "t" "本文")])]
    (is (not (str/includes? (svg/slide (m/deck "d" {}) s) "stroke-dasharray")))
    (is (str/includes? (svg/slide (m/deck "d" {}) s {:outline? true})
                       "stroke-dasharray"))))

(deftest drawing-does-not-throw-on-a-half-built-slide
  (doseq [s [{} {:slides/shapes nil} {:slides/shapes [nil]}
             {:slides/shapes [{}]}
             {:slides/shapes [{:slides/shape :rect}]}
             {:slides/shapes [{:slides/shape :text :slides/x "x"}]}]]
    (is (string? (svg/slide (m/deck "d" {}) s)) (pr-str s))))
