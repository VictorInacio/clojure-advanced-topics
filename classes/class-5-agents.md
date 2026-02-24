# Clojure Advanced Topics — Class 5 (2h)

## Agents and State Management Patterns

> **Prerequisite:** Class 4 — Refs and STM.
> All examples continue with the Mexican payment rails (SPEI, credit card, debit card).

---

## Agenda

By the end of this class, students will be able to:

- Use Agents for asynchronous, independent state updates
- Handle Agent errors with `agent-error`, `restart-agent`, and error handlers
- Use `send` vs `send-off` appropriately (CPU-bound vs I/O-bound)
- Compare all four Clojure reference types and choose the right one for each scenario
- Combine reference types in a real payment processing system

## Timeline

```
 0:00 ┬─ Part 0 — Recap & Motivation ·················· 10 min
      │   "Audit logs shouldn't slow down payments"
      │
 0:10 ┬─ PART 1 — CONTENT PRESENTATION ················
      │
 0:10 ├─ 1.1 — Agents: Async Independent State ········ 20 min
      │   send, send-off, await
      │   Agent thread pools
      │
 0:30 ├─ 1.2 — Error Handling with Agents ·············· 15 min
      │   agent-error, restart-agent, error-handler
      │   Error modes: :fail vs :continue
      │
 0:45 ├─ 1.3 — The Complete State Model ················ 15 min
      │   Atoms vs Refs vs Agents vs Vars
      │   Decision framework
      │   Combining reference types
      │
 1:00 ├─ 1.4 — Hands-on API Drill (individual) ········· 10 min
      │   Fill-in exercise: deref, swap!, reset!, alter,
      │   commute, ref-set, send, send-off, await
      │
 1:10 ┬─── 5 min break ───
      │
 1:15 ┬─ PART 2 — INTERACTIVE PRACTICE ················
      │
 1:15 ├─ 2.1 — Audit Log Agent (guided) ··············· 15 min
      │   Non-blocking audit trail for payments
      │
 1:30 ├─ 2.2 — Rate Limiter (pairs) ··················· 10 min
      │   Build a token bucket with Agents
      │
 1:40 ├─ 2.3 — Integrated System (individual) ·········· 15 min
      │   Payment system using all reference types
      │
 1:55 ┬─ Wrap-up & Key Takeaways ······················  5 min
 2:00 ┴─ End
```

---

# PART 1 — CONTENT PRESENTATION

---

## Part 0 — Recap & Motivation (10 min)

### Quick review

In Class 4 we learned:
- **Atoms**: uncoordinated, synchronous — `swap!` returns immediately with the new value
- **Refs + STM**: coordinated, synchronous — `dosync` commits all changes atomically

Both are **synchronous** — the caller waits for the state change to complete.

### The provocation

> 🎓 **SOCRATIC:** *"Every time a payment is processed, you need to: (1) write an audit log entry, (2) update analytics counters, (3) send a notification email. Should the payment response wait for ALL of these to finish?"*

→ No! The customer is waiting. The audit log, analytics, and email can happen **in the background**.

> *"But you still want ordering guarantees — log entries should appear in the order they were submitted. And if the email service throws an error, it shouldn't corrupt the audit log."*

→ You need **asynchronous, ordered, independent** state updates. That's exactly what **Agents** provide.

---

## 1.1 — Agents: Async Independent State (20 min)

### Creating and using Agents

```clojure
;; Create an Agent with an initial value:
(def audit-log (agent []))

;; Dereference to read (same as Atoms and Refs):
@audit-log
;; => []

;; Send an update function (asynchronous — returns immediately):
(send audit-log conj {:event :payment-authorized :time (System/currentTimeMillis)})
;; => #<Agent [...]>  (returns the agent, NOT the new value)

;; The update happens asynchronously:
@audit-log
;; => [{:event :payment-authorized, :time 1234567890}]
```

### `send` vs `send-off`

```clojure
;; send: uses a FIXED thread pool (CPU-bound work)
;;   Good for: computations, data transformations, in-memory updates
;;   Bad for: I/O (would block a pool thread)
(send audit-log conj {:event :payment-captured})

;; send-off: uses a GROWING thread pool (I/O-bound work)
;;   Good for: file writes, HTTP calls, database queries
;;   Bad for: nothing — but wastes threads on CPU-bound work
(send-off audit-log
  (fn [log]
    ;; Simulate writing to a file:
    (spit "/tmp/audit.log"
          (str (pr-str (last log)) "\n")
          :append true)
    (conj log {:event :written-to-disk})))
```

> 🎓 **SOCRATIC:** *"Why two different send functions? Why not just one?"*
> → `send` uses a fixed pool (size = CPU cores). If you do blocking I/O, you exhaust the pool and all agents stall.
> `send-off` creates new threads as needed — safe for I/O but wasteful for CPU work.

```
Function    Thread pool     Good for           Thread count
──────────  ──────────────  ─────────────────  ──────────────────
send        Fixed pool      CPU-bound work     cores + 2
send-off    Cached pool     I/O-bound work     Grows as needed
```

### `await` — wait for pending actions to complete

```clojure
;; Send several actions:
(send audit-log conj {:event :a})
(send audit-log conj {:event :b})
(send audit-log conj {:event :c})

;; Wait for all pending actions to complete:
(await audit-log)

;; Now guaranteed to have all three entries:
(count @audit-log)
;; => 3+ (at least a, b, c)
```

### Ordering guarantee

```clojure
;; All sends to the SAME agent are processed IN ORDER:
(def ordered-log (agent []))

(dotimes [i 10]
  (send ordered-log conj i))

(await ordered-log)
@ordered-log
;; => [0 1 2 3 4 5 6 7 8 9]  — always in order!

;; But sends to DIFFERENT agents are independent:
(def log-a (agent []))
(def log-b (agent []))

(send log-a conj :first-to-a)
(send log-b conj :first-to-b)
;; No ordering guarantee between log-a and log-b
```

> 🎓 **SOCRATIC:** *"How is this different from core.async channels?"*
> → Agents carry STATE (a value that changes over time). Channels carry MESSAGES (values that flow through).
> Agents: "update this stateful thing in the background."
> Channels: "pass this message from A to B."

### Validators on Agents

```clojure
(def balance-agent
  (agent {:balance 1000}
         :validator (fn [{:keys [balance]}] (>= balance 0))))

(send balance-agent update :balance - 500)
(await balance-agent)
@balance-agent ;; => {:balance 500}

(send balance-agent update :balance - 600)
;; Agent enters ERROR state (validator failed)
```

---

## 1.2 — Error Handling with Agents (15 min)

### The error state

When an Agent's action throws an exception, the Agent enters an error state:

```clojure
(def risky-agent (agent 0))

;; Send an action that throws:
(send risky-agent (fn [_] (throw (ex-info "Boom!" {}))))

;; Wait a moment...
(agent-error risky-agent)
;; => #error {:cause "Boom!" ...}

;; The agent is now STUCK — all future sends are rejected:
(send risky-agent inc)
;; => throws: Agent has errors
```

### `restart-agent` — recover from errors

```clojure
;; Reset the agent to a known good state:
(restart-agent risky-agent 0)

;; Now it works again:
(send risky-agent inc)
(await risky-agent)
@risky-agent ;; => 1
```

### Error modes: `:fail` vs `:continue`

```clojure
;; :fail (default) — agent stops on first error
(def fail-agent (agent 0 :error-mode :fail))

(send fail-agent (fn [_] (throw (ex-info "Payment rejected" {}))))
(Thread/sleep 100)
(agent-error fail-agent)    ;; => #error {:cause "Payment rejected" ...}
(send fail-agent inc)       ;; => throws: Agent is failed, needs restart
;; Agent is STUCK until you explicitly restart-agent

(restart-agent fail-agent 0)
(send fail-agent inc)
(await fail-agent)
@fail-agent ;; => 1 — back to normal

;; :continue — agent keeps processing, errors go to handler
(def continue-agent
  (agent 0
         :error-mode :continue
         :error-handler (fn [ag ex]
                          (println "Agent error:" (.getMessage ex))
                          (println "Agent value still:" @ag))))

(send continue-agent (fn [_] (throw (ex-info "Oops!" {}))))
;; Agent error: Oops!
;; Agent value still: 0

;; Agent is NOT in error state — it continues working:
(send continue-agent inc)
(await continue-agent)
@continue-agent ;; => 1
```

> 🎓 **SOCRATIC:** *"When would you use `:continue` vs `:fail`?"*
> → `:continue` for non-critical work: logging, metrics, notifications.
> If one log entry fails, you want to keep logging the rest.
> → `:fail` for critical state: financial balances, inventory counts.
> If an update fails, stop and investigate.

### Error handling patterns

```clojure
;; Pattern: Agent with built-in error resilience
(defn make-resilient-agent [initial-value on-error]
  (agent initial-value
         :error-mode :continue
         :error-handler (fn [ag ex]
                          (on-error ag ex))))

(def tx-log
  (make-resilient-agent []
    (fn [ag ex]
      (println "WARN: Failed to log transaction:" (.getMessage ex))
      ;; Could also: send to error queue, increment error counter, etc.
      )))
```

---

## 1.3 — The Complete State Model (20 min)

### Decision framework

```
                         ┌──────────────────────────────────┐
                         │ How many values change together?  │
                         └──────────────┬───────────────────┘
                                        │
                        ┌───────────────┴───────────────┐
                        │                               │
                   ONE value                     MULTIPLE values
                        │                               │
               ┌────────┴────────┐                      │
               │                 │                      │
          Synchronous?      Asynchronous?          Synchronous
               │                 │                      │
               ▼                 ▼                      ▼
           ┌───────┐        ┌────────┐            ┌──────────┐
           │ ATOM  │        │ AGENT  │            │   REFS   │
           │       │        │        │            │ + dosync │
           │swap!  │        │send    │            │          │
           │reset! │        │send-off│            │ alter    │
           └───────┘        └────────┘            │ commute  │
                                                  └──────────┘
```

### Comparison table

```
Feature              Atom           Ref              Agent           Var
─────────────────    ─────────────  ───────────────  ──────────────  ──────────────
Coordination         Independent    Coordinated      Independent     Thread-local
Synchronous?         Yes            Yes              No              Yes
Read                 @atom          @ref             @agent          @var / var
Update               swap!, reset!  alter, commute   send, send-off  set!, binding
Transaction?         No             Yes (dosync)     No              No
Retry on conflict?   Yes (CAS)      Yes (STM)        N/A             N/A
Error handling       Exception      Transaction      Error state     Exception
Thread safety        CAS            MVCC             Queue           Binding stack
```

### Real-world mapping

```
Component                 Best reference type    Why
────────────────────────  ─────────────────────  ──────────────────────────────
Payment counter           Atom                   Single value, sync, independent
Account balances          Refs                   Multiple accounts change together
Audit log                 Agent                  Async, ordered, non-blocking
Transaction history       Agent                  Append-only, I/O-bound writes
Rate limit tokens         Atom                   Single value, sync
Request context           Var (dynamic)          Per-thread, per-request config
Feature flags             Atom                   Single map, read-heavy
Shopping cart + inventory Refs                   Cart and inventory must be consistent
```

### Combining reference types

```clojure
;; A realistic payment system uses MULTIPLE reference types:

(def payment-counter (atom 0))            ;; Atom: simple counter
(def accounts (ref {}))                    ;; Ref: coordinated balances
(def audit-trail (agent []))               ;; Agent: async logging
(def ^:dynamic *request-id* nil)          ;; Var: per-request context

(defn process-payment! [from to amount]
  (binding [*request-id* (str "req-" (random-uuid))]
    ;; 1. Coordinated state change (synchronous)
    (let [result (dosync
                   (alter (get @accounts from) update :balance - amount)
                   (alter (get @accounts to) update :balance + amount)
                   {:status :transferred :amount amount})]
      ;; 2. Simple counter (synchronous)
      (swap! payment-counter inc)
      ;; 3. Audit log (asynchronous — doesn't slow down the response)
      (send audit-trail conj
        {:request-id *request-id*
         :from from :to to :amount amount
         :time (System/currentTimeMillis)})
      result)))
```

---

## 1.4 — Hands-on API Drill (10 min, individual)

> **Format:** Each student fills in the blanks to practice every core function for Atoms, Refs, and Agents.
> Replace `___` with the correct expression. Personalize with your name and squad.

### Exercise — fill in the blanks

```clojure
;; Replace with YOUR name and squad:
(def my-name  "Ana García")
(def my-squad "Pagos MX")

;; ═══════════════════════════════════════════════════
;; PART A — Atoms: atom, deref, @, swap!, reset!, :validator
;; ═══════════════════════════════════════════════════

;; A-1. Create an atom holding your squad's payment count
(def squad-payments (___  0))

;; A-2. Read the current value — the long way
(deref squad-payments) ;; => ___

;; A-3. Read it again — @ is a reader shorthand for deref
;;      Note: @ is NOT a function — no parentheses needed.
;;      (deref x)  ←→  @x
___squad-payments      ;; => 0

;; A-4. Increment the counter with a function
(___ squad-payments inc) ;; => 1

;; A-5. Apply a function with extra args — add 5 more
(swap! squad-payments ___ 5) ;; => 6

;; A-6. Ignore the current value — set it directly to 100
(___ squad-payments 100) ;; => 100

;; A-7. Create an atom with a validator — balance can never go negative
(def squad-balance
  (atom 1000
        :validator (fn [val] (___ val 0))))

(swap! squad-balance - 200) ;; => 800 (ok)
;; What happens here?
;; (swap! squad-balance - 900) ;; => ??? (throws — would go to -100)


;; ═══════════════════════════════════════════════════
;; PART B — Refs: ref, deref, @, dosync, alter, commute, ref-set
;; ═══════════════════════════════════════════════════

;; B-1. Create two refs — your squad's income and expenses accounts
(def squad-income   (___ 50000))
(def squad-expenses (___ 20000))

;; B-2. Read both values (@ works the same on refs)
___squad-income   ;; => ___
___squad-expenses ;; => ___

;; B-3. Transfer 3000 from income to expenses (must be coordinated)
(___
  (___ squad-income  - 3000)
  (___ squad-expenses + 3000))
;; squad-income => 47000, squad-expenses => 23000

;; B-4. Record a "visit count" — order doesn't matter, so use commute
(def visit-counter (ref 0))
(dosync
  (___ visit-counter inc)) ;; commute — safe for counters

;; B-5. Hard reset a ref to a specific value (like reset! for atoms)
(dosync
  (___ squad-income 50000))
;; squad-income => 50000

;; B-6. Verify: total must always be conserved
(println (str my-name " [" my-squad "] — total: "
              (+ @squad-income @squad-expenses)))
;; => "Ana García [Pagos MX] — total: 70000"


;; ═══════════════════════════════════════════════════
;; PART C — Agents: agent, deref, @, send, send-off, await,
;;                   agent-error, restart-agent
;; ═══════════════════════════════════════════════════

;; C-1. Create an agent holding your personal activity log
(def my-log (___ []))

;; C-2. Read the current value (@ works the same on agents)
___my-log ;; => ___

;; C-3. Append an entry using send (CPU-bound — just data manipulation)
(___ my-log conj {:who my-name :action "logged in"})

;; C-4. Append an entry using send-off (I/O-bound — simulates a write)
(___ my-log
  (fn [log]
    (println (str "[" my-squad "] writing to disk..."))
    (conj log {:who my-name :action "synced to disk"})))

;; C-5. Wait for all pending actions to finish
(___ my-log)

;; C-6. Read the final log
(println (str my-name "'s log:") @my-log)

;; C-7. Force an error and inspect it
(def failing-agent (agent 0))
(send failing-agent (fn [_] (throw (ex-info "Oops!" {:squad my-squad}))))
(Thread/sleep 100)
(___ failing-agent) ;; => #error {:cause "Oops!" ...}

;; C-8. Recover the agent
(___ failing-agent 0)
(send failing-agent inc)
(await failing-agent)
@failing-agent ;; => 1
```

### Solution

```clojure
(def my-name  "Ana García")
(def my-squad "Pagos MX")

;; ── Part A — Atoms ──────────────────────────────────

(def squad-payments (atom 0))        ;; A-1: atom

(deref squad-payments)               ;; A-2: deref => 0
@squad-payments                      ;; A-3: @    => 0  (reader shorthand, no parens)

(swap! squad-payments inc)           ;; A-4: swap! => 1
(swap! squad-payments + 5)           ;; A-5: +    => 6
(reset! squad-payments 100)          ;; A-6: reset! => 100

(def squad-balance                   ;; A-7: >=
  (atom 1000
        :validator (fn [val] (>= val 0))))

(swap! squad-balance - 200)          ;; => 800
;; (swap! squad-balance - 900)       ;; throws IllegalStateException

;; ── Part B — Refs ───────────────────────────────────

(def squad-income   (ref 50000))     ;; B-1: ref
(def squad-expenses (ref 20000))

@squad-income                        ;; B-2: @ => 50000
@squad-expenses                      ;;      @ => 20000

(dosync                              ;; B-3: dosync + alter
  (alter squad-income  - 3000)
  (alter squad-expenses + 3000))

(def visit-counter (ref 0))
(dosync
  (commute visit-counter inc))       ;; B-4: commute

(dosync
  (ref-set squad-income 50000))      ;; B-5: ref-set

(println (str my-name " [" my-squad "] — total: "
              (+ @squad-income @squad-expenses)))
;; => "Ana García [Pagos MX] — total: 73000"

;; ── Part C — Agents ─────────────────────────────────

(def my-log (agent []))              ;; C-1: agent

@my-log                              ;; C-2: @ => []

(send my-log conj                    ;; C-3: send (CPU-bound)
  {:who my-name :action "logged in"})

(send-off my-log                     ;; C-4: send-off (I/O-bound)
  (fn [log]
    (println (str "[" my-squad "] writing to disk..."))
    (conj log {:who my-name :action "synced to disk"})))

(await my-log)                       ;; C-5: await

(println (str my-name "'s log:") @my-log)
;; => Ana García's log: [{:who "Ana García", :action "logged in"}
;;                        {:who "Ana García", :action "synced to disk"}]

(def failing-agent (agent 0))
(send failing-agent (fn [_] (throw (ex-info "Oops!" {:squad my-squad}))))
(Thread/sleep 100)
(agent-error failing-agent)          ;; C-7: agent-error

(restart-agent failing-agent 0)      ;; C-8: restart-agent
(send failing-agent inc)
(await failing-agent)
@failing-agent                       ;; => 1
```

### Quick reference — all functions drilled

```
Shared       deref / @            used on Atom, Ref, and Agent
             (@ is reader sugar — no parens: @my-atom not (@my-atom))

Atom         atom                 create
             swap!                apply function to current value
             reset!               set to specific value
             :validator           reject invalid states

Ref          ref                  create
             dosync               start transaction
             alter                apply function (retries on conflict)
             commute              apply function (relaxed ordering)
             ref-set              set to specific value

Agent        agent                create
             send                 async update (fixed pool, CPU-bound)
             send-off             async update (cached pool, I/O-bound)
             await                block until pending actions complete
             agent-error          inspect error state
             restart-agent        recover from error state
```

---

# PART 2 — INTERACTIVE PRACTICE

---

## 2.1 — Audit Log Agent (15 min, guided)

> **Format:** Teacher guides students through building a production-quality audit agent.

### The scenario

Build an audit log that:
1. Accepts events asynchronously (doesn't block payment processing)
2. Buffers events and writes them to "disk" in batches
3. Handles errors gracefully (continues logging even if one write fails)

### Step by step

```clojure
;; Step 1: Define the Agent state
;; State: {:pending [] :written 0 :errors 0}
(def audit-agent
  (agent {:pending [] :written 0 :errors 0}
         :error-mode :continue
         :error-handler (fn [ag ex]
                          (println "Audit error:" (.getMessage ex)))))

;; Step 2: Add an event (fast — just appends to pending)
(defn log-event! [event]
  (send audit-agent
    (fn [state]
      (update state :pending conj
        (assoc event :timestamp (System/currentTimeMillis))))))

;; Step 3: Flush to "disk" (simulated — uses send-off for I/O)
(defn flush-audit! []
  (send-off audit-agent
    (fn [{:keys [pending written] :as state}]
      (if (seq pending)
        (do
          ;; Simulate writing to file/database:
          (println (str "[FLUSH] Writing " (count pending) " events"))
          (doseq [event pending]
            (println "  →" (:type event) (:details event)))
          {:pending [] :written (+ written (count pending)) :errors (:errors state)})
        state))))
```

### Step 4: Wire it into a payment flow

```clojure
(defn process-with-audit! [payment]
  (let [result {:status :authorized :method (:method payment)}]
    ;; Log start (non-blocking):
    (log-event! {:type :payment-started :details payment})
    ;; ... process payment ...
    (Thread/sleep 10)  ;; simulate work
    ;; Log completion (non-blocking):
    (log-event! {:type :payment-completed :details result})
    result))

;; Process several payments:
(doseq [p [{:method :spei :amount 1200}
            {:method :credit-card :amount 500}
            {:method :debit-card :amount 350}]]
  (process-with-audit! p))

;; Flush the buffer:
(flush-audit!)
(await audit-agent)

;; Check stats:
@audit-agent
;; => {:pending [], :written 6, :errors 0}
```

---

## 2.2 — Rate Limiter (15 min, pairs)

> **Format:** Each pair builds a token bucket rate limiter using an Agent.

### The challenge

Implement a token bucket that:
- Starts with N tokens
- Each request consumes one token
- Tokens replenish at a fixed rate (e.g., 5 per second)
- Returns `:allowed` or `:rate-limited` immediately (non-blocking)

### Student solution

```clojure
(defn make-rate-limiter [max-tokens refill-rate-per-sec]
  (let [limiter (agent {:tokens max-tokens
                         :max-tokens max-tokens
                         :last-refill (System/currentTimeMillis)})
        ;; Start a refill loop:
        refill-fn (fn refill [state]
                    (let [now (System/currentTimeMillis)
                          elapsed-sec (/ (- now (:last-refill state)) 1000.0)
                          new-tokens (min (:max-tokens state)
                                         (+ (:tokens state)
                                            (* elapsed-sec refill-rate-per-sec)))]
                      (assoc state :tokens new-tokens :last-refill now)))]
    ;; Periodic refill:
    (future
      (while true
        (Thread/sleep 200)
        (send limiter refill-fn)))
    limiter))

(defn try-acquire! [limiter]
  ;; This is a simplification — in production you'd use an atom for sync check
  ;; For demo purposes, we check the current state:
  (let [state @limiter]
    (if (>= (:tokens state) 1)
      (do
        (send limiter update :tokens dec)
        :allowed)
      :rate-limited)))

;; Test:
(def my-limiter (make-rate-limiter 5 2))  ;; 5 max, 2 per second

;; Rapid-fire requests:
(dotimes [i 8]
  (println (str "Request " i ": " (try-acquire! my-limiter))))
;; Request 0: :allowed
;; Request 1: :allowed
;; ...
;; Request 5: :rate-limited
;; Request 6: :rate-limited

;; Wait for refill:
(Thread/sleep 2000)
(println "After 2 seconds:" (try-acquire! my-limiter))
;; After 2 seconds: :allowed
```

> 🎓 **Discussion:** *"Is an Agent the best choice here? What about an Atom?"*
> → For a rate limiter, an **Atom** might actually be better because `try-acquire!` needs a synchronous answer. The Agent version has a race condition between reading and sending. A `swap!` on an atom would be atomic.
> This is an important lesson: **choose the right reference type for the access pattern.**

---

## 2.3 — Integrated System (15 min, individual)

> **Format:** Each student builds a mini payment system using all reference types.

### The challenge

Build a system with:
- **Atom**: payment counter
- **Refs**: account balances (2 accounts)
- **Agent**: audit log
- **Var**: request context (request ID)

Process 10 payments and verify:
1. Counter equals 10
2. Total balance is conserved
3. Audit log has 10 entries

### Student solution

```clojure
;; Reference types:
(def counter (atom 0))
(def acc-a (ref {:balance 10000}))
(def acc-b (ref {:balance 5000}))
(def audit (agent []))
(def ^:dynamic *req-id* "unknown")

(defn transfer! [amount]
  (binding [*req-id* (str "tx-" (swap! counter inc))]
    ;; Coordinated state change:
    (dosync
      (alter acc-a update :balance - amount)
      (alter acc-b update :balance + amount))
    ;; Async audit:
    (send audit conj {:req *req-id* :amount amount
                       :time (System/currentTimeMillis)})
    {:req *req-id* :status :ok}))

;; Run 10 transfers:
(doseq [_ (range 10)]
  (transfer! (+ 50 (rand-int 200))))

(await audit)

;; Verify:
(println "Counter:" @counter)              ;; => 10
(println "Total:" (+ (:balance @acc-a)
                      (:balance @acc-b)))   ;; => 15000
(println "Audit entries:" (count @audit))   ;; => 10

(assert (= 10 @counter))
(assert (= 15000 (+ (:balance @acc-a) (:balance @acc-b))))
(assert (= 10 (count @audit)))
(println "All assertions passed!")
```

---

## Wrap-up & Key Takeaways (5 min)

### The rules

1. **Start with Atoms** — simplest, works for most cases
2. **Use Refs** when multiple values must change **together** atomically
3. **Use Agents** when updates are **independent** and can happen **asynchronously**
4. **Use Vars** for **per-thread** configuration (rare — `binding` + `^:dynamic`)
5. **Never mix reference types inside `dosync`** — only Refs belong in transactions

### Summary

```
Type    Update          Blocking?    Thread safety    Ordering
──────  ──────────────  ───────────  ───────────────  ──────────────────
Atom    swap!/reset!    Yes          CAS retry        N/A (independent)
Ref     alter/commute   Yes          STM retry        Within transaction
Agent   send/send-off   No           Action queue     Per-agent FIFO
Var     binding/set!    Yes          Thread-local     Per-thread
```

> 🎓 **Tease for next class:** *"We've been transforming data with `map`, `filter`, and `reduce` — creating intermediate collections at every step. What if you could compose transformations WITHOUT intermediate collections, and reuse the same transformation on collections, channels, and more? That's Transducers — next class."*
