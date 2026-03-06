# Clojure Advanced Topics — Class 7 (2h)

## core.async Fundamentals: Channels and Go Blocks

> **Prerequisite:** Classes 1–6 (Multimethods, Protocols, Macros, Refs/STM, Agents, Transducers).
> All examples continue with the Mexican payment rails (SPEI, credit card, debit card).

---

## Agenda

By the end of this class, students will be able to:

- Explain the CSP (Communicating Sequential Processes) model
- Create channels and use `>!!`, `<!!` for blocking operations
- Write go blocks with `>!`, `<!` for lightweight asynchronous operations
- Build producer/consumer patterns with channels
- Use buffered channels and understand backpressure

## Timeline

```
 0:00 ┬─ Part 0 — 🍳 The Kitchen Grows ·················· 15 min
      │   From one super-chef to a full restaurant
      │   Stations, windows, shelves → CSP, channels, buffers
      │
 0:15 ┬─ PART 1 — CONTENT PRESENTATION ················
      │
 0:15 ├─ 1.1 — Channels and Blocking Operations ········· 25 min
      │   chan, >!!, <!!, close!
      │   Buffered channels: buffer, sliding, dropping
      │
 0:40 ├─ 1.2 — Go Blocks ································ 20 min
      │   go, >!, <!
      │   Lightweight threads (not real OS threads)
      │   Error handling in go blocks
      │
 1:00 ├─ 1.3 — Channel Kata (individual) ················ 10 min
      │   Fill the gaps to make assertions pass
      │
 1:10 ┬─── 5 min break ───
      │
 1:15 ┬─ PART 2 — INTERACTIVE PRACTICE ················
      │
 1:15 ├─ 2.1 — Payment Event Pipeline (guided) ·········· 15 min
      │   Producer → Channel → Consumer
      │
 1:30 ├─ 2.2 — Multi-Producer Fan-In (pairs) ············ 10 min
      │   Multiple payment sources → single processor
      │
 1:40 ├─ 2.3 — Backpressure Experiment (individual) ····· 10 min
      │   What happens when the consumer is slow?
      │
 1:50 ┬─ Wrap-up & Key Takeaways ························ 10 min
 2:00 ┴─ End
```

---

# PART 1 — CONTENT PRESENTATION

---

## Part 0 — 🍳 The Kitchen Grows (15 min)

> **Goal:** Extend Class 6's kitchen analogy to explain CSP, channels, and buffers before any code.

---

### Recall: Class 6's kitchen

In Class 6 we had a single **super-chef** (a transducer) who knew the entire recipe — filter, peel, cut, fry — and processed each ingredient end-to-end, one at a time. Ingredients arrived from different sources (🚚 truck, 🚲 bike, 🏪 market) and the super-chef didn't care where they came from. That's what made transducers powerful: **one recipe, any source, zero intermediate bowls**.

But our restaurant is growing...

---

### The restaurant scales up

One super-chef can't handle everything anymore. The restaurant now has **multiple cooking stations**, each with its own cook:

```
┌────────────┐     ┌────────────┐     ┌────────────┐
│  🔪 PREP   │     │  🍳 FRY    │     │  🍽️ PLATE  │
│  station    │────→│  station   │────→│  station    │
│  (Cook A)  │     │  (Cook B)  │     │  (Cook C)  │
└────────────┘     └────────────┘     └────────────┘
```

Each cook does their own work **sequentially** — one item at a time, in order. They don't reach into each other's stations. They don't share cutting boards or pans.

So how do they coordinate? **Pass-through windows.**

---

### Pass-through windows = Channels

Between each station there's a **pass-through window** — a small opening in the wall where one cook places a finished item and the next cook picks it up.

```
┌──────────┐  ┌─window─┐  ┌──────────┐  ┌─window─┐  ┌──────────┐
│  🔪 PREP │→ │ ▪ ▪ ▪  │ →│  🍳 FRY  │→ │ ▪ ▪ ▪  │ →│  🍽️ PLATE│
│  Cook A  │  │ shelf  │  │  Cook B  │  │ shelf  │  │  Cook C  │
└──────────┘  └────────┘  └──────────┘  └────────┘  └──────────┘
```

Rules:
- Cook A **puts** a prepped item on the window shelf
- Cook B **takes** it from the other side
- Neither cook enters the other's station — the window is the ONLY way to communicate
- If Cook B isn't ready, Cook A has to **wait** (or the shelf absorbs the difference)

This is the **CSP model**: independent processes communicating through channels, with no shared state.

---

### The shelf under the window = Buffer

Some windows have a **shelf** underneath — a small space where items can sit while the next cook is busy.

```
No shelf (unbuffered):
  Cook A hands the dish directly to Cook B.
  If Cook B isn't ready, Cook A stands there holding the plate.
  → Strict handshake — maximum synchronization.

Normal shelf, holds 5 items (buffered):
  Cook A can place up to 5 items on the shelf and keep working.
  On the 6th item, Cook A waits — shelf is full.
  → Absorbs temporary speed differences.

"Drop new" shelf (dropping buffer):
  Shelf holds 3 items. When full, new items fall on the floor.
  Cook A NEVER waits — but some items are lost.
  → "Discard overflow" — rate limiting.

"Push old" shelf (sliding buffer):
  Shelf holds 3 items. When full, the OLDEST item falls off
  to make room for the new one.
  Cook A NEVER waits — but only the latest items survive.
  → "Keep the freshest" — dashboards, monitoring.
```

When the shelf is full and Cook A has to wait, that's **backpressure** — the system naturally slows down the fast producer to match the slow consumer.

---

### From kitchen to core.async

```
🍳 Kitchen                     core.async
──────────────────────────     ──────────────────────────────
Cooking station                Process (go block / thread)
Cook                           Lightweight process
Pass-through window            Channel (chan)
Shelf under window             Buffer (buffer N)
"Drop new" shelf               (dropping-buffer N)
"Push old" shelf               (sliding-buffer N)
Shelf full → cook waits        Backpressure
Cook puts dish on window       (>!! ch val) or (>! ch val)
Cook takes dish from window    (<!! ch) or (<! ch)
Cook closes their window       (close! ch)
```

---

### Why not other approaches?

```
Model              Kitchen equivalent              Problem
─────────────────  ──────────────────────────────  ──────────────────────────
Callbacks          Cooks YELL across the kitchen   Loud, chaotic, hard to follow
                                                   ("callback hell")

Threads + Locks    Cooks share ONE cutting board   "Hey, I was using that!"
                   with a sign-up sheet            Deadlocks, race conditions

Actors (Erlang)    Each cook has a mailbox on       Messages pile up if cook is
                   their desk                      slow (mailbox ordering)

CSP (core.async)   Pass-through windows with       Clean separation, explicit
                   shelves                         flow control, backpressure
```

> 🎓 **SOCRATIC:** *"In the callback model, what happens when 5 cooks are all yelling orders at once?"*
> → Chaos. Nobody knows who to listen to, orders get mixed up. That's callback hell.
>
> *"In the threads+locks model, what happens when two cooks both need the cutting board?"*
> → One waits (lock contention). Or worse, each grabs one end (deadlock).
>
> *"With pass-through windows?"*
> → Each cook works at their own station. The window handles coordination. Simple, predictable.

---

### 🌉 Bridge to payments

The same pattern maps directly to a payment processing pipeline:

```
🍳 Kitchen station             💳 Payment pipeline stage
──────────────────────────     ──────────────────────────────
🔪 Prep station                Validation (check amount, method)
🍳 Fry station                 Processing (authorize, capture)
🍽️ Plate station               Notification (send receipt)

Pass-through windows           Channels between stages
Shelf capacity                 Buffer size (backpressure control)
Multiple prep cooks            Multiple producers (SPEI, card, debit)
One plating cook               Single consumer (notification sender)
```

---

### Setup

Add core.async to `deps.edn`:

```clojure
{:deps {org.clojure/clojure    {:mvn/version "1.12.0"}
        org.clojure/core.async {:mvn/version "1.6.681"}}}
```

```clojure
(require '[clojure.core.async :as async
           :refer [chan go go-loop >! <! >!! <!! close!
                   buffer sliding-buffer dropping-buffer
                   timeout thread]])
```

---

## 1.1 — Channels and Blocking Operations (25 min)

### Creating channels

Four types of pass-through windows for our kitchen:

```
🔪 Cook A                                              🍳 Cook B

1️⃣  No shelf (unbuffered):
    🔪 "Here, take this 🍟"  →  [    ]  →  🍳 "Got it!"
    Both must be present — direct handoff.

2️⃣  Shelf holds 3 (buffered):
    🔪 places 🍟 🍔 🍿       →  [🍟 🍔 🍿]  →  🍳 takes when ready
    Cook A keeps working until shelf is full.

3️⃣  "Push old" shelf (sliding, holds 3):
    shelf: [🍟 🍔 🍿]  ← FULL
    🔪 places 🌮 →  🍟 falls off the back!
    shelf: [🍔 🍿 🌮]  ← oldest gone, newest in

4️⃣  "Drop new" shelf (dropping, holds 3):
    shelf: [🍟 🍔 🍿]  ← FULL
    🔪 tries to place 🌮 → 🌮 falls on the floor!
    shelf: [🍟 🍔 🍿]  ← unchanged, newest lost
```

```clojure
;; No shelf — cook hands dish directly to the next cook
(def ch (chan))

;; Shelf holds 10 items before the cook has to wait
(def buffered-ch (chan 10))

;; "Push old" shelf — oldest item falls off when full
(def sliding-ch (chan (sliding-buffer 5)))

;; "Drop new" shelf — new items fall on the floor when full
(def dropping-ch (chan (dropping-buffer 5)))
```

### Blocking operations: `>!!` and `<!!`

**These block the current thread.** Use in the REPL and in `thread` blocks, NOT in `go` blocks.

```
🔪 Cook A (Prep) puts dishes on a shelf that holds 3:

  >!! 🍟  → shelf: [🍟 ·  · ]     Cook A keeps prepping
  >!! 🍔  → shelf: [🍟 🍔 · ]     Cook A keeps prepping
  >!! 🍿  → shelf: [🍟 🍔 🍿]     Shelf full!

🍳 Cook B (Fry) takes from the other side:

  <!! → gets 🍟   shelf: [🍔 🍿 · ]
  <!! → gets 🍔   shelf: [🍿 ·  · ]
  <!! → gets 🍿   shelf: [·  ·  · ]   Empty!
```

```clojure
(def ch (chan 3))

;; Put values (blocking):
(>!! ch {:method :spei :amount 1200})
(>!! ch {:method :credit-card :amount 500})
(>!! ch {:method :debit-card :amount 300})

;; Take values (blocking):
(<!! ch)  ;; => {:method :spei, :amount 1200}
(<!! ch)  ;; => {:method :credit-card, :amount 500}
(<!! ch)  ;; => {:method :debit-card, :amount 300}
```

> 🎓 **SOCRATIC:** *"What happens if you `>!!` on a full unbuffered channel with no one taking?"*
> → Your REPL hangs. The put blocks forever waiting for a taker. This is why you need buffered channels or concurrent consumers.

### Closing channels

Cook A places a couple of dishes, then **closes the window** for the night. Cook B can still take what's left — but once the shelf is empty, the window just returns nothing forever.

```
🔪 Cook A puts dishes, then closes the window:

  >!! 🍟  → shelf: [🍟 · ]
  >!! 🍔  → shelf: [🍟 🍔]
  close!  → 🚫 WINDOW CLOSED (but 🍟 and 🍔 still on shelf!)

🍳 Cook B takes what's left:

  <!! → 🍟  ✅ still there
  <!! → 🍔  ✅ still there
  <!! → 💤  nil — window closed, shelf empty
  <!! → 💤  nil — forever

🔪 A late cook tries to put something:

  >!! 🌮 → false — window is shut, 🌮 not accepted
```

```clojure
(def ch (chan 3))
(>!! ch :a)
(>!! ch :b)
(close! ch)

;; After close: takes return remaining values, then nil forever
(<!! ch)  ;; => :a
(<!! ch)  ;; => :b
(<!! ch)  ;; => nil (channel closed, no more values)
(<!! ch)  ;; => nil

;; After close: puts are silently dropped
(>!! ch :c)  ;; returns false (not delivered)
```

> **Key insight:** `nil` on a channel means "closed." This is why **you cannot put `nil` on a channel** — it would be ambiguous.

### Thread — blocking operations on a real thread

Unlike the part-time cooks (go blocks) who share stations, `thread` gives you a **full-time cook with their own dedicated station**. They can take as long as they need — nobody else is waiting for that workspace.

```
🧑‍🍳 Full-time cook gets their OWN station (a real OS thread):

  🧑‍🍳: "I have my own kitchen! I can take my time."
  🧑‍🍳  ... slow-roasting ... (1 second)
  🧑‍🍳 → places result on window: 🍽️ {:status :done}

  Other cooks wait at the window for the result:
  <!! → 🍽️ {:status :done}   ✅
```

```clojure
;; `thread` runs code on a real thread (not a go block).
;; Returns a channel that receives the result.
(def result-ch
  (thread
    (println "Processing on thread:" (.getName (Thread/currentThread)))
    (Thread/sleep 1000)
    {:status :done}))

(<!! result-ch)
;; => {:status :done}
```

---

## 1.2 — Go Blocks (20 min)

### What are go blocks?

Think of go blocks as **part-time cooks who share a few stations** (a thread pool). They look like they each have their own station, but behind the scenes they take turns using a limited set of workspaces. Clojure transforms the code inside a `go` block via a state machine at compile time (this is macro magic from core.async).

```
👩‍🍳👨‍🍳👩‍🍳 Three part-time cooks, but only 2 stations (thread pool):

  👩‍🍳 Cook 1 starts at station A → "I need a 🥔 from the window..."
     → no 🥔 yet → 👩‍🍳 steps aside (PARKS 💤), frees station A

  👨‍🍳 Cook 2 takes station A → chops 🌽, done → leaves station A

  🥔 arrives on the window!
  👩‍🍳 Cook 1 wakes up, takes station B → grabs 🥔 → makes 🍟 → done!

  Key: nobody BLOCKED a station — they politely stepped aside.
  That's why you can have 1000 go blocks on 8 threads.
```

```clojure
;; go returns a channel that receives the block's result
(def result-ch
  (go
    (println "In go block:" (.getName (Thread/currentThread)))
    (<! (timeout 100))  ;; "sleep" without blocking a thread
    {:status :authorized :method :spei}))

(<!! result-ch)
;; => {:status :authorized, :method :spei}
```

### `>!` and `<!` — non-blocking channel operations (ONLY inside go blocks)

Inside go blocks, cooks use `>!` and `<!` — they **park** (step aside) instead of blocking the whole station.

```
👨‍🍳 Producer cook puts dishes on window (parks if full):

  👨‍🍳 >! 🍟  → places on window, continues working
  👨‍🍳 >! 🍔  → places on window, continues working
  👨‍🍳 close! → shuts window for the night

👩‍🍳 Consumer cook takes from window (parks if empty):

  👩‍🍳 <! → 🍟  "Got fries!"    → processes it
  👩‍🍳 <! → 🍔  "Got burger!"   → processes it
  👩‍🍳 <! → nil  "Window closed — going home 🏠"
```

```clojure
;; >! and <! MUST be used inside go blocks (they "park" instead of blocking)
(def ch (chan))

;; Producer go block:
(go
  (>! ch {:method :spei :amount 1200})
  (>! ch {:method :credit-card :amount 500})
  (close! ch))

;; Consumer go block:
(go
  (loop []
    (when-let [payment (<! ch)]
      (println "Received:" (:method payment) (:amount payment))
      (recur))))
;; Received: :spei 1200
;; Received: :credit-card 500
```

### Blocking vs parking

```
🔪 >!! / <!! — Full-time cook (thread):
  "I'm standing here until I get my dish. Nobody else can use this station."
  → BLOCKS the station (expensive — one thread stuck)

👩‍🍳 >! / <! — Part-time cook (go block):
  "No dish yet? I'll step aside and let someone else use this station."
  → PARKS and yields (cheap — thread freed for other cooks)
```

```
Operation    Where to use      What happens when blocked
─────────    ────────────────  ─────────────────────────────────
>!! / <!!    Real threads      BLOCKS the OS thread (expensive)
>! / <!      go blocks only    PARKS the go block (cheap — frees the thread)
```

> 🎓 **SOCRATIC:** *"Why can't you use `>!` outside a go block?"*
> → `>!` relies on the state machine that the `go` macro generates. Outside `go`, there's no state machine — so there's nothing to "park." You'll get an AssertionError.

### `go-loop` — the common pattern

`go-loop` = `(go (loop ...))` — this is the **daily routine** of a station cook. They take an item, process it, go back to the window, take the next one, repeat until the window closes.

```
👩‍🍳 Fry cook's daily routine (go-loop):

  🔁 loop:
     <! from window → got 🥔?
        ✅ → fry it → 🍟 → print "Processing: 🍟" → back to 🔁

     <! from window → got 🌽?
        ✅ → pop it → 🍿 → print "Processing: 🍿" → back to 🔁

     <! from window → got 🍔?
        ✅ → grill it → 🍔 → print "Processing: 🍔" → back to 🔁

     <! from window → nil?
        🚫 → window closed → go home 🏠
```

```clojure
(def payment-ch (chan 100))

;; Consumer: process payments until channel closes
(go-loop []
  (when-let [payment (<! payment-ch)]
    (println "Processing:" (:method payment) (:amount payment))
    (recur)))

;; Producer: send some payments
(go
  (doseq [p [{:method :spei :amount 1200}
              {:method :debit-card :amount 350}
              {:method :credit-card :amount 5000}]]
    (>! payment-ch p))
  (close! payment-ch))
```

### Error handling in go blocks

What happens when a cook finds a rotten ingredient? If they just drop it on the floor and say nothing, nobody knows there was a problem. **Always handle errors explicitly in go blocks** — otherwise they vanish silently.

```
👩‍🍳 Cook finds a bad ingredient:

  <! from window → got 🥔 with amount -500?!
     🤢 "Negative amount!" → throw! 💥

  WITHOUT try/catch:
     💥 error goes on the result channel
     Nobody reads the result channel → error LOST FOREVER 😱

  WITH try/catch:
     💥 → catch → 📢 "ERROR: Negative amount" → logged, visible
```

```clojure
;; Setup: a channel with one good and one bad payment
(def error-demo-ch (chan 2))
(>!! error-demo-ch {:method :spei :amount 500})
(>!! error-demo-ch {:method :spei :amount -200})
(close! error-demo-ch)

;; Consumer WITH try/catch — errors are caught and logged:
(def error-result-ch
  (go-loop []
    (when-let [payment (<! error-demo-ch)]
      (try
        (if (neg? (:amount payment))
          (throw (ex-info "Negative amount" {:payment payment}))
          (println "OK:" (:amount payment)))
        (catch Exception e
          (println "ERROR in go block:" (.getMessage e))))
      (recur))))

(<!! error-result-ch)
;; OK: 500
;; ERROR in go block: Negative amount
```

> 🎓 **SOCRATIC:** *"What happens if you throw an exception inside a go block without catching it?"*
> → The error is captured and put on the go block's result channel. But if nobody reads that channel, the error is silently lost. **Always catch exceptions in go blocks.**

### Timeout channels

A `timeout` is like a **kitchen timer** — it creates a channel that automatically closes after N milliseconds. Combined with `alt!`, a cook can say "I'll wait for the special order, but if it doesn't arrive in time, I'll do something else."

```
⏰ Kitchen timer:

  👩‍🍳: "I'll wait for the special order from window A.
       But if nothing arrives in 5 minutes, I'm making 🍟 instead."

  alt!:
    window-A  → 🥩 arrived!    → {:ok 🥩}    "Cook the steak!"
    ⏰ 5 min  → ding! timeout! → {:error :timeout}  "Make 🍟 instead"

  Whichever happens FIRST wins. The other is ignored.
```

```clojure
;; (timeout ms) creates a channel that closes after ms milliseconds.

;; Timeout path — nobody puts on response-ch, timer fires after 2s:
(def response-ch (chan))

(go
  (let [result (async/alt!
                 response-ch ([v] {:ok v})
                 (timeout 2000) ([_] {:error :timeout}))]
    (println "Timeout path:" result)))
;; => (after 2s) Timeout path: {:error :timeout}
```

```clojure
;; Success path — put a value BEFORE the timer fires:
(def response-ch-2 (chan 1))

(go
  (let [result (async/alt!
                 response-ch-2 ([v] {:ok v})
                 (timeout 2000)  ([_] {:error :timeout}))]
    (println "Success path:" result)))

(>!! response-ch-2 {:status :authorized})
;; => (immediately) Success path: {:ok {:status :authorized}}
```

---

## 1.3 — Channel Kata (10 min, individual)

> **Format:** Each student works alone in the REPL. Fill in every `___` to make all assertions pass. **No peeking at the answer key!**

```
🍳 Kitchen rules refresher:

  (chan)       → no shelf (direct handoff)
  (chan N)     → shelf holds N items
  >!! / <!!   → blocking put / take (real threads)
  >! / <!     → parking put / take (go blocks only)
  close!      → shut the window — takes drain, then nil forever
  nil         → "window closed" signal (can't put nil on a channel!)
  dropping    → new items fall on the floor when shelf is full
  sliding     → oldest item falls off the back when shelf is full
```

---

### Kata 1 — Create the right channel

```clojure
;; Fill in ___ so the 5 puts do NOT block:
(def ch (chan ___))

(>!! ch :a) (>!! ch :b) (>!! ch :c) (>!! ch :d) (>!! ch :e)

(assert (= :a (<!! ch)))
(assert (= :b (<!! ch)))
```

### Kata 2 — What happens after close?

```clojure
(def ch (chan 2))
(>!! ch :first)
(close! ch)

(assert (= ___ (<!! ch)))   ;; first take
(assert (= ___ (<!! ch)))   ;; second take — channel drained
(assert (= ___ (<!! ch)))   ;; third take — still drained
```

### Kata 3 — Can you put after close?

```clojure
(def ch (chan 10))
(close! ch)

(assert (= ___ (>!! ch :anything)))   ;; what does put return?
```

### Kata 4 — Dropping buffer: who survives?

```clojure
;; 🍳 "Drop new" shelf — new items fall on the floor when full

(def ch (chan (dropping-buffer 3)))
(>!! ch :a) (>!! ch :b) (>!! ch :c) (>!! ch :d) (>!! ch :e)

(assert (= ___ (<!! ch)))   ;; first
(assert (= ___ (<!! ch)))   ;; second
(assert (= ___ (<!! ch)))   ;; third
```

### Kata 5 — Sliding buffer: who survives?

```clojure
;; 🍳 "Push old" shelf — oldest item falls off when a new one arrives

(def ch (chan (sliding-buffer 3)))
(>!! ch :a) (>!! ch :b) (>!! ch :c) (>!! ch :d) (>!! ch :e)

(assert (= ___ (<!! ch)))   ;; first
(assert (= ___ (<!! ch)))   ;; second
(assert (= ___ (<!! ch)))   ;; third
```

### Kata 6 — Go block result

```clojure
;; A go block returns a channel with its result.
;; Fill in ___ to make the assertion pass:

(def result-ch (go (+ 10 20 ___)))

(assert (= 42 (<!! result-ch)))
```

### Kata 7 — Pick the right operator

```clojure
;; Fill in ___ with the correct put operator for INSIDE a go block:

(def ch (chan 1))
(go (___ ch {:method :spei :amount 500}))

(assert (= {:method :spei :amount 500} (<!! ch)))
```

### Kata 8 — Complete the go-loop

```clojure
;; Fill in ___ so the consumer collects all values into the atom:

(def ch (chan 3))
(def result (atom []))

(>!! ch 10) (>!! ch 20) (>!! ch 30)
(close! ch)

(def done-ch
  (go-loop []
    (when-let [v (<! ch)]
      (swap! result conj ___)
      (recur))))

(<!! done-ch)  ;; wait for consumer to finish
(assert (= [10 20 30] @result))
```

---

### 🔑 Answer key (teacher only — reveal after 10 min)

```
Kata 1:  (chan 5)                — or any N >= 5
Kata 2:  :first, nil, nil       — drain remaining, then nil forever
Kata 3:  false                   — put on closed channel returns false
Kata 4:  :a, :b, :c             — dropping keeps FIRST 3, :d and :e fell on the floor
Kata 5:  :c, :d, :e             — sliding keeps LAST 3, :a and :b fell off the back
Kata 6:  12                      — (+ 10 20 12) = 42
Kata 7:  >!                      — inside go block, use >! (parking), not >!! (blocking)
Kata 8:  v                       — swap! result conj v (the value just taken from the channel)
```

---

# PART 2 — INTERACTIVE PRACTICE

---

## 2.1 — Payment Event Pipeline (15 min, guided)

> **Format:** Teacher walks through building a producer → channel → consumer pipeline.

### The scenario

Build a pipeline where:
1. A **producer** generates payment events
2. Events go through a **channel**
3. A **consumer** processes each event and prints results

### Step 1: Create the channel

```clojure
(def payment-events (chan 50))
```

### Step 2: Build the consumer

```clojure
(defn start-consumer! [ch consumer-id]
  (go-loop []
    (when-let [event (<! ch)]
      (let [start (System/nanoTime)
            ;; Simulate processing time
            _ (<! (timeout (rand-int 50)))
            elapsed (/ (- (System/nanoTime) start) 1e6)]
        (println (str "[Consumer " consumer-id "] "
                      (:method event) " $" (:amount event)
                      " (" (.format (java.text.DecimalFormat. "#.#") elapsed) "ms)")))
      (recur))))

(start-consumer! payment-events "A")
```

### Step 3: Build the producer

```clojure
(defn start-producer! [ch n]
  (go
    (doseq [i (range n)]
      (let [payment {:id     (str "pay-" i)
                     :method (rand-nth [:spei :credit-card :debit-card])
                     :amount (+ 100 (rand-int 10000))
                     :time   (System/currentTimeMillis)}]
        (>! ch payment)
        (<! (timeout (rand-int 20)))))  ;; variable rate
    (println "[Producer] Done — sent" n "events")))

(start-producer! payment-events 20)
;; Watch the consumer print events as they arrive
```

### Step 4: Add a second consumer

```clojure
(start-consumer! payment-events "B")

;; Now start another producer batch:
(start-producer! payment-events 20)
;; Events are distributed between Consumer A and Consumer B
```

> 🎓 **SOCRATIC:** *"Two consumers on the same channel — how are events distributed?"*
> → First-come, first-served. Each event goes to ONE consumer (not both). This is work distribution, not broadcasting.

---

## 2.2 — Multi-Producer Fan-In (10 min, pairs)

> **Format:** Each pair builds a system with multiple producers feeding one consumer.

### The challenge

Create three producers simulating different payment sources:

1. **SPEI producer** — sends events every 100ms
2. **Card producer** — sends events every 50ms
3. **Debit card producer** — sends events every 200ms

All feed into a single channel. One consumer processes everything.

### Student solution

```clojure
(def unified-ch (chan 100))

(defn spei-producer [ch n]
  (go
    (dotimes [i n]
      (<! (timeout 100))
      (>! ch {:source :spei :id (str "spei-" i) :amount (+ 500 (rand-int 50000))}))
    (println "[SPEI] Done")))

(defn card-producer [ch n]
  (go
    (dotimes [i n]
      (<! (timeout 50))
      (>! ch {:source :card :id (str "card-" i) :amount (+ 100 (rand-int 20000))}))
    (println "[Card] Done")))

(defn debit-producer [ch n]
  (go
    (dotimes [i n]
      (<! (timeout 200))
      (>! ch {:source :debit-card :id (str "debit-" i) :amount (+ 50 (rand-int 5000))}))
    (println "[Debit] Done")))

;; Single consumer:
(defn unified-consumer [ch]
  (go-loop [counts {}]
    (if-let [event (<! ch)]
      (let [new-counts (update counts (:source event) (fnil inc 0))]
        (println (str "[" (:source event) "] " (:id event) " $" (:amount event)))
        (recur new-counts))
      (do
        (println "=== Final counts ===" counts)
        counts))))

;; Start consumer FIRST (it parks on <! waiting for events):
(unified-consumer unified-ch)

;; Start producers:
(spei-producer unified-ch 5)
(card-producer unified-ch 10)
(debit-producer unified-ch 3)
;; Events print as they arrive — interleaved from all three sources!

;; After producers finish, close the channel to see final counts:
;; (Wait a bit for all producers to complete, then:)
;; (close! unified-ch)
```

> 🎓 **Discussion:** *"How do you know when ALL producers are done so you can close the channel?"*
> → In production, you'd use a coordination mechanism (e.g., count down, or merge the producer channels). We'll see `async/merge` in the next class.

---

## 2.3 — Backpressure Experiment (10 min, individual)

> **Format:** Each student experiments with different buffer types and sizes.

### The experiment

A fast producer (one event per ms) and a slow consumer (100ms per event). What happens?

```clojure
;; Experiment 1: Unbuffered channel
(let [ch (chan)]
  (go
    (dotimes [i 10]
      (println "Putting" i "at" (System/currentTimeMillis))
      (>! ch i))
    (close! ch))
  (go-loop []
    (when-let [v (<! ch)]
      (println "  Got" v "at" (System/currentTimeMillis))
      (<! (timeout 100))  ;; slow consumer
      (recur))))
;; Observe: producer is forced to wait for consumer on each put
```

```clojure
;; Experiment 2: Buffered channel (size 5)
(let [ch (chan 5)]
  (go
    (dotimes [i 10]
      (println "Putting" i "at" (System/currentTimeMillis))
      (>! ch i))
    (println "Producer done")
    (close! ch))
  (go-loop []
    (when-let [v (<! ch)]
      (println "  Got" v "at" (System/currentTimeMillis))
      (<! (timeout 100))
      (recur))))
;; Observe: first 5 puts are instant (buffer absorbs), then producer slows
```

```clojure
;; Experiment 3: Dropping buffer (size 3)
(let [ch (chan (dropping-buffer 3))]
  (go
    (dotimes [i 10]
      (>! ch i)
      (println "Put" i))
    (println "Producer done — but some events were dropped!")
    (close! ch))
  ;; Wait a bit for producer to finish, then consume:
  (Thread/sleep 500)
  (go-loop []
    (when-let [v (<! ch)]
      (println "  Got" v)
      (recur))))
;; Observe: only first 3 values survive — rest were dropped
```

```clojure
;; Experiment 4: Sliding buffer (size 3)
(let [ch (chan (sliding-buffer 3))]
  (go
    (dotimes [i 10]
      (>! ch i)
      (println "Put" i))
    (println "Producer done — oldest events were dropped!")
    (close! ch))
  ;; Wait a bit for producer to finish, then consume:
  (Thread/sleep 500)
  (go-loop []
    (when-let [v (<! ch)]
      (println "  Got" v)
      (recur))))
;; Observe: only LAST 3 values survive (7, 8, 9) — oldest were evicted
```

### Questions for discussion

> 🎓 **SOCRATIC:**
> 1. *"When would you use a dropping buffer for payments?"*
>    → Rate-limiting: "process at most N pending events, discard overflow"
>
> 2. *"When would you use a sliding buffer?"*
>    → "Always show the latest N events" — dashboards, monitoring
>
> 3. *"When should you use NO buffer (unbuffered)?"*
>    → When you want strict synchronization — producer and consumer handshake on every event

---

## Wrap-up & Key Takeaways (10 min)

### Summary

```
Concept         Kitchen term                          Key function
──────────────  ────────────────────────────────────  ────────────
Channel         Pass-through window between stations  (chan), (chan N)
>!! / <!!       Cook hands/takes dish (waits)         Use in REPL, thread
>! / <!         Part-time cook hands/takes (yields)   Use in go blocks ONLY
go              Part-time cook (shares stations)      Returns result channel
go-loop         go + loop (most common pattern)
close!          Cook closes their window              Takes return nil after
timeout         Timer that rings after N ms           For delays and deadlines
thread          Full-time cook at their own station   For blocking I/O
```

### When to use which

```
Scenario                            Use
──────────────────────────────────  ─────────────────────
CPU-bound lightweight processing    go block
Blocking I/O (HTTP, DB, file)       thread (real thread)
REPL experimentation                >!! / <!! (blocking)
Communication between processes     Channels
Rate limiting                       Buffered channels
"Show me the latest N"              Sliding buffer
"Discard overflow"                  Dropping buffer
"Strict handshake"                  Unbuffered channel
```

> 🎓 **Tease for next class:** *"We can now put and take from channels. But what if a consumer needs to wait on MULTIPLE channels and respond to whichever has data first? What if we want to build pipelines with fan-out and fan-in? That's `alt!`, `merge`, `pub/sub`, and `pipeline` — next class."*
