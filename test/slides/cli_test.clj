(ns slides.cli-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [slides.office :as office]
            [slides.pptx :as pptx]
            [slides.cli :as cli]))

(deftest usage-description
  (let [usage-fn (ns-resolve 'slides.cli 'usage)]
    (is (some? usage-fn))
    (is (fn? @usage-fn))
    (let [usage (@usage-fn)]
      (is (string? usage))
      (is (re-find #"from-pptx" usage))
      (is (re-find #"pptx" usage))
      (is (re-find #"update" usage)))))

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
