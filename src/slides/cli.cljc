(ns slides.cli
  "CLI entrypoint for slides Office/PPTX conversion."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [slides.office :as office]
            [slides.pptx :as pptx])
  #?(:clj (:gen-class)))

(defn- usage []
  (str "slides cli\n\n"
       "Commands:\n"
       "  from-pptx <base.pptx> <out.edn>            Office PPTX -> deck EDN (CLJC)\n"
       "  pptx <deck.edn> <out.pptx>                  EDN deck -> PPTX\n"
       "  update <base.pptx> <deck.edn> <out.pptx>    update workflow using EDN deck\n"))

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

(defn -main [& args]
  #?(:clj
     (try
       (case (first args)
         "from-pptx" (let [[_ file out-path] args]
                       (when-not (and file out-path)
                         (throw (ex-info (usage) {})))
                       (let [deck (office/deck-from-office-bytes
                                   (read-bytes file)
                                   {:title (deck-title-from-path file)})
                             out (str out-path)]
                         (spit out (pr-str deck))
                         (prn {:slides/path out
                               :slides/slides (count (:slides/slides deck))
                               :slides/title (:slides/title deck)})))
         "pptx" (let [[_ edn-path out-path] args]
                  (when-not (and edn-path out-path)
                    (throw (ex-info (usage) {})))
                  (prn (pptx/write-pptx! out-path (read-deck-edn edn-path))))
         "update" (let [[_ base-path edn-path out-path] args]
                    (when-not (and base-path edn-path out-path)
                      (throw (ex-info (usage) {})))
                    (prn (pptx/update-pptx! base-path out-path (read-deck-edn edn-path))))
         (println (usage)))
       (catch Exception e
         (binding [*out* *err*]
           (println (.getMessage e)))
         (System/exit 1)))
     :cljs
     (throw (ex-info "slides CLI requires a JVM host" {:feature :slides/cli}))))
