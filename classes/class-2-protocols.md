# Clojure Advanced Topics — Class 2 (2h)

## Protocols and Extensibility

> **Prerequisite:** Class 1 — Multimethods (`payments-mm` project).
> All examples continue with the Mexican payment rails (SPEI, debit card, credit card).

---

## Agenda

By the end of this class, students will be able to:

- Define contracts with `defprotocol` and implement them with `defrecord`
- Extend protocols to existing types (including Java classes) with `extend-type` and `extend-protocol`
- Explain the performance difference between protocols and multimethods at the JVM level
- Choose between protocols and multimethods for a given problem
- Use REPL tooling to inspect protocols, records, and interfaces

## Timeline

```
 0:00 ┬─ Part 0 — Recap & Motivation ·················· 10 min
      │   Quick review of multimethods
      │   "What if we need speed AND type-based contracts?"
      │
 0:10 ┬─ Part 1 — The Concept: Protocols ·············· 15 min
      │   defprotocol mental model
      │   Protocols vs Java interfaces vs multimethods
      │
 0:25 ┬─ Part 2 — defrecord: Types with Protocols ····· 20 min
      │   Inline implementation, field access, immutability
      │
 0:45 ┬─ Part 3 — extend-type & extend-protocol ······· 20 min
      │   Extending protocols to maps, strings, Java classes
      │
 1:05 ┬─── 5 min break ───
      │
 1:10 ┬─ Part 4 — Java Interop & Performance ·········· 20 min
      │   JVM interface dispatch, type casting, benchmarks
      │
 1:30 ┬─ Part 5 — Data Sources Problem ················ 15 min
      │   Common interface for DB, API, CSV
      │
 1:45 ┬─ Part 6 — Guided Exercise ····················· 10 min
      │   Add a new payment gateway + new data source
      │
 1:55 ┬─ Wrap-up & Key Takeaways ······················  5 min
      │   When to use protocols vs multimethods
 2:00 ┴─ End
```

---

## Part 0 — Recap & Motivation (10 min)

### Quick review

In Class 1, we built payment processing with multimethods:

```clojure
(defmulti validate! :method)
(defmulti process2 (fn [p] [(:method p) (payment-tier p)]))
```

Multimethods gave us **open polymorphism** — dispatch on any value, extend from anywhere.

### The provocation

> **SOCRATIC:** *"Our payment system now processes 10,000 transactions/second. The multimethod dispatch shows up in profiling. Can we make it faster without losing extensibility?"*

> *"Also — our team wants to define a clear contract: every payment gateway MUST implement `authorize`, `capture`, and `refund`. With multimethods, how do you enforce that?"*

You can't. Multimethods are completely open — there's no way to say "you must implement these three operations together." You can forget one and only discover it at runtime.

**Protocols solve both problems:** enforced contracts + JVM-speed dispatch.

---

## Part 1 — The Concept: Protocols (15 min)

### What is a protocol?

A protocol is a **named set of functions** that different types can implement. Think of it as a Clojure-flavored interface.

```clojure
(defprotocol PaymentGateway
  "Contract for payment gateway integrations."
  (authorize [this payment]  "Authorize a payment, return authorization token.")
  (capture   [this payment]  "Capture a previously authorized payment.")
  (refund    [this payment]  "Refund a captured payment."))
```

> **SOCRATIC:** *"This looks like a Java interface. What are the differences?"*

### Protocols vs Java interfaces vs Multimethods

```
Feature                 Java Interface          Clojure Protocol        Clojure Multimethod
─────────────────────   ─────────────────────   ─────────────────────   ─────────────────────
Dispatch on             class of this           type of 1st arg         ANY computed value
Extend existing types?  No (need wrapper)       Yes (extend-type)       Yes (defmethod)
Enforced contract?      Yes (compile-time)      Yes (runtime)           No
Performance             fastest (vtable)        near-interface speed    slower (map lookup)
Multiple dispatch?      No                      No                      Yes
Extend from other JAR?  No                      Yes                     Yes
```

### The mental model

```
┌──────────────────────────────────┐
│  (defprotocol PaymentGateway     │  ← "What operations exist?"
│    (authorize [this payment])    │
│    (capture   [this payment])    │
│    (refund    [this payment]))   │
└──────────┬───────────────────────┘
           │ "Who implements them?"
           ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│ defrecord     │  │ extend-type   │  │ extend-type   │
│ SpeiGateway   │  │ java.util.Map │  │ java.lang.String │
│ (inline impl) │  │ (maps work!)  │  │ (Java classes!) │
└───────────────┘  └───────────────┘  └───────────────┘
```

### Under the hood: what `defprotocol` generates

```clojure
(defprotocol PaymentGateway
  (authorize [this payment])
  (capture   [this payment])
  (refund    [this payment]))
```

This macro generates **three things**:

1. A **JVM interface** `PaymentGateway` (a real Java interface!)
2. **Three Clojure functions** (`authorize`, `capture`, `refund`) that dispatch on the type of the first argument
3. An internal **protocol map** for extending to types that don't directly implement the interface

You can verify in the REPL:

```clojure
;; It's a real Java interface
(class PaymentGateway)
;; => java.lang.Class

;; The generated functions are regular Clojure functions
(type authorize)
;; => the function object

;; Inspect the protocol metadata
(:on-interface PaymentGateway)
```

> **SOCRATIC:** *"In Class 1, we learned that `MultiFn` is a Java class. Now we see that `defprotocol` generates a Java interface. What does this tell you about Clojure's relationship with the JVM?"*
> → Clojure doesn't fight the JVM — it leverages it. Protocols compile down to the JVM's native dispatch mechanism (interface method calls), which is why they're fast.

---

## Part 2 — defrecord: Types with Protocols (20 min)

> **Goal:** Implement the `PaymentGateway` protocol for our Mexican payment methods.

### Creating records that implement the protocol

```clojure
(defrecord SpeiGateway [bank-code timeout-ms]
  PaymentGateway
  (authorize [this payment]
    (let [{:keys [spei-clabe amount]} payment]
      (when-not (and (string? spei-clabe)
                     (re-matches #"\d{18}" spei-clabe)
                     (pos? amount))
        (throw (ex-info "Invalid SPEI payment" {:payment payment})))
      {:status :authorized
       :method :spei
       :bank   (:bank-code this)
       :token  (str "SPEI-" (random-uuid))}))

  (capture [this payment]
    {:status   :confirmed
     :provider :spei-rails
     :bank     (:bank-code this)})

  (refund [this payment]
    {:status   :refund-initiated
     :provider :spei-rails
     :eta      "same-day"}))

(defrecord DebitCardGateway [acquirer-id network]
  PaymentGateway
  (authorize [this payment]
    (let [{:keys [card-number pin amount]} payment]
      (when-not (and (string? card-number)
                     (re-matches #"\d{16}" card-number)
                     (re-matches #"\d{4}" (str pin))
                     (pos? amount))
        (throw (ex-info "Invalid debit card payment" {:payment payment})))
      {:status   :authorized
       :method   :debit-card
       :acquirer (:acquirer-id this)
       :network  (:network this)
       :token    (str "DEBIT-" (random-uuid))}))

  (capture [this _payment]
    {:status   :captured
     :acquirer (:acquirer-id this)
     :network  (:network this)})

  (refund [_this _payment]
    {:status :refund-queued
     :eta    "3-5 business days"}))

(defrecord CardGateway [acquirer-id mcc]
  PaymentGateway
  (authorize [this payment]
    (let [{:keys [card-number cvv holder amount]} payment]
      (when-not (and (string? holder)
                     (pos? amount)
                     (re-matches #"\d{16}" (str card-number))
                     (re-matches #"\d{3,4}" (str cvv)))
        (throw (ex-info "Invalid card payment" {:payment payment})))
      {:status   :authorized
       :method   :credit-card
       :acquirer (:acquirer-id this)
       :token    (str "CARD-" (random-uuid))}))

  (capture [this payment]
    {:status   :captured
     :acquirer (:acquirer-id this)})

  (refund [this payment]
    {:status :refund-queued
     :eta    "5-10 business days"}))
```

### Try it

```clojure
;; Create gateway instances
(def spei-gw  (->SpeiGateway "BANORTE" 5000))
(def debit-gw (->DebitCardGateway "PROSA" :visa-electron))
(def card-gw  (->CardGateway "PROSA" "5411"))

;; Authorize a SPEI payment
(authorize spei-gw {:spei-clabe "032180000118359719" :amount 1200.0})
;; => {:status :authorized, :method :spei, :bank "BANORTE", :token "SPEI-..."}

;; Authorize then capture a card payment
(let [auth-result (authorize card-gw {:card-number "4111111111111111"
                                       :cvv "123"
                                       :holder "Maria"
                                       :amount 350.0})]
  (capture card-gw auth-result))
;; => {:status :captured, :acquirer "PROSA"}

;; Debit card refund
(refund debit-gw {:card-number "5555555555554444" :amount 200.0})
;; => {:status :refund-queued, :eta "3-5 business days"}
```

### Exploring records in the REPL

```clojure
;; Records ARE maps
(def gw (->SpeiGateway "BANORTE" 5000))

(:bank-code gw)        ;; => "BANORTE"
(:timeout-ms gw)       ;; => 5000
(assoc gw :region :mx) ;; => still a SpeiGateway (with extra key!)
(dissoc gw :bank-code) ;; => becomes a regular map (no longer a SpeiGateway)

;; Records are also Java objects
(class gw)             ;; => user.SpeiGateway
(instance? PaymentGateway gw)  ;; => true

;; Two constructors are auto-generated:
;; ->SpeiGateway  — positional
;; map->SpeiGateway — from a map
(= (->SpeiGateway "BANORTE" 5000)
   (map->SpeiGateway {:bank-code "BANORTE" :timeout-ms 5000}))
;; => true
```

> **SOCRATIC:** *"Records are maps AND Java objects. Why does that matter?"*
> → You get the best of both worlds: Clojure's map operations (assoc, update, destructuring) AND JVM-speed method dispatch. No wrapper objects, no adapters.

---

## Part 3 — extend-type & extend-protocol (20 min)

> **Goal:** Extend the protocol to types you don't own — including plain maps and Java classes.

### The problem

What if some parts of the codebase still use plain maps (like our Class 1 code)?

```clojure
;; This payment is a plain map, not a record:
(def legacy-payment {:method     :spei
                     :spei-clabe "032180000118359719"
                     :amount     1200.0})

;; This will fail:
;; (authorize legacy-payment {:amount 100})
;; => No implementation of method: :authorize of protocol: PaymentGateway
```

### extend-type: add protocol support to an existing type

```clojure
;; Make plain Clojure maps work as simple "pass-through" gateways:
(extend-type clojure.lang.PersistentArrayMap
  PaymentGateway
  (authorize [this payment]
    {:status :authorized-via-map
     :method (:method this)
     :token  (str "MAP-" (random-uuid))})
  (capture [this payment]
    {:status :captured-via-map
     :method (:method this)})
  (refund [this payment]
    {:status :refund-via-map
     :method (:method this)}))

;; Also extend PersistentHashMap (larger maps use a different class)
(extend-type clojure.lang.PersistentHashMap
  PaymentGateway
  (authorize [this payment]
    {:status :authorized-via-map
     :method (:method this)
     :token  (str "MAP-" (random-uuid))})
  (capture [this payment]
    {:status :captured-via-map
     :method (:method this)})
  (refund [this payment]
    {:status :refund-via-map
     :method (:method this)}))
```

```clojure
;; Now plain maps work:
(authorize {:method :spei} {:amount 100})
;; => {:status :authorized-via-map, :method :spei, :token "MAP-..."}
```

> **SOCRATIC:** *"Why two extend-type calls? Why PersistentArrayMap AND PersistentHashMap?"*
> → Small maps (up to 8 keys) use `PersistentArrayMap`. Larger maps use `PersistentHashMap`. They're different Java classes. Protocols dispatch on the JVM type, so you need both. This is a real-world gotcha.

### extend-protocol: same thing, organized by protocol

`extend-protocol` flips the grouping — one protocol, multiple types at once:

```clojure
(defprotocol Auditable
  "Types that can produce an audit log entry."
  (audit-entry [this action]))

(extend-protocol Auditable
  ;; For our record types:
  SpeiGateway
  (audit-entry [this action]
    {:gateway :spei :bank (:bank-code this) :action action})

  CardGateway
  (audit-entry [this action]
    {:gateway :card :acquirer (:acquirer-id this) :action action})

  DebitCardGateway
  (audit-entry [this action]
    {:gateway :debit-card :acquirer (:acquirer-id this) :action action})

  ;; For plain maps (legacy code):
  clojure.lang.IPersistentMap
  (audit-entry [this action]
    {:gateway :unknown :method (:method this) :action action}))
```

```clojure
(audit-entry spei-gw :authorize)
;; => {:gateway :spei, :bank "BANORTE", :action :authorize}

(audit-entry {:method :crypto} :authorize)
;; => {:gateway :unknown, :method :crypto, :action :authorize}
```

> **Tip:** In the last `extend-protocol` example we used `clojure.lang.IPersistentMap` (the **interface** that both map types implement) instead of listing both concrete classes. This is the idiomatic way to cover all map types at once.

### extend-type vs extend-protocol: when to use each

```
Use case                                                          Choose
────────────────────────────────────────────────────────────────  ───────────────
"I have a type and want to implement several protocols for it"    extend-type
"I have a protocol and want to implement it for several types"    extend-protocol
"I'm defining a new type and implementing the protocol inline"    defrecord
```

They compile to the same thing — the choice is about code organization.

---

## Part 4 — Java Interop & Performance (20 min)

> **Goal:** Show protocols working with Java classes and explain why they're fast.

### Extending protocols to Java classes

Your payment system receives amounts as different Java types. Let's normalize them:

```clojure
(defprotocol MoneyAmount
  "Normalize different representations to a canonical payment amount."
  (to-amount [this] "Convert to a double amount in MXN."))

(extend-protocol MoneyAmount
  ;; Java Double — already a number
  java.lang.Double
  (to-amount [this] this)

  ;; Java Long / Integer — convert to double
  java.lang.Long
  (to-amount [this] (double this))

  java.lang.Integer
  (to-amount [this] (double this))

  ;; Java String — parse "1,234.56" or "1234.56"
  java.lang.String
  (to-amount [this]
    (-> this
        (clojure.string/replace "," "")
        (Double/parseDouble)))

  ;; Java BigDecimal — common in financial APIs
  java.math.BigDecimal
  (to-amount [this] (.doubleValue this))

  ;; Clojure maps — extract :amount key
  clojure.lang.IPersistentMap
  (to-amount [this]
    (to-amount (:amount this))))
```

```clojure
;; Works with any Java type:
(to-amount 1200.0)                          ;; => 1200.0
(to-amount 1200)                            ;; => 1200.0
(to-amount "1,234.56")                      ;; => 1234.56
(to-amount (BigDecimal. "999.99"))           ;; => 999.99
(to-amount {:amount "2,500.00" :currency :MXN}) ;; => 2500.0

;; Use it in a payment flow:
(let [raw-amount "15,000.50"
      payment    {:method :spei
                  :spei-clabe "032180000118359719"
                  :amount (to-amount raw-amount)}]
  (authorize spei-gw payment))
;; => {:status :authorized, :method :spei, :bank "BANORTE", ...}
```

> **SOCRATIC:** *"We just made `String`, `BigDecimal`, and `Long` — classes we didn't write — support our protocol. Could you do this in Java?"*
> → Not without wrapping them. You can't add an interface to `java.lang.String`. Protocols make Java's closed classes open for extension.

### Working with Java collections

```clojure
(defprotocol PaymentBatch
  "Process a batch of payments from different sources."
  (process-batch [this gateway]))

(extend-protocol PaymentBatch
  ;; Clojure vector of payments
  clojure.lang.PersistentVector
  (process-batch [this gateway]
    (mapv #(authorize gateway %) this))

  ;; Java ArrayList (from a Java API or JDBC result)
  java.util.ArrayList
  (process-batch [this gateway]
    (mapv #(authorize gateway %) (seq this)))

  ;; Java array
  (Class/forName "[Ljava.lang.Object;")
  (process-batch [this gateway]
    (mapv #(authorize gateway %) (seq this))))
```

```clojure
;; From a Clojure vector
(process-batch [{:spei-clabe "032180000118359719" :amount 100.0}
                {:spei-clabe "032180000118359719" :amount 200.0}]
               spei-gw)

;; From a Java ArrayList (simulating a JDBC result)
(process-batch (java.util.ArrayList.
                 [{:spei-clabe "032180000118359719" :amount 300.0}])
               spei-gw)
```

### Why protocols are faster: JVM dispatch

This is the key insight. Let's compare what happens at the JVM level:

**Multimethod dispatch (Class 1):**

```
(process2 payment)
  1. Call dispatch-fn(payment)         → compute [:spei :standard]
  2. Look up [:spei :standard] in      → HashMap-like lookup
     the MultiFn's methodTable
  3. If not cached, walk isa? hierarchy → potentially slow
  4. Cache the result                   → subsequent calls faster
  5. Call the matched function
```

**Protocol dispatch:**

```
(authorize spei-gw payment)
  1. JVM checks: does spei-gw's class  → native instanceof check
     implement the PaymentGateway       → single CPU instruction
     interface?
  2. YES → call the interface method    → direct vtable dispatch
     directly                           → same speed as Java
```

> **SOCRATIC:** *"The JVM has been optimizing interface dispatch for 25+ years. Why would we NOT leverage that?"*

### Benchmark: protocols vs multimethods

```clojure
;; --- Multimethod version (from Class 1) ---
(defmulti process-mm :method)

(defmethod process-mm :spei [{:keys [amount]}]
  {:status :confirmed :amount amount})

(defmethod process-mm :credit-card [{:keys [amount]}]
  {:status :approved :amount amount})

;; --- Protocol version ---
(defprotocol FastProcess
  (process-fast [this]))

(defrecord SpeiPayment [amount clabe]
  FastProcess
  (process-fast [_this]
    {:status :confirmed :amount amount}))

(defrecord CardPayment [amount card-number holder]
  FastProcess
  (process-fast [_this]
    {:status :approved :amount amount}))

;; --- Benchmark ---
(def test-map  {:method :spei :amount 1200.0})
(def test-rec  (->SpeiPayment 1200.0 "032180000118359719"))

;; Simple timing (for demonstration — use criterium for real benchmarks)
(time (dotimes [_ 1000000] (process-mm test-map)))
;; ~150-300ms (multimethod: dispatch fn + map lookup + cache check)

(time (dotimes [_ 1000000] (process-fast test-rec)))
;; ~30-60ms (protocol: direct JVM interface call)
```

> The protocol version is typically **3-5x faster** because it compiles to a direct JVM `invokeinterface` bytecode instruction — the same mechanism Java uses for its own interfaces.

### When does performance matter?

```
Scenario                                        Recommendation
──────────────────────────────────────────────  ──────────────────────────────
HTTP handler (ms latency)                       Doesn't matter — use either
Hot loop processing millions of records         Protocol (measurable difference)
Payment validation (called once per request)    Doesn't matter — clarity wins
Real-time event stream                          Protocol
```

> **SOCRATIC:** *"If both work and multimethods are more flexible, when would you pick protocols?"*
> → When you need **type-based contracts** (enforce a set of operations), **Java interop**, or **performance in hot paths**. In our payment system, the gateway integrations are a good fit — they're type-based, they need a clear contract, and they might be called in batches.

---

## Part 5 — Data Sources Problem (15 min)

> **Goal:** Apply protocols to a real architectural problem — multiple data sources.

### The scenario

Your payment system needs to load transaction history from three different sources:

```
Source          Description                     Format
──────────────  ──────────────────────────────  ────────────
Database        PostgreSQL with JDBC            Result sets
External API    Partner bank REST API           JSON strings
CSV file        Monthly reconciliation export   CSV rows
```

> **SOCRATIC:** *"How would you solve this with multimethods? How about with Java interfaces?"*
> → Multimethods: dispatch on a `:source-type` key. Works but no contract enforcement.
> → Java interfaces: need to own all the classes. Can't extend `String` or `File`.
> → Protocols: define a contract, extend it to anything, including Java types.

### Define the contract

```clojure
(defprotocol TransactionSource
  "Contract for loading payment transactions from any source."
  (fetch-transactions [this criteria]
    "Fetch transactions matching criteria. Returns a seq of maps.")
  (source-info [this]
    "Return metadata about this source."))
```

### Implement for different source types

```clojure
(defrecord DatabaseSource [db-spec table-name]
  TransactionSource
  (fetch-transactions [this {:keys [from-date to-date method]}]
    ;; In real code, this would use JDBC.
    ;; Simulated for the classroom:
    (println (str "Querying " (:table-name this)
                  " WHERE date BETWEEN " from-date " AND " to-date))
    [{:id 1 :method method :amount 1200.0 :source :db}
     {:id 2 :method method :amount 350.0  :source :db}])

  (source-info [this]
    {:type :database :table (:table-name this)}))

(defrecord ApiSource [base-url api-key]
  TransactionSource
  (fetch-transactions [this {:keys [from-date to-date method]}]
    (println (str "GET " (:base-url this) "/transactions?method=" (name method)))
    [{:id 101 :method method :amount 5000.0 :source :api}])

  (source-info [this]
    {:type :api :url (:base-url this)}))

(defrecord CsvSource [file-path delimiter]
  TransactionSource
  (fetch-transactions [this {:keys [method]}]
    (println (str "Reading " (:file-path this)))
    [{:id 201 :method method :amount 999.0 :source :csv}])

  (source-info [this]
    {:type :csv :file (:file-path this)}))
```

### Use them uniformly

```clojure
(def sources [(->DatabaseSource {:host "localhost"} "transactions")
              (->ApiSource "https://api.banco.mx" "secret-key")
              (->CsvSource "/data/reconciliation-2025-01.csv" ",")])

;; Load from ALL sources with one function:
(defn load-all-transactions [sources criteria]
  (->> sources
       (mapcat #(fetch-transactions % criteria))
       (into [])))

(load-all-transactions sources {:from-date "2025-01-01"
                                :to-date   "2025-01-31"
                                :method    :spei})
;; Querying transactions WHERE date BETWEEN 2025-01-01 AND 2025-01-31
;; GET https://api.banco.mx/transactions?method=spei
;; Reading /data/reconciliation-2025-01.csv
;; => [{:id 1, ...} {:id 2, ...} {:id 101, ...} {:id 201, ...}]
```

### Extend to a Java type — java.io.File

What if someone passes a raw `File` object?

```clojure
(extend-type java.io.File
  TransactionSource
  (fetch-transactions [this {:keys [method]}]
    (println (str "Reading file: " (.getAbsolutePath this)))
    ;; In production: parse the file contents
    [{:id 301 :method method :source :file :path (.getName this)}])

  (source-info [this]
    {:type :file :name (.getName this) :size (.length this)}))

;; Now java.io.File objects are valid transaction sources:
(def report-file (java.io.File. "/data/report.csv"))
(source-info report-file)
;; => {:type :file, :name "report.csv", :size 0}

;; Add it to our sources seamlessly:
(load-all-transactions (conj sources report-file)
                       {:method :credit-card})
```

> **SOCRATIC:** *"We just made `java.io.File` — a class from 1996 — satisfy our 2025 Clojure protocol. How many lines of the File class did we edit?"*
> → Zero. That's the power of `extend-type`.

---

## Part 6 — Guided Exercise (10 min)

> Students do these themselves. Teacher walks around and helps.

### Exercise 1: Add a new payment gateway

Add a `MercadoPagoGateway` record that implements `PaymentGateway`:

- Fields: `api-key`, `sandbox?`
- `authorize`: validate that payment has `:mp-token` and positive `:amount`, return `{:status :authorized :provider :mercado-pago}`
- `capture`: return `{:status :captured :provider :mercado-pago}`
- `refund`: return `{:status :refund-queued :eta "24h"}`

Also make it `Auditable`.

```clojure
;; Student solution (reveal after they try):

(defrecord MercadoPagoGateway [api-key sandbox?]
  PaymentGateway
  (authorize [this payment]
    (when-not (and (:mp-token payment) (pos? (:amount payment)))
      (throw (ex-info "Invalid MercadoPago payment" {:payment payment})))
    {:status   :authorized
     :provider :mercado-pago
     :sandbox? (:sandbox? this)
     :token    (str "MP-" (random-uuid))})

  (capture [_this _payment]
    {:status :captured :provider :mercado-pago})

  (refund [_this _payment]
    {:status :refund-queued :eta "24h"})

  Auditable
  (audit-entry [this action]
    {:gateway :mercado-pago :sandbox? (:sandbox? this) :action action}))

;; Test it:
(def mp-gw (->MercadoPagoGateway "mp-key-123" true))

(authorize mp-gw {:mp-token "tok_abc" :amount 150.0})
;; => {:status :authorized, :provider :mercado-pago, :sandbox? true, ...}

(audit-entry mp-gw :authorize)
;; => {:gateway :mercado-pago, :sandbox? true, :action :authorize}
```

> **SOCRATIC:** *"How many existing lines did you edit?"* → Zero. Same as multimethods.
> *"But now the compiler tells you if you forgot `capture` or `refund`. Did multimethods do that?"* → No.

### Exercise 2 (bonus): Extend MoneyAmount to a new Java type

Make `java.util.Currency` return its numeric code as a double:

```clojure
;; Solution:
(extend-type java.util.Currency
  MoneyAmount
  (to-amount [this]
    (double (.getNumericCode this))))

(to-amount (java.util.Currency/getInstance "MXN"))
;; => 484.0
```

---

## Wrap-up & Key Takeaways (5 min)

### Decision guide: Protocols vs Multimethods

```
                          ┌─────────────────────────┐
                          │ What are you dispatching │
                          │        on?               │
                          └────────┬────────────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │                             │
              Type of first arg           Computed value / multiple args
                    │                             │
                    ▼                              ▼
            ┌──────────────┐              ┌───────────────┐
            │  PROTOCOL    │              │  MULTIMETHOD   │
            │              │              │                │
            │ • Fast       │              │ • Flexible     │
            │ • Contract   │              │ • Any dispatch │
            │ • Java types │              │ • Hierarchies  │
            └──────────────┘              └───────────────┘
```

```
Use                                Protocols                        Multimethods
─────────────────────────────────  ───────────────────────────────  ──────────────────────────
Payment gateway integrations       Yes — type-based, need contract
Risk tier routing                                                   Yes — computed [method tier]
Java class interop                 Yes — extend-type
Settlement rules by [method curr]                                   Yes — juxt dispatch
Data source abstraction            Yes — clear contract per source
Fee calculation by hierarchy                                        Yes — derive + isa?
```

### Summary

1. **`defprotocol`** = named set of functions (contract). Generates a JVM interface.
2. **`defrecord`** = immutable type with named fields. Implements protocols inline. Is a map AND a Java object.
3. **`extend-type`** = add protocol support to a type you don't own (even Java classes).
4. **`extend-protocol`** = same, but organized by protocol instead of type.
5. **Performance** = protocols use JVM interface dispatch (3-5x faster than multimethods in hot paths).
6. **Choose protocols** when dispatch is type-based and you want a contract. **Choose multimethods** when dispatch is value-based or multi-dimensional.

> **Tease for next class:** *"We've seen how to create contracts (protocols) and how to dispatch on arbitrary values (multimethods). Next class: Spec — how to describe the SHAPE of data and validate it automatically."*

---

## Appendix — Extra Examples (Backup)

> Use if you finish early.

### A) Satisfies? and runtime introspection

```clojure
;; Check if a value satisfies a protocol:
(satisfies? PaymentGateway spei-gw)    ;; => true
(satisfies? PaymentGateway {:a 1})     ;; => true (we extended maps!)
(satisfies? PaymentGateway "hello")    ;; => false

;; Check if a value's type directly implements (not via extend):
(instance? PaymentGateway spei-gw)     ;; => true (defrecord = implements)
(instance? PaymentGateway {:a 1})      ;; => false (extend-type != implements)
```

> **SOCRATIC:** *"Why is `satisfies?` true for maps but `instance?` is false?"*
> → `extend-type` doesn't change the class — it registers a lookup in the protocol's dispatch table. `instance?` checks the Java class hierarchy. `satisfies?` checks both the class AND the dispatch table.

### B) Reify: anonymous protocol implementations

```clojure
;; One-off implementation without creating a named type:
(def test-gateway
  (reify PaymentGateway
    (authorize [_ payment]
      {:status :test-authorized :amount (:amount payment)})
    (capture [_ _] {:status :test-captured})
    (refund [_ _] {:status :test-refunded})))

;; Useful for testing:
(authorize test-gateway {:amount 100.0})
;; => {:status :test-authorized, :amount 100.0}
```

### C) Protocol + multimethod: composing both

Use a protocol for the gateway contract, and a multimethod for routing TO the gateway:

```clojure
(defmulti resolve-gateway
  "Pick the right gateway based on payment method and country."
  (juxt :method :country))

(defmethod resolve-gateway [:spei :MX]        [_] spei-gw)
(defmethod resolve-gateway [:debit-card :MX]   [_] debit-gw)
(defmethod resolve-gateway [:credit-card :MX]  [_] card-gw)
(defmethod resolve-gateway [:credit-card :US]  [_] (->CardGateway "STRIPE" "5411"))
(defmethod resolve-gateway :default            [_] card-gw)

;; Full flow: multimethod picks the gateway, protocol handles the operation
(defn pay! [payment]
  (let [gateway (resolve-gateway payment)]
    (authorize gateway payment)))

(pay! {:method :spei :country :MX
       :spei-clabe "032180000118359719" :amount 1200.0})
;; => {:status :authorized, :method :spei, :bank "BANORTE", ...}

(pay! {:method :credit-card :country :US
       :card-number "4111111111111111" :cvv "123"
       :holder "John" :amount 50.0})
;; => {:status :authorized, :method :credit-card, :acquirer "STRIPE", ...}
```

> **SOCRATIC:** *"Why not use ONLY multimethods or ONLY protocols?"*
> → Different tools for different jobs. The multimethod gives you flexible ROUTING (on `[method country]`). The protocol gives you a fast, enforced CONTRACT (authorize/capture/refund). Together they're more powerful than either alone.

---

## Teacher Reference: Protocol Internals Cheat Sheet

```
defprotocol PaymentGateway
  ├── Generates JVM interface: PaymentGateway.class
  ├── Generates fn: authorize  (dispatches on type of 1st arg)
  ├── Generates fn: capture
  └── Generates fn: refund

defrecord SpeiGateway [fields...] PaymentGateway (...)
  ├── Generates Java class: SpeiGateway.class implements PaymentGateway
  ├── Generates: ->SpeiGateway (positional factory)
  ├── Generates: map->SpeiGateway (map factory)
  └── Implements: ILookup, IPersistentMap, Associative, ... (it's a map!)

extend-type clojure.lang.PersistentArrayMap PaymentGateway (...)
  └── Registers functions in the protocol's internal dispatch table
      (does NOT modify PersistentArrayMap.class)

Dispatch flow:
  (authorize x payment)
    1. Is x's class in the protocol's interface? → direct Java call (fast)
    2. Else, is x's class in the protocol's extension table? → function lookup
    3. Else → throw "No implementation"
```