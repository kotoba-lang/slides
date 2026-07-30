(ns slides.svg
  "A slide, drawn.

  The editor could edit a text box and sent every other shape to the JSON
  pane, on the grounds that position and fill are a canvas's job. They are —
  and a canvas is a picture, which is what this makes. Numbers for `x`, `y`,
  `w` and `h` are worth typing once you can see what they move.

  ## SVG, and here rather than in an application

  A string, so the same slide can be tested without a browser, rendered
  server-side, and put in a page by anything that can put a string in a
  page. `slides.pptx` already knows how to draw these shapes into a
  presentation; this is the same knowledge pointed at a screen, and an
  application drawing its own would be the second one.

  ## Inches, because that is what a slide is in

  `slides.model` measures in inches — `:slides/x 0.8` is eight tenths of an
  inch from the left — and a deck is 10 × 5.625 unless it says otherwise.
  The SVG uses those numbers directly as its viewBox, so a shape's numbers
  and its position are the same thing and nothing has to be converted to
  find out where something is."
  (:require [clojure.string :as str]
            [slides.svgraph :as svgraph]))

(defn- esc [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- num-or [x fallback]
  (if (and (number? x) #?(:clj (Double/isFinite (double x)) :cljs (js/isFinite x)))
    x
    fallback))

(defn- colour
  "`EAF0F8` → `#EAF0F8`.

  The model stores what OOXML stores, which is six hex digits and no hash.
  Passing that to SVG unchanged names no colour at all — and an SVG with an
  unparseable fill draws the shape black, which looks deliberate."
  [value fallback]
  (let [v (str/trim (str value))]
    (cond
      (str/blank? v) fallback
      (str/starts-with? v "#") v
      (re-matches #"[0-9A-Fa-f]{6}" v) (str "#" v)
      :else v)))

(defn- text-shape [s]
  (let [x (num-or (:slides/x s) 0) y (num-or (:slides/y s) 0)
        w (num-or (:slides/w s) 1) h (num-or (:slides/h s) 0.5)
        ;; Points to inches: a slide's font size is in points and everything
        ;; else here is in inches, so a 28pt line is 28/72 of an inch tall.
        size (/ (num-or (:slides/font-size s) 18) 72.0)
        lines (str/split-lines (str (:slides/text s)))]
    (str "<text x=\"" x "\" y=\"" (+ y size)
         "\" font-size=\"" size
         "\" fill=\"" (colour (:slides/color s) "#24292f") "\""
         (when (:slides/bold s) " font-weight=\"bold\"")
         (when (:slides/italic s) " font-style=\"italic\"")
         ">"
         (apply str
                (map-indexed
                 (fn [i line]
                   ;; One `tspan` per line: SVG text does not wrap and a
                   ;; newline inside it renders as a space, so a two-line
                   ;; title would come out as one long one.
                   (str "<tspan x=\"" x "\" dy=\"" (if (zero? i) 0 (* size 1.2)) "\">"
                        (esc line) "</tspan>"))
                 (if (seq lines) lines [""])))
         "</text>"
         ;; The box is not drawn — a text box has no outline on a slide —
         ;; but its height is what the editor is moving, so it is worth
         ;; being able to see. Only when asked.
         (when (:slides.svg/outline? s)
           (str "<rect x=\"" x "\" y=\"" y "\" width=\"" w "\" height=\"" h
                "\" fill=\"none\" stroke=\"#d0d7de\" stroke-dasharray=\"2 2\"/>")))))

(defn- rect-shape [s]
  (str "<rect x=\"" (num-or (:slides/x s) 0) "\" y=\"" (num-or (:slides/y s) 0)
       "\" width=\"" (num-or (:slides/w s) 1) "\" height=\"" (num-or (:slides/h s) 1)
       "\" fill=\"" (colour (:slides/fill s) "#EAF0F8") "\""
       (when (:slides/line s)
         (str " stroke=\"" (colour (:slides/line s) "#496B9A")
              "\" stroke-width=\"0.02\""))
       "/>"))

(defn- image-shape [s]
  ;; A data URI, because the bytes are already base64 in the model and the
  ;; alternative is a second place they live. An image with no data draws
  ;; its frame, so an editor can still find and move it.
  (let [x (num-or (:slides/x s) 0) y (num-or (:slides/y s) 0)
        w (num-or (:slides/w s) 1) h (num-or (:slides/h s) 1)
        data (str (:slides/image-data s))]
    (if (str/blank? data)
      (str "<rect x=\"" x "\" y=\"" y "\" width=\"" w "\" height=\"" h
           "\" fill=\"none\" stroke=\"#d0d7de\" stroke-dasharray=\"3 3\"/>")
      (str "<image x=\"" x "\" y=\"" y "\" width=\"" w "\" height=\"" h
           "\" href=\"data:" (esc (or (:slides/media-type s) "image/png"))
           ";base64," (esc data) "\" preserveAspectRatio=\"xMidYMid meet\"/>"))))

(defn shape
  "One shape as SVG, or nil for a kind this does not know.

  Nil rather than a placeholder: a box drawn for something this cannot
  render says *here is a shape* when what is true is *this does not know how
  to draw it*, and the two look identical on screen."
  [s]
  (when (map? s)
    (case (:slides/shape s)
      :text (text-shape s)
      :rect (rect-shape s)
      :image (image-shape s)
      nil)))

(defn slide
  "One slide as an SVG string.

  Always something, even for a slide with no shapes: an empty slide is a
  real thing to be looking at, unlike an empty chart, and drawing the page
  is how you see that it is empty rather than broken."
  ([deck s] (slide deck s {}))
  ([deck s {:keys [outline?]}]
   (let [w (num-or (:slides/width deck) svgraph/default-width-in)
         h (num-or (:slides/height deck) svgraph/default-height-in)
         shapes (filter map? (:slides/shapes s))]
     (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " w " " h "\""
          " role=\"img\" aria-label=\""
          (esc (or (:slides/title s) (:slides/id s) "スライド")) "\">"
          "<rect x=\"0\" y=\"0\" width=\"" w "\" height=\"" h "\" fill=\"#ffffff\"/>"
          (apply str (keep #(shape (cond-> % outline? (assoc :slides.svg/outline? true)))
                           shapes))
          "</svg>"))))

(defn deck
  "Every slide of `deck`, drawn.

  `{:id :title :svg}` per slide, in order — the order is the deck's and not
  something a caller should have to restore."
  ([d] (deck d {}))
  ([d opts]
   (mapv (fn [s] {:id (:slides/id s)
                  :title (:slides/title s)
                  :svg (slide d s opts)})
         (filter map? (:slides/slides d)))))
