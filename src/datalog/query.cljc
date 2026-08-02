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
  everything; that is a caller's explicit choice, not this namespace's.
  `cardinality` carries the same requirement, for the same reason and by the
  same mechanism -- see its docstring."
  (:require [datalog.index :as index]))

(defn- query-seq
  "Matching quads as a SEQ, in index order, routed exactly as the ns docstring
  describes. `query` turns this into a set; `cardinality` counts it without
  ever building one.

  This used to build the set inline, so a caller that only wanted to know HOW
  MANY rows a pattern matches still paid to hash every quad into a set and then
  discarded it. That caller exists and is not rare: the query planner estimates
  every clause this way on every query
  (kotobase-peer.core/datalog-query-plan), and one such estimate -- 361,246
  rows for a both-ends-unbound `knows` clause -- measured as 38% of an LDBC
  IC09 query's total time (kotobase-peer
  bench/results/2026-08-02-scan-instrumentation.edn)."
  [db [s p o]]
  (cond
    (some? s)
    (for [[p2 os] (index/entity-attrs db s)
          :when (or (nil? p) (= p p2))
          o2 os
          :when (or (nil? o) (= o o2))]
      {:s s :p p2 :o o2})

    (and (some? p) (some? o))
    (for [s2 (index/by-predicate-value db p o)] {:s s2 :p p :o o})

    (some? p)
    (for [[s2 os] (index/by-predicate db p) o2 os] {:s s2 :p p :o o2})

    ;; `[_ _ o]` — bound VALUE only. Before this branch existed the pattern
    ;; fell through to `:else` and returned the ENTIRE database, ignoring the
    ;; object it was given: a single-clause query answered wrongly, and a
    ;; Datalog clause of this shape produced an intermediate set of every
    ;; datom. There is no index for it (`:ocp` covers only ref-valued objects,
    ;; matching `assert-quad`'s `ref?`), so it is an honest O(database) scan --
    ;; but a correct one. Found by datom-source's conformance suite.
    (some? o)
    (for [[s2 pm] (:spo db) [p2 os] pm o2 os :when (= o o2)]
      {:s s2 :p p2 :o o2})

    :else
    (for [[s2 pm] (:spo db) [p2 os] pm o2 os] {:s s2 :p p2 :o o2})))

(defn query
  "`pattern` is `[s p o]`, any position `nil` for wildcard. `visible?` is
  applied as a post-filter over every candidate quad before it's returned
  -- see the ns docstring. Returns a set of matching `{:s :p :o}` quads."
  [db pattern visible?]
  (into #{} (filter visible?) (query-seq db pattern)))

(defn cardinality
  "How many quads `pattern` matches under `visible?` -- the same number as
  `(count (query db pattern visible?))`, without building the set.

  Same `visible?` contract as `query`: REQUIRED, and applied to every
  candidate, so a count can never report rows a read would not return. What is
  skipped is only the set -- no hashing, no membership checks, no retained
  collection.

  `query` returns a set, so in principle it could report fewer rows than this
  counts if a pattern could yield the same quad twice. None can: every branch
  of `query-seq` walks its index once and each quad appears at one position in
  it. The equivalence test asserts that rather than assuming it."
  [db pattern visible?]
  (count (filter visible? (query-seq db pattern))))
