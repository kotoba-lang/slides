(ns slides.svg-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
  ;;
  ;; `1` and not `1.0`: this asked for `1.0`, which is what `(/ 72 72.0)`
  ;; prints on the JVM and not what it prints in ClojureScript, so the
  ;; assertion was a host's spelling of a number rather than the number.
  ;; Both hosts now write the same text for the same measurement.
  (is (str/includes? (one (m/text-box "t" "本文" {:slides/font-size 72}))
                     "font-size=\"1\"")))

(deftest the-same-deck-draws-the-same-bytes-on-either-host
  ;; Not a second spelling of the test above: that one is about a unit, this
  ;; is about every number in the picture. A drawing whose bytes depend on
  ;; where it was drawn cannot be compared, cached or diffed, and the two
  ;; hosts disagree on exactly the numbers that come out whole — which is
  ;; most of a slide's, since decks are laid out in tenths of an inch.
  (let [out (one (m/text-box "t" "本文" {:slides/font-size 72 :slides/x 2 :slides/y 1})
                 (m/rect "r" {:slides/x 3 :slides/w 2 :slides/h 1}))]
    (is (not (str/includes? out ".0\"")) out)
    ;; And the tail of a float is not a measurement either.
    (is (str/includes? (one (m/rect "r" {:slides/x (+ 0.1 0.2)})) "x=\"0.3\""))))

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

(defn- parse-num [s]
  #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s)))

(defn- stroked
  "Every element in `out` that draws a stroke."
  [out]
  (->> (str/split out #"<")
       (filter #(str/includes? % "stroke=\""))
       (remove #(str/includes? % "stroke=\"none\""))))

(deftest a-stroke-is-in-inches-like-everything-else-here
  ;; SVG's default stroke-width is one user unit, and a user unit here is an
  ;; inch: a frame that leaves it out draws in inch-wide strokes across a
  ;; ten-inch slide. The outlines did exactly that — three shapes came out as
  ;; grey blocks covering the slide — while every assertion about them
  ;; passed, because the element was right and only its width was wrong. A
  ;; string test cannot see a picture, but it can see that a number is a
  ;; whole inch, which is the part that was checkable all along.
  (let [out (svg/slide (m/deck "d" {})
                       (reduce m/add-shape (m/slide "s" {})
                               [(m/text-box "t" "本文")
                                (m/rect "r" {:slides/line "496B9A"})
                                (m/image "i" "")])
                       {:outline? true})
        elements (stroked out)]
    (is (= 3 (count elements)) (pr-str elements))
    (doseq [el elements]
      (let [w (some-> (re-find #"stroke-width=\"([0-9.]+)\"" el) second parse-num)]
        (is (some? w) (str "a stroke with no width takes the default inch: " el))
        (is (and (pos? w) (< w 0.1)) (str "a stroke a tenth of an inch thick: " el))))
    (doseq [[_ dash] (re-seq #"stroke-dasharray=\"([^\"]+)\"" out)
            n (map parse-num (str/split dash #"[ ,]+"))]
      (is (and (pos? n) (< n 0.5)) (str "half an inch of dash: " dash)))))

(deftest the-marks-powerpoint-carries-are-the-marks-that-are-drawn
  ;; `slides.pptx` writes underline and strikethrough; this drew neither, so
  ;; a deck with either looked plain in the preview and arrived marked up in
  ;; PowerPoint — a preview that denies what the file says.
  (let [drawn (fn [attrs] (one (m/text-box "t" "本文" attrs)))]
    (is (str/includes? (drawn {:slides/bold true}) "font-weight=\"bold\""))
    (is (str/includes? (drawn {:slides/italic true}) "font-style=\"italic\""))
    (is (str/includes? (drawn {:slides/underline true}) "text-decoration=\"underline\""))
    (is (str/includes? (drawn {:slides/strikethrough true})
                       "text-decoration=\"line-through\""))
    (testing "both at once is one attribute, which is what SVG takes"
      (is (str/includes? (drawn {:slides/underline true :slides/strikethrough true})
                         "text-decoration=\"underline line-through\"")))
    (testing "and a plain run says nothing about decoration"
      (is (not (str/includes? (drawn {}) "text-decoration"))))))

(deftest a-linked-shape-is-drawn-inside-an-anchor
  ;; `slides.pptx` has written `:slides/hyperlink` as a relationship on the
  ;; shape all along and this drew nothing, so a linked shape looked
  ;; ordinary in every preview and arrived clickable in PowerPoint.
  (let [drawn (fn [url] (one (m/text-box "t" "ここを見て"
                                         {:slides/hyperlink url})))]
    (is (str/includes? (drawn "https://example.com/?a=1&b=2")
                       "<a href=\"https://example.com/?a=1&amp;b=2\" rel=\"noreferrer noopener\">"))
    (is (str/includes? (drawn "https://example.com") "</a>"))
    (testing "a scheme that is not a place is not drawn as one"
      ;; The shape stays; only the link goes. An allowlist, because an
      ;; `<a href=\"javascript:…\">` in a preview is script in the reader's
      ;; session.
      (doseq [refused ["javascript:alert(1)" "data:text/html,<script>" "/relative"
                       "example.com" "" nil]]
        (let [out (drawn refused)]
          (is (not (str/includes? out "<a ")) (pr-str refused))
          (is (str/includes? out "ここを見て") (pr-str refused)))))
    (testing "and a rect can carry one too"
      (is (str/includes? (one (m/rect "r" {:slides/hyperlink "https://example.com"}))
                         "<a href=\"https://example.com\"")))))

(deftest a-table-is-drawn-as-a-grid-with-its-text-in-it
  ;; `slides.pptx` writes a table as a native `<a:tbl>`; this drew nothing,
  ;; so a deck with one showed an empty rectangle in every preview and
  ;; arrived in PowerPoint with the table in it.
  (let [out (one (m/table "tb" [["名前" "点"] ["a" "10"]]))]
    (is (= 4 (count (re-seq #"stroke=\"#8c959f\"" out))) "two rows of two cells")
    (doseq [text ["名前" "点" "a" "10"]]
      (is (str/includes? out (str ">" text "</text>")) text))
    (testing "a ragged row is input, not an impossibility"
      ;; The writer pads to the widest; the preview draws the widest too, so
      ;; the two agree about how many columns there are.
      (let [ragged (one (m/table "tb" [["a" "b" "c"] ["d"]]))]
        (is (= 6 (count (re-seq #"stroke=\"#8c959f\"" ragged))))
        (is (str/includes? ragged ">d</text>"))))
    (testing "and an empty cell draws its box and no text"
      (let [gap (one (m/table "tb" [["a" ""]]))]
        (is (= 2 (count (re-seq #"stroke=\"#8c959f\"" gap))))
        (is (= 1 (count (re-seq #"<text" gap))))))))
