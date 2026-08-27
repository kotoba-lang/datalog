(ns datalog.async-test
  (:require [cljs.test :refer [deftest is async]]
            [datalog.core :as d]
            [datalog.index :as index]
            [datom.source :as source]))

(def ^:private everything (constantly true))

(defn- db-of [quads]
  (reduce #(index/assert-quad %1 %2 (constantly false))
          (index/empty-db)
          quads))

(defn- matches? [{:keys [s p o]} [want-s want-p want-o]]
  (and (or (nil? want-s) (= want-s s))
       (or (nil? want-p) (= want-p p))
       (or (nil? want-o) (= want-o o))))

(defn- async-source [quads calls]
  (reify source/IAsyncPatternSource
    (-scan-async [_ pattern]
      (swap! calls conj pattern)
      (js/Promise.resolve (into #{} (filter #(matches? % pattern)) quads)))))

(deftest q-async-matches-q-across-the-query-language
  (async done
    (let [quads [{:s "alice" :p "parent" :o "bob"}
                 {:s "bob" :p "parent" :o "carol"}
                 {:s "carol" :p "parent" :o "dave"}
                 {:s "alice" :p "score" :o 10}
                 {:s "bob" :p "score" :o 20}
                 {:s "carol" :p "score" :o 30}
                 {:s "alice" :p "active" :o true}
                 {:s "carol" :p "active" :o true}]
          db (db-of quads)
          calls (atom [])
          src (async-source quads calls)
          rules '[[(ancestor ?x ?y) [?x "parent" ?y]]
                  [(ancestor ?x ?y) [?x "parent" ?z] (ancestor ?z ?y)]]
          queries [{:find '[?person ?score]
                    :where '[[?person "score" ?score]
                             (not [?person "active" false])]
                    :order-by '[[?score :desc]]
                    :limit 2}
                   {:find '[?descendant]
                    :where '[(ancestor "alice" ?descendant)]
                    :rules rules}
                   {:find '[(count ?person) (sum ?score)]
                    :where '[[?person "score" ?score]]}
                   {:find '[?person ?score]
                    :where '[[?person "score" ?score]
                             [(>= ?score 15)]
                             [(< ?score 35)]]}
                   {:find '[?person]
                    :where '[(or [?person "active" true]
                                 [?person "score" 20])]}]]
      (-> (js/Promise.all
           (into-array (map #(d/q-async src % everything) queries)))
          (.then (fn [actuals]
                   (doseq [[query actual] (map vector queries (js->clj actuals))]
                     (is (= (d/q db query everything) actual)))
                   (is (seq @calls) "the async source was actually scanned")
                   (done)))
          (.catch (fn [e]
                    (is false (str "q-async threw: " (or (.-stack e) e)))
                    (done)))))))

(deftest q-async-rejects-unsafe-input-as-a-promise
  (async done
    (let [src (async-source [] (atom []))]
      (-> (d/q-async src
                     {:find '[?x]
                      :where '[(not [?x "secret" true])]}
                     everything)
          (.then (fn [_]
                   (is false "unsafe negation must not resolve")
                   (done)))
          (.catch (fn [e]
                    (is (re-find #"unsafe negation" (.-message e)))
                    (done)))))))
