(ns slides.model
  "Pure EDN model for the GFTD slides/docs/drive/sheets workspace.")

(def item-kinds
  #{:slides/deck
    :slides/doc
    :slides/file
    :slides/folder
    :slides/sheet})

(def link-kinds
  #{:contains
    :uses
    :embeds
    :derived-from
    :publishes
    :mentions})

(defn now-placeholder []
  "host-time")

(defn workspace
  ([id] (workspace id {}))
  ([id attrs]
   (merge {:slides/id id
           :slides/type :workspace
           :slides/items {}
           :slides/links []}
          attrs)))

(defn item
  ([kind id] (item kind id {}))
  ([kind id attrs]
   (merge {:slides/id id
           :slides/kind kind
           :slides/title id}
          attrs)))

(defn deck [id attrs]
  (item :slides/deck id (merge {:slides/slides [] :slides/theme :gftd} attrs)))

(defn slide
  ([id] (slide id {}))
  ([id attrs]
   (merge {:slides/id id
           :slides/title id
           :slides/shapes []}
          attrs)))

(defn text-box
  ([id text] (text-box id text {}))
  ([id text attrs]
   (merge {:slides/id id
           :slides/shape :text
           :slides/text text
           :slides/x 0.8
           :slides/y 0.8
           :slides/w 8.4
           :slides/h 1.0
           :slides/font-size 28}
          attrs)))

(defn rect
  ([id] (rect id {}))
  ([id attrs]
   (merge {:slides/id id
           :slides/shape :rect
           :slides/x 0.8
           :slides/y 2.1
           :slides/w 8.4
           :slides/h 2.0
           :slides/fill "EAF0F8"
           :slides/line "496B9A"}
          attrs)))

(defn add-slide [deck slide]
  (update deck :slides/slides conj slide))

(defn add-shape [slide shape]
  (update slide :slides/shapes conj shape))

(defn doc [id attrs]
  (item :slides/doc id (merge {:slides/blocks []} attrs)))

(defn file [id attrs]
  (item :slides/file id (merge {:slides/object-ref nil :slides/media-type "application/octet-stream"} attrs)))

(defn folder [id attrs]
  (item :slides/folder id (merge {:slides/children []} attrs)))

(defn sheet [id attrs]
  (item :slides/sheet id (merge {:slides/tables []} attrs)))

(defn add-item [ws it]
  (assoc-in ws [:slides/items (:slides/id it)] it))

(defn remove-item [ws id]
  (-> ws
      (update :slides/items dissoc id)
      (update :slides/links
              (fn [links]
                (vec (remove #(or (= id (:slides/from %))
                                  (= id (:slides/to %)))
                             links))))))

(defn link
  ([ws from to kind] (link ws from to kind {}))
  ([ws from to kind attrs]
   (update ws :slides/links conj
           (merge {:slides/from from
                   :slides/to to
                   :slides/link-kind kind}
                  attrs))))

(defn items [ws]
  (vals (:slides/items ws)))

(defn item-by-id [ws id]
  (get-in ws [:slides/items id]))

(defn items-by-kind [ws kind]
  (->> (items ws)
       (filter #(= kind (:slides/kind %)))
       (sort-by :slides/id)
       vec))

(defn outgoing [ws id]
  (->> (:slides/links ws)
       (filter #(= id (:slides/from %)))
       vec))

(defn incoming [ws id]
  (->> (:slides/links ws)
       (filter #(= id (:slides/to %)))
       vec))

(defn seed-workspace []
  (-> (workspace "gftd" {:slides/title "GFTD Workspace"})
      (add-item
       (-> (deck "intro-deck" {:slides/title "GFTD intro deck"})
           (add-slide
            (-> (slide "slide-1" {:slides/title "GFTD intro"})
                (add-shape (text-box "title" "GFTD intro"))
                (add-shape (text-box "body" "EDN-native CLJC workspace graph"
                                      {:slides/y 2.0 :slides/font-size 20}))))))
      (add-item (doc "narrative-doc" {:slides/title "Narrative source"}))
      (add-item (folder "shared-drive" {:slides/title "Shared drive"}))
      (add-item (sheet "planning-sheet" {:slides/title "Planning sheet"}))
      (link "intro-deck" "narrative-doc" :uses)
      (link "intro-deck" "planning-sheet" :embeds)
      (link "shared-drive" "intro-deck" :contains)
      (link "shared-drive" "narrative-doc" :contains)
      (link "shared-drive" "planning-sheet" :contains)))
