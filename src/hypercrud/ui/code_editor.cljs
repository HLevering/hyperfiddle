(ns hypercrud.ui.code-editor
  (:require [reagent.core :as reagent]))


(defn did-mount! [value change!]
  (fn [this]
    (let [ref (.fromTextArea
                 js/CodeMirror
                 (reagent/dom-node this)
                 #js {:mode "clojure"
                      :lineNumbers true
                      :matchBrackets true
                      :autoCloseBrackets true
                      :viewportMargin js/Infinity})]
      (aset this "codeMirrorRef" ref)
      (.on ref "blur" (let [last-val (atom value)]
                         (fn [e]
                           (let [old @last-val
                                 new (.getValue e)]
                             (reset! last-val new)
                             (change! [:db/retract old]
                                      [:db/add new]))))))))


(defn code-editor* [value change!]
  (reagent/create-class
    {:render (fn []
               [:textarea {:default-value value :auto-complete "off" :class "text"}])
     :component-did-mount (did-mount! value change!)
     :component-will-unmount #(.toTextArea (aget % "codeMirrorRef"))}))
