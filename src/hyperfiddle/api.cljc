(ns hyperfiddle.api                                         ; cljs can always import this
  (:refer-clojure :exclude [memoize])
  (:require
    [cats.monad.either :refer [left right]]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as timbre]))


(defprotocol ConnectionFacade
  :extend-via-metadata true
  (basis [conn])                                            ; Warning: protocol #'hyperfiddle.api/Domain is overwriting method basis of protocol ConnectionFacade
  (db [conn])
  (transact [conn arg-map])                                 ; This is raw Datomic transact and does not perform hf/process-tx
  (with-db [conn]))

(defprotocol DbFacade
  :extend-via-metadata true
  (as-of [db time-point])
  (basis-t [db])
  (pull [db arg-map])
  (with [db arg-map])
  (history [db])                                            ; TODO
  )

; This protocol can be multimethods
(defprotocol Browser
  (a [ctx])
  (attr [ctx] [ctx a])
  (browse-element [ctx i])
  (data [ctx])
  (dbname [ctx])
  (eav [ctx])
  (e [ctx])
  (element [ctx])
  (element-type [ctx])
  (fiddle [ctx])
  (identity? [ctx])
  (link-tx [ctx])
  (qfind [ctx])
  (qfind-level? [ctx])
  (spread-attributes [ctx])
  (id [ctx pulltree])
  (row-key [ctx row])
  (tempid! [ctx] [ctx dbname])
  (v [ctx]))

(defmulti subject (fn [ctx] (type ctx)))
(defmulti db-record (fn [ctx] (type ctx)))

(defprotocol UI
  (display-mode [ctx])
  (display-mode? [ctx k]))

(defprotocol Domain
  (basis [domain])
  (type-name [domain])
  (fiddle-dbname [domain])
  (database [domain dbname])                                ; database-record
  (databases [domain])
  (environment [domain])
  (url-decode [domain s])
  (url-encode [domain route])
  (api-routes [domain])

  (system-fiddle? [domain fiddle-ident])
  (hydrate-system-fiddle [domain fiddle-ident])
  #?(:clj (connect [domain dbname] [domain dbname on-created!]))
  (memoize [domain f]))

(defprotocol HF-Runtime
  (domain [rt])
  (io [rt])
  (hydrate [rt pid request])
  (set-route [rt pid route] [rt pid route force-hydrate] "Set the route of the given branch. This may or may not trigger IO. Returns a promise"))

;(def def-validation-message hypercrud.browser.context/def-validation-message)
; Circular dependencies and :require order problems. This is a static operation, no ctx dependency.
; But hyperfiddle.api can only have protocols, no concrete impls for the require order to work.
; Protocols and methods need a dispatch parameter, and static fns don't have one.
(defmulti def-validation-message (fn [pred & [s]] :default)) ; describe-invalid-reason

(defmulti tx (fn [ctx eav props]
               (let [dispatch-v (link-tx ctx)]
                 ; UX - users actually want to see this in console
                 (timbre/info "hf/tx: " dispatch-v " eav: " (pr-str eav))
                 dispatch-v)))

#?(:clj
   (do
     (defmulti process-tx                                   ; todo tighten params
       (fn [$                                               ; security can query the database e.g. for attribute whitelist
            domain                                          ; spaghetti dependency, todo fix
            dbname
            #_hf-db                                         ; security can inspect domain/database configuration, e.g. for database-level user whitelist
            ; Removed to reduce parameter noise downstack - the one use case is able to reconstruct hf-db from [domain, dbname]
            subject                                         ; security can know the user submitting this tx
            tx]
         (get-in (databases domain) [dbname :database/write-security :db/ident] ::allow-anonymous-edits)))

     (defmethod process-tx ::allow-anonymous-edits [$ domain dbname subject tx] tx)

     (def ^:dynamic *$* nil)
     (def ^:dynamic *subject*)                              ; FK into $hyperfiddle-users, e.g. #uuid "b7a4780c-8106-4219-ac63-8f8df5ea11e3"
     (def ^:dynamic *route* nil)
     ))

; #?(:cljs)
(defn domain-security
  ([ctx] (get-in (db-record ctx) [:database/write-security :db/ident] ::allow-anonymous-edits))
  ([hf-db subject] (get-in hf-db [:database/write-security :db/ident] ::allow-anonymous-edits)))

(defmulti subject-may-transact+ "returns (left tooltip-msg) or (right)" (fn [hf-db subject] (domain-security hf-db subject))) ; todo pass ctx
(defmulti subject-may-create? domain-security)
(defmulti subject-may-edit-entity? "Attribute whitelist is not implemented here, this is about entity level writes" domain-security)
(defmulti subject-may-edit-attr? domain-security)

(defmethod subject-may-transact+ ::allow-anonymous-edits [hf-db subject] (right)) ; :hyperfiddle.security/allow-anonymous
(defmethod subject-may-create? ::allow-anonymous-edits [hf-db subject ctx] true)
(defmethod subject-may-edit-entity? ::allow-anonymous-edits [hf-db subject ctx] true)
(defmethod subject-may-edit-attr? ::allow-anonymous-edits [ctx] true) ; no tx constraints by default

(declare render-dispatch)

; Dispatch is a set
(defmulti render (fn [ctx props]
                   (render-dispatch ctx props)))

(defn extract-set [ctx & fs]
  (->> ctx ((apply juxt fs)) set))

(defn render-dispatch [ctx props]
  ; Is there a method which is a subset of what we've got?
  (or
    (if (hyperfiddle.api/display-mode? ctx :user)
      (or
        (let [d (extract-set ctx hyperfiddle.api/fiddle hyperfiddle.api/a)]
          (if (contains? (methods render) d)
            d))
        (let [d (extract-set ctx hyperfiddle.api/a)]
          (if (contains? (methods render) d)
            d))))
    ; Legacy compat - options by fiddle/renderer explicit props route to select via ref renderer
    (if (:options props)
      (extract-set (hyperfiddle.api/attr ctx) :db/valueType :db/cardinality))
    (if (hyperfiddle.api/identity? ctx) #{:db.unique/identity})
    (if-let [attr (hyperfiddle.api/attr ctx)]
      (extract-set attr :db/valueType :db/cardinality))
    (if (hyperfiddle.api/element ctx)
      (extract-set ctx hyperfiddle.api/element-type))       ; :hf/variable, :hf/aggregate, :hf/pull
    ;(contrib.datomic/parser-type (context/qfind ctx))       ; :hf/find-rel :hf/find-scalar
    ;:hf/blank
    ))

(defmethod tx :default [ctx eav props]
  nil)

(defmethod tx :default [ctx eav props]
  nil)

(defmethod tx :zero [ctx eav props]
  [])                                                       ; hack to draw as popover

(defmethod tx :db/add [ctx [e a v] props]
  {:pre [e a v]}
  [[:db/add e a v]])

(defmethod tx :db/retract [ctx [e a v] props]
  {:pre [e a v]}
  [[:db/retract e a v]])

(defmethod tx :db/retractEntity [ctx [e a v] props]
  {:pre [v]}
  [[:db/retractEntity v]])

; Compat

(defmulti txfn (fn [user-txfn e a v ctx] user-txfn))

(defmethod txfn :default [_ e a v ctx]
  nil)

(defmethod txfn :zero [_ e a v ctx]
  [])                                                       ; hack to draw as popover

(defmethod txfn :db/add [_ e a v ctx]
  {:pre [e a v]}
  [[:db/add e a v]])

(defmethod txfn :db/retract [_ e a v ctx]
  {:pre [e a v]}
  [[:db/retract e a v]])

(defmethod txfn :db/retractEntity [_ _ _ v ctx]
  {:pre [v]}
  [[:db/retractEntity v]])

; All hyperfiddle specs should be here in this namespace.
; Namespaces other than "hyperfiddle" are henceforth forbidden and all legacy namespaces should be migrated.
; If you feel the need to organize attributes that "traverse together" use a comment in this file
; or (future) use spec2 schema & select.

(s/def ::invalid-messages (s/coll-of string?))
(s/def ::is-invalid boolean?)
