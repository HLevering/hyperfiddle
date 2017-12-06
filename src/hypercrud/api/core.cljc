(ns hypercrud.api.core
  (:refer-clojure :exclude [sync]))


(defprotocol HypercrudDataAPI
  (global-basis [rt])
  (local-basis [rt])
  (hydrate-route [rt])
  (hydrate-requests [rt local-basis stage requests])
  (sync [rt dbs])
  (transact! [rt tx-groups]))
