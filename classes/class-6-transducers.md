# Clojure Advanced Topics — Class 6 (2h)

## Transducers: Efficient Data Transformations

> **Prerequisite:** Classes 1–5 (Multimethods, Protocols, Macros, Refs/STM, Agents).
> All examples continue with the Mexican payment rails (SPEI, credit card, debit card).

---

## Agenda

By the end of this class, students will be able to:

- Explain what transducers are and why intermediate collections are wasteful
- Create transducers with the 1-arity versions of `map`, `filter`, `remove`, `take`, etc.
- Compose transducers with `comp` and understand the execution order
- Apply transducers using `transduce`, `into`, `sequence`, and `eduction`
- Use transducers across different contexts (collections, channels, files)

## Timeline

```
 0:00 ┬─ Part 0 — 🍳 The Kitchen Analogy ·············· 15 min
      │   Cooking intro: batch kitchen vs assembly line
      │   Emoji-driven intuition for transducers
      │   Multiple sources: truck, bike, market
      │
 0:15 ┬─ Part 0.5 — Recap & Motivation ················ 10 min
      │   "Your batch process runs out of memory"
      │
 0:25 ┬─ PART 1 — CONTENT PRESENTATION ················
      │
 0:25 ├─ 1.1 — The Problem: Intermediate Collections ··· 15 min
      │   Traditional seq pipeline overhead
      │   Visualizing what happens in memory
      │
 0:40 ├─ 1.2 — What Are Transducers? ··················· 20 min
      │   1-arity map, filter, etc.
      │   Composition with comp
      │   The mental model
      │
 1:00 ├─ 1.3 — Applying Transducers ···················· 20 min
      │   transduce, into, sequence, eduction
      │   Choosing the right application function
      │
 1:20 ┬─── 5 min break ───
      │
 1:25 ┬─ PART 2 — INTERACTIVE PRACTICE ················
      │
 1:25 ├─ 2.1 — Payment Batch Processing ················ 15 min
      │   Demo: refactor seq pipeline to transducers
      │
 1:40 ├─ 2.2 — Custom Transducer: batch-by-method ····· 15 min
      │   Demo: build a stateful transducer
      │
 1:55 ├─ 2.3 — Performance Comparison ················· 15 min
      │   Demo: benchmark 3 approaches, discuss trade-offs
      │
 2:10 ┬─ Wrap-up & Key Takeaways ······················  5 min
 2:15 ┴─ End
```

---

# PART 0 — 🍳 THE KITCHEN ANALOGY (15 min)

> **Goal:** Build intuition for transducers before any formal definition, using a cooking metaphor with emoji ingredients.

---

## Setting up the kitchen

```clojure
;; Each ingredient is a map
(def kitchen-ingredients
  [{:item "🥔" :name "potato"}
   {:item "🐄" :name "cow"}
   {:item "🥔" :name "potato"}
   {:item "🌽" :name "corn"}
   {:item "🐄" :name "cow"}
   {:item "🥔" :name "potato"}
   {:item "🌽" :name "corn"}
   {:item "🥔" :name "potato"}])

(mapv :item kitchen-ingredients)
;; => [🥔 🐄 🥔 🌽 🐄 🥔 🌽 🥔]
;; 4 potatoes, 2 cows, 2 corn
```

Today's menu: **We only make FRIES 🍟**

Pipeline: **filter potatoes → peel → cut → fry**

But there are TWO ways to organize the kitchen...

---

## 🏭 Kitchen A — The BATCH kitchen (sequences)

4 chefs, each does ONE job. Each chef processes ALL items and puts results in a NEW BOWL before the next chef starts.

```clojure
;; Our transformation functions
(defn potato? [x] (= "potato" (:name x)))
(defn peel-it [x] (assoc x :item "🥔✨"))
(defn cut-it  [x] (assoc x :item "🔪🥔"))
(defn fry-it  [x] (assoc x :item "🍟"))
```

> 🎓 **SOCRATIC:** *"If we apply the filter step to all 8 ingredients, how many items survive?"*
> → Only 4 (the potatoes). The 2 cows and 2 corn are rejected.

```clojure
;; 👨‍🍳 Chef 1 (FILTER): "I only keep potatoes!"
(def bowl-1 (filter potato? kitchen-ingredients))
(mapv :item bowl-1)
;; => [🥔 🥔 🥔 🥔]        📦 Bowl 1 ALLOCATED

;; 👩‍🍳 Chef 2 (PEEL): "I peel everything in Bowl 1!"
(def bowl-2 (map peel-it bowl-1))
(mapv :item bowl-2)
;; => [🥔✨ 🥔✨ 🥔✨ 🥔✨]  📦 Bowl 2 ALLOCATED

;; 👨‍🍳 Chef 3 (CUT): "I cut everything in Bowl 2!"
(def bowl-3 (map cut-it bowl-2))
(mapv :item bowl-3)
;; => [🔪🥔 🔪🥔 🔪🥔 🔪🥔]  📦 Bowl 3 ALLOCATED

;; 👩‍🍳 Chef 4 (FRY): "I fry everything in Bowl 3!"
(def bowl-4 (map fry-it bowl-3))
(mapv :item bowl-4)
;; => [🍟 🍟 🍟 🍟]          📦 Bowl 4 ALLOCATED
```

```
Summary:
  Delivery:          [🥔 🐄 🥔 🌽 🐄 🥔 🌽 🥔]  (original)
  📦 Bowl 1 (filter): [🥔 🥔 🥔 🥔]
  📦 Bowl 2 (peel):   [🥔✨ 🥔✨ 🥔✨ 🥔✨]
  📦 Bowl 3 (cut):    [🔪🥔 🔪🥔 🔪🥔 🔪🥔]
  📦 Bowl 4 (fry):    [🍟 🍟 🍟 🍟]

  TOTAL BOWLS: 4 📦📦📦📦
  Each bowl = an intermediate collection in memory!
```

> 🎓 **SOCRATIC:** *"We used 4 steps and got 4 intermediate bowls. After ALL 4 steps, how many bowls sit in memory?"* → 4. Each step creates one.

The `->>` threading version:

```clojure
(->> kitchen-ingredients
     (filter potato?)   ;; 📦 bowl 1
     (map peel-it)      ;; 📦 bowl 2
     (map cut-it)       ;; 📦 bowl 3
     (map fry-it))      ;; 📦 bowl 4
;; => ({:item "🍟", :name "potato"} ...)
;; 4 intermediate collections, even though we only needed the final 🍟!
```

---

## ⚡ Kitchen B — The ASSEMBLY LINE kitchen (transducers)

ONE super-chef who knows ALL the steps. Picks up each ingredient, does EVERYTHING to it, puts fries directly on the plate. NO intermediate bowls!

> 🎓 **SOCRATIC:** *"What if instead of 4 separate chefs, we had ONE super-chef who does filter→peel→cut→fry on each item BEFORE touching the next? How many intermediate bowls?"* → Zero!

```
Processing item by item:

  #1 🥔 (potato) → potato? ✅ → peel 🥔✨ → cut 🔪🥔 → fry 🍟  ➡️ plate!
  #2 🐄 (cow)    → potato? ❌ SKIP (not even peeled, cut, or fried!)
  #3 🥔 (potato) → potato? ✅ → peel 🥔✨ → cut 🔪🥔 → fry 🍟  ➡️ plate!
  #4 🌽 (corn)   → potato? ❌ SKIP
  #5 🐄 (cow)    → potato? ❌ SKIP
  #6 🥔 (potato) → potato? ✅ → peel 🥔✨ → cut 🔪🥔 → fry 🍟  ➡️ plate!
  #7 🌽 (corn)   → potato? ❌ SKIP
  #8 🥔 (potato) → potato? ✅ → peel 🥔✨ → cut 🔪🥔 → fry 🍟  ➡️ plate!

  🍽️  Final plate: [🍟 🍟 🍟 🍟]
  TOTAL BOWLS USED: ZERO! 🎉
```

---

## 🔬 From kitchen to Clojure code

> 🎓 **SOCRATIC:** *"We know `(filter potato? ingredients)` with 2 arguments returns a sequence. What does `(filter potato?)` with only 1 argument return?"* → A transducer! A function — a recipe card, not food.

```clojure
;; 2 arguments → gives you FOOD (a sequence)
(filter potato? kitchen-ingredients)
;; => ({:item "🥔", :name "potato"} ...)
;; type: clojure.lang.LazySeq

;; 1 argument → gives you a RECIPE (a transducer)
(filter potato?)
;; => #object[clojure.core$filter$fn__5977 ...]
;; type: a function! No data processed yet.
```

Build recipe cards and combine them with `comp`:

```clojure
;; 4 individual recipe cards:
;; (filter potato?)  → "keep only potatoes"
;; (map peel-it)     → "peel them"
;; (map cut-it)      → "cut them"
;; (map fry-it)      → "fry them"

;; Combine into ONE super-recipe:
(def xf-make-fries
  (comp
    (filter potato?)   ;; step 1 (runs first)
    (map peel-it)      ;; step 2
    (map cut-it)       ;; step 3
    (map fry-it)))     ;; step 4 (runs last)

;; Still just a function! No data processed yet.
(type xf-make-fries)
;; => clojure.core$comp$fn__5895
```

> 🎓 **SOCRATIC:** *"We have our combined recipe `xf-make-fries`. Which function do we use to collect results into a vector?"* → `into`!

Apply the transducer:

```clojure
;; Apply the recipe to our ingredients:
(into [] xf-make-fries kitchen-ingredients)
;; => [{:item "🍟", :name "potato"} {:item "🍟", :name "potato"}
;;     {:item "🍟", :name "potato"} {:item "🍟", :name "potato"}]
;; 0 intermediate bowls!
```

### Side-by-side comparison

```
🏭 SEQUENCES (batch kitchen)         ⚡ TRANSDUCERS (assembly line)
────────────────────────────         ────────────────────────────────
(->> ingredients                     (into []
  (filter potato?)  ;; 📦 bowl 1      (comp
  (map peel-it)     ;; 📦 bowl 2        (filter potato?)  ;; no bowl
  (map cut-it)      ;; 📦 bowl 3        (map peel-it)     ;; no bowl
  (map fry-it))     ;; 📦 bowl 4        (map cut-it)      ;; no bowl
                                        (map fry-it))     ;; no bowl
4 intermediate bowls 📦📦📦📦          ingredients)
                                      0 intermediate bowls 🎉
```

The code is almost identical! The only changes:
1. Wrap steps in `(comp ...)` instead of threading
2. Use `(into [] ...)` or `(transduce ...)` to apply
3. Each `(map f)` and `(filter p)` uses 1 argument — no collection

### 🍔 Bonus: Multiple dishes from the same ingredients

```clojure
;; Define different recipes (transducers):
(def xf-fries   (comp (filter #(= "potato" (:name %))) (map (fn [_] "🍟"))))
(def xf-burgers (comp (filter #(= "cow" (:name %)))    (map (fn [_] "🍔"))))
(def xf-popcorn (comp (filter #(= "corn" (:name %)))   (map (fn [_] "🍿"))))

;; Same ingredients, 3 different recipes:
(into [] xf-fries   kitchen-ingredients) ;; => [🍟 🍟 🍟 🍟]
(into [] xf-burgers kitchen-ingredients) ;; => [🍔 🍔]
(into [] xf-popcorn kitchen-ingredients) ;; => [🍿 🍿]

;; Define ONCE, apply ANYWHERE. 🎯
```

---

## 🚚🚲🏪 Multiple Sources — Same recipe, different deliveries

Our restaurant is growing! Ingredients now arrive from:

- **🚚 The Truck (Kafka consumer)** — Big bulk deliveries, crates of ingredients in batches
- **🚲 The Bike (REST API)** — Small orders, one or two ingredients at a time
- **🏪 The Local Market (Database query)** — We go pick up exactly what we need

The KITCHEN (our processing logic) should NOT CARE where the ingredients come from. The recipe is the same!

```clojure
;; The ONE recipe — defined ONCE
(def xf-make-fries
  (comp
    (filter potato?)
    (map peel-it)
    (map cut-it)
    (map fry-it)))
```

### 🚚 Source 1: The Truck (Kafka consumer) — bulk delivery

```clojure
(def truck-crate-1
  [{:item "🥔" :name "potato" :source "truck"}
   {:item "🥔" :name "potato" :source "truck"}
   {:item "🐄" :name "cow"    :source "truck"}
   {:item "🥔" :name "potato" :source "truck"}
   {:item "🌽" :name "corn"   :source "truck"}])

(def truck-crate-2
  [{:item "🐄" :name "cow"    :source "truck"}
   {:item "🥔" :name "potato" :source "truck"}
   {:item "🥔" :name "potato" :source "truck"}
   {:item "🌽" :name "corn"   :source "truck"}
   {:item "🥔" :name "potato" :source "truck"}])

;; Same recipe, different crates:
(mapv :item (into [] xf-make-fries truck-crate-1)) ;; => [🍟 🍟 🍟]
(mapv :item (into [] xf-make-fries truck-crate-2)) ;; => [🍟 🍟 🍟]
```

### 🚲 Source 2: The Bike (REST API) — small deliveries

```clojure
(def bike-delivery-1 [{:item "🥔" :name "potato" :source "bike"}])
(def bike-delivery-2 [{:item "🐄" :name "cow"    :source "bike"}])
(def bike-delivery-3 [{:item "🥔" :name "potato" :source "bike"}
                       {:item "🌽" :name "corn"   :source "bike"}])

(mapv :item (into [] xf-make-fries bike-delivery-1)) ;; => [🍟]
(mapv :item (into [] xf-make-fries bike-delivery-2)) ;; => [] (cow filtered out!)
(mapv :item (into [] xf-make-fries bike-delivery-3)) ;; => [🍟] (only potato passed)
```

### 🏪 Source 3: Local Market (Database query)

```clojure
(def market-inventory
  [{:item "🥔" :name "potato" :source "market" :price 5}
   {:item "🐄" :name "cow"    :source "market" :price 500}
   {:item "🌽" :name "corn"   :source "market" :price 8}
   {:item "🥔" :name "potato" :source "market" :price 4}
   {:item "🥔" :name "potato" :source "market" :price 6}])

;; Same recipe:
(mapv :item (into [] xf-make-fries market-inventory)) ;; => [🍟 🍟 🍟]

;; We can also aggregate — how much did the potatoes cost?
(transduce
  (comp (filter potato?) (map :price))
  + 0
  market-inventory)
;; => 15
```

### 🔄 All sources mixed together

```clojure
;; In a real system, events arrive from ALL sources mixed:
(def event-stream
  [{:item "🥔" :name "potato" :source "🚚 truck"  :time "10:00"}
   {:item "🐄" :name "cow"    :source "🚚 truck"  :time "10:00"}
   {:item "🥔" :name "potato" :source "🚚 truck"  :time "10:00"}
   {:item "🥔" :name "potato" :source "🚲 bike"   :time "10:01"}
   {:item "🌽" :name "corn"   :source "🚲 bike"   :time "10:02"}
   {:item "🥔" :name "potato" :source "🏪 market" :time "10:03"}
   {:item "🐄" :name "cow"    :source "🏪 market" :time "10:03"}
   {:item "🌽" :name "corn"   :source "🚚 truck"  :time "10:04"}
   {:item "🥔" :name "potato" :source "🚚 truck"  :time "10:04"}
   {:item "🥔" :name "potato" :source "🚲 bike"   :time "10:05"}])

;; The transducer doesn't care about the source:
(into [] xf-make-fries event-stream)
;; => 6 fries [🍟 🍟 🍟 🍟 🍟 🍟]

;; How many fries per source?
(transduce
  (filter potato?)
  (fn ([] {}) ([r] r) ([acc item] (update acc (:source item) (fnil inc 0))))
  event-stream)
;; => {"🚚 truck" 3, "🚲 bike" 2, "🏪 market" 1}
```

### 🌉 Bridge to payments

```
🍳 Kitchen                💳 Payments
────────────────────────  ──────────────────────────────────
🥔🐄🌽 Ingredients        Transactions (SPEI, credit, debit)
📋 Recipe (xf-make-fries)  Pipeline (xf-process-payments)
🚚 Truck (Kafka)           Kafka consumer (bulk transactions)
🚲 Bike (REST API)         REST endpoint (single payments)
🏪 Market (DB query)       Database reconciliation batch
(filter potato?)           (filter #(= :spei (:method %)))
(map peel-it)              (map :amount)
(map fry-it)               (map #(* % 1.16))
🍟 Fries                   Processed payment amounts
```

```clojure
;; Same pattern with real payment data:
(def xf-spei-revenue
  (comp
    (filter #(= :spei (:method %)))
    (filter #(= :approved (:status %)))
    (map :amount)
    (map #(* % 1.16))))

;; Apply to ANY source — the transducer doesn't care:
(transduce xf-spei-revenue + 0 kafka-batch)    ;; 🚚 Kafka
(transduce xf-spei-revenue + 0 api-payment)    ;; 🚲 REST API
(transduce xf-spei-revenue + 0 db-results)     ;; 🏪 Database
(into []   xf-spei-revenue   future-source)    ;; 🆕 Any new source!
```

### 🎯 Key takeaway — The 3-way decoupling

```
Transducers DECOUPLE three things:

┌─────────────┬──────────────────────────────────────────┐
│ WHAT to do  │ The transducer (comp filter map map ...) │
│             │ Defined ONCE, reused everywhere          │
├─────────────┼──────────────────────────────────────────┤
│ WHERE from  │ The source (Kafka, REST, DB, file, ...)  │
│             │ The transducer doesn't know or care      │
├─────────────┼──────────────────────────────────────────┤
│ WHERE to    │ The destination (reduce, vector, set...) │
│             │ transduce, into, sequence, eduction      │
└─────────────┴──────────────────────────────────────────┘

🍳 In kitchen terms:
   WHAT:  the recipe (filter+peel+cut+fry)
   FROM:  🚚 truck, 🚲 bike, 🏪 market
   TO:    🍽️ plate (into []), 🧮 count (transduce +)
```

> 🎓 **Transition:** *"Now you have the intuition — transducers are reusable recipe cards that work on any data source with zero intermediate bowls. Let's formalize this and see why your 2-million-record batch job runs out of memory..."*

---

# PART 1 — CONTENT PRESENTATION

---

## Part 0 — Recap & Motivation (10 min)

### The provocation

> 🎓 **SOCRATIC:** *"Your payment reconciliation job processes 2 million transactions every night. The pipeline filters by method, maps to extract amounts, filters again by threshold, and reduces to compute totals. It works but keeps running out of memory. Why?"*

```clojure
;; This creates 4 intermediate collections:
(->> transactions
     (filter #(= :spei (:method %)))          ;; collection #1
     (map :amount)                             ;; collection #2
     (filter #(> % 1000))                      ;; collection #3
     (map #(* % 1.16))                         ;; collection #4
     (reduce + 0))                             ;; final result
```

Each step creates a new lazy sequence. With 2M records and 4 steps, you have 4 lazy sequences being realized in memory.

> *"What if you could fuse all these steps into a SINGLE pass over the data?"*
> → That's exactly what transducers do.

---

## 1.1 — The Problem: Intermediate Collections (15 min)

### Visualizing the seq pipeline

```
Transaction 1 ─→ filter → ✓ → map → 1200 → filter → ✓ → map → 1392
Transaction 2 ─→ filter → ✗ (dropped)
Transaction 3 ─→ filter → ✓ → map → 500  → filter → ✗ (dropped)
Transaction 4 ─→ filter → ✓ → map → 2000 → filter → ✓ → map → 2320

With sequences, each step creates a full intermediate collection:

  Step 1 (filter): [tx1, tx3, tx4]              ← allocates
  Step 2 (map):    [1200, 500, 2000]             ← allocates
  Step 3 (filter): [1200, 2000]                  ← allocates
  Step 4 (map):    [1392, 2320]                  ← allocates
  Step 5 (reduce): 3712                          ← final value
```

### The cost

```
With sequences (N elements, M steps):
  - M intermediate collections created
  - Each element passes through M function calls with seq overhead
  - Lazy realization adds per-element overhead

With transducers (N elements, M steps):
  - ZERO intermediate collections
  - Each element passes through a composed function (one call stack)
  - Direct function calls, no seq overhead
```

### Quick comparison

```clojure
(def payments (vec (repeatedly 1000000
                     #(hash-map :method (rand-nth [:spei :credit-card :debit-card])
                                :amount (+ 100 (rand-int 50000))
                                :currency :MXN))))

;; Sequence version:
(time
  (->> payments
       (filter #(= :spei (:method %)))
       (map :amount)
       (filter #(> % 1000))
       (reduce + 0)))
;; ~200-400ms

;; Transducer version (preview — we'll explain the syntax next):
(time
  (transduce
    (comp (filter #(= :spei (:method %)))
          (map :amount)
          (filter #(> % 1000)))
    + 0 payments))
;; ~100-200ms (typically 1.5-3x faster)
```

> 🎓 **SOCRATIC:** *"The transducer version is faster. But look at the code — it's almost identical. What changed?"*
> → The `map` and `filter` are called with ONE argument (no collection). They return transducers — transformation recipes — that get composed and applied in a single pass.

---

## 1.2 — What Are Transducers? (20 min)

### The key insight

In Clojure, `map`, `filter`, `take`, etc. have two arities:

```clojure
;; 2-arity: takes a function AND a collection → returns a sequence
(map inc [1 2 3])
;; => (2 3 4)

;; 1-arity: takes ONLY a function → returns a TRANSDUCER
(map inc)
;; => a transducer (a function that transforms a reducing function)
```

A transducer is a **transformation recipe** decoupled from any data source or destination.

### Building transducers

```clojure
;; These are all transducers (1-arity calls):
(def xf-spei    (filter #(= :spei (:method %))))
(def xf-amount  (map :amount))
(def xf-large   (filter #(> % 1000)))
(def xf-with-iva (map #(* % 1.16)))

;; Each one is a function — not a collection, not a lazy seq
(type xf-spei)
;; => clojure.core$filter$fn__XXXX (a function)
```

### Composing transducers with `comp`

```clojure
;; comp connects transducers into a pipeline:
(def xf-pipeline
  (comp
    (filter #(= :spei (:method %)))     ;; step 1
    (map :amount)                        ;; step 2
    (filter #(> % 1000))                 ;; step 3
    (map #(* % 1.16))))                  ;; step 4
```

> 🎓 **SOCRATIC:** *"`comp` normally runs right-to-left: `(comp f g)` means `f(g(x))`. But transducers in comp run LEFT-to-RIGHT. Why?"*
>
> → It's a consequence of how transducers wrap reducing functions. Each transducer wraps the next one, so `comp` naturally gives you left-to-right order. **Read transducer comps top-to-bottom, like a pipeline.**

### The mental model

```
Traditional seq pipeline:
  collection →[filter]→ new-seq →[map]→ new-seq →[filter]→ new-seq →[reduce]→ result

Transducer pipeline:
  collection → [filter∘map∘filter composed into one step] → reduce → result

The transducer fuses all steps into the reducing function itself.
Each element goes through all steps before the next element is processed.
```

### Under the hood (simplified)

A transducer is a function that takes a reducing function and returns a new reducing function:

```clojure
;; Conceptually (simplified):
;; (filter pred) returns:
;;   (fn [rf]              ;; rf = the "next step" reducing function
;;     (fn [result input]  ;; the new reducing function
;;       (if (pred input)
;;         (rf result input)    ;; passes to next step
;;         result)))            ;; skips this element

;; When you comp them, you stack:
;; (comp (filter p1) (map f) (filter p2))
;; becomes roughly:
;; (fn [rf]
;;   (fn [result input]
;;     (if (p1 input)                          ;; filter step 1
;;       (let [mapped (f input)]               ;; map step 2
;;         (if (p2 mapped)                     ;; filter step 3
;;           (rf result mapped)                ;; pass to reduce
;;           result))
;;       result)))
```

> 🎓 **SOCRATIC:** *"A transducer transforms a reducing function. What's a reducing function?"*
> → It's any function that takes an accumulator and an input and returns a new accumulator.
> `+` is a reducing function: `(+ acc input)`. `conj` is a reducing function: `(conj acc input)`.

---

## 1.3 — Applying Transducers (20 min)

### The four ways to apply a transducer

#### 1. `transduce` — apply transducer with a reducing function

```clojure
;; transduce xf f init coll
(transduce
  (comp (filter #(= :spei (:method %)))
        (map :amount))
  +        ;; reducing function
  0        ;; initial value
  payments)
;; => sum of all SPEI amounts
```

```clojure
;; Count high-value SPEI payments:
(transduce
  (comp (filter #(= :spei (:method %)))
        (filter #(> (:amount %) 10000)))
  (fn
    ([] 0)              ;; 0-arity: init value
    ([result] result)   ;; 1-arity: completion
    ([result _input]    ;; 2-arity: step
     (inc result)))
  payments)
```

> **Note:** `transduce` calls the reducing function's 0-arity for init (if you don't pass `init`), and 1-arity for completion. The built-in functions like `+` and `conj` already have these arities.

#### 2. `into` — apply transducer and collect into a target collection

```clojure
;; Collect SPEI amounts into a vector:
(into []
  (comp (filter #(= :spei (:method %)))
        (map :amount))
  payments)
;; => [1200 500 2000 ...]

;; Collect into a set (automatic deduplication):
(into #{}
  (map :method)
  payments)
;; => #{:spei :credit-card :debit-card}

;; Collect into a map (build a lookup table):
(into {}
  (map (fn [p] [(:id p) p]))
  payments)
```

#### 3. `sequence` — apply transducer lazily

```clojure
;; Returns a lazy sequence (like the traditional pipeline, but single-pass):
(def lazy-amounts
  (sequence
    (comp (filter #(= :spei (:method %)))
          (map :amount))
    payments))

(take 5 lazy-amounts)
;; => (1200 2500 800 15000 350)
```

> 🎓 **SOCRATIC:** *"Wait — `sequence` is lazy. Doesn't that bring back the same overhead as regular sequences?"*
> → The laziness is only at the boundary (when you consume elements). The transducer steps inside are still fused — no intermediate collections between `filter` and `map`.

#### 4. `eduction` — reusable, on-demand transformation

```clojure
;; eduction creates a reducible/iterable view — not realized until consumed
(def spei-amounts
  (eduction
    (comp (filter #(= :spei (:method %)))
          (map :amount))
    payments))

;; Every time you reduce it, it re-processes from scratch:
(reduce + 0 spei-amounts)
(reduce max 0 spei-amounts)
(into [] spei-amounts)
```

### Choosing the right application

```
Function     Returns           Eager/Lazy    Use when
───────────  ────────────────  ────────────  ─────────────────────────────────
transduce    Single value      Eager         Aggregation (sum, count, max, etc.)
into         Collection        Eager         Building a result collection
sequence     Lazy seq          Lazy          Large/infinite data, streaming
eduction     Reducible view    Deferred      Reusable pipelines, multiple consumers
```

### Transducers that take/drop

```clojure
;; take and drop work as transducers:
(into []
  (comp (filter #(= :spei (:method %)))
        (take 5))
  payments)
;; => first 5 SPEI payments (stops processing after 5!)

;; This is MORE efficient than the seq version because it short-circuits:
;; The transducer signals "reduced" after 5 elements — no more processing.

(into []
  (comp (map :amount)
        (drop 100)
        (take 10))
  payments)
;; => amounts 101-110
```

### Stateful transducers

Some transducers carry internal state:

```clojure
;; dedupe — removes consecutive duplicates (stateful)
(into []
  (dedupe)
  [:spei :spei :debit-card :debit-card :spei])
;; => [:spei :debit-card :spei]

;; partition-all — groups elements (stateful)
(into []
  (comp (filter #(= :spei (:method %)))
        (partition-all 3))
  payments)
;; => [[p1 p2 p3] [p4 p5 p6] ...]  (groups of 3)
```

---

# PART 2 — DEMONSTRATIONS

---

## 2.1 — Payment Batch Processing (15 min)

> **Format:** Teacher demonstrates refactoring a seq pipeline to transducers, step by step in the REPL.

### The scenario

We have a batch of 100,000 payment records and a seq pipeline that computes total approved SPEI revenue with IVA. Let's refactor it live.

### Generate sample data

```clojure
(def sample-payments
  (vec (repeatedly 100000
         (fn []
           {:id     (str "pay-" (random-uuid))
            :method (rand-nth [:spei :credit-card :debit-card])
            :amount (+ 50 (rand-int 50000))
            :status (rand-nth [:pending :approved :rejected])
            :currency :MXN}))))

(count sample-payments)
;; => 100000
(frequencies (map :method sample-payments))
;; => {:spei ~33333, :credit-card ~33333, :debit-card ~33334}
```

### The current seq pipeline

```clojure
(defn total-spei-revenue-seq [payments]
  (->> payments
       (filter #(= :spei (:method %)))       ;; keep SPEI only
       (filter #(= :approved (:status %)))   ;; keep approved only
       (map :amount)                          ;; extract amount
       (map #(* % 1.16))                      ;; add IVA (16%)
       (reduce +)))                           ;; sum

;; This pipeline creates 4 intermediate lazy sequences:
;;   1. (filter :spei)     → lazy seq
;;   2. (filter :approved) → lazy seq
;;   3. (map :amount)      → lazy seq
;;   4. (map * 1.16)       → lazy seq
;;   5. (reduce +)         → final number

(time (total-spei-revenue-seq sample-payments))
;; "Elapsed time: ~15ms"
```

### Refactoring to transducers

The refactoring recipe:
1. Take each step BEFORE the reduce — each one is already a transducer when called with 1 arg
2. Wrap them in `(comp ...)`
3. The `reduce` becomes the reducing function in `transduce`

```
SEQ version:                 TRANSDUCER version:
─────────────────────        ─────────────────────
(->> payments                (transduce
  (filter spei?)               (comp
  (filter approved?)              (filter spei?)
  (map :amount)                   (filter approved?)
  (map * 1.16)                    (map :amount)
  (reduce +))                     (map * 1.16))
                                + 0
                                payments)
```

```clojure
(defn total-spei-revenue-xf [payments]
  (transduce
    (comp
      (filter #(= :spei (:method %)))
      (filter #(= :approved (:status %)))
      (map :amount)
      (map #(* % 1.16)))
    + 0
    payments))

(time (total-spei-revenue-xf sample-payments))
;; "Elapsed time: ~8ms"
```

### Verify and benchmark

```clojure
;; Same result?
(= (total-spei-revenue-seq sample-payments)
   (total-spei-revenue-xf sample-payments))
;; => true

;; Benchmark — run 5 times for stable numbers:
(dotimes [i 5]
  (let [t0 (System/nanoTime)
        _  (total-spei-revenue-seq sample-payments)
        t1 (System/nanoTime)
        _  (total-spei-revenue-xf sample-payments)
        t2 (System/nanoTime)
        ms-seq (/ (- t1 t0) 1e6)
        ms-xf  (/ (- t2 t1) 1e6)]
    (println (format "Run %d — SEQ: %.1fms | XF: %.1fms | speedup: %.1fx"
                     (inc i) ms-seq ms-xf (/ ms-seq ms-xf)))))
;; Run 1 — SEQ: 11.4ms | XF: 10.6ms | speedup: 1.1x
;; Run 2 — SEQ: 15.6ms | XF:  9.9ms | speedup: 1.6x
;; ...
```

### Extract the transducer for reuse

The best part — define the transducer ONCE, use it four different ways:

```clojure
(def xf-approved-spei-with-iva
  (comp
    (filter #(= :spei (:method %)))
    (filter #(= :approved (:status %)))
    (map :amount)
    (map #(* % 1.16))))

;; USE 1: Total revenue
(transduce xf-approved-spei-with-iva + 0 sample-payments)
;; => 3.24e8

;; USE 2: Maximum single payment with IVA
(transduce xf-approved-spei-with-iva max 0 sample-payments)
;; => ~58000

;; USE 3: Collect all amounts into a vector
(count (into [] xf-approved-spei-with-iva sample-payments))
;; => ~11100

;; USE 4: Count payments (custom reducing function)
(transduce xf-approved-spei-with-iva
  (fn ([] 0) ([r] r) ([r _] (inc r)))
  sample-payments)
;; => ~11100
```

The SAME transducer for sum, max, collect, and count. With the seq version you'd copy-paste the filter/map chain each time.

---

## 2.2 — Custom Transducer: batch-by-method (15 min)

> **Format:** Teacher builds a stateful transducer live, explaining each piece.

### The goal

Build a `batch-by-method` transducer that groups **consecutive** payments by method, emitting a batch summary when the method changes:

```clojure
;; Input:
[{:method :spei       :amount 100}
 {:method :spei       :amount 200}
 {:method :debit-card :amount 50}
 {:method :debit-card :amount 75}
 {:method :spei       :amount 300}]

;; Output:
[{:method :spei       :payments [{...} {...}] :total 300}
 {:method :debit-card :payments [{...} {...}] :total 125}
 {:method :spei       :payments [{...}]       :total 300}]
```

### First: understanding volatiles

Stateful transducers use `volatile!` — mutable boxes optimized for single-threaded use:

```clojure
(def my-box (volatile! 0))
@my-box                     ;; => 0  (read)
(vreset! my-box 42)         ;; set to 42
@my-box                     ;; => 42
(vswap! my-box inc)         ;; apply inc
@my-box                     ;; => 43
(vswap! my-box + 10)        ;; apply (+ current 10)
@my-box                     ;; => 53
;; volatile! = create, @v = read, vreset! = set, vswap! = update
```

### The structure of a custom transducer

```clojure
(defn my-transducer []
  (fn [rf]                       ;; rf = the "next step" reducing function
    (let [state (volatile! ...)] ;; mutable state lives here
      (fn
        ([] (rf))                ;; 0-arity: init — just delegate
        ([result]                ;; 1-arity: completion — flush state, then delegate
         ...)
        ([result input]          ;; 2-arity: step — the main logic per element
         ...)))))
```

For `batch-by-method` we need two pieces of state:
- `batch` — the current group being accumulated (a vector)
- `current-method` — the method of the current group

### The logic

```
For each element:
  IF element's method == current-method:
    → add it to the batch, continue
  ELSE (method changed!):
    → emit the completed batch as a summary
    → start a new batch with this element
    → update current-method

At completion (1-arity):
  → emit whatever is left in the batch (the last group)
```

### Full implementation

```clojure
(defn batch-by-method []
  (fn [rf]
    (let [batch (volatile! [])
          current-method (volatile! nil)]
      (fn
        ([] (rf))
        ([result]
         (let [final (if (seq @batch)
                       (rf result {:method   @current-method
                                   :payments @batch
                                   :total    (reduce + (map :amount @batch))})
                       result)]
           (rf final)))
        ([result input]
         (let [m (:method input)]
           (if (= m @current-method)
             ;; SAME method → add to current batch
             (do (vswap! batch conj input) result)
             ;; DIFFERENT method → emit old batch, start new one
             (let [prev-result (if (seq @batch)
                                 (rf result {:method   @current-method
                                             :payments @batch
                                             :total    (reduce + (map :amount @batch))})
                                 result)]
               (vreset! batch [input])
               (vreset! current-method m)
               prev-result))))))))
```

### Tracing it step by step

```
Input: [{:spei 100} {:spei 200} {:debit 50} {:debit 75} {:spei 300}]

#1 {:spei 100}  current=nil, method=:spei  → DIFFERENT → batch empty, nothing to emit
                 batch=[{:spei 100}], current=:spei

#2 {:spei 200}  current=:spei, method=:spei → SAME → batch=[{:spei 100} {:spei 200}]

#3 {:debit 50}  current=:spei, method=:debit → DIFFERENT!
                 EMIT {:method :spei, :total 300}
                 batch=[{:debit 50}], current=:debit

#4 {:debit 75}  current=:debit, method=:debit → SAME → batch=[{:debit 50} {:debit 75}]

#5 {:spei 300}  current=:debit, method=:spei → DIFFERENT!
                 EMIT {:method :debit, :total 125}
                 batch=[{:spei 300}], current=:spei

COMPLETION: batch has [{:spei 300}] → EMIT {:method :spei, :total 300}
```

### Running it

```clojure
(into []
  (batch-by-method)
  [{:method :spei       :amount 100}
   {:method :spei       :amount 200}
   {:method :debit-card :amount 50}
   {:method :debit-card :amount 75}
   {:method :spei       :amount 300}])
;; => [{:method :spei,       :payments [...], :total 300}
;;     {:method :debit-card, :payments [...], :total 125}
;;     {:method :spei,       :payments [...], :total 300}]
```

### Composing with other transducers

Because it's a transducer, it composes like any built-in:

```clojure
;; Batch by method, then keep only batches with total > 200:
(into []
  (comp (batch-by-method)
        (filter #(> (:total %) 200)))
  [{:method :spei       :amount 100}
   {:method :spei       :amount 200}
   {:method :debit-card :amount 50}
   {:method :debit-card :amount 75}
   {:method :spei       :amount 300}])
;; => [{:method :spei, :total 300}
;;     {:method :spei, :total 300}]
;; The :debit-card batch (total=125) was filtered out!
```

---

## 2.3 — Performance Comparison (15 min)

> **Format:** Teacher demonstrates three approaches to the same problem, benchmarks them, and discusses trade-offs.

### The task

Given 500,000 payments, compute a per-method summary report:

```clojure
{:spei        {:count N :total-amount X :avg-amount Y}
 :credit-card {:count N :total-amount X :avg-amount Y}
 :debit-card  {:count N :total-amount X :avg-amount Y}}
```

### Generate data

```clojure
(def big-payments
  (vec (repeatedly 500000
         (fn [] {:method (rand-nth [:spei :credit-card :debit-card])
                 :amount (+ 50 (rand-int 50000))}))))
```

### Approach 1: Sequence pipeline (group-by + map)

Uses `group-by` to split into sub-collections, then processes each group.

**Trade-off:** Simple to read, but `group-by` allocates sub-vectors for each group.

```clojure
(defn report-seq [payments]
  (->> payments
       (group-by :method)
       (map (fn [[method ps]]
              [method {:count        (count ps)
                       :total-amount (reduce + (map :amount ps))
                       :avg-amount   (/ (reduce + (map :amount ps))
                                        (count ps))}]))
       (into {})))
```

### Approach 2: Single reduce

One pass through all payments, accumulating count and total per method.

**Trade-off:** Single pass (good), but `update-in` with `fnil` on every element creates overhead.

```clojure
(defn report-reduce [payments]
  (let [result (reduce
                 (fn [acc {:keys [method amount]}]
                   (-> acc
                       (update-in [method :count] (fnil inc 0))
                       (update-in [method :total-amount] (fnil + 0) amount)))
                 {}
                 payments)]
    (into {}
      (map (fn [[method {:keys [count total-amount]}]]
             [method {:count count
                      :total-amount total-amount
                      :avg-amount (/ total-amount count)}]))
      result)))
```

### Approach 3: Transducer with custom reducing function

Separates the transformation (map to extract pairs) from the accumulation (custom reducing fn). The completion arity computes averages at the end.

**Trade-off:** Clean separation of concerns, but same `update-in` cost per element.

```clojure
(defn report-xf [payments]
  (transduce
    ;; Transducer: extract just what we need
    (map (fn [{:keys [method amount]}]
           [method amount]))
    ;; Custom reducing function with 3 arities
    (fn
      ([] {})                    ;; 0-arity: init
      ([result]                  ;; 1-arity: completion — compute averages
       (into {}
         (map (fn [[m {:keys [count total]}]]
                [m {:count        count
                    :total-amount total
                    :avg-amount   (/ total count)}]))
         result))
      ([acc [method amount]]     ;; 2-arity: step — accumulate
       (-> acc
           (update-in [method :count] (fnil inc 0))
           (update-in [method :total] (fnil + 0) amount))))
    payments))
```

### Benchmark

```clojure
;; Warm up JIT first
(dotimes [_ 3]
  (report-seq big-payments)
  (report-reduce big-payments)
  (report-xf big-payments))

;; Timed runs
(dotimes [i 5]
  (let [t0 (System/nanoTime) _ (report-seq big-payments)
        t1 (System/nanoTime) _ (report-reduce big-payments)
        t2 (System/nanoTime) _ (report-xf big-payments)
        t3 (System/nanoTime)]
    (println (format "Run %d | Seq: %5.1fms | Reduce: %5.1fms | XF: %5.1fms"
                     (inc i)
                     (/ (- t1 t0) 1e6)
                     (/ (- t2 t1) 1e6)
                     (/ (- t3 t2) 1e6)))))
;; Run 1 | Seq:  82.9ms | Reduce: 147.4ms | XF: 161.5ms
;; Run 2 | Seq:  85.5ms | Reduce: 146.8ms | XF: 159.8ms
;; ...
```

### Discussion

`group-by` is fastest here — surprising! Why?

- `group-by` uses **transient maps** internally — very fast accumulation
- After grouping, we only iterate **3 groups** (one per method)
- The `reduce` and transducer approaches call `update-in` with `fnil` on **every element** (500K times), creating per-element map allocation overhead

**Where transducers win:** filter/map/take pipelines (eliminating intermediate sequences). Let's prove it:

```clojure
;; Filter+map pipeline on the same 500K payments:
;; "Get all SPEI amounts > 10000, multiply by 1.16, sum"

;; Seq version:
(time (->> big-payments
           (filter #(= :spei (:method %)))
           (map :amount)
           (filter #(> % 10000))
           (map #(* % 1.16))
           (reduce + 0)))
;; ~25ms

;; Transducer version:
(time (transduce
        (comp (filter #(= :spei (:method %)))
              (map :amount)
              (filter #(> % 10000))
              (map #(* % 1.16)))
        + 0 big-payments))
;; ~20ms (consistently faster)
```

**Key takeaway:** Always measure! Transducers shine on multi-step filter/map pipelines. For group-by aggregation, the built-in `group-by` with transients is already well-optimized. The right tool depends on the problem shape.

---

## Wrap-up & Key Takeaways (5 min)

### When to use transducers

```
Scenario                                         Use transducers?
──────────────────────────────────────────────  ────────────────────
Small collection, simple pipeline               Not necessary — seq is fine
Large collection, multi-step transformation     Yes — avoid intermediate allocs
Reusable transformation across contexts          Yes — same xf for reduce/into/chan
Performance-critical hot path                   Yes
Simple map or filter on its own                 No — just use regular seq version
```

### Summary

1. **Transducers** = composable transformation recipes, decoupled from data source/destination
2. **1-arity** `(map f)`, `(filter pred)` returns a transducer, not a sequence
3. **`comp`** composes transducers **left-to-right** (opposite of regular function composition)
4. **Apply with:** `transduce` (reduce), `into` (collect), `sequence` (lazy), `eduction` (deferred)
5. **Benefits:** no intermediate collections, single-pass, reusable across contexts

> 🎓 **Tease for next class:** *"Transducers give us composable, efficient transformations. But what about coordinating multiple asynchronous processes? What if three payment sources produce events at different rates and you need a unified pipeline? That's core.async — channels, go blocks, and the CSP model — next class."*
