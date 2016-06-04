(ns com.hypirion.conduit-test
  (:require [clojure.test :refer :all]
            [com.hypirion.conduit :refer :all])
  (:refer-clojure :exclude [await]))

(defn- taking
  "Transducer version of take, implemented via conduit."
  [n]
  (conduit
   (dotimes [_ n]
     (yield (await)))))

(defn- dropping
  "Transducer version of drop, implemented via conduit."
  [n]
  (conduit
   (dotimes [_ n]
     (await))
   (while true
     (yield (await)))))

(def ^:private catting
  "cat implemented via conduit."
  (conduit
   (while true
     (doseq [val (await)]
       (yield val)))))

(defn- partitioning-by
  "Transducer version of partition-by, implemented via conduit."
  [f]
  (conduit
   (let [first-val (await)]
     (loop [vs [first-val]
            to-cmp (f first-val)]
       (if-let-await! v
         (let [new-to-cmp (f v)]
             (if (= to-cmp new-to-cmp)
               (recur (conj vs v) to-cmp)
               (do (yield vs)
                   (recur [v] new-to-cmp))))
         ;; no more values, so flush out vs and exit
         (yield vs))))))

(defn- partitioning
  "Transducer version of partition, implemented via conduit.

  Presumably not that efficient."
  ([n]
   (partitioning n n))
  ([n step]
   (conduit
    (loop [acc []]
      (let [chunk (loop [acc' acc]
                    (if (= (count acc') n)
                      acc'
                      (recur (conj acc' (await)))))]
        (yield chunk)
        (recur (vec (drop step chunk))))))))

;; This one is slightly scary: (take 10 (sequence natural-numbers [])) diverges,
;; which _makes sense_ but may be slightly surprising. (sequence (comp
;; natural-numbers (take 10)) []) works as intended though.
(def natural-numbers
  (conduit
   (loop [n 0]
     (yield n)
     (recur (inc' n)))))

(deftest conduit-test
  (testing "basic usage"
    (is (= (reduce + (take 10 (range)))
           (transduce (taking 10) + (range))))
    (is (= (reduce + (drop 10 (range 100)))
           (transduce (dropping 10) + (range 100))))
    (is (= (partition 2 1 (range 10))
           (sequence (partitioning 2 1) (range 10)))))
  (testing "short circuiting on infinities"
    (is (= (range 10) (sequence (taking 10) (range))))
    (is (= (range 10) (sequence (comp natural-numbers (taking 10)) nil)))
    (is (= (range 10 20) (sequence (comp (dropping 10) (taking 10)) (range)))))
  (testing "composition"
    (is (= (take 10 (partition 2 1 (range)))
           (sequence (comp (partitioning 2 1) (taking 10)) (range))))
    (is (= (sequence (comp (partitioning 2 1) cat) (range 10))
           [0 1 1 2 2 3 3 4 4 5 5 6 6 7 7 8 8 9])))
  (testing "completion"
    (is (= (sequence (partitioning-by identity) [0 1 1 2 2 3 4 3 3])
           [[0] [1 1] [2 2] [3] [4] [3 3]]))
    (is (= (sequence (partitioning-by identity) [])
           []))))
