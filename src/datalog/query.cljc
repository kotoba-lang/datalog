(ns datalog.query
  "`[s p o]` triple-pattern query with wildcards (nil) over a `datalog.index`
  db, routing to whichever index matches the bound positions -- bound subject
  -> spo, bound predicate only -> pso, bound predicate + bound object -> pos,
  bound object only -> honest full scan, fully unbound -> full spo scan.

  This is a pure routing function over `datalog.index`'s four indices. It has
  no storage of its own and reads only the in-memory db it is handed.

  `query`'s `visible?` is REQUIRED: a query is not a bare read. This
  namespace stays auth-agnostic by design -- no purpose/scope/capability
  opinion lives here -- but it refuses to run a query without the caller
  stating a visibility decision, because there is no permissive default that
  is safe to fall back on silently. Pass `(constantly true)` to see
  everything; that is a caller's explicit choice, not this namespace's."
  (:require [datalog.index :as index]))

(defn- query* [db [s p o]]
  (cond
    (some? s)
    (into #{}
          (for [[p2 os] (index/entity-attrs db s)
                :when (or (nil? p) (= p p2))
                o2 os
                :when (or (nil? o) (= o o2))]
            {:s s :p p2 :o o2}))

    (and (some? p) (some? o))
    (into #{} (for [s2 (index/by-predicate-value db p o)] {:s s2 :p p :o o}))

    (some? p)
    (into #{} (for [[s2 os] (index/by-predicate db p) o2 os] {:s s2 :p p :o o2}))

    ;; `[_ _ o]` -- bound VALUE only. Before this branch existed the pattern
    ;; fell through to `:else` and returned the ENTIRE database, ignoring the
    ;; object it was given: a single-clause query answered wrongly, and a
    ;; Datalog clause of this shape produced an intermediate set of every
    ;; datom. There is no index for it (`:ocp` covers only ref-valued objects,
    ;; matching `assert-quad`'s `ref?`), so it is an honest O(database) scan --
    ;; but a correct one.
    (some? o)
    (into #{} (for [[s2 pm] (:spo db) [p2 os] pm o2 os :when (= o o2)]
                {:s s2 :p p2 :o o2}))

    :else
    (into #{} (for [[s2 pm] (:spo db) [p2 os] pm o2 os] {:s s2 :p p2 :o o2}))))

(defn query
  "`pattern` is `[s p o]`, any position `nil` for wildcard. `visible?` is
  applied as a post-filter over every candidate quad before it's returned
  -- see the ns docstring. Returns a set of matching `{:s :p :o}` quads."
  [db pattern visible?]
  (into #{} (filter visible? (query* db pattern))))
