(ns hypercrud.form.field
  (:require [hypercrud.form.option :as option]))


(defrecord Field [prompt
                  ident valueType cardinality isComponent   ;attribute
                  ;valid? required? read-only? default-value ;these should be functions that consume a record
                  options])


(defn convert-valueType [datomic-valueType]
  (condp = datomic-valueType
    :db.type/keyword :keyword
    :db.type/string :string
    :db.type/boolean :boolean
    :db.type/long :long
    :db.type/bigint :bigint
    :db.type/float :float
    :db.type/double :double
    :db.type/bigdec :bigdec
    :db.type/ref :ref
    :db.type/instant :instant
    :db.type/uuid :uuid
    :db.type/uri :uri
    :db.type/bytes :bytes))


(defn field
  ([{:keys [:field/prompt :field/label-prop :field/form :field/query] :as db-field}
    {:keys [:attribute/ident :attribute/valueType :attribute/cardinality :attribute/isComponent] :as db-attribute}]
   (->Field prompt ident (convert-valueType valueType) cardinality isComponent (option/gimme-useful-options {:label-prop label-prop
                                                                                                             :form form
                                                                                                             :query (:query/value query)})))
  ([{:keys [prompt ident valueType cardinality isComponent options]}]
   (->Field prompt ident valueType cardinality (or isComponent false) (option/gimme-useful-options options))))


(defn hole->field
  ([{:keys [:hole/name
            :field/prompt :field/query :field/label-prop
            :attribute/valueType :attribute/cardinality]}]
   (->Field prompt name (convert-valueType valueType) cardinality false (option/gimme-useful-options {:label-prop label-prop
                                                                                                      :query (:query/value query)}))))
