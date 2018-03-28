(ns hypercrud.browser.system-fiddle
  (:require [clojure.string :as str]
            [hypercrud.compile.macros :refer [str-and-code]]
            [hypercrud.util.non-fatal :refer [try-either]]
            [hyperfiddle.ide.fiddles.schema :as schema]))


(defn system-fiddle? [fiddle-id]
  (and (keyword? fiddle-id)                                 ; why long here wut?
       (namespace fiddle-id)
       ; hyperfiddle.ide is real
       (or (-> (namespace fiddle-id) (str/starts-with? "hyperfiddle.system"))
           (-> (namespace fiddle-id) (str/starts-with? "hyperfiddle.schema")))))

; these need to be thick/hydrated params bc we are manufacturing a pulled tree here.

(def fiddle-system-edit
  {:fiddle/ident :hyperfiddle.system/edit
   :fiddle/type :entity})

(defn fiddle-blank-system-remove []
  {:fiddle/ident :hyperfiddle.system/remove
   :fiddle/type :blank
   :fiddle/renderer (str-and-code
                      (fn [result fes anchors ctx]
                        [:p "Retract entity?"]))})


(defn hydrate-system-fiddle [fiddle-id]
  (try-either                                               ; catch all the pre assertions
    (cond
      (= fiddle-id :hyperfiddle.system/edit) fiddle-system-edit
      (= fiddle-id :hyperfiddle.system/remove) fiddle-blank-system-remove
      :else (let [$db (name fiddle-id)]
              (condp = (namespace fiddle-id)
                "hyperfiddle.schema" (schema/schema $db)
                "hyperfiddle.schema.db-cardinality-options" (schema/db-cardinality-options $db)
                "hyperfiddle.schema.db-unique-options" (schema/db-unique-options $db)
                "hyperfiddle.schema.db-valueType-options" (schema/db-valueType-options $db)
                "hyperfiddle.schema.db-attribute-edit" (schema/db-attribute-edit $db))))))
