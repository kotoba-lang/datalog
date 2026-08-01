(ns datalog.index-test
  "Ported from `arrangement`'s `arrangement.core-test` -- the deftests that
  cover the four covering indexes and nothing else. `arrangement`'s
  `link-edn-safe-roundtrip` and `ref-indexing-naturalizes-to-ipld-link` are
  deliberately NOT ported: they test the IPLD default this extraction
  removed, and they belong to the persistence half that stayed behind."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datalog.index :as index]))

(def ^:private no-refs
  "This fixture's objects are never references -- nothing reaches `:ocp`."
  (constantly false))

(deftest assert-and-lookup
  (let [db (-> (index/empty-db)
               (index/assert-quad {:s "alice" :p "role" :o "admin"} no-refs)
               (index/assert-quad {:s "alice" :p "name" :o "Alice"} no-refs)
               (index/assert-quad {:s "bob" :p "role" :o "user"} no-refs))]
    (is (= {"role" #{"admin"} "name" #{"Alice"}} (index/entity-attrs db "alice")))
    (is (= {"alice" #{"admin"} "bob" #{"user"}} (index/by-predicate db "role")))
    (is (= #{"alice"} (index/by-predicate-value db "role" "admin")))
    (is (= #{"bob"} (index/by-predicate-value db "role" "user")))))

(deftest retract-removes-from-all-4-indices
  (let [db (-> (index/empty-db)
               (index/assert-quad {:s "alice" :p "role" :o "admin"} no-refs)
               (index/retract-quad {:s "alice" :p "role" :o "admin"} no-refs))]
    (is (= {} (index/entity-attrs db "alice")))
    (is (= {} (index/by-predicate db "role")))
    (is (= #{} (index/by-predicate-value db "role" "admin")))
    (testing "the index maps are pruned empty, not left holding empty sets"
      (is (= (index/empty-db) db)))))

(deftest ref-indexing-is-opt-in
  (let [ref? #(str/starts-with? % "bafy")
        db (-> (index/empty-db)
               (index/assert-quad {:s "alice" :p "knows" :o "bafybob"} ref?)
               (index/assert-quad {:s "alice" :p "name" :o "Alice"} ref?))]
    (is (= {"knows" #{"alice"}} (index/refs-to db "bafybob")))
    (is (= {} (index/refs-to db "Alice")) "non-ref object is not reverse-indexed")
    (testing "a matching ref? un-indexes it again"
      (let [db2 (index/retract-quad db {:s "alice" :p "knows" :o "bafybob"} ref?)]
        (is (= {} (index/refs-to db2 "bafybob")))))))

(deftest ref-predicate-is-required-not-defaulted
  ;; The one coupling this extraction cut: in `arrangement` these had an
  ;; arity-2 form defaulting `ref?` to `ipld.core/link?`, which is the only
  ;; reason the quad index ever mentioned IPLD. Here the caller must say.
  ;; Called through `apply` so the ClojureScript compiler does not reject
  ;; the arity statically -- the point is the RUNTIME behaviour. The two
  ;; platforms fail for different reasons and that is fine: on the JVM a
  ;; missing arity is an ArityException, while ClojureScript emits no arity
  ;; check for a single-arity fn and instead dies calling the `nil` `ref?`.
  ;; Either way there is no arity-2 form quietly supplying a default.
  (testing "there is no arity-2 assert-quad/retract-quad to fall back on"
    (is (thrown? #?(:clj clojure.lang.ArityException :cljs js/Error)
                 (apply index/assert-quad [(index/empty-db) {:s "a" :p "b" :o "c"}])))
    (is (thrown? #?(:clj clojure.lang.ArityException :cljs js/Error)
                 (apply index/retract-quad [(index/empty-db) {:s "a" :p "b" :o "c"}]))))
  (testing "(constantly false) is the no-reverse-index choice"
    (let [db (index/assert-quad (index/empty-db) {:s "a" :p "b" :o "c"} no-refs)]
      (is (= {} (:ocp db)))
      (is (= {} (index/refs-to db "c")))))
  (testing "(constantly true) reverse-indexes every object"
    (let [db (index/assert-quad (index/empty-db) {:s "a" :p "b" :o "c"} (constantly true))]
      (is (= {"b" #{"a"}} (index/refs-to db "c"))))))

(deftest indexes-hold-values-not-just-strings
  ;; s/p/o are opaque to this namespace -- it only uses them as map keys, so
  ;; keywords/numbers/maps work exactly as well as strings. Nothing here
  ;; decodes, hashes, or content-addresses a value.
  (let [db (-> (index/empty-db)
               (index/assert-quad {:s 1 :p :age :o 42} no-refs)
               (index/assert-quad {:s 1 :p :tags :o #{:a :b}} no-refs))]
    (is (= {:age #{42} :tags #{#{:a :b}}} (index/entity-attrs db 1)))
    (is (= #{1} (index/by-predicate-value db :age 42)))))

(deftest duplicate-assert-is-idempotent
  (let [once (-> (index/empty-db)
                 (index/assert-quad {:s "a" :p "b" :o "c"} no-refs))
        twice (index/assert-quad once {:s "a" :p "b" :o "c"} no-refs)]
    (is (= once twice))))
