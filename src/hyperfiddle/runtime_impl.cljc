(ns hyperfiddle.runtime-impl
  (:require
    [cats.labs.promise]
    [cats.monad.either :as either]
    [clojure.spec.alpha :as s]
    [contrib.reactive :as r]
    [hyperfiddle.route :as route]
    [hyperfiddle.runtime :as runtime]
    [hyperfiddle.state :as state]
    [promesa.core :as p]))


(defn hydrate-impl [rt pid request]
  (r/fmap-> (r/cursor (state/state rt) [:hyperfiddle.runtime/partitions pid :ptm])
            (get request (either/left {:message "Loading" :data {:request request}}))))

(defn set-route [rt pid route force-hydrate]
  {:pre [(s/valid? :hyperfiddle/route route)]}
  (let [current-route (get-in @(state/state rt) [:hyperfiddle.runtime/partitions pid :route])]
    (state/dispatch! rt [:partition-route pid route])
    (if (and (not force-hydrate) (route/equal-without-frag? route current-route))
      ; just update state without re-hydrating
      (p/resolved nil)
      (runtime/bootstrap-data rt pid runtime/LEVEL-GLOBAL-BASIS))))
