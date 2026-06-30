(ns slides.web
  "Entry point for the slides web editor. The :cljs build compiles this
  namespace; it delegates to slides.web.app/init! which mounts the reagent
  root (pure-hiccup views + re-frame state via shitsuke)."
  (:require [slides.web.app :as app]))

(defn init! []
  (app/init!))

(set! (.-onload js/window) init!)
