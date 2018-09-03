(ns hyperfiddle.ui.popover
  (:require
    [contrib.css :refer [css]]
    [contrib.data :refer [abs-normalized]]
    [contrib.eval :as eval]
    [contrib.keypress :refer [with-keychord]]
    [contrib.reactive :as r]
    [contrib.pprint :refer [pprint-str]]
    [contrib.try$ :refer [try-promise]]
    [contrib.ui.tooltip :refer [tooltip tooltip-props]]
    [cuerdas.core :as string]
    [hypercrud.browser.context :as context]
    [hypercrud.util.branch :as branch]
    [hyperfiddle.actions :as actions]
    [hyperfiddle.runtime :as runtime]
    [hyperfiddle.tempid :as tempid]
    [promesa.core :as p]
    [re-com.core :as re-com]
    [taoensso.timbre :as timbre]))


(let [safe-eval-string #(try-promise (eval/eval-string! %))
      memoized-eval-string (memoize safe-eval-string)]
  (defn stage! [link-ref route popover-id child-branch ctx]
    (let [{:keys [:link/tx-fn] :as link} @link-ref]
      (-> (if (and (string? tx-fn) (not (string/blank? tx-fn)))
            (memoized-eval-string tx-fn)
            (p/resolved (constantly nil)))
          (p/then
            (fn [user-txfn]
              (p/promise
                (fn [resolve! reject!]
                  (let [swap-fn (fn [multi-color-tx]
                                  (let [result (let [result (user-txfn ctx multi-color-tx route)]
                                                 ; txfn may be sync or async
                                                 (if-not (p/promise? result) (p/resolved result) result))]
                                    ; let the caller of this :stage fn know the result
                                    ; This is super funky, a swap-fn should not be effecting, but seems like it would work.
                                    (p/branch result
                                              (fn [v] (resolve! nil))
                                              (fn [e]
                                                (reject! e)
                                                (timbre/warn e)))

                                    ; return the result to the action, it could be a promise
                                    result))]
                    (runtime/dispatch! (:peer ctx)
                                       (actions/stage-popover (:peer ctx) (:hypercrud.browser/invert-route ctx) child-branch
                                                              link swap-fn ; the swap-fn could be determined via the link rel
                                                              (actions/close-popover (:branch ctx) popover-id))))))))
          ; todo something better with these exceptions (could be user error)
          (p/catch (fn [err] (js/alert (pprint-str err))))))))

(defn close! [popover-id ctx]
  (runtime/dispatch! (:peer ctx) (actions/close-popover (:branch ctx) popover-id)))

(defn cancel! [popover-id child-branch ctx]
  (runtime/dispatch! (:peer ctx) (actions/batch
                                   (actions/close-popover (:branch ctx) popover-id)
                                   (actions/discard-partition child-branch))))

(defn managed-popover-body [route popover-id child-branch link-ref ctx]
  [:div.hyperfiddle-popover-body                            ; wrpaper helps with popover max-width, hard to layout without this
   ; NOTE: this ctx logic and structure is the same as the popover branch of browser-request/recurse-request
   (let [ctx (cond-> (context/clean ctx)                    ; hack clean for block level errors
               child-branch (assoc :branch child-branch))]
     [hyperfiddle.ui/iframe ctx {:route route}])            ; cycle
   (when child-branch
     [:button {:on-click (r/partial stage! link-ref route popover-id child-branch ctx)} "stage"])
   (if child-branch
     [:button {:on-click #(cancel! popover-id child-branch ctx)} "cancel"]
     [:button {:on-click #(close! popover-id ctx)} "close"])])

(defn open! [route popover-id child-branch ctx]
  (runtime/dispatch! (:peer ctx)
                     (if child-branch
                       (actions/add-partition (:peer ctx) route child-branch (::runtime/branch-aux ctx)
                                              (actions/open-popover (:branch ctx) popover-id))
                       (actions/open-popover (:branch ctx) popover-id))))

(defn- show-popover? [popover-id ctx]
  (runtime/state (:peer ctx) [::runtime/partitions (:branch ctx) :popovers popover-id]))

(defn- wrap-with-tooltip [popover-id ctx props child]
  ; omit the formula tooltip when popover is open
  (if @(show-popover? popover-id ctx)
    child
    [tooltip (tooltip-props props) child]))

; props = {
;   :route          [fiddle args]
;   :tooltip        String | [Keyword Hiccup]
;   :dont-branch?   Boolean
;
;   ; any other standard HTML button props, e.g.
;   :class    String
;   :style    Map[CSS-Key, CSS-Value]
;   :on-click (e) => ()
; }
(defn popover-cmp [link-ref ctx visual-ctx props label]
  ; try to auto-generate branch/popover-id from the product of:
  ; - link's :db/id
  ; - route
  ; - visual-ctx's data & path (where this popover is being drawn NOT its dependencies)
  (let [child-branch (let [child-id-str (-> [(tempid/tempid-from-ctx visual-ctx)
                                             @(r/fmap :db/id link-ref)
                                             (:route props)]
                                            hash abs-normalized - str)]
                       (branch/encode-branch-child (:branch ctx) child-id-str))
        popover-id child-branch                             ; just use child-branch as popover-id
        child-branch (when-not (:dont-branch? props) child-branch)
        button-effect! (if (:route props) open! (r/partial stage! link-ref))
        btn-props (-> props
                      (dissoc :route :tooltip :dont-branch?)
                      (assoc :on-click (r/partial button-effect! (:route props) popover-id child-branch ctx))
                      ; use twbs btn coloring but not "btn" itself
                      (update :class #(css % (if (:route props) "btn-default" "btn-warning"))))]
    (if-not (:route props)
      [:button btn-props [:span (str label "!")]]
      [wrap-with-tooltip popover-id ctx props
       [with-keychord
        "esc" #(do (js/console.warn "esc") (if child-branch
                                             (cancel! popover-id child-branch ctx)
                                             (close! popover-id ctx)))
        [re-com/popover-anchor-wrapper
         :showing? (show-popover? popover-id ctx)
         :position :below-center
         :anchor [:button btn-props [:span (str label "▾")]]
         :popover [re-com/popover-content-wrapper
                   :no-clip? true
                   :body [managed-popover-body (:route props) popover-id child-branch link-ref ctx]]]]])))
