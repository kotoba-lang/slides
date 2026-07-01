(ns slides.cli-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [slides.causal :as causal]
            [slides.office :as office]
            [slides.pptx :as pptx]
            [slides.cli :as cli]
            [slides.svgraph :as svgraph]
            [slides.visual :as visual]))

(deftest usage-description
  (let [usage-fn (ns-resolve 'slides.cli 'usage)]
    (is (some? usage-fn))
    (is (fn? @usage-fn))
    (let [usage (@usage-fn)]
      (is (string? usage))
      (is (re-find #"from-pptx" usage))
      (is (re-find #"pptx" usage))
      (is (re-find #"pptx-causal" usage))
      (is (re-find #"causal-deck" usage))
      (is (re-find #"svgraph" usage))
      (is (re-find #"update" usage))
      (is (re-find #"render-pptx" usage))
      (is (re-find #"visual-diff" usage)))))

(deftest package-bin-points-to-cljs-wrapper
  (let [package-json (slurp "package.json")
        package-lock (slurp "package-lock.json")
        bin (java.io.File. "bin/kotoba-slides.cljs")]
    (is (re-find #"\"kotoba-slides\"\s*:\s*\"bin/kotoba-slides\.cljs\"" package-json))
    (is (re-find #"\"kotoba-slides\"\s*:\s*\"bin/kotoba-slides\.cljs\"" package-lock))
    (is (not (re-find #"bin/kotoba-slides\.js" package-json)))
    (is (not (re-find #"bin/kotoba-slides\.js" package-lock)))
    (is (.exists bin))
    (is (.canExecute bin))))

(deftest parse-deck-title-from-path
  (let [title-fn (ns-resolve 'slides.cli 'deck-title-from-path)]
    (is (some? title-fn))
    (is (fn? @title-fn))
    (is (= "report" (@title-fn "/tmp/report.pptx")))
    (is (= "my.report" (@title-fn "/tmp/my.report.pptx")))
    (is (= "README" (@title-fn "/tmp/README")))
    (is (= "my deck" (@title-fn "C:\\Users\\Alice\\my deck.pptx")))))

(deftest read-bytes-reads-file-content
  (let [tmp (java.io.File/createTempFile "slides-cli-bytes" ".bin")
        path (.getAbsolutePath tmp)
        payload (.getBytes "payload-bytes" "UTF-8")
        read-bytes-fn (ns-resolve 'slides.cli 'read-bytes)]
    (is (some? read-bytes-fn))
    (is (fn? @read-bytes-fn))
    (spit tmp "payload-bytes")
    (try
      (let [bytes (@read-bytes-fn path)]
        (is (= (vec payload) (vec bytes))))
      (finally
        (.delete tmp)))))

(deftest read-deck-edn-requires-map
  (let [tmp (java.io.File/createTempFile "slides-cli-deck" ".edn")
        path (.getAbsolutePath tmp)
        read-deck-fn (ns-resolve 'slides.cli 'read-deck-edn)]
    (is (some? read-deck-fn))
    (is (fn? @read-deck-fn))
    (try
      (spit tmp "[:not :a :map]")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"deck EDN must be a map"
                            (@read-deck-fn path)))
      (spit tmp "{:slides/title \"Deck\"}")
      (is (= {:slides/title "Deck"} (@read-deck-fn path)))
      (finally
        (.delete tmp)))))

(deftest require-args-throws-usage-for-missing-arguments
  (let [require-args-fn (ns-resolve 'slides.cli 'require-args!)]
    (is (some? require-args-fn))
    (is (fn? @require-args-fn))
    (is (nil? (@require-args-fn "in.edn" "out.pptx")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"slides cli"
                          (@require-args-fn "in.edn" nil)))))

(deftest unknown-command-shows-usage
  (is (re-find #"slides cli" (with-out-str (cli/-main "help")))))

(deftest from-pptx-command-writes-edn
  (let [input (java.io.File/createTempFile "slides-from-pptx" ".pptx")
        output (java.io.File/createTempFile "slides-from-pptx-out" ".edn")
        payload {:slides/title "From command" :slides/slides [{:slides/id "s1"}]}
        expected-title (str/replace (.getName input) #"\.pptx$" "")
        in-path (.getAbsolutePath input)
        out-path (.getAbsolutePath output)]
    (spit input "pptx-placeholder")
    (try
      (with-redefs [office/deck-from-office-bytes (fn [_ options]
                                                   (assoc payload
                                                          :slides/title (or (:title options)
                                                                            (:slides/title options)
                                                                            "From command")))]
        (cli/-main "from-pptx" in-path out-path)
        (let [written (edn/read-string (slurp out-path))]
          (is (= expected-title (:slides/title written)))
          (is (= 1 (count (:slides/slides written))))))
      (finally
        (.delete input)
        (.delete output)))))

(deftest pptx-command-writes-pptx
  (let [deck {:slides/title "From editor"}
        in (java.io.File/createTempFile "slides-cli-edn" ".edn")
        out (java.io.File/createTempFile "slides-cli-pptx" ".pptx")
        in-path (.getAbsolutePath in)
        out-path (.getAbsolutePath out)
        expected {:slides/path out-path
                  :slides/bytes 32
                  :slides/slides 0}
        wrote (atom nil)]
    (try
      (let [read-edn-var (ns-resolve 'slides.cli 'read-deck-edn)
            write-pptx-var (ns-resolve 'slides.pptx 'write-pptx!)
            printed (with-out-str
                      (with-redefs-fn {read-edn-var (fn [path]
                                                      (is (= in-path path))
                                                      deck)
                                        write-pptx-var (fn [path actual]
                                                         (is (= out-path path))
                                                         (is (= deck actual))
                                                         (reset! wrote expected)
                                                         expected)}
                        #(cli/-main "pptx" in-path out-path)))]
        (is (= expected @wrote))
        (is (re-find #":path" printed))
        (is (= expected (edn/read-string printed))))
      (finally
        (.delete in)
        (.delete out)))))

(deftest update-command-writes-updated-pptx
  (let [deck {:slides/title "Update editor"}
        base (java.io.File/createTempFile "slides-cli-base" ".pptx")
        out (java.io.File/createTempFile "slides-cli-updated" ".pptx")
        base-path (.getAbsolutePath base)
        edn-path (.getAbsolutePath (java.io.File/createTempFile "slides-cli-edn" ".edn"))
        out-path (.getAbsolutePath out)
        expected {:slides/path out-path
                  :slides/bytes 64
                  :slides/slides 0}
        wrote (atom nil)]
    (try
      (let [read-edn-var (ns-resolve 'slides.cli 'read-deck-edn)
            update-pptx-var (ns-resolve 'slides.pptx 'update-pptx!)
            printed (with-out-str
                      (with-redefs-fn {read-edn-var (fn [path]
                                                      (is (= edn-path path))
                                                      deck)
                                        update-pptx-var (fn [base path actual]
                                                          (reset! wrote [base path actual])
                                                          (is (= base-path base))
                                                          (is (= out-path path))
                                                          (is (= deck actual))
                                                          expected)}
                        #(cli/-main "update" base-path edn-path out-path)))]
        (is (re-find #":path" printed))
        (is (= [base-path out-path deck] @wrote))
        (is (= expected (edn/read-string printed))))
      (finally
        (.delete base)
        (.delete out)
        (.delete (java.io.File. edn-path))))))

(deftest render-pptx-command-writes-slide-pngs
  (let [pptx (java.io.File/createTempFile "slides-cli-render" ".pptx")
        out-dir (str (.getAbsolutePath (java.io.File/createTempFile "slides-cli-render-out" "")) "-dir")
        expected {:slides/renderer :libreoffice
                  :slides/pptx (.getAbsolutePath pptx)
                  :slides/pngs [(str out-dir "/slide-1.png")]
                  :slides/slides 1}
        rendered (atom nil)]
    (try
      (.delete (java.io.File. out-dir))
      (let [render-var (ns-resolve 'slides.visual 'render-pptx-pngs!)
            printed (with-out-str
                      (with-redefs-fn {render-var (fn [path dir opts]
                                                    (is (= (.getAbsolutePath pptx) path))
                                                    (is (= out-dir dir))
                                                    (is (= {} opts))
                                                    (reset! rendered expected)
                                                    expected)}
                        #(cli/-main "render-pptx" (.getAbsolutePath pptx) out-dir)))]
        (is (= expected @rendered))
        (is (= expected (edn/read-string printed))))
      (finally
        (.delete pptx)))))

(deftest visual-diff-command-compares-rendered-pptx
  (let [before (java.io.File/createTempFile "slides-cli-before" ".pptx")
        after (java.io.File/createTempFile "slides-cli-after" ".pptx")
        out-dir (str (.getAbsolutePath (java.io.File/createTempFile "slides-cli-diff-out" "")) "-dir")
        expected {:slides/comparison {:slides/metric "RMSE"}}
        compared (atom nil)]
    (try
      (.delete (java.io.File. out-dir))
      (let [compare-var (ns-resolve 'slides.visual 'compare-pptx!)
            printed (with-out-str
                      (with-redefs-fn {compare-var (fn [before-path after-path dir opts]
                                                     (is (= (.getAbsolutePath before) before-path))
                                                     (is (= (.getAbsolutePath after) after-path))
                                                     (is (= out-dir dir))
                                                     (is (= {} opts))
                                                     (reset! compared expected)
                                                     expected)}
                        #(cli/-main "visual-diff"
                                    (.getAbsolutePath before)
                                    (.getAbsolutePath after)
                                    out-dir)))]
        (is (= expected @compared))
        (is (= expected (edn/read-string printed))))
      (finally
        (.delete before)
        (.delete after)))))

(deftest render-pptx-command-accepts-timeout
  (let [pptx (java.io.File/createTempFile "slides-cli-render-timeout" ".pptx")
        out-dir (str (.getAbsolutePath (java.io.File/createTempFile "slides-cli-render-timeout-out" "")) "-dir")
        opts-seen (atom nil)]
    (try
      (let [render-var (ns-resolve 'slides.visual 'render-pptx-pngs!)]
        (with-out-str
          (with-redefs-fn {render-var (fn [_ _ opts]
                                        (reset! opts-seen opts)
                                        {:slides/slides 0})}
            #(cli/-main "render-pptx" (.getAbsolutePath pptx) out-dir "20" "72"))))
      (is (= {:timeout-seconds 20 :dpi 72} @opts-seen))
      (finally
        (.delete pptx)))))

(deftest pptx-causal-command-writes-causal-pptx
  (let [deck {:slides/title "Causal editor"}
        in (java.io.File/createTempFile "slides-cli-causal-edn" ".edn")
        out (java.io.File/createTempFile "slides-cli-causal" ".pptx")
        in-path (.getAbsolutePath in)
        out-path (.getAbsolutePath out)
        expected {:slides/path out-path
                  :slides/bytes 96
                  :slides/slides 1
                  :slides/causal true}
        wrote (atom nil)]
    (try
      (let [read-edn-var (ns-resolve 'slides.cli 'read-deck-edn)
            write-causal-var (ns-resolve 'slides.causal 'write-pptx!)
            printed (with-out-str
                      (with-redefs-fn {read-edn-var (fn [path]
                                                      (is (= in-path path))
                                                      deck)
                                        write-causal-var (fn [path actual]
                                                           (is (= out-path path))
                                                           (is (= deck actual))
                                                           (reset! wrote expected)
                                                           expected)}
                        #(cli/-main "pptx-causal" in-path out-path)))]
        (is (= expected @wrote))
        (is (= expected (edn/read-string printed))))
      (finally
        (.delete in)
        (.delete out)))))

(deftest causal-deck-command-writes-embedded-deck-edn
  (let [deck {:slides/title "Embedded" :slides/slides [{:slides/id "s1"}]}
        input (java.io.File/createTempFile "slides-cli-causal-deck" ".pptx")
        output (java.io.File/createTempFile "slides-cli-causal-deck-out" ".edn")
        in-path (.getAbsolutePath input)
        out-path (.getAbsolutePath output)]
    (try
      (with-redefs [causal/read-deck-bytes (fn [_] deck)]
        (cli/-main "causal-deck" in-path out-path)
        (is (= deck (edn/read-string (slurp out-path)))))
      (finally
        (.delete input)
        (.delete output)))))

(deftest svgraph-command-writes-projection-edn
  (let [deck {:slides/title "Graph"
              :slides/slides [{:slides/id "s1" :slides/shapes []}]}
        projection {:svgraph/version "svgraph-presentation/1"
                    :svgraph/slides [{:svgraph/id "s1"}]}
        in (java.io.File/createTempFile "slides-cli-svgraph-edn" ".edn")
        out (java.io.File/createTempFile "slides-cli-svgraph" ".edn")
        in-path (.getAbsolutePath in)
        out-path (.getAbsolutePath out)]
    (try
      (let [read-edn-var (ns-resolve 'slides.cli 'read-deck-edn)
            projection-var (ns-resolve 'slides.svgraph 'presentation)]
        (with-redefs-fn {read-edn-var (fn [path]
                                        (is (= in-path path))
                                        deck)
                          projection-var (fn [actual]
                                           (is (= deck actual))
                                           projection)}
          #(cli/-main "svgraph" in-path out-path))
        (is (= projection (edn/read-string (slurp out-path)))))
      (finally
        (.delete in)
        (.delete out)))))
