# Clojure Advanced Topics — Class 4 (2h)

## Refs and Software Transactional Memory (STM)

> **Prerequisite:** Classes 1–3 (Multimethods, Protocols, Macros).
> All examples continue with the Mexican payment rails (SPEI, credit card, debit card).

---

## Agenda

By the end of this class, students will be able to:

- Explain Clojure's state model: identity vs value
- Compare Atoms, Refs, and Agents and know when to use each
- Use Refs with `dosync` for coordinated, synchronous state changes
- Implement financial transactions with atomicity guarantees using STM
- Handle retry behavior and understand `commute` vs `alter`

## Timeline

```
 0:00 ┬─ Recap & Motivation ··························· 10 min
      │   "Transfer money between accounts — both or neither"
      │
 0:10 ├─ 1.1 — Clojure's State Model ·················· 20 min
      │   Identity, value, state, time
      │   Atoms & Compare-and-Swap (CAS) deep dive
      │   Atoms' limitations → why we need Refs
      │
 0:30 ├─ 1.2 — Refs and dosync ························ 20 min
      │   Creating Refs, alter, commute
      │   Transactions: ACI properties (not D)
      │   Retry behavior, conflict resolution
      │
 0:50 ├─ 1.3 — STM in Practice ························ 20 min
      │   Bank accounts: transfers, balance checks
      │   ensure — read consistency
      │   Validators and watches
      │
 1:10 ┬─── 5 min break ───
      │
 1:15 ├─ 1.4 — Payment Ledger with Double-Entry ······· 15 min
      │   Double-entry ledger built on Refs
      │
 1:30 ├─ 1.5 — Concurrent Transfers & STM Stress Test · 15 min
      │   Proving atomicity with many threads
      │
 1:45 ├─ 1.6 — Balance Invariants Under Concurrency ··· 10 min
      │   Validators + watches under concurrent load
      │
 1:55 ┬─ Wrap-up & Key Takeaways ······················  5 min
 2:00 ┴─ End
```

---

## Recap & Motivation (10 min)

### The provocation

> 🎓 **SOCRATIC:** *"Your payment system needs to transfer 1,000 MXN from Account A to Account B. This involves two steps: debit A, credit B. What happens if the system crashes between the debit and the credit?"*

- Account A lost 1,000 MXN
- Account B didn't receive anything
- Money has vanished from the system

> *"In Java, you'd use `synchronized` blocks or explicit locks. What's wrong with locks?"*
>
> → Deadlocks (A waits for B, B waits for A). Priority inversion. Forgotten unlock in exception paths. Locks don't compose — you can't easily combine two locked operations into a bigger one.

**Clojure's STM** (Software Transactional Memory) solves this:
- No locks
- Transactions are automatic (like database transactions)
- If there's a conflict, the transaction retries automatically
- Composable — you can combine transactions freely

---

## 1.1 — Clojure's State Model (15 min)

### Identity vs Value

```
Concept     What it is                              Example
──────────  ──────────────────────────────────────  ──────────────────────
Value       Immutable data. Never changes.           {:balance 1000, :owner "María"}
Identity    A stable reference to a succession       Account A
            of values over time
State       The value an identity has at a point      "At 10:15, Account A has $1000"
            in time
```

### Clojure's reference types — the big picture

```
Type    Coordination    Synchronous?    Use case
──────  ──────────────  ──────────────  ────────────────────────────────
Atom    Uncoordinated   Synchronous     Single independent value
Ref     Coordinated     Synchronous     Multiple values that change together
Agent   Uncoordinated   Asynchronous    Fire-and-forget updates
Var     Thread-local    Synchronous     Per-thread config (dynamic binding)
```

### Atoms and Compare-and-Swap (CAS)

> 📺 **Recommended viewing:** [Tim Baldridge — Concurrency in Clojure: Atoms](https://www.youtube.com/watch?v=I72WLDu05Tw)
> Tim demonstrates the race condition problem, builds CAS from scratch, and shows how `swap!` is just an abstraction over the compare-and-set loop.

Before introducing Refs, let's understand **why** Atoms work — and the fundamental primitive underneath them: **Compare-and-Swap (CAS)**.

#### The race condition problem

The danger is the **gap between reading and writing**. When multiple threads read a value, compute a new one, and write it back, updates get lost:

```clojure
(def a (atom 0))

;; 100 threads, each incrementing 1,000 times → expected: 100,000
(let [futures (doall
                (repeatedly 100
                  #(future (dotimes [_ 1000]
                             (reset! a (inc @a))))))]  ;; ← READ then WRITE: race!
  (doseq [f futures] @f))

@a
;; => ~43,000 (NOT 100,000!)
;; Threads overwrite each other's increments.
```

> 🎓 **SOCRATIC:** *"Why is `(reset! a (inc @a))` broken under concurrency?"*
> → Between `@a` (read) and `reset!` (write), another thread can change the value. Your write overwrites their update — it's lost forever.

#### `compare-and-set!` — the low-level primitive

CAS says: "Only write the new value **if the current value is still what I think it is**."

```clojure
(def a (atom 0))

(compare-and-set! a 0 1)   ;; => true  (was 0, set to 1)
(compare-and-set! a 0 2)   ;; => false (is 1, not 0 — someone changed it!)
@a ;; => 1
```

To make this work under concurrency, you **retry in a loop** until it succeeds:

```clojure
(def a (atom 0))

;; Manual CAS loop — retry until it works:
(let [futures (doall
                (repeatedly 100
                  #(future (dotimes [_ 1000]
                             (loop []
                               (let [old-val @a]
                                 (when-not (compare-and-set! a old-val (inc old-val))
                                   (recur))))))))]
  (doseq [f futures] @f))

@a
;; => 100,000 ✓ — every increment accounted for!
```

> The key insight: if `compare-and-set!` returns `false`, **another thread made progress**. The system always moves forward — no deadlocks possible.

#### `swap!` — CAS made ergonomic

This CAS-retry loop is so common that Clojure wraps it for you:

```clojure
;; swap! IS this loop. Conceptually:
(defn my-swap! [atom f & args]
  (loop []
    (let [old-val @atom
          new-val (apply f old-val args)]
      (if (compare-and-set! atom old-val new-val)
        new-val
        (recur)))))

;; So this:
(swap! counter inc)
;; is equivalent to the CAS retry loop above.
```

```clojure
(def a (atom 0))

(let [futures (doall
                (repeatedly 100
                  #(future (dotimes [_ 1000]
                             (swap! a inc)))))]
  (doseq [f futures] @f))

@a ;; => 100,000 ✓
```

#### CAS at the hardware level

CAS is not a software lock — it's a **CPU instruction** (e.g., `CMPXCHG` on x86):

```
1. Core locks a specific CACHE LINE (not a mutex — instantaneous hardware coordination)
2. Compares the value in memory with the expected value
3. If equal: writes the new value and releases the cache line
4. If not equal: releases the cache line, reports failure
```

This is faster than OS-level locks (no system call needed), and it's the primitive on which **all other concurrency mechanisms are built** — including traditional locks, Clojure's Atoms, and even the STM we're about to learn.

### The limitation of Atoms

```clojure
;; Two atoms — two accounts:
(def account-a (atom {:balance 1000}))
(def account-b (atom {:balance 500}))

;; Transfer 200 from A to B:
(swap! account-a update :balance - 200)
;; At this EXACT moment: A has 800, B has 500
;; Total in the system = 1300 instead of 1500!
;; If the system crashes here, money is LOST.
(swap! account-b update :balance + 200)
;; Now: A=800, B=700, total=1500 (correct again)
```

> 🎓 **SOCRATIC:** *"Between the two swaps, the system is in an inconsistent state. Can we make both changes happen atomically?"*
> → Not with Atoms. Each `swap!` is independent. There's no way to group two atom updates into one transaction.
> → This is exactly what Refs solve.

---

## 1.2 — Refs and dosync (25 min)

### Creating Refs

```clojure
;; Refs are created with `ref`:
(def account-a (ref {:owner "María" :balance 10000}))
(def account-b (ref {:owner "Carlos" :balance 5000}))

;; Read with @ or deref (same as atoms):
@account-a
;; => {:owner "María", :balance 10000}

(:balance @account-b)
;; => 5000
```

### `dosync` — the transaction boundary

All Ref changes MUST happen inside `dosync`:

```clojure
;; Transfer 2000 from A to B:
(dosync
  (alter account-a update :balance - 2000)
  (alter account-b update :balance + 2000))

@account-a ;; => {:owner "María", :balance 8000}
@account-b ;; => {:owner "Carlos", :balance 7000}
;; Total: 15000 — same as before. Consistent!
```

```clojure
;; What happens if you try without dosync?
(alter account-a update :balance - 100)
;; => IllegalStateException: No transaction running
;; Clojure REFUSES to modify a Ref outside a transaction.
```

> 🎓 **SOCRATIC:** *"Why does Clojure force you to use `dosync`?"*
> → It guarantees that Ref changes are always coordinated. You can't accidentally make a partial update.

### How STM works (conceptual)

```
1. dosync STARTS
2. Transaction takes a snapshot of all involved Refs
3. Changes happen on the snapshot (not the real values)
4. At COMMIT:
   a. Check: has any involved Ref been modified by another thread?
   b. If NO conflict → commit all changes atomically
   c. If CONFLICT → discard work, RETRY from step 1

This is called "optimistic concurrency" — assume no conflict,
check at commit, retry if wrong.
```

```
Thread 1:                    Thread 2:
(dosync                      (dosync
  (alter A - 100)              (alter A - 50)
  (alter B + 100))             (alter B + 50))

Timeline:
  T1: snapshot A=1000, B=500
  T2: snapshot A=1000, B=500
  T1: A→900, B→600 (in snapshot)
  T1: commit → A=900, B=600 ✓
  T2: tries to commit → A was changed! RETRY
  T2: new snapshot A=900, B=600
  T2: A→850, B→650 (in snapshot)
  T2: commit → A=850, B=650 ✓
```

### ACI properties (no D)

STM provides three of the four ACID properties:

```
Property       STM     Database
─────────────  ──────  ────────
Atomicity      ✓       ✓        All changes or none
Consistency    ✓       ✓        Validators enforce rules
Isolation      ✓       ✓        Transactions don't see each other's uncommitted work
Durability     ✗       ✓        STM is in-memory only (not persisted)
```

### `alter` vs `commute`

**`alter`** — strict ordering (default choice):

```clojure
(dosync
  (alter account-a update :balance - amount)
  (alter account-b update :balance + amount))
;; If another transaction changes A or B, this retries.
```

**`commute`** — relaxed ordering for commutative operations:

```clojure
;; Incrementing a counter — order doesn't matter:
(def tx-count (ref 0))

(dosync
  (commute tx-count inc))
;; `commute` may re-apply the function at commit time with the latest value.
;; Fewer retries, but only safe for commutative operations (inc, +, conj).
```

> 🎓 **SOCRATIC:** *"Why would `commute` cause fewer retries?"*
> → With `alter`, if another thread changed the Ref, the transaction must retry entirely.
> With `commute`, Clojure re-applies the function on the latest value at commit time.
> Since the operation is commutative (order doesn't matter), the result is correct.

### When to use `alter` vs `commute`

```
Operation                     alter or commute?
──────────────────────────    ─────────────────
Transfer between accounts     alter (order matters: debit THEN credit)
Increment a counter           commute (+ is commutative)
Append to a log               commute (conj is commutative relative to other conj's)
Set a specific value           alter (ref-set — not commutative)
```

---

## 1.3 — STM in Practice (20 min)

### Building a bank with Refs

```clojure
(defn make-account [owner initial-balance]
  (ref {:owner   owner
        :balance initial-balance
        :history []}
       :validator (fn [{:keys [balance]}]
                    (>= balance 0))))  ;; ← INVARIANT: balance never negative

(def accounts
  {:maria  (make-account "María García" 10000)
   :carlos (make-account "Carlos López" 5000)
   :ana    (make-account "Ana Martínez" 8000)})
```

### Validator — enforce invariants

```clojure
;; The :validator function is called on every change.
;; If it returns false, the transaction is ABORTED (not retried — aborted).

(dosync
  (alter (:maria accounts) update :balance - 50000))
;; => IllegalStateException: Invalid reference state
;; María only has 10000 — the validator rejected -40000 balance.
```

### Transfer function

```clojure
(defn transfer! [from-account to-account amount description]
  (dosync
    (let [from-balance (:balance @from-account)
          timestamp    (System/currentTimeMillis)]
      (when (< from-balance amount)
        (throw (ex-info "Insufficient funds"
                        {:available from-balance :requested amount})))
      (alter from-account
        (fn [acc]
          (-> acc
              (update :balance - amount)
              (update :history conj
                {:type :debit :amount amount :desc description :time timestamp}))))
      (alter to-account
        (fn [acc]
          (-> acc
              (update :balance + amount)
              (update :history conj
                {:type :credit :amount amount :desc description :time timestamp}))))
      {:status      :completed
       :from        (:owner @from-account)
       :to          (:owner @to-account)
       :amount      amount
       :description description})))
```

```clojure
(transfer! (:maria accounts) (:carlos accounts) 2000 "Dinner payment")
;; => {:status :completed, :from "María García", :to "Carlos López", ...}

(:balance @(:maria accounts))   ;; => 8000
(:balance @(:carlos accounts))  ;; => 7000
```

### `ensure` — read consistency

```clojure
;; Sometimes you READ a Ref in a transaction and need it to stay consistent.
;; `ensure` guarantees the Ref won't change during the transaction:

(defn total-balance [account-refs]
  (dosync
    (reduce + (map #(do (ensure %) (:balance @%)) account-refs))))

(total-balance (vals accounts))
;; => 23000 (always consistent — no partial updates visible)
```

> 🎓 **SOCRATIC:** *"Why do we need `ensure` for reading? Isn't the snapshot already consistent?"*
> → The snapshot is from the START of the transaction. If a Ref changes during a long-running transaction,
> the snapshot might be stale. `ensure` guarantees the value hasn't changed since the snapshot.
> For short transactions, it usually doesn't matter. For balance calculations where consistency is critical, use `ensure`.

### Watches — react to changes

```clojure
(add-watch (:maria accounts) :balance-alert
  (fn [key ref old-state new-state]
    (when (< (:balance new-state) 1000)
      (println "LOW BALANCE ALERT:" (:owner new-state)
               "has only $" (:balance new-state)))))

;; Transfer that triggers the alert:
(transfer! (:maria accounts) (:carlos accounts) 7500 "Large payment")
;; LOW BALANCE ALERT: María García has only $ 500
```

---

## 1.4 — Payment Ledger with Double-Entry (15 min)

Building on the bank example above, let's model a proper **double-entry ledger** — the accounting pattern that real payment systems use.

Every payment must create two entries:
1. **Debit** the customer's wallet
2. **Credit** the merchant's account

Plus a global transaction counter.

### Building it step by step

```clojure
;; Step 1: Create the state
(def customer-wallet (ref {:balance 5000 :transactions []}))
(def merchant-account (ref {:balance 0 :transactions []}))
(def tx-counter (ref 0))

;; Step 2: Payment function with double-entry
(defn pay-merchant! [amount method description]
  (dosync
    (let [tx-id (commute tx-counter inc)  ;; counter is commutative
          entry {:tx-id tx-id :amount amount :method method :desc description
                 :time (System/currentTimeMillis)}]
      ;; Debit customer
      (alter customer-wallet
        (fn [w]
          (-> w
              (update :balance - amount)
              (update :transactions conj (assoc entry :type :debit)))))
      ;; Credit merchant
      (alter merchant-account
        (fn [m]
          (-> m
              (update :balance + amount)
              (update :transactions conj (assoc entry :type :credit)))))
      {:tx-id tx-id :status :completed})))
```

### Verifying the double-entry invariant

```clojure
(pay-merchant! 500 :spei "Coffee subscription")
;; => {:tx-id 1, :status :completed}

(pay-merchant! 1200 :credit-card "Online course")
;; => {:tx-id 2, :status :completed}

;; Verify double-entry invariant:
(+ (:balance @customer-wallet)
   (:balance @merchant-account))
;; => 5000 (always equals the initial customer balance — money is conserved)

(:transactions @customer-wallet)
;; => [{:tx-id 1, :type :debit, :amount 500, ...}
;;     {:tx-id 2, :type :debit, :amount 1200, ...}]

(:transactions @merchant-account)
;; => [{:tx-id 1, :type :credit, :amount 500, ...}
;;     {:tx-id 2, :type :credit, :amount 1200, ...}]
```

---

## 1.5 — Concurrent Transfers & STM Stress Test (15 min)

Now let's prove that STM actually works under pressure. We create 4 accounts with 10,000 MXN each (40,000 total), launch 100 concurrent transfers of random amounts between random accounts, and verify that the total balance is **always** 40,000.

```clojure
(def stress-accounts
  (mapv #(ref {:id % :balance 10000}) (range 4)))

(defn random-transfer! []
  (let [from-idx (rand-int 4)
        to-idx   (loop [i (rand-int 4)]
                   (if (= i from-idx) (recur (rand-int 4)) i))
        amount   (inc (rand-int 500))]
    (try
      (dosync
        (let [from-balance (:balance @(stress-accounts from-idx))]
          (when (>= from-balance amount)
            (alter (stress-accounts from-idx) update :balance - amount)
            (alter (stress-accounts to-idx) update :balance + amount))))
      (catch Exception e
        ;; Insufficient funds — that's OK
        nil))))

;; Launch 100 concurrent transfers:
(let [futures (doall
                (repeatedly 100
                  #(future (random-transfer!))))]
  ;; Wait for all to complete:
  (doseq [f futures] @f)

  ;; Check total balance:
  (let [total (reduce + (map #(:balance @%) stress-accounts))]
    (println "Total balance:" total)
    (assert (= 40000 total) "INVARIANT VIOLATED!")
    (println "Individual balances:" (mapv #(:balance @%) stress-accounts))))

;; Total balance: 40000  ← ALWAYS 40000, guaranteed by STM
;; Individual balances: [8723 12451 9120 9706]  (varies each run)
```

> 🎓 **SOCRATIC:** *"Run it 10 times. Is the total EVER not 40,000?"*
> → Never. STM guarantees atomicity. Even with 100 concurrent transactions fighting over 4 accounts.

### Bonus: count retries

```clojure
(def retry-count (atom 0))

(defn random-transfer-counted! []
  (let [from-idx (rand-int 4)
        to-idx   (loop [i (rand-int 4)]
                   (if (= i from-idx) (recur (rand-int 4)) i))
        amount   (inc (rand-int 500))]
    (try
      (dosync
        (swap! retry-count inc)  ;; ← counts every attempt (including retries)
        (let [from-balance (:balance @(stress-accounts from-idx))]
          (when (>= from-balance amount)
            (alter (stress-accounts from-idx) update :balance - amount)
            (alter (stress-accounts to-idx) update :balance + amount))))
      (catch Exception _ nil))))

;; Reset and run:
(reset! retry-count 0)
(let [futures (doall (repeatedly 100 #(future (random-transfer-counted!))))]
  (doseq [f futures] @f)
  (println "Attempts (incl. retries):" @retry-count)
  (println "Transactions requested: 100"))
;; Attempts: ~110-150 (some transactions retried due to conflicts)
```

---

## 1.6 — Balance Invariants Under Concurrency (10 min)

This final example ties everything together: **validators**, **watches**, and **concurrent transactions** — all working in harmony.

We create accounts with two constraints:
1. **Validator:** balance must be >= 0 AND <= 100,000 (anti-fraud cap)
2. **Watch:** prints an alert when any account drops below 1,000

Then we run 50 concurrent transfers and verify nothing breaks.

```clojure
(def safe-accounts
  (mapv (fn [id]
          (ref {:id id :balance 10000}
               :validator (fn [{:keys [balance]}]
                            (and (>= balance 0) (<= balance 100000)))))
        (range 4)))

;; Add watches:
(doseq [acc safe-accounts]
  (add-watch acc :low-balance
    (fn [_ _ old new]
      (when (and (>= (:balance old) 1000)
                 (< (:balance new) 1000))
        (println "LOW BALANCE:" (:id new) "→ $" (:balance new))))))

;; Run concurrent transfers:
(let [futures (doall
                (repeatedly 50
                  #(future
                     (try
                       (let [from (rand-int 4)
                             to (loop [i (rand-int 4)]
                                  (if (= i from) (recur (rand-int 4)) i))]
                         (dosync
                           (let [amount (min (inc (rand-int 2000))
                                            (:balance @(safe-accounts from)))]
                             (when (pos? amount)
                               (alter (safe-accounts from) update :balance - amount)
                               (alter (safe-accounts to) update :balance + amount)))))
                       (catch Exception _ nil)))))]
  (doseq [f futures] @f)
  (println "Total:" (reduce + (map #(:balance @%) safe-accounts)))
  (println "Balances:" (mapv #(:balance @%) safe-accounts)))
```

---

## Wrap-up & Key Takeaways (5 min)

### When to use Refs vs Atoms

```
Scenario                                              Use
───────────────────────────────────────────────────   ─────────
Single counter, single config value                   Atom
Transfer between accounts (coordinated change)        Refs
Shopping cart + inventory (multiple related values)    Refs
Simple cache                                          Atom
Financial ledger (must be consistent)                 Refs
```

### Summary

1. **Refs** = coordinated, synchronous state via transactions
2. **`dosync`** = transaction boundary — all changes or none
3. **`alter`** = strict update — retries on conflict
4. **`commute`** = relaxed update for commutative operations — fewer retries
5. **`ensure`** = read consistency inside transactions
6. **Validators** = enforce invariants (balance >= 0)
7. **Watches** = react to state changes (alerts, logging)
8. **STM** = optimistic concurrency — no locks, no deadlocks, automatic retry

> **Next class:** *Refs are synchronous — the transaction blocks until complete. What if you want to fire-and-forget? Send an update and continue without waiting? That's Agents — asynchronous, independent state updates.*
