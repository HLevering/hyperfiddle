(ns hyperfiddle.blocks.view-mode-selector
  (:require [contrib.reactive :as r]
            [hyperfiddle.ui.checkbox :refer [RadioGroup]]
            [hyperfiddle.view.controller :as view]))

(defn set-view-mode! [mode _]
  (view/set-mode! mode))

(defn ViewModeSelector [{:keys [mode]}]
  [RadioGroup {:name      "view"
               :options   [{:key :hypercrud.browser.browser-ui/api, :text "edn"}
                           {:key :hypercrud.browser.browser-ui/xray, :text "data"}
                           {:key :hypercrud.browser.browser-ui/user, :text "view"}]
               :value     mode
               :on-change (r/partial set-view-mode!)
               :props     {:style {:display        :inline-grid
                                   :grid-auto-flow :column
                                   :grid-gap       "0.75rem"}}}])