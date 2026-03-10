# Clojure Advanced Topics — Class 8 (2h)

## core.async Advanced: alt!, Pipelines, and Patterns

> **Prerequisite:** Class 7 — core.async fundamentals (channels, go blocks, >!, <!).
> All examples continue with the Mexican payment rails (SPEI, credit card, debit card).

---

## Agenda

By the end of this class, students will be able to:

- Use `alt!` and `alt!!` to select from multiple channels
- Build fan-in patterns with `async/merge`
- Use `pub/sub` for topic-based event distribution
- Apply `pipeline` and `pipeline-async` for parallel processing
- Combine transducers with channels for streaming pipelines

## Timeline

```
 0:00 ┬─ Part 0 — Recap & Motivation ·················· 10 min
      │   "Your consumer waits on one channel — what about three?"
      │
 0:10 ┬─ PART 1 — CONTENT PRESENTATION ················
      │
 0:10 ├─ 1.1 — alt! and Multiplexing ·················· 20 min
      │   alt!, alt!!, alts!
      │   Timeout patterns, priority
      │
 0:30 ├─ 1.2 — Fan-In and Fan-Out ····················· 15 min
      │   merge, mult, tap
      │
 0:45 ├─ 1.3 — Pub/Sub ································ 15 min
      │   pub, sub — topic-based routing
      │
 1:00 ├─ 1.4 — Channel Kata ··························· 10 min
      │   Fill-in-the-blank: alts!, pub/sub
      │
 1:10 ┬─── 5 min break ───
      │
 1:15 ├─ 1.5 — Pipelines ······························ 10 min
      │   pipeline, pipeline-async
      │   Transducers on channels
      │
 1:25 ┬─ PART 2 — INTERACTIVE PRACTICE ················
      │
 1:25 ├─ 2.1 — Payment Router (guided) ················ 10 min
      │   Route events to method-specific consumers
      │
 1:35 ├─ 2.2 — Notification System (pairs) ············ 10 min
      │   pub/sub for email, SMS, and audit log
      │
 1:45 ├─ 2.3 — Streaming Pipeline (individual) ·········  5 min
      │   Transducers + channels for real-time processing
      │
 1:50 ┬─ FINAL PROJECT KICKOFF ·························  10 min
      │   Subject checklist, domain rule, team formation
      │
 2:00 ┴─ End
```

---

# PART 1 — CONTENT PRESENTATION

---

## Part 0 — Recap & Motivation (10 min)

### Quick review

In Class 7 we learned:
- Channels for communication between processes
- `go` blocks for lightweight concurrency
- `>!`/`<!` for non-blocking channel operations
- Buffered channels for backpressure control

### The provocation

> 🎓 **SOCRATIC:** *"Your payment consumer reads from one channel. But now you have three channels: SPEI confirmations, card authorizations, and timeout alerts. The consumer needs to react to whichever channel has data first. How?"*

You can't just `<!` from each one sequentially — you'd block on the first one and miss data from the others. You need **multiplexing**.

```clojure
(require '[clojure.core.async :as async
           :refer [chan go go-loop >! <! >!! <!! close!
                   alt! alt!! alts! alts!!
                   timeout thread
                   merge mult tap untap
                   pub sub unsub
                   pipeline pipeline-async]])
```

---

## 1.1 — alt! and Multiplexing (20 min)

### `alt!` — wait on multiple channels, take from the first available

```clojure
(def spei-ch (chan 10))
(def card-ch (chan 10))
(def alert-ch (chan 10))

(go-loop []
  (alt!
    spei-ch  ([event] (println "SPEI event:" event))
    card-ch  ([event] (println "Card event:" event))
    alert-ch ([alert] (println "ALERT:" alert))
    (timeout 5000) ([_] (println "No events for 5 seconds")))
  (recur))
```

> 🎓 **SOCRATIC:** *"`alt!` looks like a `cond` for channels. What's the key difference?"*
> → `cond` checks conditions that are already true/false. `alt!` **waits** until one of the channels has data. It's a blocking select.

### How `alt!` chooses

When **multiple channels** have data simultaneously:
- By default: **random** choice (fair scheduling)
- With `:priority true`: tries channels **in order** (top to bottom)

```clojure
(go
  (alt!
    spei-ch  ([v] (println "SPEI:" v))
    card-ch  ([v] (println "Card:" v))
    :priority true))  ;; ← SPEI always checked first
```

### Putting with `alt!`

`alt!` can also attempt puts:

```clojure
(def delivery-ch (chan 1))
(def delivery-val {:status :authorized :id "x1"})

(go
  (alt!
    [[delivery-ch delivery-val]]  ;; try to put `delivery-val` on delivery-ch
    ([delivered?] (println "Delivered?" delivered?))

    (timeout 1000)
    ([_] (println "Timeout — couldn't deliver in 1 second"))))
;; Delivered? true

(<!! delivery-ch)
;; => {:status :authorized, :id "x1"}
```

### `alts!` — programmatic version (data-driven)

When your channels are in a collection (not known at compile time):

```clojure
(def channels [spei-ch card-ch alert-ch])

(go
  (let [[value port] (alts! channels)]
    (condp = port
      spei-ch  (println "From SPEI:" value)
      card-ch  (println "From Card:" value)
      alert-ch (println "Alert:" value))))
```

> `alts!` returns `[value port]` — the value AND which channel it came from.

### Timeout pattern — the essential recipe

```clojure
;; Simulate a gateway that takes ~500ms to respond:
(defn authorize [gateway payment]
  (Thread/sleep 500)
  {:status :authorized :id (:id payment) :gateway (:name gateway)})

(def spei-gw {:name "SPEI Gateway"})

(defn authorize-with-timeout! [gateway payment timeout-ms]
  (go
    (let [result-ch (thread (authorize gateway payment))  ;; blocking I/O
          [result port] (alts! [result-ch (timeout timeout-ms)])]
      (if (= port result-ch)
        {:ok result}
        {:error :timeout :after-ms timeout-ms}))))

;; Success path (500ms < 3000ms):
(<!! (authorize-with-timeout! spei-gw
       {:id "s1" :spei-clabe "032180000118359719" :amount 1200}
       3000))
;; => {:ok {:status :authorized, :id "s1", :gateway "SPEI Gateway"}}

;; Timeout path (500ms > 100ms):
(<!! (authorize-with-timeout! spei-gw
       {:id "s2" :spei-clabe "032180000118359719" :amount 1200}
       100))
;; => {:error :timeout, :after-ms 100}
```

> 🎓 **SOCRATIC:** *"This implements a timeout WITHOUT `Thread/sleep` or `Future.get(timeout)`. How?"*
> → `(timeout 3000)` creates a channel that closes after 3 seconds. `alts!` picks whichever fires first — the result or the timeout. Elegant and composable.

---

## 1.2 — Fan-In and Fan-Out (15 min)

### Fan-In: `merge` — combine multiple channels into one

```clojure
;; Three source channels:
(def spei-events (chan 10))
(def card-events (chan 10))
(def debit-events (chan 10))

;; Merge into a single channel:
(def all-events (merge [spei-events card-events debit-events] 100))

;; Single consumer:
(go-loop []
  (when-let [event (<! all-events)]
    (println "Event:" (:source event) (:id event))
    (recur)))

;; Feed from different sources:
(go (>! spei-events {:source :spei :id "s1" :amount 1200}))
(go (>! card-events {:source :card :id "c1" :amount 500}))
(go (>! debit-events {:source :debit-card :id "o1" :amount 350}))
```

### Fan-Out: `mult` + `tap` — broadcast one channel to many

```clojure
;; Source channel:
(def events (chan 10))

;; Create a mult (multiplexer):
(def events-mult (mult events))

;; Create tap channels (subscribers):
(def audit-ch (chan 10))
(def notification-ch (chan 10))
(def analytics-ch (chan 10))

(tap events-mult audit-ch)
(tap events-mult notification-ch)
(tap events-mult analytics-ch)

;; Each subscriber gets EVERY event:
(go-loop [] (when-let [e (<! audit-ch)]
              (println "[Audit]" e) (recur)))
(go-loop [] (when-let [e (<! notification-ch)]
              (println "[Notify]" e) (recur)))
(go-loop [] (when-let [e (<! analytics-ch)]
              (println "[Analytics]" e) (recur)))

;; One put → three consumers see it:
(>!! events {:type :payment-authorized :method :spei :amount 1200})
;; [Audit] {...}
;; [Notify] {...}
;; [Analytics] {...}
```

### Fan-In vs Fan-Out

```
Pattern    Description                   core.async function
─────────  ──────────────────────────    ────────────────────
Fan-In     Many sources → one channel   merge
Fan-Out    One source → many consumers  mult + tap
```

---

## 1.3 — Pub/Sub (15 min)

### Topic-based routing

`pub` creates a publication that routes messages to topic-specific subscribers:

```clojure
;; Source channel:
(def event-ch (chan 100))

;; Create a publication that routes by :method
(def event-pub (pub event-ch :method))

;; Subscribe channels to specific topics:
(def spei-sub (chan 10))
(def card-sub (chan 10))
(def debit-sub (chan 10))

(sub event-pub :spei spei-sub)
(sub event-pub :credit-card card-sub)
(sub event-pub :debit-card debit-sub)
```

Each subscriber only sees events matching their topic:

```clojure
;; Consumers:
(go-loop [] (when-let [e (<! spei-sub)]
              (println "[SPEI Handler]" (:id e) "$" (:amount e))
              (recur)))

(go-loop [] (when-let [e (<! card-sub)]
              (println "[Card Handler]" (:id e) "$" (:amount e))
              (recur)))

(go-loop [] (when-let [e (<! debit-sub)]
              (println "[Debit Handler]" (:id e) "$" (:amount e))
              (recur)))

;; Publish events — each goes to the RIGHT handler:
(>!! event-ch {:method :spei :id "s1" :amount 1200})
;; [SPEI Handler] s1 $ 1200

(>!! event-ch {:method :credit-card :id "c1" :amount 500})
;; [Card Handler] c1 $ 500

(>!! event-ch {:method :debit-card :id "o1" :amount 350})
;; [Debit Handler] o1 $ 350
```

### Custom topic function

The topic function can be anything:

```clojure
;; Route by risk level (separate channel — a pub needs its OWN source):
(def risk-event-ch (chan 100))

(def risk-pub
  (pub risk-event-ch
       (fn [{:keys [amount]}]
         (cond
           (> amount 50000)  :high-risk
           (> amount 10000)  :medium-risk
           :else             :low-risk))))

(def high-risk-ch (chan 10))
(def low-risk-ch (chan 10))
(sub risk-pub :high-risk high-risk-ch)
(sub risk-pub :low-risk low-risk-ch)

;; Test:
(>!! risk-event-ch {:id "r1" :amount 60000 :method :spei})
(>!! risk-event-ch {:id "r2" :amount 500 :method :card})

(<!! high-risk-ch)  ;; => {:id "r1", :amount 60000, :method :spei}
(<!! low-risk-ch)   ;; => {:id "r2", :amount 500, :method :card}
```

> 🎓 **SOCRATIC:** *"How is pub/sub different from mult/tap?"*
> → `mult/tap` broadcasts EVERY event to EVERY subscriber.
> `pub/sub` routes events to subscribers based on a TOPIC. It's selective.

---

## 1.4 — Channel Kata (10 min, individual)

> **Format:** Fill in the blanks (`__`) so every `assert` passes. Evaluate each block in your REPL.

### alts! — identify the winner

**Kata 1:** Only one channel has data. Which one does `alts!` pick?

```clojure
(def ch-a (chan 1))
(def ch-b (chan 1))
(>!! ch-b :winner)

(let [[val port] (<!! (go (alts! [ch-a ch-b])))]
  (assert (= val __))       ;; what value came out?
  (assert (= port __)))     ;; which channel delivered it?
```

### alts! — timeout wins

**Kata 2:** The slow channel takes 2 seconds. Pick a timeout so `alts!` gives up first.

```clojure
(def slow-ch (chan))
(go (<! (timeout 2000)) (>! slow-ch :done))

(let [[val port] (<!! (go (alts! [slow-ch (timeout __)])))]
  (assert (nil? val))            ;; timeout channel closes → nil
  (assert (not= port slow-ch))) ;; it was NOT the slow channel
```

### alt! — priority

**Kata 3:** Both channels have data. Fill in the missing keyword so `pri-a` always wins.

```clojure
(def pri-a (chan 1))
(def pri-b (chan 1))
(>!! pri-a :first)
(>!! pri-b :second)

(def result
  (<!! (go (alt!
             pri-a ([v] v)
             pri-b ([v] v)
             __))))            ;; what keyword makes pri-a always win?
(assert (= result :first))
```

### pub — topic function

**Kata 4:** Create a `pub` that routes events by their `:method` key.

```clojure
(def kata-src (chan 10))
(def kata-pub (pub kata-src __))  ;; what goes here?

(def kata-spei (chan 1))
(sub kata-pub :spei kata-spei)

(>!! kata-src {:method :spei :id "k1" :amount 100})
(assert (= "k1" (:id (<!! kata-spei))))
```

### sub — right topic

**Kata 5:** Subscribe to the correct topic so you receive the credit-card event.

```clojure
(def kata-src2 (chan 10))
(def kata-pub2 (pub kata-src2 :method))

(def kata-card (chan 1))
(sub kata-pub2 __ kata-card)  ;; what topic?

(>!! kata-src2 {:method :credit-card :id "k2" :amount 500})
(assert (= "k2" (:id (<!! kata-card))))
```

### mult/tap — broadcast

**Kata 6:** One value goes in, three taps come out. What do they all receive?

```clojure
(def kata-src3 (chan 1))
(def kata-m (mult kata-src3))
(def t1 (chan 1))
(def t2 (chan 1))
(def t3 (chan 1))
(tap kata-m t1)
(tap kata-m t2)
(tap kata-m t3)

(>!! kata-src3 :broadcast)
(let [results [(<!! t1) (<!! t2) (<!! t3)]]
  (assert (= results __)))  ;; what's the vector?
```

---

### Answer key (for teacher)

```
Kata 1: :winner / ch-b
Kata 2: any value < 2000 (e.g. 100)
Kata 3: :priority true
Kata 4: :method
Kata 5: :credit-card
Kata 6: [:broadcast :broadcast :broadcast]
```

---

## 1.5 — Pipelines (10 min)

### `pipeline` — parallel transducer processing on channels

```clojure
;; Apply a transducer in parallel across N threads:
(def input-ch (chan 100))
(def output-ch (chan 100))

;; 4 parallel workers applying the transducer:
(pipeline 4 output-ch
  (comp
    (filter #(> (:amount %) 1000))
    (map #(assoc % :status :approved)))
  input-ch)

;; Feed input:
(go (doseq [p [{:id 1 :amount 500}
               {:id 2 :amount 2000}
               {:id 3 :amount 150}
               {:id 4 :amount 8000}]]
      (>! input-ch p))
    (close! input-ch))

;; Read output:
(go-loop []
  (when-let [result (<! output-ch)]
    (println "Approved:" result)
    (recur)))
;; Approved: {:id 2, :amount 2000, :status :approved}
;; Approved: {:id 4, :amount 8000, :status :approved}
```

### `pipeline-async` — for async operations

When each step involves async I/O:

```clojure
(def in-ch (chan 10))
(def out-ch (chan 10))

(pipeline-async 4 out-ch
  (fn [payment result-ch]
    (go
      (let [;; simulate async authorization
            _ (<! (timeout (rand-int 100)))
            result (assoc payment :status :authorized :token (str "T-" (random-uuid)))]
        (>! result-ch result)
        (close! result-ch))))
  in-ch)
```

### Transducers on channels

You can attach transducers directly when creating channels:

```clojure
;; Channel with a transducer — transforms every value put into it:
(def filtered-ch
  (chan 100
       (comp (filter #(= :spei (:method %)))
             (map #(select-keys % [:id :amount])))))

(>!! filtered-ch {:method :spei :id "s1" :amount 1200 :extra :data})
(>!! filtered-ch {:method :credit-card :id "c1" :amount 500})  ;; filtered out!
(>!! filtered-ch {:method :spei :id "s2" :amount 800 :extra :more})

(<!! filtered-ch)  ;; => {:id "s1", :amount 1200}
(<!! filtered-ch)  ;; => {:id "s2", :amount 800}
;; The credit-card event was silently dropped by the filter transducer
```

> 🎓 **SOCRATIC:** *"We used the SAME transducers from Class 6 — but now on channels instead of collections. What does this demonstrate?"*
> → Transducers are truly context-independent. Same transformation, different execution contexts: collections (Class 6), channels (Class 8).

---

# PART 2 — INTERACTIVE PRACTICE

---

## 2.1 — Payment Router (10 min, guided)

> **Format:** Build a payment routing system using pub/sub.

### The scenario

Payment events arrive on a single channel. Route them to specialized handlers based on method, with a catch-all for unknown methods.

### Step by step

```clojure
;; 1. Create the infrastructure
(def incoming (chan 100))
(def payment-pub (pub incoming :method))

;; 2. Create handler channels
(def spei-handler-ch (chan 20))
(def card-handler-ch (chan 20))
(def unknown-handler-ch (chan 20))

(sub payment-pub :spei spei-handler-ch)
(sub payment-pub :credit-card card-handler-ch)
(sub payment-pub :debit-card card-handler-ch)  ;; both card types go to same handler

;; 3. Start handlers
(defn make-handler [ch handler-name process-fn]
  (go-loop [processed 0]
    (if-let [event (<! ch)]
      (do
        (let [result (process-fn event)]
          (println (str "[" handler-name "] " (:id event) " → " (:status result))))
        (recur (inc processed)))
      (println (str "[" handler-name "] Shutting down. Processed: " processed)))))

(make-handler spei-handler-ch "SPEI"
  (fn [e] {:status :confirmed :provider :spei-rails}))

(make-handler card-handler-ch "Card"
  (fn [e] {:status :authorized :provider :mx-acquirer}))

;; 4. Send events
(go
  (doseq [p [{:method :spei :id "s1" :amount 1200}
              {:method :credit-card :id "c1" :amount 500}
              {:method :debit-card :id "d1" :amount 300}
              {:method :spei :id "s2" :amount 8000}
              {:method :credit-card :id "c2" :amount 15000}]]
    (>! incoming p))
  (println "[Router] All events dispatched"))
```

---

## 2.2 — Notification System (10 min, pairs)

> **Format:** Each pair builds a notification fan-out system.

### The challenge

When a payment is authorized, send notifications to three systems:
1. **Email service** — sends receipt to customer
2. **SMS service** — sends SMS for high-value payments (> $5000)
3. **Audit log** — logs every event

Use `mult`/`tap` for fan-out and transducers on channels for filtering.

### Student solution

```clojure
;; Source of authorized payments:
(def authorized-ch (chan 50))
(def auth-mult (mult authorized-ch))

;; Email: gets every event
(def email-ch (chan 50))
(tap auth-mult email-ch)

;; SMS: only high-value (use a filtered channel)
(def sms-raw-ch (chan 50))
(tap auth-mult sms-raw-ch)

;; Audit: gets everything
(def audit-ch (chan 50))
(tap auth-mult audit-ch)

;; Email handler
(go-loop []
  (when-let [event (<! email-ch)]
    (println "[EMAIL] Receipt sent for" (:id event) "- $" (:amount event))
    (recur)))

;; SMS handler (with client-side filtering)
(go-loop []
  (when-let [event (<! sms-raw-ch)]
    (when (> (:amount event) 5000)
      (println "[SMS] High-value alert:" (:id event) "- $" (:amount event)))
    (recur)))

;; Audit handler
(go-loop []
  (when-let [event (<! audit-ch)]
    (println "[AUDIT]" (pr-str event))
    (recur)))

;; Test it:
(go
  (doseq [p [{:id "p1" :amount 1200 :method :spei}
              {:id "p2" :amount 8000 :method :credit-card}
              {:id "p3" :amount 300 :method :debit-card}
              {:id "p4" :amount 25000 :method :spei}]]
    (>! authorized-ch p)))

;; Expected output:
;; [EMAIL] Receipt sent for p1 - $ 1200
;; [AUDIT] {:id "p1", ...}
;; [EMAIL] Receipt sent for p2 - $ 8000
;; [SMS] High-value alert: p2 - $ 8000
;; [AUDIT] {:id "p2", ...}
;; etc.
```

---

## 2.3 — Streaming Pipeline (5 min, individual)

> **Format:** Each student builds a complete streaming pipeline combining transducers and channels.

### The challenge

Build a real-time payment aggregation pipeline:

```
Input events → Filter approved → Group by method → Emit running totals
```

```clojure
;; Input channel with transducer (pre-filter):
(def raw-events
  (chan 100 (filter #(= :approved (:status %)))))

;; Processing pipeline:
(def totals (atom {}))

(go-loop []
  (when-let [{:keys [method amount]} (<! raw-events)]
    (swap! totals update method (fnil + 0) amount)
    (println "Running totals:" @totals)
    (recur)))

;; Simulate events:
(go
  (doseq [e [{:method :spei :amount 1200 :status :approved}
              {:method :credit-card :amount 500 :status :rejected}   ;; filtered out
              {:method :spei :amount 800 :status :approved}
              {:method :debit-card :amount 350 :status :approved}
              {:method :credit-card :amount 2000 :status :approved}
              {:method :spei :amount 5000 :status :pending}]]        ;; filtered out
    (>! raw-events e)
    (<! (timeout 200))))

;; Expected output:
;; Running totals: {:spei 1200}
;; Running totals: {:spei 2000}
;; Running totals: {:spei 2000, :debit-card 350}
;; Running totals: {:spei 2000, :debit-card 350, :credit-card 2000}
```

---

---

# FINAL PROJECT KICKOFF (10 min)

---

## Final Project — Overview

Starting next class, you'll build a **complete Clojure system** for a business domain of your choice that demonstrates every topic from the module.

### The one rule

> **Your domain cannot be payments.** We've used SPEI, credit cards, and debit cards for 8 classes. The project proves you own the concepts by applying them to a fresh context.

### Subject checklist

Every project must use **all subjects** covered so far. Each has a minimum requirement.

```
 #   Subject                  Minimum                                           Guiding question
───  ───────────────────────  ──────────────────────────────────────────────   ────────────────────────────────────
 1   Multimethods             1 defmulti, 3+ dispatch values                   "What entity has open types?"
 2   Protocols                1 defprotocol, 2+ record implementations         "What contract do components share?"
 3   Basic Macros             1 domain macro (with-*, def*)                    "What boilerplate repeats 3+ times?"
 4   Refs / STM               1 dosync with 2+ refs                            "Where must two states change together?"
 5   Agents                   1 agent for async state                          "What updates don't need coordination?"
 6   Transducers              1 composed pipeline (comp + transduce/into)       "Where do I chain map/filter/reduce?"
 7   core.async (basic)       Channels + go blocks + producer/consumer         "What processes communicate via messages?"
 8   core.async (advanced)    1+ pattern: alt!, pub/sub, pipeline, mult/tap    "Where do I need multiplexing or routing?"
```

> Subjects 9 (Java Interop) and 10 (Advanced Macros) will be added in upcoming classes.

### Suggested domains

| Domain | Why it works |
|--------|-------------|
| **Library / Bookstore** | Media types (multimethods), loans/returns (STM), notifications (agents, core.async) |
| **Restaurant / Food Delivery** | Order types (multimethods), menu (protocols), kitchen queue (core.async), inventory (refs) |
| **Hospital Patient Flow** | Patient types (multimethods), departments (pub/sub), beds (STM), alerts (agents) |
| **E-Commerce Inventory** | Product categories (multimethods), stock + cart (STM), order pipeline (core.async) |
| **Ride-Sharing / Logistics** | Vehicle types (protocols), driver assignment (STM), dispatch (core.async) |
| **IoT Sensor Dashboard** | Sensor types (multimethods), data streams (core.async), aggregation (transducers) |
| **Music Streaming Service** | Content types (protocols), playlists (STM), recommendation pipeline (transducers) |
| **School / University** | Student types (protocols), enrollment (STM), grade pipeline (transducers), notifications (pub/sub) |

### For next class

> 🎓 *"Come to Class 9 with:*
> 1. *A team (2-3 people)*
> 2. *A domain idea*
> 3. *A rough idea of how each subject maps to your domain*
>
> *We'll spend the full class planning your architecture and starting the skeleton."*

### Pattern cheat sheet (today's class)

```
Pattern           What it does                    Functions
────────────────  ──────────────────────────────  ──────────────────
Multiplexing      Wait on multiple channels        alt!, alts!
Timeout           Deadline for an operation         (timeout ms) + alt!
Fan-In            Many sources → one consumer      merge
Fan-Out           One source → many consumers      mult + tap
Pub/Sub           Topic-based routing               pub + sub
Pipeline          Parallel transducer processing    pipeline
Transducer chan   Transform on put                  (chan N xf)
```

---

# BONUS — Multi-Terminal Channel Demo

---

> **When to use:** If there is extra time, or as a dramatic opener for the project kickoff.
> This demo makes channels TANGIBLE — students see messages flow between physical terminal windows.

## Setup: One JVM, Multiple REPLs

All nREPL connections to the same server share the **same JVM process** — same vars, same channels, same atoms. A channel defined in one terminal is the exact same object in another terminal.

### Step 1 — Start an nREPL server

Make sure `deps.edn` includes an nREPL alias:

```clojure
;; deps.edn
{:deps {org.clojure/clojure    {:mvn/version "1.12.0"}
        org.clojure/core.async {:mvn/version "1.7.790"}}
 :aliases
 {:nrepl {:extra-deps {nrepl/nrepl {:mvn/version "1.3.0"}}
          :main-opts  ["-m" "nrepl.server"]}}}
```

```bash
# Terminal 1 — Start the nREPL server:
clj -M:nrepl
# => nREPL server started on port 52483 on host localhost - nrepl://localhost:52483
```

> Note the port number (or read it from `.nrepl-port`).

### Step 2 — Connect clients from other terminals

```bash
# Terminal 2 (Alice):
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
    -M -m nrepl.cmdline --connect --host localhost --port 52483

# Terminal 3 (Bob):
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.3.0"}}}' \
    -M -m nrepl.cmdline --connect --host localhost --port 52483
```

> **IDE alternative:** If students use IntelliJ/Cursive or Emacs/CIDER, they can open multiple REPL tabs connected to the same process — same effect.

## The Demo

### Act 1 — Create the shared channel (any terminal)

```clojure
;; In Terminal 2 (Alice):
(require '[clojure.core.async :refer [chan >!! <!! close! go-loop <!]])

(def shared-ch (chan 10))
(println "Channel created! I'm:" (.getName (Thread/currentThread)))
```

### Act 2 — The blocking take (Bob's terminal hangs!)

```clojure
;; In Terminal 3 (Bob) — type this and press Enter:
(require '[clojure.core.async :refer [chan >!! <!! close!]])

(<!! shared-ch)
;; Bob's REPL is now FROZEN — the cursor doesn't come back.
;; Bob is blocked, waiting for someone to put a value on the channel.
```

> 🎓 **To the class:** *"Look at Bob's terminal — it's frozen. The REPL is blocked on `<!!`. It's literally waiting for data. Now watch what happens when Alice sends something..."*

### Act 3 — Alice puts, Bob unblocks

```clojure
;; In Terminal 2 (Alice):
(>!! shared-ch {:from "Alice"
                :msg "Can you see this, Bob?"
                :thread (.getName (Thread/currentThread))
                :at (java.time.Instant/now)})
;; => true
```

> **Immediately** look at Bob's terminal — it unblocks and prints:
>
> ```clojure
> ;; Bob's terminal suddenly returns:
> ;; => {:from "Alice", :msg "Can you see this, Bob?",
> ;;     :thread "nREPL-session-abc123...", :at #inst "2026-..."}
> ```

> 🎓 *"Alice typed in HER terminal. Bob's terminal unblocked in HIS. Same channel, different threads, different REPL sessions — but one JVM. This is CSP in action."*

### Act 4 — Shared go-loop consumer (visible to all)

```clojure
;; In ANY terminal — start a logger:
(def logger
  (go-loop []
    (when-let [msg (<! shared-ch)]
      (println (str ">> [" (:from msg) "] " (:msg msg)
                    " (thread: " (:thread msg) ")"))
      (recur))))
```

Now BOTH Alice and Bob can put messages and see them printed:

```clojure
;; Alice (Terminal 2):
(>!! shared-ch {:from "Alice" :msg "Hello everyone!" :thread (.getName (Thread/currentThread))})

;; Bob (Terminal 3):
(>!! shared-ch {:from "Bob" :msg "I can talk too!" :thread (.getName (Thread/currentThread))})
```

Output appears in whichever terminal started the logger:

```
>> [Alice] Hello everyone! (thread: nREPL-session-abc123...)
>> [Bob] I can talk too! (thread: nREPL-session-def456...)
```

> 🎓 *"Notice the thread names are different — each REPL connection runs on its own nREPL session thread. The channel is the bridge. No shared mutable state, no locks, no synchronization code — just put and take."*

### Act 5 — Close and observe

```clojure
;; Alice closes the channel:
(close! shared-ch)

;; Bob tries to take:
(<!! shared-ch)
;; => nil  (channel is closed, returns nil forever)

;; The logger go-loop exits cleanly (when-let fails on nil)
```

## Why this matters

```
What they SAW                              What it teaches
─────────────────────────────────────────  ─────────────────────────────────────
Bob's REPL froze on <!!                    Blocking take — waits for data
Alice's put unblocked Bob                  Channels are the synchronization mechanism
Different thread names in messages         Each REPL session = different thread
Logger received from both terminals        go-loop as a consumer, multiple producers
close! made <!! return nil                 Channel lifecycle — close propagates
```

> 🎓 *"Everything we coded with `go` blocks and channels in this class — this is what it looks like from the outside. Real threads, real blocking, real message passing. Your final project will have these same patterns, just inside one program instead of across terminals."*
