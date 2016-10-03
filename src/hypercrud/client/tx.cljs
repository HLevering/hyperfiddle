(ns hypercrud.client.tx)


(defn tempid? [eid] (< eid 0))


(defn edit-entity [id a rets adds]
  (vec (concat (map (fn [val] [:db/retract id a val]) rets)
               (map (fn [val] [:db/add id a val]) adds))))


(defn update-entity-attr [{:keys [:db/id] :as entity} a new-val]
  (edit-entity id a [(get entity a)] [new-val]))


(defn simplify [simplified-tx next-stmt]
  (let [[op e a v] next-stmt
        g (group-by (fn [[op' e' a' v']] (and (= e' e) (= a' a) (= v' v)))
                    simplified-tx)
        [op' e' a' v'] (first (get g true))                 ;if this count > 1, we have duplicate stmts, they are harmless and discard dups here.
        non-related (get g false)]
    (cond
      (= op :db/add) (if (= op' :db/retract)
                       non-related                          ;we have a related previous stmt that cancels us and it out
                       (conj non-related next-stmt))
      (= op :db/retract) (if (= op' :db/add)
                           non-related                      ;we have a related previous stmt that cancels us and it out
                           (conj non-related next-stmt))
      :else (throw "match error"))))


(defn into-tx [tx more-statements]
  "We don't care about the cardinality (schema) because the UI code is always
  retracting values before adding new value, even in cardinality one case. This is a very
  convenient feature and makes the local datoms cancel out properly always to not cause
  us to re-assert datoms needlessly in datomic"
  (reduce simplify tx more-statements))


(defn apply-stmt-to-entity [schema entity [op _ a v]]
  (let [cardinality (get-in schema [a :db/cardinality])
        _ (assert cardinality (str "schema attribute not found: " (pr-str a)))]
    (cond
      (and (= op :db/add) (= cardinality :db.cardinality/one)) (assoc entity a v)
      (and (= op :db/retract) (= cardinality :db.cardinality/one)) (dissoc entity a)
      (and (= op :db/add) (= cardinality :db.cardinality/many)) (update-in entity [a] (fnil #(conj % v) #{}))
      (and (= op :db/retract) (= cardinality :db.cardinality/many)) (update-in entity [a] (fnil #(disj % v) #{}))
      :else (throw "match error"))))


(defn build-entity-lookup
  ([schema statements] (build-entity-lookup schema statements {}))
  ([schema statements lookup]
   (reduce (fn [lookup [op e a v]]
             (update lookup e (fn [entity]
                                (let [entity (or entity {:db/id e})]
                                  (apply-stmt-to-entity schema entity [op e a v])))))
           lookup
           statements)))


(defn ref->v [v]
  (if (map? v) (:db/id v) v))


(defn entity->statements [schema {eid :db/id :as entity}]   ; entity always has :db/id
  (->> (dissoc entity :db/id)
       (mapcat (fn [[attr val]]
                 (let [cardinality (get-in schema [attr :db/cardinality])
                       valueType (get-in schema [attr :db/valueType])
                       _ (assert cardinality (str "schema attribute not found: " (pr-str attr)))]
                   (if (= valueType :db.type/ref)
                     (cond
                       (= cardinality :db.cardinality/one) [[:db/add eid attr (ref->v val)]]
                       (= cardinality :db.cardinality/many) (mapv (fn [val] [:db/add eid attr (ref->v val)]) val))
                     (cond
                       (= cardinality :db.cardinality/one) [[:db/add eid attr val]]
                       (= cardinality :db.cardinality/many) (mapv (fn [val] [:db/add eid attr val]) val))))))))


(defn entity? [v]
  (map? v))

(defn entity-children [schema entity]
  (mapcat (fn [[attr val]]
            (let [cardinality (get-in schema [attr :db/cardinality])
                  valueType (get-in schema [attr :db/valueType])]
              (cond
                (and (= valueType :db.type/ref) (= cardinality :db.cardinality/one)) [val]
                (and (= valueType :db.type/ref) (= cardinality :db.cardinality/many)) (vec val)
                :else [])))
          entity))


(defn pulled-entity->entity [schema {eid :db/id :as entity}]
  (->> (dissoc entity :db/id)                               ; if we add :db/id to the schema this step should not be necessary
       (map (fn [[attr val]]
              [attr (let [{:keys [:db/cardinality :db/valueType]} (get schema attr)
                          _ (assert cardinality (str "schema attribute not found: " (pr-str attr)))]
                      (if (= valueType :db.type/ref)
                        (cond
                          (= cardinality :db.cardinality/one) (ref->v val)
                          (= cardinality :db.cardinality/many) (set (mapv ref->v val)))
                        val))]))
       (into {:db/id eid})))


(defn pulled-tree-to-entities [schema pulled-tree]
  (->> (tree-seq (fn [v] (entity? v))
                 #(entity-children schema %)
                 pulled-tree)
       (map (juxt :db/id #(pulled-entity->entity schema %)))
       (into {})))


(defn pulled-tree-to-statements [schema pulled-tree]
  ;; branch nodes are type entity. which right now is hashmap.
  (let [traversal (tree-seq (fn [v] (entity? v))
                            #(entity-children schema %)
                            pulled-tree)]
    (mapcat #(entity->statements schema %) traversal)))
