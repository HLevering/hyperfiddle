(ns hypercrud.browser.auto-anchor-formula
  (:require [hypercrud.types.DbId :refer [->DbId]]))


(defn deterministic-ident [fe e a v]
  ; Need comment explaining why.
  ; [fe e a v] quad is sufficient to answer "where are we".
  ; Why Db is omitted?
  ; Why value is only inspected in :many for unique hashing?
  (-> (str (-> fe :find-element/name) "."
           (-> e :db/id :id) "."
           (-> a :attribute/ident) "."
           (case (get-in a [:attribute/cardinality :db/ident])
             :db.cardinality/one nil
             :db.cardinality/many (hash (into #{} (mapv :db/id v))) ; todo scalar
             nil nil #_":db/id has a faked attribute with no cardinality, need more thought to make elegant"))
      hash js/Math.abs - str
      ))

(defn auto-entity-dbid [ctx & [conn-id]]
  (->DbId (deterministic-ident
            (-> ctx :find-element)
            (-> ctx :entity)
            (-> ctx :attribute)
            (-> ctx :value))
          (or conn-id (-> ctx :entity :db/id :conn-id))))

(defn auto-formula [anchor]                                 ; what about long-coersion?
  ; Future improvement:
  ; we already have this info in the runtime param-ctx, so we could delay until formula runtime
  ; and not look at the anchor at all and bypass the formula read-eval.

  ; this is a 3x3 matrix - repeating, entity, attribute. Find element is not part of the matrix.
  ; link-query's always have a find-element, if they have an attribute
  ; link-entity's never do, despite having an attribute.

  ; Fn syntax can go here if we have proper cljs dynamic vars.
  (let [{dependent? :anchor/repeating?
         fe :anchor/find-element
         a :anchor/attribute
         create? :anchor/create?} anchor]
    (cond

      (and fe a (not create?))
      (pr-str `(fn [ctx#]
                 (let [attr# (-> ctx# :attribute)]
                   (case (-> attr# :attribute/cardinality :db/ident)
                     :db.cardinality/one {:entity (get-in ctx# [:value :db/id])}
                     :db.cardinality/many {:entity (get-in ctx# [:entity :db/id])
                                           :a (:attribute/ident attr#)}))))

      (and fe a create?)
      ; inherit parent since the fe is never explicitly set by user
      ; it would be more correct to use the FE if we have it, but
      ; that information is guaranteed to be the same?
      (pr-str `(fn [ctx#]
                 (assert (-> ctx# :db .-conn-id))
                 {:entity (auto-entity-dbid ctx# (-> ctx# :db .-conn-id))}))

      ; entity edit
      (and fe (not a) (not create?))
      (pr-str `(fn [ctx#]
                 {:entity (get-in ctx# [:entity :db/id])}))

      ; entity create
      ; is it managed or not? We need a connection. Either we got the find-element, or
      ; we are managed. If we're managed, we need an entity in scope, to conjure a connection.
      ; So despite not really needing the value in scope, we need the connection, so we need the value.
      ; This is counter intuitive. It only happens for sys links. Regular links set the linkentity/connection
      ; so don't have this problem.
      ; Mystery deepens: If ur a syslink u better have a conn-id here because autolink inspects the entity connid to manufacture
      ; the right entity connection. If you're an explicit link with a conn set, it doesn't matter what you put here since the server
      ; will ignore this and use the explicit conn. This is only needed to plumb a connection to the autolink logic so it can choose the right connection.
      (and #_(not dependent?) create? (not a))
      (pr-str `(fn [ctx#]
                 {:entity (auto-entity-dbid ctx# ~(-> fe :find-element/connection :db/id :id))}))

      ; index links can't be dependent on anything
      (and #_ (not dependent?) (not fe) (not a)) nil

      ; relation edit (this is not really a thing)
      ; If this is a thing, it probably is a query with named params and a custom formula.
      (and #_ dependent? (not fe) (not a)) nil

      ;:else (assert false (str "auto-formula matrix - missing pattern: " (util/pprint-str [r c e a])))
      )))
