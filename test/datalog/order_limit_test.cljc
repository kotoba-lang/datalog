(ns datalog.order-limit-test
  "ADR-2608021000 §6-2: :order-by / :limit, so a top-N query is expressible in
  the query instead of in host code. Every LDBC SNB complex read is a
  'most recent N' query."
  (:require [clojure.test :refer [deftest is]]
            [datalog.index :as index]
            [datalog.core :as d]))

(def ^:private everything (constantly true))

(def ^:private no-refs
  "`datalog.index/assert-quad`'s `ref?`, which is REQUIRED here (arrangement
  defaulted it to `ipld.core/link?`; this library has no IPLD to default to).
  These fixtures use no reverse-reference lookup, so nothing is `:vaet`-indexed."
  (constantly false))

(defn- db []
  (reduce (fn [db [s p o]] (index/assert-quad db {:s s :p p :o o} no-refs))
          (index/empty-db)
          [["m1" "creator" "alice"] ["m1" "date" "300"]
           ["m2" "creator" "alice"] ["m2" "date" "100"]
           ["m3" "creator" "alice"] ["m3" "date" "200"]
           ["m4" "creator" "bob"]   ["m4" "date" "400"]]))

(def ^:private by-alice
  '{:find [?msg ?date]
    :in [?person]
    :where [[?msg "creator" ?person] [?msg "date" ?date]]})

(deftest without-order-or-limit-the-result-is-still-a-set
  ;; The return type only changes when the caller asks for order. Existing
  ;; callers must not start receiving vectors.
  (let [r (d/q (db) by-alice everything ["alice"])]
    (is (set? r))
    (is (= #{["m1" "300"] ["m2" "100"] ["m3" "200"]} r))))

(deftest order-by-descending-then-limit-is-top-n
  (let [r (d/q (db) (assoc by-alice :order-by '[[?date :desc]] :limit 2)
               everything ["alice"])]
    (is (vector? r) "an ordered result is a sequence, not a set")
    (is (= [["m1" "300"] ["m3" "200"]] r))))

(deftest order-by-defaults-to-ascending-and-takes-a-bare-var
  (let [r (d/q (db) (assoc by-alice :order-by '[?date]) everything ["alice"])]
    (is (= [["m2" "100"] ["m3" "200"] ["m1" "300"]] r))))

(deftest secondary-key-breaks-ties
  ;; Both m5 and m6 share a date; the second key decides, and it decides the
  ;; same way every run -- a top-N that is not deterministic is not a top-N.
  (let [db2 (-> (db)
                (index/assert-quad {:s "m5" :p "creator" :o "alice"} no-refs)
                (index/assert-quad {:s "m5" :p "date" :o "300"} no-refs)
                (index/assert-quad {:s "m6" :p "creator" :o "alice"} no-refs)
                (index/assert-quad {:s "m6" :p "date" :o "300"} no-refs))
        r (d/q db2 (assoc by-alice :order-by '[[?date :desc] [?msg :asc]] :limit 3)
               everything ["alice"])]
    (is (= [["m1" "300"] ["m5" "300"] ["m6" "300"]] r))))

(deftest limit-larger-than-the-result-is-not-an-error
  (is (= 3 (count (d/q (db) (assoc by-alice :order-by '[?date] :limit 99)
                       everything ["alice"])))))

(deftest ordering-by-a-key-outside-find-is-rejected
  ;; Silently ignoring it would return an arbitrary order that looks sorted.
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (d/q (db) (assoc by-alice :order-by '[?person]) everything ["alice"]))))
