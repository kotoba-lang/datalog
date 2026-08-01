# datalog

A storage-free Datalog query engine in portable `.cljc`: four covering
indexes, triple-pattern routing over them, and a semi-naive fixpoint
evaluator with recursive rules, negation, disjunction, and aggregates.

Extracted from [`kotoba-lang/arrangement`](https://github.com/kotoba-lang/arrangement),
whose `arrangement.core` interleaved this query half with a persistence half
(commit/restore against a prolly-tree, addressed by CID). Only the query half
is here. The coupling between the two ran in exactly one direction and
through exactly one line — see "The one cut", below.

## Namespaces

| ns | what it is | requires |
| --- | --- | --- |
| `datalog.index` | the four covering indexes `{:spo :pso :pos :ocp}` — EAVT/AEVT/AVET/VAET in Datomic's vocabulary | nothing |
| `datalog.query` | single `[s p o]` triple pattern with `nil` wildcards, routed to whichever index has the bound positions leading | `datalog.index` |
| `datalog.core` | the Datalog engine: multi-clause join, `:find`/`:in`/`:where`/`:rules` | `datalog.query`, `datom.source` |

```clojure
(require '[datalog.index :as index]
         '[datalog.core :as dl])

(def db (-> (index/empty-db)
            (index/assert-quad {:s "alice" :p "role" :o "admin"} (constantly false))
            (index/assert-quad {:s "alice" :p "name" :o "Alice"} (constantly false))
            (index/assert-quad {:s "bob"   :p "role" :o "user"}  (constantly false))))

(dl/q db {:find '[?name]
          :where '[[?s "role" "admin"]
                   [?s "name" ?name]]}
      (constantly true))
;; => #{["Alice"]}
```

The third argument to `q` (and to `datalog.query/query`) is `visible?`, and
it is **required**. There is no permissive-default arity. A query is not a
bare read: a caller must state a visibility decision, even if that decision
is `(constantly true)`. This namespace has no opinion about purpose, scope,
or capability — it just refuses to invent one for you.

That requirement is load-bearing for negation. `(not [e a v])` is evaluated
through the same `visible?`-filtered scan as every positive clause, so a
redacted fact and an absent fact are indistinguishable to `not`. Negation is
deliberately *not* implemented as "run the positive query, then
set-difference against the unfiltered db" — that would let a query infer a
hidden fact's presence by testing its absence.

## The one cut

In `arrangement`, `assert-quad`/`retract-quad` had an arity-2 form that
defaulted `ref?` — the predicate deciding whether an object also gets a
reverse-reference (`:ocp`) entry — to `ipld.core/link?`. That default was the
**only** reason the quad index depended on IPLD; the index itself never did
anything with a Link beyond calling a predicate on it.

Here `ref?` is a required parameter. Rather than invent a new implicit
default (every candidate is a guess about the caller's data model, and a
wrong guess silently yields an empty or over-full reverse index), the caller
says. Wrap it if you want the old ergonomics:

```clojure
(defn assert-quad [db q] (index/assert-quad db q ipld/link?))
```

Pass `(constantly false)` if you do not use reverse lookup; `refs-to` then
always returns `{}`.

## Dependencies

Exactly one, and it has `:deps {}` of its own — so that is the entire
transitive runtime closure:

```clojure
io.github.kotoba-lang/datom-source {:git/sha "32fd54f091348dab3c78bee765bb7214a056488d"}
```

Pinned to the SHA `arrangement`'s `main` uses. `datom.source/IPatternSource`
is the seam that lets `dl/q` run against something other than a materialized
in-memory db (a cursor over persisted indexes, a merge of partitions) without
changing a line of join logic; `datalog.core/scan*` dispatches on it.

Deliberately absent: `io-ipld`, `io-multiformats`, `prolly-tree`,
`block-cache`. Nothing in this repo stores, hashes, or content-addresses
anything.

## What this is NOT

- **Not a database.** No persistence, no commits, no snapshots, no restore,
  no transactor, no durability of any kind. `empty-db` returns a map; you
  thread it through pure functions and hold it wherever you like. Persisting
  one is `arrangement`'s job.
- **No IPLD, no CID, no content addressing.** See "The one cut".
- **Not Datomic-compatible.** The `:find`/`:where` surface is Datomic-*shaped*
  — it borrows the syntax so the shape is familiar — but it is not that
  grammar, and queries are not portable between the two. No entity ids, no
  schema, no attribute cardinality/uniqueness, no `db/ident`, no history or
  `as-of`/`since`, no transaction metadata.
- **Not a query planner.** `:where` clauses join strictly left-to-right in
  the order written. Clause order is a performance decision the caller owns.

## Known gaps

Stated because they are real, not because they are planned:

- **No `pull`.** Projection is positional `:find` vectors only. There is no
  pull expression, no pattern syntax, no nested entity maps.
- **No `not-join`.** Negation is single-triple `(not [e a v])`. A negated
  multi-clause conjunction with explicit variable scoping does not exist.
- **No stratified negation.** `(not (rule-name ...))` — negating a *rule
  invocation* rather than a triple pattern — throws a clear error rather
  than silently misbehaving. Combining recursion and negation soundly
  requires stratification (a rule may never negate itself, even
  transitively), and that analysis is not implemented.
- **No `:find` shorthands.** No scalar (`?x .`), collection (`[?x ...]`), or
  tuple (`[?x ?y]`) find specs; no `:with`.
- **Function/predicate clauses are a fixed whitelist,** not arbitrary code.
  See `query-fns` in `datalog.core`: comparisons, arithmetic, `str`,
  `count`, `ground`, and four non-regex string predicates. Regex is
  deliberately excluded — a caller-supplied pattern is a ReDoS vector, and a
  query is caller-supplied data in this threat model.
- **Aggregates are a fixed set:** `count`, `count-distinct`, `sum`, `avg`,
  `min`, `max`.
- **`[_ _ o]` — bound object only — is an honest O(database) scan.** There is
  no index for it: `:ocp` covers only objects an `assert-quad` caller marked
  as refs. Correct, but linear.
- **`retract-quad` must be given a `ref?` that agrees with the one used to
  assert the same quad,** or the `:ocp` entry is left behind. Nothing
  enforces this.
- **Values are opaque.** s/p/o are used as map keys and nothing more — no
  typed-value support, no ordering, no range queries. `<`/`>` exist as
  *predicate clause functions* over already-bound values, not as index
  operations.
- **The fixpoint has a defensive iteration cap** (10,000) that throws rather
  than hanging. Derivation is monotonic over a finite domain so a correct
  query converges long before it; the cap exists to fail loudly.

## Tests

Ported from `arrangement`'s suite — the deftests covering the index
accessors, query routing, and Datalog. `arrangement`'s IPLD-Link and
commit/restore tests were not ported; they belong to the half that stayed.

```bash
clojure -M:test      # JVM: 58 tests, 83 assertions, 0 failures, 0 errors
clojure -M:lint      # clj-kondo: 0 errors, 0 warnings
npm install && npm run test:cljs
                     # ClojureScript (shadow-cljs :node-test):
                     # 58 tests, 83 assertions, 0 failures, 0 errors
```

Both jobs run in CI on every push and PR. The ClojureScript job is a real
ClojureScript compile-and-run, not a `.cljc` file merely *named* portable:
`gen-shadow-cljs-edn.cljs` resolves `clojure -Spath` into
`shadow-cljs.edn`'s `:source-paths`, so cljs compiles against the same
pinned dependency SHAs the JVM job uses, and portability is machine-verified
rather than asserted.

Two `:fn-arity` warnings are expected in the cljs build: the
`visible-is-required` tests deliberately call `query`/`q` with a missing
`visible?` to prove there is no permissive-default arity.

## License

MIT.
