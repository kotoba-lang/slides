#!/usr/bin/env nbb
(ns slides-import-e2e
  (:require [clojure.string :as str]
            ["node:child_process" :refer [execFileSync spawn]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["playwright" :refer [chromium]]))

(def base-url "http://127.0.0.1:4173")
(def downloads-root "/tmp")

(defn fail! [message]
  (throw (js/Error. message)))

(defn ok [condition message]
  (when-not condition
    (fail! message)))

(defn match? [pattern value]
  (boolean (re-find pattern (str value))))

(defn invoke [obj method & args]
  (let [f (aget obj method)]
    (when-not f
      (fail! (str "missing JS method: " method)))
    (.apply f obj (to-array args))))

(defn promise [value]
  (js/Promise.resolve value))

(defn then [value f]
  (.then (promise value) f))

(defn chain [value & fs]
  (reduce then (promise value) fs))

(defn sleep! [ms]
  (js/Promise.
   (fn [resolve _reject]
     (js/setTimeout resolve ms))))

(defn wait-until! [label f]
  (let [deadline (+ (.now js/Date) 10000)]
    (letfn [(tick []
              (then (f)
                    (fn [done?]
                      (if done?
                        true
                        (do
                          (when (> (.now js/Date) deadline)
                            (fail! (str "timed out waiting for " label)))
                          (then (sleep! 100) tick))))))]
      (tick))))

(defn locator [page selector]
  (invoke page "locator" selector))

(defn locator-text [page selector]
  (invoke (locator page selector) "textContent"))

(defn locator-value [page selector]
  (invoke (locator page selector) "inputValue"))

(defn wait-text! [page selector pattern]
  (wait-until! (str selector " to match " pattern)
               #(then (locator-text page selector)
                      (fn [text]
                        (match? pattern text)))))

(defn wait-value! [page selector pattern]
  (wait-until! (str selector " value to match " pattern)
               #(then (locator-value page selector)
                      (fn [value]
                        (match? pattern value)))))

(defn wait-visible! [page selector]
  (invoke (locator page selector) "waitFor" #js {:state "visible"}))

(defn click! [page selector]
  (invoke (locator page selector) "click"))

(defn fill! [page selector value]
  (invoke (locator page selector) "fill" value))

(defn set-input-files! [page selector file]
  (invoke (locator page selector) "setInputFiles" file))

(defn unzip-text [pptx-path entry-path]
  (execFileSync "unzip"
                #js ["-p" pptx-path entry-path]
                #js {:encoding "utf8"}))

(defn prepare-dir! [dir]
  (.rmSync fs dir #js {:recursive true :force true})
  (.mkdirSync fs dir #js {:recursive true}))

(defn save-download! [page click-selector out-path]
  (let [download-promise (invoke page "waitForEvent" "download")]
    (chain nil
           (fn [_] (click! page click-selector))
           (fn [_] download-promise)
           (fn [download]
             (then (invoke download "saveAs" out-path)
                   (fn [_] out-path))))))

(defn track-browser-errors! [page]
  (let [errors (atom [])]
    (invoke page "on" "console"
            (fn [msg]
              (when (= "error" (invoke msg "type"))
                (swap! errors conj (invoke msg "text")))))
    (invoke page "on" "pageerror"
            (fn [err]
              (swap! errors conj (.-message err))))
    errors))

(defn expect-no-browser-errors! [page errors]
  (then (locator-text page "#error")
        (fn [error-text]
          (ok (= "" error-text) "expected #error to be empty")
          (ok (empty? @errors) (str "unexpected browser errors: " (pr-str @errors)))
          true)))

(defn open-app! [browser]
  (then (invoke browser "newPage")
        (fn [page]
          (chain nil
                 (fn [_] (invoke page "goto" base-url #js {:waitUntil "networkidle"}))
                 (fn [_] (wait-visible! page "text=Open PPTX"))
                 (fn [_]
                   (then (invoke (locator page "#pptx-file") "count")
                         (fn [count]
                           (ok (= 1 count) "expected #pptx-file")
                           page)))))))

(defn close-page! [page]
  (invoke page "close"))

(defn imports-pptx-edits-shape-and-writes-pptx! [browser]
  (let [downloads (path/join downloads-root "kotoba-slides-downloads")
        out (path/join downloads "browser-import-export.pptx")]
    (then (open-app! browser)
          (fn [page]
            (let [errors (track-browser-errors! page)]
              (prepare-dir! downloads)
              (chain nil
                     (fn [_] (set-input-files! page "#pptx-file" (path/resolve "docs/sample.pptx")))
                     (fn [_] (wait-text! page "#status" #"slides"))
                     (fn [_] (wait-visible! page "[data-shape=\"0\"]"))
                     (fn [_] (click! page "[data-shape=\"0\"]"))
                     (fn [_] (wait-visible! page "#shape-text"))
                     (fn [_] (fill! page "#shape-text" "Browser Edited PPTX Title"))
                     (fn [_] (wait-text! page "[data-shape=\"0\"]" #"Browser Edited PPTX Title"))
                     (fn [_] (click! page "#mode-edn"))
                     (fn [_] (wait-value! page "#deck-edn" #"Browser Edited PPTX Title"))
                     (fn [_] (wait-value! page "#deck-edn" #":slides/format :pptx"))
                     (fn [_] (wait-value! page "#deck-edn" #":slides/text-extraction :drawingml-runs"))
                     (fn [_] (save-download! page "#download-pptx" out))
                     (fn [_]
                       (ok (> (.-size (.statSync fs out)) 1000) "downloaded PPTX was too small")
                       (ok (match? #"Browser Edited PPTX Title" (unzip-text out "ppt/slides/slide1.xml"))
                           "edited text missing from slide XML")
                       (ok (match? #"a:theme" (unzip-text out "ppt/theme/theme1.xml"))
                           "theme XML missing")
                       (ok (match? #":slides-causal/deck" (unzip-text out "ocz/causal.edn"))
                           "causal payload missing deck EDN")
                       (ok (match? #":office/generator \"kotoba-lang/office\"" (unzip-text out "ocz/causal.edn"))
                           "causal payload missing office generator")
                       true)
                     (fn [_] (set-input-files! page "#pptx-file" out))
                     (fn [_] (click! page "#mode-edn"))
                     (fn [_] (wait-value! page "#deck-edn" #":slides/text-extraction :causal-edn"))
                     (fn [_] (wait-value! page "#deck-edn" #"Browser Edited PPTX Title"))
                     (fn [_]
                       (expect-no-browser-errors! page errors))
                     (fn [_] (close-page! page))))))))

(defn applies-edn-components-and-exports-editable-pptx! [browser]
  (let [downloads (path/join downloads-root "kotoba-slides-edn-downloads")
        out (path/join downloads "edn-component-export.pptx")
        deck-edn "{:slides/id \"component-deck\"
 :slides/title \"Component Deck\"
 :slides/width 10
 :slides/height 5.625
 :slides/design {:slides/components {:hero {:slides/shape :text
                                             :slides/text-style :title
                                             :slides/x 1.0
                                             :slides/y 0.8
                                             :slides/w 8.0
                                             :slides/h 1.0}}
                 :slides/text-styles {:title {:slides/font-size 42
                                               :slides/color \"123456\"
                                               :slides/bold true}}}
 :slides/slides [{:slides/id \"slide-1\"
                  :slides/title \"EDN Component\"
                  :slides/shapes [{:slides/id \"hero\"
                                   :slides/component :hero
                                   :slides/text \"EDN Component Title\"}]}]}"]
    (then (open-app! browser)
          (fn [page]
            (let [errors (track-browser-errors! page)]
              (prepare-dir! downloads)
              (chain nil
                     (fn [_] (click! page "#mode-edn"))
                     (fn [_] (fill! page "#deck-edn" deck-edn))
                     (fn [_] (click! page "#apply-edn"))
                     (fn [_] (wait-text! page "[data-shape=\"0\"]" #"EDN Component Title"))
                     (fn [_] (click! page "#mode-edn"))
                     (fn [_] (wait-value! page "#deck-edn" #":slides/component :hero"))
                     (fn [_] (save-download! page "#download-pptx" out))
                     (fn [_]
                       (let [slide-xml (unzip-text out "ppt/slides/slide1.xml")]
                         (ok (match? #"EDN Component Title" slide-xml) "component title missing from slide XML")
                         (ok (match? #"sz=\"4200\"" slide-xml) "font size missing from slide XML")
                         (ok (str/includes? slide-xml "123456") "font color missing from slide XML"))
                       (ok (match? #":slides-causal/deck" (unzip-text out "ocz/causal.edn"))
                           "component export missing causal payload")
                       (expect-no-browser-errors! page errors))
                     (fn [_] (close-page! page))))))))

(defn surfaces-invalid-edn-without-losing-current-deck! [browser]
  (then (open-app! browser)
        (fn [page]
          (let [errors (track-browser-errors! page)]
            (chain nil
                   (fn [_] (wait-text! page "#status" #"2\s*slides"))
                   (fn [_] (click! page "#mode-edn"))
                   (fn [_] (fill! page "#deck-edn" "{:slides/id \"broken\""))
                   (fn [_] (click! page "#apply-edn"))
                   (fn [_]
                     (wait-until! "#error to be non-empty"
                                  #(then (locator-text page "#error")
                                         (fn [text]
                                           (not= "" text)))))
                   (fn [_] (wait-text! page "#status" #"2\s*slides"))
                   (fn [_] (click! page "#mode-visual"))
                   (fn [_] (wait-text! page "[data-shape=\"2\"]" #"EDN to editable PPTX"))
                   (fn [_]
                     (ok (empty? @errors) (str "unexpected browser errors: " (pr-str @errors)))
                     (close-page! page)))))))

(defn surfaces-unsupported-pptx-input-as-editor-error! [browser]
  (let [bad-path (path/join downloads-root "kotoba-slides-not-a-pptx.txt")]
    (then (open-app! browser)
          (fn [page]
            (let [errors (track-browser-errors! page)]
              (.writeFileSync fs bad-path "not a pptx zip")
              (chain nil
                     (fn [_] (set-input-files! page "#pptx-file" bad-path))
                     (fn [_] (wait-text! page "#error" #"PPTX ZIP end record was not found|central directory|local file header"))
                     (fn [_] (wait-text! page "#status" #"2\s*slides"))
                     (fn [_]
                       (ok (empty? @errors) (str "unexpected browser errors: " (pr-str @errors)))
                       (close-page! page))))))))

(defn start-server! []
  (spawn "python3"
         #js ["-m" "http.server" "4173" "--directory" "docs"]
         #js {:stdio "ignore"}))

(let [server (start-server!)
      browser (atom nil)]
  (-> (sleep! 500)
      (then (fn [_] (invoke chromium "launch" #js {:headless true})))
      (then (fn [b]
              (reset! browser b)
              (chain nil
                     (fn [_] (imports-pptx-edits-shape-and-writes-pptx! b))
                     (fn [_] (applies-edn-components-and-exports-editable-pptx! b))
                     (fn [_] (surfaces-invalid-edn-without-losing-current-deck! b))
                     (fn [_] (surfaces-unsupported-pptx-input-as-editor-error! b)))))
      (then (fn [_]
              (println "slides e2e pass")))
      (.catch (fn [error]
                (.error js/console (or (.-stack error) error))
                (.exit js/process 1)))
      (.finally (fn []
                  (when @browser
                    (invoke @browser "close"))
                  (.kill server)))))
