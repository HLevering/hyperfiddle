(ns hypercrud.server.api
  (:require [clojure.set :as set]
            [clojure.walk :as walk]
            [datomic.api :as d]
            [hypercrud.server.database :as database]
            [hypercrud.server.db-root :as db]
            [hypercrud.server.util.datomic-adapter :as datomic-adapter]
            [hypercrud.types.DbId :refer [->DbId]]
            [hypercrud.types.DbVal :refer [->DbVal]]
            [hypercrud.types.DbError :refer [->DbError]]
            [hypercrud.types.EntityRequest]
            [hypercrud.types.QueryRequest]
            [hypercrud.util.branch :as branch]
            [hypercrud.util.core :as util])
  (:import (hypercrud.types.DbId DbId)
           (hypercrud.types.DbVal DbVal)
           (hypercrud.types.EntityRequest EntityRequest)
           (hypercrud.types.QueryRequest QueryRequest)))


(defn get-secure-db [dbval root-db]
  (if (= db/root-id (.conn-id dbval))
    root-db
    (let [security-predicate (constantly true)              ;todo lookup
          ; database-id could be a lookup ref in hypercrud fixtures
          conn (database/get-db-conn! root-db (.conn-id dbval))
          db (d/db conn)
          t (or (.branch dbval) (d/basis-t db))]
      (d/filter (d/as-of db (d/t->tx t)) security-predicate))))

(defmulti parameter (fn [this & args] (class this)))

(defmethod parameter :default [this & args] this)

(defmethod parameter DbVal [dbval get-secure-db-with & args]
  (-> (get-secure-db-with (:conn-id dbval) (:branch dbval)) :db))

(defmethod parameter DbId [dbid get-secure-db-with & [dbval]]
  (let [conn-id (:conn-id (or dbval dbid))                  ; this will break when we remove connid from dbid
        db (get-secure-db-with conn-id (:branch dbval))
        hid (:id dbid)]                                     ; htempid (string) or did (num) or lookup ref
    (if (datomic-adapter/hc-tempid? hid)
      (get (:tempid->id db)
           hid                                              ; the with-db has seen this tempid in the staged-tx
           (Long/parseLong hid))                            ; dangling tempid not yet seen, it will pass through datomic. Due to datomic bug, it has to go through as a long
      hid)))                                                ; don't parse lookup refs or not-tempids

(defn recursively-replace-ids [pulled-tree conn-id id->tempid]
  (let [replace-tempid (fn [did]

                         ; datomic tempid? can't know from the pulled-tree.
                         ; if we see it in the id->tempid we can fix it.
                         ; But what about tempids that aren't part of the tx? They should pass through.
                         ; -1 is still -1 in datomic.

                         ; either we saw it in the tx, fix it
                         ; or we have a negative id that we didn't see, fix it
                         ; or its legit

                         (let [hid (or (some-> (get id->tempid did) str)
                                       (if (> 0 did) (str did))
                                       did #_"not a tempid")]
                           (->DbId hid conn-id)))]
    (walk/postwalk (fn [o]
                     (if (map? o)
                       (util/update-existing o :db/id replace-tempid)
                       o))
                   pulled-tree)))

(defmulti hydrate* (fn [this & args] (class this)))

(defmethod hydrate* EntityRequest [{:keys [e a dbval pull-exp]} get-secure-db-with]
  (try
    (let [{:keys [id->tempid] pull-db :db} (get-secure-db-with (:conn-id dbval) (:branch dbval))
          pull-exp (if a [{a pull-exp}] pull-exp)
          pulled-tree (d/pull pull-db pull-exp (parameter e get-secure-db-with dbval))
          pulled-tree (recursively-replace-ids pulled-tree (:conn-id dbval) id->tempid)
          pulled-tree (if a (get pulled-tree a []) pulled-tree)]
      pulled-tree)
    (catch Throwable e
      (.println *err* (pr-str e))
      (->DbError (str e)))))

(defmethod hydrate* QueryRequest [{:keys [query params pull-exps]} get-secure-db-with]
  (try
    (assert query "hydrate: missing query")
    (let [ordered-params (->> (util/parse-query-element query :in)
                              (mapv #(get params (str %)))
                              (mapv #(parameter % get-secure-db-with)))
          ordered-find-element-symbols (util/parse-query-element query :find)
          ordered-pull-exps (->> ordered-find-element-symbols
                                 (mapv (fn [find-element-symbol]
                                         ; correlate
                                         (let [pull-exp (get pull-exps (str find-element-symbol))]
                                           (assert (not= nil pull-exp) (str "hydrate: missing pull expression for " find-element-symbol))
                                           pull-exp))))]
      (->> (apply d/q query ordered-params)                 ;todo gaping security hole
           (util/transpose)
           (util/zip ordered-pull-exps)
           (mapv (fn [[[dbval pull-exp] values]]
                   (let [{:keys [id->tempid] pull-db :db} (get-secure-db-with (:conn-id dbval) (:branch dbval))]
                     ; traverse tree, turning pulled :db/id into idents where possible?
                     (->> (d/pull-many pull-db pull-exp values)
                          (mapv #(recursively-replace-ids % (:conn-id dbval) id->tempid))))))
           (util/transpose)
           (mapv #(zipmap (mapv str ordered-find-element-symbols) %))))

    (catch Throwable e
      (.println *err* (pr-str e))
      (->DbError (str e)))))

(defn build-get-secure-db-with [hctx-groups root-read-sec-predicate root-validate-tx]
  (let [db-with-lookup (atom {})]
    (fn get-secure-db-with [conn-id branch]
      ; todo huge issues with lookup refs for conn-ids, they will have misses in the lookup cache and hctx-groups
      (or (get-in @db-with-lookup [conn-id branch])
          (let [dtx (->> (get hctx-groups [conn-id branch])
                         (mapv datomic-adapter/stmt-dbid->id))
                db (let [conn (if (= db/root-id conn-id)
                                (database/get-root-conn)
                                (database/get-db-conn! (:db (get-secure-db-with db/root-id nil)) conn-id))]
                     (d/db conn))
                ; is it a history query? (let [db (if (:history? dbval) (d/history db) db)])
                _ (let [validate-tx (if (= db/root-id conn-id)
                                      root-validate-tx
                                      ; todo look up relevant project tx validator
                                      (constantly true))]
                    (assert (validate-tx db dtx) (str "staged tx for " conn-id " failed validation")))
                project-db-with (let [read-sec-predicate (if (= db/root-id conn-id)
                                                           root-read-sec-predicate
                                                           ;todo lookup project sec pred
                                                           (constantly true))
                                      ; todo d/with an unfiltered db
                                      {:keys [db-after tempids]} (d/with db dtx)
                                      id->tempid (database/build-id->tempid-lookup db-after tempids dtx)]
                                  {:db (d/filter db-after read-sec-predicate)
                                   :id->tempid id->tempid
                                   :tempid->id (set/map-invert id->tempid)})]
            (swap! db-with-lookup assoc-in [conn-id branch] project-db-with)
            project-db-with)))))

(defn hydrate [root-security-predicate root-validate-tx hctx-groups request root-t]
  (let [get-secure-db-with (build-get-secure-db-with hctx-groups root-security-predicate root-validate-tx)
        pulled-trees-map (->> request
                              (mapv (juxt identity #(hydrate* % get-secure-db-with)))
                              (into {}))]
    {:t (if root-t (-> (database/get-root-conn) d/db d/basis-t))
     :pulled-trees-map pulled-trees-map}))

(defn transact! [root-validate-tx htx]
  (let [root-conn (database/get-root-conn)
        root-db (d/db root-conn)                            ; tx validation needs schema, so gets unfiltered db
        dtx-groups (doall (->> htx
                               (util/map-keys (fn [conn-id]
                                                ;; todo the root transaction may contain the entity for conn-id
                                                ;; we need to first d/with the root transaction before we can can entity
                                                ; database-id could be a lookup ref in hypercrud fixtures
                                                (if-not (number? conn-id)
                                                  (:db/id (d/entity root-db conn-id))
                                                  conn-id)
                                                #_"resolve lookup refs in conn position"
                                                ))
                               (util/map-values #(mapv datomic-adapter/stmt-dbid->id %))))

        valid? (every? (fn [[conn-id tx]]
                         ;; conn-id allowed to be a tempid, resolve it (this requires already committing the root tx-group)
                         (let [maybe-db (if-not (datomic-adapter/hc-tempid? conn-id)
                                          (get-secure-db (->DbVal conn-id nil) root-db))]
                           ; todo look up relevant project tx validator, need to d/with the root transaction (validator may not be committed yet)
                           (root-validate-tx maybe-db tx)))
                       dtx-groups)]
    (if-not valid?
      (throw (RuntimeException. "user tx failed validation"))
      (let [build-hc-tempid-lookup (fn [conn-id id->tempid]
                                     (->> id->tempid
                                          (mapv (fn [[id tempid]]
                                                  [(->DbId (str tempid) conn-id) (->DbId id conn-id)]))
                                          (into {})))
            ;; first transact the root - there may be security changes or database/ident changes
            root-id->tempid (let [dtx (get dtx-groups db/root-id)
                                  {:keys [db-after tempids]} @(d/transact (database/get-root-conn) dtx)]
                              (database/build-id->tempid-lookup db-after tempids dtx))

            dtx-groups (->> dtx-groups
                            (util/map-keys (fn [conn-id]
                                             (if (and (not= db/root-id conn-id) (datomic-adapter/hc-tempid? conn-id))
                                               (get root-id->tempid conn-id)
                                               conn-id))))
            ;; project-txs might reference a root tempid in the connection position of a dbid e.g.
            ;;    [:db/add #DbId[-100 -1] :post/title "first post"]
            ;; happens when our samples need fixtures
            hc-tempids (->> (concat (->> (dissoc dtx-groups db/root-id)
                                         (mapv (fn [[conn-id dtx]]
                                                 ; todo this root-db is stale
                                                 (let [conn (database/get-db-conn! root-db conn-id)
                                                       {:keys [db-after tempids]} @(d/transact conn dtx)]
                                                   (->> (database/build-id->tempid-lookup db-after tempids dtx)
                                                        (build-hc-tempid-lookup conn-id)))))
                                         doall)
                                    [(build-hc-tempid-lookup db/root-id root-id->tempid)])
                            (apply merge))]
        {:tempids hc-tempids}))))

(defn latest [conn]
  (str (-> (d/db conn) d/basis-t)))

(defn root-latest []
  (latest (database/get-root-conn)))
