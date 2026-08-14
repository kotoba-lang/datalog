(ns datalog.index
  "The in-memory, 4-covering-index arrangement a Datalog query runs over --
  Datomic's own vocabulary for this exact structure: `spo`/`pso`/`pos`/`ocp`
  here are EAVT/AEVT/AVET/VAET there.

  A triple is `{:s subject :p predicate :o object}`. s/p/o are opaque values
  for indexing purposes -- this namespace never inspects, decodes, or hashes
  them, it only uses them as map keys, so anything with value equality and a
  hash works (strings, keywords, numbers, records, content addresses).

  **No storage, no content addressing, no IPLD.** This namespace is a pure data
  structure plus `datom.source/in-range?` so a value interval on `:pos` uses
  the same total order as the query seam. `empty-db` returns a plain map;
  every mutation is a pure function of a map to a map. Persisting one of these -- snapshotting the indices into a
  prolly-tree, addressing that by CID, committing/restoring -- is somebody
  else's job (see `kotoba-lang/arrangement`, which this was extracted from
  and which keeps the persistence half). The split is deliberate: querying is
  useful without any of that, and the coupling ran exactly one way.

  ## Why `ref?` is a required argument here

  In `arrangement` these functions had an arity-2 form that defaulted `ref?`
  to `ipld.core/link?` (\"a reference is whatever is an IPLD Link\"). That one
  default was the ONLY thing tying the quad index to IPLD -- the index itself
  never did anything with a Link except call a predicate on it. Rather than
  invent a new implicit default here (every candidate is a guess about the
  caller's data model, and a wrong guess silently produces an empty or
  over-full reverse index), `ref?` is a required parameter. State it, or wrap
  these functions in your own arity-2 that supplies your domain's answer:

      (defn assert-quad [db q] (index/assert-quad db q ipld/link?))

  Pass `(constantly false)` if you do not use reverse-reference lookup;
  `refs-to` then always returns `{}` and `:ocp` stays empty."
  (:require [datom.source :as ds]))

(defn empty-db
  "A db with all four indices empty. Just a map -- nothing to close, nothing
  to open, safe to hold in an atom or thread through pure functions."
  []
  {:spo {} :pso {} :pos {} :ocp {}})

(defn- upd [m k1 k2 v]
  (update m k1 (fnil (fn [m2] (update m2 k2 (fnil conj #{}) v)) {})))

(defn- rm [m k1 k2 v]
  (if-let [m2 (get m k1)]
    (let [s (disj (get m2 k2 #{}) v)]
      (if (empty? s)
        (let [m2' (dissoc m2 k2)]
          (if (empty? m2') (dissoc m k1) (assoc m k1 m2')))
        (assoc m k1 (assoc m2 k2 s))))
    m))

(defn assert-quad
  "Add `{:s :p :o}` to `db`'s four indices. `ref?` is REQUIRED (see the ns
  docstring): it decides whether `:o` is additionally indexed in `:ocp` for
  reverse-reference lookup via `refs-to`. Pure -- returns a new db."
  [db {:keys [s p o]} ref?]
  (cond-> db
    true     (update :spo upd s p o)
    true     (update :pso upd p s o)
    true     (update :pos upd p o s)
    (ref? o) (update :ocp upd o p s)))

(defn- ->mutable
  "One index, `{k1 {k2 #{v}}}`, with every inner map made transient. The outer
  map stays persistent on purpose: after a key exists, a bulk insert mutates
  its inner map in place and never touches the outer map again."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc k (transient v))) {} m))

(defn- freeze [m]
  (reduce-kv (fn [acc k v] (assoc acc k (persistent! v))) {} m))

(defn- upd-mut [m k1 k2 v]
  (if-let [inner (get m k1)]
    ;; `assoc!` USUALLY mutates in place and returns the same object, so the
    ;; outer map usually needs no path copy at all. It does NOT always: a
    ;; transient array-map returns a different object when it grows past eight
    ;; entries and becomes a hash-map. Discarding the return value therefore
    ;; silently truncates every inner map at exactly eight entries -- which is
    ;; what the first version of this did, and what the equivalence test caught.
    (let [inner' (assoc! inner k2 (conj (get inner k2 #{}) v))]
      (if (identical? inner' inner) m (assoc m k1 inner')))
    (assoc m k1 (assoc! (transient {}) k2 #{v}))))

(defn mutable-db
  "A mutable bulk-loading accumulator over `db`. Pair with `assert-quads!` and
  finish with `persist-db`. NOT a db: nothing else in this namespace accepts
  one, and it is not safe to share or to hold across threads.

  This exists because the cost `assert-quads` pays once -- walking the existing
  outer keys to make their inner maps transient -- is proportional to the db,
  not to the batch. Calling `assert-quads` in a loop therefore pays that walk
  once per batch, which is quadratic in the number of batches.

  Measured 2026-08-13, LDBC SNB SF-1 (31,837,452 datoms) in 500,000-datom
  batches: the looped `assert-quads` form was still running after 25 minutes,
  with a JVM thread dump showing the main thread inside the transient-conversion
  walk. That was mistaken for the engine being super-linear in ingest. It is
  not; the loop was."
  [db]
  {:spo (->mutable (:spo db)) :pso (->mutable (:pso db))
   :pos (->mutable (:pos db)) :ocp (->mutable (:ocp db))})

(defn assert-quads!
  "Add `quads` to a `mutable-db`, returning the accumulator. Cheap to call
  repeatedly: no per-call walk of the existing db."
  [mdb quads ref?]
  (loop [qs (seq quads)
         spo (:spo mdb) pso (:pso mdb) pos (:pos mdb) ocp (:ocp mdb)]
    (if-let [{:keys [s p o]} (first qs)]
      (recur (next qs)
             (upd-mut spo s p o)
             (upd-mut pso p s o)
             (upd-mut pos p o s)
             (if (ref? o) (upd-mut ocp o p s) ocp))
      {:spo spo :pso pso :pos pos :ocp ocp})))

(defn persist-db
  "Finish a `mutable-db`, returning a db. The accumulator must not be used
  afterwards."
  [mdb]
  {:spo (freeze (:spo mdb)) :pso (freeze (:pso mdb))
   :pos (freeze (:pos mdb)) :ocp (freeze (:ocp mdb))})

(defn assert-quads
  "Bulk `assert-quad`. Same result as `(reduce #(assert-quad %1 %2 ref?) db qs)`
  -- there is a test asserting exactly that -- built for loading a dataset
  rather than for one write.

  Measured 2026-08-13, loading 2,000,000 real LDBC SNB datoms into an empty db
  on an Apple M4: **888k datoms/s here against 600k for reducing `assert-quad`**
  -- about 1.5x. Worth having for a dataset load, and no more than that.

  It is deliberately recorded here that this was written believing the per-quad
  path was a far worse bottleneck than it is. It is not: 2,000,000 datoms take
  3.3 seconds through `assert-quad`. Do not cite this function as the reason a
  large load became possible.

  ONE CALL PER LOAD. It pays an up-front walk over the existing db's outer keys,
  so calling it once per batch in a loop is quadratic in the number of batches.
  To load in batches, use `mutable-db` / `assert-quads!` / `persist-db`."
  [db quads ref?]
  (persist-db (assert-quads! (mutable-db db) quads ref?)))

(defn retract-quad
  "Remove `{:s :p :o}` from `db`'s four indices. `ref?` is REQUIRED and must
  agree with the one used to assert the same quad -- otherwise the `:ocp`
  entry is left behind. Pure -- returns a new db."
  [db {:keys [s p o]} ref?]
  (cond-> db
    true     (update :spo rm s p o)
    true     (update :pso rm p s o)
    true     (update :pos rm p o s)
    (ref? o) (update :ocp rm o p s)))

(defn entity-attrs
  "All `{p #{o...}}` for subject `s` (EAVT-style)."
  [db s] (get (:spo db) s {}))

(defn by-predicate
  "All `{s #{o...}}` for predicate `p` (AEVT-style scan)."
  [db p] (get (:pso db) p {}))

(defn by-predicate-value
  "All subjects `s` where `[s p o]` holds (AVET-style point lookup)."
  [db p o] (get-in db [:pos p o] #{}))

(defn by-predicate-range
  "AVET-style value interval: `{s #{o...}}` for predicate `p` whose object
  is in `[lo, hi)` (see `datom.source/in-range?`).

  Walks the values of `p` in `:pos` and filters. That is O(|values of p|),
  not a tree cut — in-memory `:pos` is a hash map, not a sorted map
  (sorted-map cannot be transient, and `assert-quads!` depends on that).
  The tree cut lives in `prolly-tree/scan-range` and in lake columnar
  stats; this function is the same *question* on the hot db."
  ([db p lo hi] (by-predicate-range db p lo hi {}))
  ([db p lo hi opts]
   (reduce-kv (fn [acc o ss]
                (if (ds/in-range? o lo hi opts)
                  (reduce (fn [m s] (update m s (fnil conj #{}) o)) acc ss)
                  acc))
              {}
              (get-in db [:pos p] {}))))

(defn refs-to
  "All `{p #{s...}}` referencing object `o` (VAET-style reverse lookup) --
  only populated for quads asserted with a truthy `ref?`."
  [db o] (get (:ocp db) o {}))
