(ns slides.cli
  "CLI entrypoint for slides Office/PPTX conversion."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [slides.causal :as causal]
            [slides.office :as office]
            [slides.pptx :as pptx]
            [slides.svgraph :as svgraph]
            #?(:clj [slides.visual :as visual]))
  #?(:clj (:gen-class)))

(defn- usage []
  (str "slides cli\n\n"
       "Commands:\n"
       "  from-pptx <base.pptx> <out.edn>            Office PPTX -> deck EDN (CLJC)\n"
       "  pptx <deck.edn> <out.pptx>                  EDN deck -> PPTX\n"
       "  pptx-causal <deck.edn> <out.pptx>           EDN deck -> PPTX with ocz/causal.edn\n"
       "  causal-deck <deck.pptx> <out.edn>           read embedded slides deck from PPTX\n"
       "  svgraph <deck.edn> <out.edn>                EDN deck -> svgraph presentation EDN\n"
       "  update <base.pptx> <deck.edn> <out.pptx>    update workflow using EDN deck\n"
       "  render-pptx <deck.pptx> <out-dir>           render PPTX slides to PNGs via LibreOffice\n"
       "  visual-diff <before.pptx> <after.pptx> <out-dir> compare rendered PPTX slide PNGs\n"))

(defn- read-edn [path]
  #?(:clj (edn/read-string (slurp path))
     :cljs
     (throw (ex-info "slides CLI file reader requires a JVM host" {:feature :slides/cli}))))

(defn- read-deck-edn [path]
  (let [deck (read-edn path)]
    (when-not (map? deck)
      (throw (ex-info "deck EDN must be a map" {:path path
                                                :type (type deck)})))
    deck))

(defn- read-bytes [path]
  #?(:clj
     (java.nio.file.Files/readAllBytes
      (java.nio.file.Paths/get (str path) (into-array String [])))
     :cljs
     (throw (ex-info "slides CLI byte reader requires a JVM host" {:feature :slides/cli}))))

(defn- last-separator-index [s]
  (max (or (str/last-index-of s "/") -1)
       (or (str/last-index-of s "\\") -1)))

(defn- deck-title-from-path [path]
  (let [s (str path)
        idx (last-separator-index s)
        name (if (neg? idx) s (subs s (inc idx)))]
    (or (second (re-find #"(.*)\.[^./]+$" (str name)))
        (str name))))

(defn- require-args! [& xs]
  (when-not (every? some? xs)
    (throw (ex-info (usage) {}))))

(defn -main [& args]
  #?(:clj
     (try
       (case (first args)
         "from-pptx" (let [[_ file out-path] args]
                       (require-args! file out-path)
                       (let [deck (office/deck-from-office-bytes
                                   (read-bytes file)
                                   {:title (deck-title-from-path file)})
                             out (str out-path)]
                         (spit out (pr-str deck))
                         (prn {:slides/path out
                               :slides/slides (count (:slides/slides deck))
                               :slides/title (:slides/title deck)})))
         "pptx" (let [[_ edn-path out-path] args]
                  (require-args! edn-path out-path)
                  (prn (pptx/write-pptx! out-path (read-deck-edn edn-path))))
         "pptx-causal" (let [[_ edn-path out-path] args]
                         (require-args! edn-path out-path)
                         (prn (causal/write-pptx! out-path (read-deck-edn edn-path))))
         "causal-deck" (let [[_ pptx-path out-path] args]
                         (require-args! pptx-path out-path)
                         (let [deck (causal/read-deck-bytes (read-bytes pptx-path))]
                           (when-not (map? deck)
                             (throw (ex-info "PPTX does not contain a slides causal deck"
                                             {:path pptx-path})))
                           (spit (str out-path) (pr-str deck))
                           (prn {:slides/path (str out-path)
                                 :slides/title (:slides/title deck)
                                 :slides/slides (count (:slides/slides deck))})))
         "svgraph" (let [[_ edn-path out-path] args]
                     (require-args! edn-path out-path)
                     (let [out (str out-path)
                           projection (svgraph/presentation (read-deck-edn edn-path))]
                       (spit out (pr-str projection))
                       (prn {:slides/path out
                             :svgraph/slides (count (:svgraph/slides projection))})))
         "update" (let [[_ base-path edn-path out-path] args]
                    (require-args! base-path edn-path out-path)
                    (prn (pptx/update-pptx! base-path out-path (read-deck-edn edn-path))))
         "render-pptx" (let [[_ pptx-path out-dir] args]
                         (require-args! pptx-path out-dir)
                         (prn (visual/render-pptx-pngs! pptx-path out-dir)))
         "visual-diff" (let [[_ before-path after-path out-dir] args]
                         (require-args! before-path after-path out-dir)
                         (prn (visual/compare-pptx! before-path after-path out-dir)))
         (println (usage)))
       (catch Exception e
         (binding [*out* *err*]
           (println (.getMessage e)))
         (System/exit 1)))
     :cljs
     (throw (ex-info "slides CLI requires a JVM host" {:feature :slides/cli}))))
