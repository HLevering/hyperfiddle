(ns hyperfiddle.fiddle-test
  (:require
    [clojure.test :refer [deftest is]]
    [contrib.datomic :refer [smart-lookup-ref-no-tempids]]
    [contrib.validation :refer [validate]]
    [hyperfiddle.fiddle :refer [apply-defaults]]
    [hyperfiddle.ui]))


(deftest test-data-defaults []
  (is (not (nil? (:fiddle/query (apply-defaults {:fiddle/type :query})))))
  (is (not (nil? (:fiddle/pull (apply-defaults {:fiddle/type :entity}))))))

#?(:cljs
   (deftest test-ui-defaults []
     (let [fiddle-val (apply-defaults {})]
       (is (not (nil? (:fiddle/markdown fiddle-val))))
       (is (not (nil? (:fiddle/renderer fiddle-val)))))))
