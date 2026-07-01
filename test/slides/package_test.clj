(ns slides.package-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def manifest-required
  [:kotoba.package/name
   :kotoba.package/version
   :kotoba.package/repo-rid
   :kotoba.package/source
   :kotoba.package/capabilities
   :kotoba.package/signatures])

(def lock-required
  [:dep/name
   :dep/version
   :dep/repo-rid
   :dep/commit
   :dep/tree-cid
   :dep/manifest-cid
   :dep/signers
   :dep/capabilities])

(def allowed-kinds
  #{:library :adapter :schema-contract :tool :component})

(defn read-edn [path]
  (edn/read-string (slurp path)))

(defn cid? [x]
  (and (string? x) (str/starts-with? x "bafy")))

(defn contract? [x]
  (and (keyword? x)
       (str/starts-with? (subs (str x) 1) "app.kotoba.")))

(defn valid-signature? [sig]
  (and (string? (:did sig))
       (str/starts-with? (:did sig) "did:key:")
       (= :ed25519 (:alg sig))
       (string? (:sig sig))
       (not (str/blank? (:sig sig)))))

(defn valid-manifest? [m]
  (and (every? #(contains? m %) manifest-required)
       (contains? allowed-kinds (:kotoba.package/kind m))
       (cid? (:kotoba.package/repo-rid m))
       (cid? (get-in m [:kotoba.package/source :tree-cid]))
       (cid? (get-in m [:kotoba.package/source :manifest-cid]))
       (vector? (:kotoba.package/capabilities m))
       (seq (:kotoba.package/signatures m))
       (every? valid-signature? (:kotoba.package/signatures m))
       (every? contract? (:kotoba.package/provides m []))
       (every? contract? (:kotoba.package/consumes m []))))

(deftest package-manifest-declares-slides-library-boundary
  (let [m (read-edn "package-manifest.edn")]
    (is (valid-manifest? m))
    (is (= "kotoba-lang/slides" (:kotoba.package/name m)))
    (is (= :draft-unpublished (:kotoba.package/status m)))
    (is (= :library (:kotoba.package/kind m)))
    (is (empty? (:kotoba.package/capabilities m)))
    (is (= #{:app.kotoba.slides.deck
             :app.kotoba.slides.workspace
             :app.kotoba.slides.pptx
             :app.kotoba.slides.causalPayload
             :app.kotoba.svgraph.presentation}
           (set (:kotoba.package/provides m))))
    (is (empty? (:kotoba.package/consumes m)))))

(deftest office-adapter-manifest-is-explicit
  (let [m (read-edn "adapters/office/package-manifest.edn")]
    (is (valid-manifest? m))
    (is (= "kotoba-lang/slides-office" (:kotoba.package/name m)))
    (is (= :adapter (:kotoba.package/kind m)))
    (is (empty? (:kotoba.package/capabilities m)))
    (is (= #{:app.kotoba.slides.officeImport}
           (set (:kotoba.package/provides m))))
    (is (= #{:app.kotoba.slides.deck
             :app.kotoba.office.graph
             :app.kotoba.officeStyle.styleIr}
           (set (:kotoba.package/consumes m))))
    (is (= #{"kotoba-lang/slides"
             "kotoba-lang/office"
             "kotoba-lang/office-style"}
           (set (map :dep/name (:kotoba.package/dependencies m)))))))

(deftest lockfile-pins-workspace-surface-dependencies
  (let [lock (read-edn "kotoba.lock.edn")
        deps (:deps lock)
        by-name (into {} (map (juxt :dep/name identity) deps))]
    (is (= 1 (:kotoba.lock/version lock)))
    (is (= :draft-unpublished (:kotoba.lock/status lock)))
    (is (= #{"kotoba-lang/slides"
             "kotoba-lang/office"
             "kotoba-lang/office-style"
             "kotoba-lang/docs"
             "kotoba-lang/sheets"
             "kotoba-lang/drive"
             "kotoba-lang/forms"}
           (set (keys by-name))))
    (doseq [[name dep] by-name]
      (testing name
        (is (every? #(contains? dep %) lock-required))
        (is (contains? allowed-kinds (:dep/kind dep)))
        (is (cid? (:dep/repo-rid dep)))
        (is (cid? (:dep/tree-cid dep)))
        (is (cid? (:dep/manifest-cid dep)))
        (is (vector? (:dep/capabilities dep)))
        (is (empty? (:dep/capabilities dep)))
        (is (seq (:dep/signers dep)))
        (is (every? contract? (:dep/provides dep [])))
        (is (every? contract? (:dep/consumes dep [])))))
    (is (contains? (set (:dep/consumes (by-name "kotoba-lang/docs")))
                   :app.kotoba.slides.deck))
    (is (contains? (set (:dep/provides (by-name "kotoba-lang/drive")))
                   :app.kotoba.drive.objectRef))))
