(ns slides.cli
  (:require [clojure.edn :as edn]
            [slides.pptx :as pptx])
  (:gen-class))

(defn- usage []
  (str "slides cli\n\n"
       "Commands:\n"
       "  pptx <deck.edn> <out.pptx>                  EDN deck -> PPTX\n"
       "  update <base.pptx> <deck.edn> <out.pptx>    update workflow using EDN deck\n"))

(defn- read-edn [path]
  (edn/read-string (slurp path)))

(defn -main [& args]
  (try
    (case (first args)
      "pptx" (let [[_ edn-path out-path] args]
               (when-not (and edn-path out-path)
                 (throw (ex-info (usage) {})))
               (prn (pptx/write-pptx! out-path (read-edn edn-path))))
      "update" (let [[_ base-path edn-path out-path] args]
                 (when-not (and base-path edn-path out-path)
                   (throw (ex-info (usage) {})))
                 (prn (pptx/update-pptx! base-path out-path (read-edn edn-path))))
      (println (usage)))
    (catch Exception e
      (binding [*out* *err*]
        (println (.getMessage e)))
      (System/exit 1))))
