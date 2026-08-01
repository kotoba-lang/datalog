(ns datalog.index
  "The in-memory, 4-covering-index arrangement a Datalog query runs over --
  Datomic's own vocabulary for this exact structure: `spo`/`pso`/`pos`/`ocp`
  here are EAVT/AEVT/AVET/VAET there.

  A triple is `{:s subject :p predicate :o object}`. s/p/o are opaque values
  for indexing purposes -- this namespace never inspects, decodes, or hashes
  them, it only uses them as map keys, so anything with value equality and a
  hash works (strings, keywords, numbers, records, content addresses).

  **No storage, no content addressing, no IPLD.** This namespace is pure data
  structure: `empty-db` returns a plain map, every operation is a pure
  function of a map to a map, and the whole thing is `.cljc` with zero
  requires. Persisting one of these -- snapshotting the indices into a
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
  `refs-to` then always returns `{}` and `:ocp` stays empty.")

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

(defn refs-to
  "All `{p #{s...}}` referencing object `o` (VAET-style reverse lookup) --
  only populated for quads asserted with a truthy `ref?`."
  [db o] (get (:ocp db) o {}))
