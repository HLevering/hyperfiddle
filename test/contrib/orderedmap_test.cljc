(ns contrib.orderedmap-test
  (:require
    [contrib.orderedmap :refer [ordered-map]]
    [clojure.test :refer [deftest is testing]]))

(deftest orderedmap|construct
  (let [m (apply ordered-map (interleave (range 1 30) (range 1 30)))]
    (testing
      "Ordered Map Construct"
      (is (nil? (get m 30)))
      (is (= (get m 1) 1))
      (is (nil? (get m nil)))
      (is (= (keys m) (range 1 30)))
      (is (= (vals m) (range 1 30)))
      (is (= (map vector (range 1 30) (range 1 30)) (seq m))))))


(deftest orderedmap|assoc
  (let [m (apply ordered-map (interleave (range 1 30) (range 1 30)))]
    (testing
      "Ordered Map Assoc"
      (is (= (get (assoc m 30 30) 30) 30))
      (is (= (get (assoc m 1 :a) 1) :a))
      (is (= [1 1] (first m)))
      (is (= (get (assoc m nil 1) nil) 1))
      (is (= (keys (assoc m 30 30)) (range 1 31)))
      (is (= (vals (assoc m 30 30)) (range 1 31)))
      (is (= (map vector (range 1 31) (range 1 31)) (seq (assoc m 30 30)))))))

(deftest orderedmap|dissoc
  (let [m (apply ordered-map (interleave (range 1 30) (range 1 30)))]
   (testing
     "Ordered Map Dissoc"
     (is (nil? (get (dissoc m 29) 29)))
     (is (nil? (get (dissoc m 1) 1)))
     (is (nil? (get (dissoc (assoc m nil :asdf) nil) nil)))
     (is (= (keys (dissoc m 29)) (range 1 29)))
     (is (= (vals (dissoc m 29)) (range 1 29)))
     (is (= (map vector (range 1 29) (range 1 29)) (seq (dissoc m 29)))))))

