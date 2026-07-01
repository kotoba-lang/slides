(ns slides.coverage-gate
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-lcov-path "target/coverage/lcov.info")

(def namespace-thresholds
  {"slides/build.clj" 85.0
   "slides/cli.cljc" 85.0
   "slides/design.cljc" 90.0
   "slides/hiccup.cljc" 85.0
   "slides/model.cljc" 90.0
   "slides/office.cljc" 90.0
   "slides/pptx.cljc" 85.0
   "slides/render.cljc" 90.0
   "slides/routes.cljc" 90.0
   "slides/site.clj" 85.0
   "slides/validate.cljc" 90.0
   "slides/web/events.cljc" 90.0
   "slides/web/sample.cljc" 100.0
   "slides/web/ssr.clj" 90.0
   "slides/web/views.cljc" 90.0})

(def aggregate-threshold 90.0)

(defn- tracked-path [source-file]
  (when-let [[_ path] (re-find #"(?:^|/)src/(slides/.+)$" (str source-file))]
    path))

(defn- parse-da [line]
  (when-let [[_ line-no hits] (re-find #"^DA:(\d+),(\d+)" line)]
    [(parse-long line-no) (parse-long hits)]))

(defn parse-lcov [text]
  (loop [lines (str/split-lines text)
         current nil
         records []]
    (if-let [line (first lines)]
      (cond
        (str/starts-with? line "SF:")
        (recur (rest lines)
               {:source (subs line 3)
                :lines []}
               records)

        (= "end_of_record" line)
        (recur (rest lines)
               nil
               (cond-> records current (conj current)))

        (and current (str/starts-with? line "DA:"))
        (recur (rest lines)
               (update current :lines conj (parse-da line))
               records)

        :else
        (recur (rest lines) current records))
      (cond-> records current (conj current)))))

(defn coverage-summary [records]
  (let [summaries (->> records
                       (keep (fn [{:keys [source lines]}]
                               (when-let [path (tracked-path source)]
                                 (let [total (count lines)
                                       covered (count (filter (fn [[_ hits]] (pos? hits)) lines))]
                                   [path {:total total
                                          :covered covered
                                          :percent (if (pos? total)
                                                     (* 100.0 (/ covered total))
                                                     100.0)}]))))
                       (into {}))
        total (reduce + (map :total (vals summaries)))
        covered (reduce + (map :covered (vals summaries)))]
    {:files summaries
     :aggregate {:total total
                 :covered covered
                 :percent (if (pos? total)
                            (* 100.0 (/ covered total))
                            100.0)}}))

(defn failures [{:keys [files aggregate]}]
  (vec
   (concat
    (for [[path threshold] namespace-thresholds
          :let [summary (get files path)]
          :when (or (nil? summary)
                    (< (:percent summary) threshold))]
      {:path path
       :threshold threshold
       :percent (:percent summary 0.0)
       :missing? (nil? summary)})
    (when (< (:percent aggregate) aggregate-threshold)
      [{:path "ALL FILES"
        :threshold aggregate-threshold
        :percent (:percent aggregate)}]))))

(defn format-percent [x]
  (format "%.2f" (double x)))

(defn report! [{:keys [files aggregate] :as summary}]
  (println "Coverage thresholds:")
  (doseq [[path threshold] (sort namespace-thresholds)
          :let [percent (get-in files [path :percent] 0.0)]]
    (println (format "  %-22s %6s%% >= %.2f%%"
                     path
                     (format-percent percent)
                     threshold)))
  (println (format "  %-22s %6s%% >= %.2f%%"
                   "ALL FILES"
                   (format-percent (:percent aggregate))
                   aggregate-threshold))
  summary)

(defn check! [lcov-path]
  (let [file (io/file lcov-path)]
    (when-not (.isFile file)
      (throw (ex-info (str "Coverage report not found: " lcov-path)
                      {:path lcov-path})))
    (let [summary (coverage-summary (parse-lcov (slurp file)))
          failed (failures summary)]
      (report! summary)
      (when (seq failed)
        (doseq [{:keys [path threshold percent missing?]} failed]
          (binding [*out* *err*]
            (println (format "coverage gate failed: %s has %s%%, needs %.2f%%%s"
                             path
                             (format-percent percent)
                             threshold
                             (if missing? " (missing from lcov)" "")))))
        (System/exit 1))
      summary)))

(defn -main [& [lcov-path]]
  (check! (or lcov-path default-lcov-path))
  (shutdown-agents))
