(ns slides.hiccup-test
  (:require [clojure.test :refer [deftest is]]
            [slides.hiccup :as h]))

(deftest renders-tags-attributes-and-escaped-text
  (is (= "<div class=\"panel active\" id=\"main\">Tom &amp; &lt;Q&gt;</div>"
         (h/->html [:div#main.panel {:class "active"} "Tom & <Q>"]))))

(deftest renders-void-tags-without-closing-tags
  (is (= "<input type=\"file\" accept=\".pptx\">"
         (h/->html [:input {:type "file" :accept ".pptx"}]))))

(deftest flattens-sequences-and-allows-trusted-raw-nodes
  (is (= "<ul><li>1</li><li>2</li><span>raw</span></ul>"
         (h/->html [:ul
                    (for [n [1 2]]
                      [:li n])
                    [:hiccup/raw "<span>raw</span>"]]))))
