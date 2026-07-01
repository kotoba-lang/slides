(ns slides.visual-test
  (:require [clojure.test :refer [deftest is]]
            [slides.visual :as visual]))

(deftest require-tools-reports-missing-renderers
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"PPTX visual rendering requires"
       (visual/require-tools! {:libreoffice nil
                               :pdftoppm nil}))))

(deftest render-pptx-pngs-uses-libreoffice-and-pdftoppm
  (let [pptx (java.io.File/createTempFile "slides-visual" ".pptx")
        out-dir (str (.getAbsolutePath (java.io.File/createTempFile "slides-visual-out" "")) "-dir")
        commands (atom [])]
    (try
      (.delete (java.io.File. out-dir))
      (with-redefs [visual/run-command! (fn [cmd & _]
                                          (swap! commands conj (mapv str cmd))
                                          (cond
                                            (some #{"--convert-to"} cmd)
                                            (spit (str out-dir "/" (.replaceFirst (.getName pptx) "\\.pptx$" ".pdf"))
                                                  "%PDF")

                                            (some #{"-png"} cmd)
                                            (spit (str out-dir "/slide-1.png") "PNG"))
                                          {:exit 0 :stdout "" :stderr ""})]
        (let [result (visual/render-pptx-pngs!
                      (.getAbsolutePath pptx)
                      out-dir
                      {:tools {:libreoffice "/usr/bin/soffice"
                               :pdftoppm "/usr/bin/pdftoppm"}})]
          (is (= :libreoffice (:slides/renderer result)))
          (is (= 1 (:slides/slides result)))
          (is (= [(str out-dir "/slide-1.png")] (:slides/pngs result)))
          (is (= "/usr/bin/soffice" (ffirst @commands)))
          (is (= "/usr/bin/pdftoppm" (first (second @commands))))))
      (finally
        (.delete pptx)))))

(deftest compare-pngs-accepts-imagemagick-difference-exit
  (let [expected (java.io.File/createTempFile "slides-expected" ".png")
        actual (java.io.File/createTempFile "slides-actual" ".png")
        out-dir (str (.getAbsolutePath (java.io.File/createTempFile "slides-diff" "")) "-dir")]
    (try
      (.delete (java.io.File. out-dir))
      (spit expected "A")
      (spit actual "B")
      (with-redefs [visual/run-command! (fn [_ _]
                                          {:exit 1
                                           :stdout ""
                                           :stderr "123.0 (0.42)"})]
        (let [result (visual/compare-pngs!
                      [(.getAbsolutePath expected)]
                      [(.getAbsolutePath actual)]
                      out-dir
                      {:tools {:compare "/usr/bin/compare"}
                       :metric "RMSE"})]
          (is (= "RMSE" (:slides/metric result)))
          (is (= "123.0 (0.42)"
                 (get-in result [:slides/comparisons 0 :slides/value])))))
      (finally
        (.delete expected)
        (.delete actual)))))

(deftest compare-pngs-requires-equal-lengths
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"PNG sequence lengths differ"
       (visual/compare-pngs! ["a.png"] [] "/tmp/slides-visual-diff"
                             {:tools {:compare "/usr/bin/compare"}}))))
