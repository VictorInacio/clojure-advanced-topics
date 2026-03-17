# Clojure Advanced Topics — Class 9 (2h)

## Java Interoperability — Harnessing the JVM

> **Prerequisite:** Classes 1–8.
> All examples continue with the Mexican payment rails (SPEI, credit card, debit card).

---

## Agenda

By the end of this class, students will be able to:

- Call Java methods, constructors, and static fields from Clojure
- Use `doto` for fluent Java object setup
- Work with `java.time` for payment timestamps and due dates
- Implement Java interfaces with `reify`
- Extend Java classes with `proxy`
- Apply type hints to eliminate reflection warnings and improve performance
- Navigate Java collections and convert between Java and Clojure types
- Use Java libraries (hashing, formatting, I/O) in real payment scenarios

## Timeline

```
 0:00 ┬─ Part 0 — Recap & Motivation ·················· 10 min
      |   "Your Clojure code runs on the JVM — use ALL of it"
      |
 0:10 ┬─ PART 1 — CONTENT PRESENTATION ················
      |
 0:10 ├─ 1.1 — Interop Fundamentals ··················· 15 min
      |   .method, Class/static, new, doto, ..
      |
 0:25 ├─ 1.2 — java.time for Payment Dates ············ 20 min
      |   Instant, LocalDate, Duration, Period
      |   Formatting and parsing
      |
 0:45 ├─ 1.3 — Implementing Java Interfaces: reify ···· 15 min
      |   Comparator, Callable, Runnable
      |   Multiple interfaces at once
      |
 1:00 ├─ 1.4 — Extending Classes: proxy ··············· 10 min
      |   proxy for abstract classes, override methods
      |
 1:10 ┬─── 5 min break ───
      |
 1:15 ├─ 1.5 — Type Hints & Reflection ················ 15 min
      |   ^String, *warn-on-reflection*, benchmarking
      |
 1:30 ├─ 1.6 — Java Collections & Streams ············· 10 min
      |   ArrayList, HashMap, arrays, Java Streams
      |   Clojure <-> Java conversions
      |
 1:40 ├─ 1.7 — Practical Patterns ····················· 10 min
      |   Hashing payments (MessageDigest)
      |   Currency formatting (NumberFormat)
      |   try/catch with Java exceptions
      |
 1:50 ├─ 1.8 — Java Interop Kata ····················· 15 min
      |   Fill-in-the-blank: all concepts combined
      |
 2:05 ┬─ Wrap-up & Key Takeaways ······················  5 min
 2:10 ┴─ End
```

---

# PART 1 — CONTENT PRESENTATION

---

## Part 0 — Recap & Motivation (10 min)

### The provocation

> "You've been writing Clojure for 8 classes. Every `println`, every `Thread/sleep`, every `System/currentTimeMillis` — that was Java interop. You've been doing it all along without thinking about it."

Clojure runs on the **JVM**. The entire Java ecosystem — thousands of libraries, battle-tested APIs, decades of engineering — is available to you with **zero ceremony**. No FFI, no bindings, no wrappers.

> **SOCRATIC:** *"Your payment system needs to: compute SHA-256 hashes for receipts, format currency for Mexican pesos, calculate due dates with business-day rules, and sort transactions by multiple criteria. Do you write all of this from scratch in Clojure?"*

-> No! Java already has `MessageDigest`, `NumberFormat`, `java.time`, and `Comparator`. Clojure's interop lets you use them directly.

### What we already know (quick check)

```clojure
;; You've been using Java interop since Class 1:
(println "Hello")                       ;; calls System.out.println
(System/currentTimeMillis)              ;; static method call
(Thread/sleep 100)                      ;; static method call
(.getName (Thread/currentThread))       ;; instance method call
(str "abc")                             ;; String interop under the hood
```

Today we formalize everything and add the advanced patterns: `reify`, `proxy`, type hints, and real library usage.

---

## 1.1 — Interop Fundamentals (15 min)

### The four forms of Java interop

```
Form                         Clojure syntax              Java equivalent
───────────────────────────  ──────────────────────────  ─────────────────────────
Instance method              (.method obj args)           obj.method(args)
Static method                (Class/method args)          Class.method(args)
Static field                 Class/FIELD                  Class.FIELD
Constructor                  (ClassName. args)            new ClassName(args)
```

### Instance methods

```clojure
;; .method on an object — the dot comes FIRST:
(.toUpperCase "hello")
;; => "HELLO"

(.substring "SPEI-Transfer-001" 0 4)
;; => "SPEI"

;; Chain methods — read inside-out (like function composition):
(.contains (.toUpperCase "spei-transfer") "SPEI")
;; => true

;; Get the class of any value:
(.getClass 42)
;; => java.lang.Long

(.getClass "hello")
;; => java.lang.String

(.getClass {:a 1})
;; => clojure.lang.PersistentArrayMap
```

> **SOCRATIC:** *"In Java you write `obj.method(args)`. In Clojure you write `(.method obj args)`. Why does Clojure put the method first?"*
> -> Because Clojure is a Lisp — the operator always comes first: `(operator operand ...)`. The dot-method IS the operator.

### Static methods and fields

```clojure
;; Static method — Class/method:
(Math/sqrt 144)
;; => 12.0

(Math/pow 2 10)
;; => 1024.0

(Integer/parseInt "42")
;; => 42

(Long/parseLong "1234567890")
;; => 1234567890

;; Static fields — Class/FIELD (no parentheses!):
Math/PI
;; => 3.141592653589793

Integer/MAX_VALUE
;; => 2147483647

Long/MAX_VALUE
;; => 9223372036854775807
```

### Constructors

```clojure
;; ClassName. — note the trailing dot:
(java.util.Date.)
;; => #inst "2026-..."

(java.util.ArrayList.)
;; => []

(java.util.ArrayList. [1 2 3])
;; => [1 2 3]

(StringBuilder. "Payment: ")
;; => #object[java.lang.StringBuilder "Payment: "]

;; String constructor with bytes:
(String. (.getBytes "Hola SPEI" "UTF-8") "UTF-8")
;; => "Hola SPEI"
```

### `doto` — fluent object setup

When you need to call multiple methods on the same object (builder pattern):

```clojure
;; Without doto (repetitive):
(let [sb (StringBuilder.)]
  (.append sb "Payment: ")
  (.append sb "SPEI")
  (.append sb " $1,200")
  (.toString sb))
;; => "Payment: SPEI $1,200"

;; With doto (clean):
(-> (doto (StringBuilder.)
      (.append "Payment: ")
      (.append "SPEI")
      (.append " $1,200"))
    (.toString))
;; => "Payment: SPEI $1,200"

;; doto returns the OBJECT, not the return value of the last method.
;; That's why we use -> to chain .toString at the end.
```

### `doto` with collections

```clojure
;; Build a Java ArrayList:
(doto (java.util.ArrayList.)
  (.add {:id "s1" :method :spei :amount 1200})
  (.add {:id "c1" :method :credit-card :amount 500})
  (.add {:id "d1" :method :debit-card :amount 350}))
;; => [{:id "s1", ...} {:id "c1", ...} {:id "d1", ...}]
```

### The `..` macro — chaining method calls

```clojure
;; Without .. (nested, hard to read):
(.toString (.append (.append (StringBuilder.) "SPEI") "-001"))
;; => "SPEI-001"

;; With .. (reads left to right):
(.. (StringBuilder.) (append "SPEI") (append "-001") (toString))
;; => "SPEI-001"

;; Real example — get system properties:
(.. System (getProperties) (get "java.version"))
;; => "17.0.6" (or your Java version)
```

### `import` — shorten class names

```clojure
;; Without import (verbose):
(java.time.Instant/now)

;; With import:
(import '[java.time Instant LocalDate Duration])

(Instant/now)
;; => #object[java.time.Instant "2026-03-12T..."]

;; In ns form (preferred):
;; (ns my-app.core
;;   (:import [java.time Instant LocalDate Duration Period]
;;            [java.time.format DateTimeFormatter]
;;            [java.security MessageDigest]
;;            [java.text NumberFormat]
;;            [java.util Locale]))
```

---

## 1.2 — java.time for Payment Dates (20 min)

### Why java.time?

Every payment system needs dates: transaction timestamps, settlement dates, due dates, reporting periods. The `java.time` API (Java 8+) is the gold standard — immutable, thread-safe, and comprehensive.

```clojure
(import '[java.time Instant LocalDate LocalDateTime ZonedDateTime
                     Duration Period ZoneId]
        '[java.time.format DateTimeFormatter]
        '[java.time.temporal ChronoUnit])
```

### Instant — a point in time (UTC)

```clojure
;; Current instant:
(Instant/now)
;; => #object[java.time.Instant "2026-03-12T21:56:51Z"]

;; From epoch millis (like System/currentTimeMillis):
(Instant/ofEpochMilli 1710288000000)
;; => #object[java.time.Instant "2024-03-13T00:00:00Z"]

;; Parse an ISO string:
(Instant/parse "2026-03-12T15:30:00Z")
;; => #object[java.time.Instant "2026-03-12T15:30:00Z"]

;; Compare instants:
(let [now   (Instant/now)
      later (.plusSeconds now 60)]
  (.isBefore now later))
;; => true

;; Millis between two instants:
(let [start (Instant/now)
      _     (Thread/sleep 10)
      end   (Instant/now)]
  (.between ChronoUnit/MILLIS start end))
;; => ~10 (approximately)
```

### LocalDate — date without time (for settlement dates)

```clojure
;; Today:
(LocalDate/now)
;; => #object[java.time.LocalDate "2026-03-12"]

;; Specific date:
(LocalDate/of 2026 3 15)
;; => #object[java.time.LocalDate "2026-03-15"]

;; Parse:
(LocalDate/parse "2026-03-15")
;; => #object[java.time.LocalDate "2026-03-15"]

;; Date arithmetic:
(let [today       (LocalDate/now)
      settlement  (.plusDays today 3)
      due-date    (.plusMonths today 1)]
  {:today      (str today)
   :settlement (str settlement)
   :due-date   (str due-date)})
;; => {:today "2026-03-12", :settlement "2026-03-15", :due-date "2026-04-12"}

;; Day of week:
(.getDayOfWeek (LocalDate/of 2026 3 15))
;; => #object[java.time.DayOfWeek "SUNDAY"]
```

### Duration and Period — time spans

```clojure
;; Duration — precise (hours, minutes, seconds, nanos):
(Duration/ofHours 48)
;; => #object[java.time.Duration "PT48H"]

(Duration/between
  (Instant/parse "2026-03-12T10:00:00Z")
  (Instant/parse "2026-03-12T18:30:00Z"))
;; => #object[java.time.Duration "PT8H30M"]

;; Period — calendar-based (years, months, days):
(Period/between
  (LocalDate/of 2026 1 1)
  (LocalDate/of 2026 3 15))
;; => #object[java.time.Period "P2M14D"]

(.getDays (Period/between (LocalDate/of 2026 3 1) (LocalDate/of 2026 3 15)))
;; => 14
```

### DateTimeFormatter — formatting and parsing

```clojure
;; Predefined formats:
(let [now (LocalDateTime/now)]
  (.format now DateTimeFormatter/ISO_LOCAL_DATE_TIME))
;; => "2026-03-12T..."

;; Custom format:
(let [fmt (DateTimeFormatter/ofPattern "dd/MM/yyyy HH:mm")
      now (LocalDateTime/now)]
  (.format now fmt))
;; => "12/03/2026 14:30" (approximately)

;; Parse with custom format:
(let [fmt (DateTimeFormatter/ofPattern "dd/MM/yyyy")]
  (LocalDate/parse "15/03/2026" fmt))
;; => #object[java.time.LocalDate "2026-03-15"]

;; Mexican date format for receipts:
(defn format-receipt-date [instant]
  (let [mexico-zone (ZoneId/of "America/Mexico_City")
        zoned       (.atZone instant mexico-zone)
        fmt         (DateTimeFormatter/ofPattern "dd/MMM/yyyy HH:mm:ss z")]
    (.format zoned fmt)))

(format-receipt-date (Instant/now))
;; => "12/Mar/2026 15:59:26 CST" (approximately, locale-dependent)
```

### Payment scenario: settlement date calculator

```clojure
;; SPEI settles same day if before 5pm, next business day otherwise.
;; Credit card settles in 2 business days.

(import '[java.time DayOfWeek])

(defn next-business-day [^LocalDate date]
  (let [dow (.getDayOfWeek date)]
    (cond
      (= dow DayOfWeek/SATURDAY) (.plusDays date 2)
      (= dow DayOfWeek/SUNDAY)   (.plusDays date 1)
      :else                       date)))

(defn settlement-date [method ^LocalDate trade-date]
  (case method
    :spei        (next-business-day trade-date)
    :credit-card (next-business-day (.plusDays trade-date 2))
    :debit-card  (next-business-day (.plusDays trade-date 1))))

;; Test with a Friday:
(let [friday (LocalDate/of 2026 3 13)]  ;; March 13, 2026 is a Friday
  {:spei        (str (settlement-date :spei friday))
   :credit-card (str (settlement-date :credit-card friday))
   :debit-card  (str (settlement-date :debit-card friday))})
;; => {:spei "2026-03-13",          <- same day (business day)
;;     :credit-card "2026-03-16",   <- Friday + 2 = Sunday -> Monday
;;     :debit-card "2026-03-16"}    <- Friday + 1 = Saturday -> Monday
```

> **SOCRATIC:** *"All `java.time` objects are immutable. `.plusDays` returns a NEW LocalDate — the original is unchanged. Sound familiar?"*
> -> It's the same philosophy as Clojure's persistent data structures! Immutable values, new values via transformation.

---

## 1.3 — Implementing Java Interfaces: `reify` (15 min)

### Why `reify`?

Many Java APIs expect you to pass an object that implements an interface. In Java you'd write an anonymous class. In Clojure, you use `reify`.

### Comparator — custom sorting

```clojure
(import '[java.util Comparator Collections ArrayList])

;; Sort payments by amount (ascending):
(def by-amount
  (reify Comparator
    (compare [_ a b]
      (Long/compare (:amount a) (:amount b)))))

(let [payments [{:id "s1" :amount 1200}
                {:id "c1" :amount 500}
                {:id "d1" :amount 8000}
                {:id "s2" :amount 350}]
      java-list (ArrayList. payments)]
  (Collections/sort java-list by-amount)
  (vec java-list))
;; => [{:id "s2", :amount 350} {:id "c1", :amount 500}
;;     {:id "s1", :amount 1200} {:id "d1", :amount 8000}]

;; Or simply use Clojure's sort-by (which uses Comparator under the hood):
(sort-by :amount [{:id "s1" :amount 1200}
                  {:id "c1" :amount 500}])
;; => ({:id "c1", :amount 500} {:id "s1", :amount 1200})
```

### Runnable and Callable

```clojure
(import '[java.util.concurrent Callable Executors Future])

;; Runnable — no return value:
(let [task (reify Runnable
             (run [_]
               (println "Processing payment on thread:"
                        (.getName (Thread/currentThread)))))]
  (.start (Thread. task)))
;; Prints: "Processing payment on thread: Thread-N"

;; Callable — returns a value:
(let [task (reify Callable
             (call [_]
               {:status :authorized
                :token  (str "T-" (random-uuid))
                :thread (.getName (Thread/currentThread))}))]
  (.call task))
;; => {:status :authorized, :token "T-...", :thread "..."}
```

### Using Callable with ExecutorService

```clojure
;; Thread pool for payment processing:
(let [pool  (Executors/newFixedThreadPool 3)
      tasks (mapv (fn [payment]
                    (reify Callable
                      (call [_]
                        (Thread/sleep (rand-int 100))  ;; simulate work
                        (assoc payment :status :authorized
                                       :thread (.getName (Thread/currentThread))))))
                  [{:id "s1" :method :spei :amount 1200}
                   {:id "c1" :method :credit-card :amount 500}
                   {:id "d1" :method :debit-card :amount 350}])
      futures (.invokeAll pool tasks)]
  (mapv #(.get ^Future %) futures))
;; => [{:id "s1", :method :spei, :amount 1200, :status :authorized, :thread "pool-N-thread-1"}
;;     {:id "c1", :method :credit-card, :amount 500, :status :authorized, :thread "pool-N-thread-2"}
;;     {:id "d1", :method :debit-card, :amount 350, :status :authorized, :thread "pool-N-thread-3"}]
```

### Multiple interfaces with `reify`

```clojure
;; Implement both Runnable and Object:
(let [task (reify
             Runnable
             (run [_]
               (println "Running payment batch..."))

             Object
             (toString [_]
               "PaymentBatchTask[pending]"))]
  (println "Task:" (.toString task))
  (.run task))
;; Task: PaymentBatchTask[pending]
;; Running payment batch...
```

> **SOCRATIC:** *"When should you use `reify` vs Clojure protocols?"*
> -> Use `reify` when a Java API demands a specific Java interface.
> Use protocols when you're designing Clojure-to-Clojure contracts.
> `reify` is the bridge to Java. Protocols are Clojure-native polymorphism.

---

## 1.4 — Extending Classes: `proxy` (10 min)

### When you need `proxy`

`reify` implements interfaces. `proxy` extends **concrete or abstract classes**. You need it when a Java API requires subclassing.

### Basic proxy

```clojure
;; Extend Thread (a class, not an interface):
(let [t (proxy [Thread] []
          (run []
            (println "Payment worker running on:"
                     (.getName (Thread/currentThread)))))]
  (.start t)
  (.join t 1000))
;; Prints: "Payment worker running on: Thread-N"
```

### Proxy with constructor args

```clojure
;; Extend java.io.FilterInputStream to log reads:
(import '[java.io ByteArrayInputStream FilterInputStream])

(defn logging-input-stream [^bytes data]
  (let [inner (ByteArrayInputStream. data)]
    (proxy [FilterInputStream] [inner]
      (read
        ([]
         (let [b (proxy-super read)]
           (when (>= b 0)
             (print (char b)))
           b))))))

(let [stream (logging-input-stream (.getBytes "SPEI" "UTF-8"))]
  (while (>= (.read stream) 0)))
;; Prints: SPEI
```

### `proxy` vs `reify` decision

```
Need                                Use
──────────────────────────────────  ─────────
Implement Java interface(s)         reify
Extend a concrete/abstract class    proxy
Pure Clojure polymorphism           defprotocol + defrecord
```

> `proxy` creates a new class at runtime. It's heavier than `reify`. Avoid it unless you specifically need to subclass.

---

## 1.5 — Type Hints & Reflection (15 min)

### The reflection problem

When Clojure can't determine the Java type at compile time, it uses **reflection** to find the right method at runtime. Reflection is slow.

```clojure
;; Enable reflection warnings:
(set! *warn-on-reflection* true)

;; This triggers a reflection warning:
(defn slow-upper [s]
  (.toUpperCase s))
;; WARNING: reference to field toUpperCase can't be resolved.

;; Clojure doesn't know `s` is a String, so it uses reflection
;; to find .toUpperCase at RUNTIME — every single call.
```

### Type hints fix reflection

```clojure
;; Add a type hint with ^:
(defn fast-upper [^String s]
  (.toUpperCase s))
;; No warning! Clojure generates a direct method call.

;; On return values:
(defn ^String payment-id [^String prefix ^long seq-num]
  (str prefix "-" (format "%06d" seq-num)))

(payment-id "SPEI" 42)
;; => "SPEI-000042"
```

### Common type hints

```
Hint              Java type
────────────────  ──────────────────
^String           java.lang.String
^long             long (primitive)
^double           double (primitive)
^int              int (primitive — use sparingly)
^boolean          boolean (primitive)
^bytes            byte[]
^objects          Object[]
^java.util.List   java.util.List
^Instant          java.time.Instant (if imported)
```

### Performance impact — measuring reflection

```clojure
;; Without type hints (reflection):
(defn slow-length [s]
  (.length s))

;; With type hints (direct call):
(defn fast-length [^String s]
  (.length s))

;; Benchmark:
(let [s "SPEI-Payment-Authorization-Token-ABC123"]
  (time (dotimes [_ 1000000] (slow-length s)))
  (time (dotimes [_ 1000000] (fast-length s))))
;; slow: ~1500ms (with reflection)
;; fast: ~5ms (direct method call)
;; ~300x speedup!
```

### Where to hint

```clojure
;; 1. Function parameters:
(defn process [^String id ^long amount] ...)

;; 2. Let bindings:
(let [^String name (.getName obj)
      ^long   len  (.length name)]
  ...)

;; 3. Inline (on the expression):
(.toUpperCase ^String (get payment :id))

;; 4. Def:
(def ^String default-currency "MXN")
```

> **SOCRATIC:** *"Should you type-hint everything?"*
> -> No! Only hint where:
>   1. `*warn-on-reflection*` shows a warning, AND
>   2. The code is in a hot path (called frequently)
>
> Type hints are an optimization, not a requirement. Clojure is dynamically typed by design.

---

## 1.6 — Java Collections & Streams (10 min)

### Clojure <-> Java collection conversions

```clojure
;; Clojure collections implement Java interfaces automatically:
(instance? java.util.List [1 2 3])          ;; => true
(instance? java.util.Map {:a 1 :b 2})      ;; => true
(instance? java.util.Set #{1 2 3})          ;; => true
(instance? Iterable [1 2 3])               ;; => true

;; So you can pass them to Java methods directly:
(Collections/unmodifiableList [1 2 3])
;; => [1 2 3]

(Collections/max [3 1 4 1 5 9 2 6])
;; => 9
```

### When you need a mutable Java collection

```clojure
;; ArrayList from Clojure vector:
(let [payments (ArrayList. [{:id "s1" :amount 1200}
                             {:id "c1" :amount 500}])]
  (.add payments {:id "d1" :amount 350})
  (.size payments))
;; => 3

;; HashMap from Clojure map:
(let [m (java.util.HashMap. {:name "SPEI" :code "SP" :active true})]
  (.put m :version 2)
  (.get m :name))
;; => "SPEI"
```

### Arrays

```clojure
;; Create Java arrays:
(int-array [1 2 3 4 5])
;; => [I@...

(long-array 10)
;; => [J@... (10 zeroes)

(into-array String ["SPEI" "Visa" "Mastercard"])
;; => [Ljava.lang.String;@...

;; Access array elements:
(let [arr (int-array [10 20 30])]
  (aget arr 1))
;; => 20

;; Set array elements:
(let [arr (int-array [10 20 30])]
  (aset arr 1 99)
  (vec arr))
;; => [10 99 30]

;; Array operations are FAST — no boxing, no immutability overhead.
;; Use for performance-critical numeric code.
```

### Java Streams (Java 8+)

```clojure
;; Java Streams are like lazy sequences with parallel support:
(import '[java.util.stream Collectors])

(let [payments [{:id "s1" :amount 1200}
                {:id "c1" :amount 500}
                {:id "d1" :amount 8000}
                {:id "s2" :amount 350}]
      total (-> payments
                (.stream)
                (.filter (reify java.util.function.Predicate
                           (test [_ p] (> (:amount p) 1000))))
                (.mapToLong (reify java.util.function.ToLongFunction
                              (applyAsLong [_ p] (:amount p))))
                (.sum))]
  total)
;; => 9200
```

> **SOCRATIC:** *"Java Streams vs Clojure transducers — both avoid intermediate collections. When do you pick each?"*
> -> Use Clojure transducers when you're in Clojure-land (they compose better, work on channels too).
> Use Java Streams when interacting with Java APIs that return/expect streams.

---

## 1.7 — Practical Patterns (10 min)

### Pattern 1: Hashing payments with MessageDigest

```clojure
(import '[java.security MessageDigest]
        '[java.util Base64])

(defn sha256 [^String input]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes input "UTF-8"))]
    (.encodeToString (Base64/getEncoder) hash-bytes)))

;; Hash a payment for receipt verification:
(defn payment-hash [payment]
  (sha256 (pr-str (select-keys payment [:id :amount :method :timestamp]))))

(payment-hash {:id "s1" :amount 1200 :method :spei
               :timestamp "2026-03-12T15:30:00Z"
               :extra-field "ignored"})
;; => "aBcDeFg..." (Base64 encoded SHA-256)

;; Same input always produces the same hash:
(= (payment-hash {:id "s1" :amount 1200 :method :spei :timestamp "2026-03-12T15:30:00Z"})
   (payment-hash {:id "s1" :amount 1200 :method :spei :timestamp "2026-03-12T15:30:00Z"}))
;; => true
```

### Pattern 2: Currency formatting with NumberFormat

```clojure
(import '[java.text NumberFormat]
        '[java.util Locale Currency])

;; Mexican peso formatting:
(defn format-mxn [amount]
  (let [fmt (NumberFormat/getCurrencyInstance (Locale. "es" "MX"))]
    (.format fmt (double amount))))

(format-mxn 1200)
;; => "$1,200.00" (or "MXN1,200.00" depending on JDK)

(format-mxn 1500000.50)
;; => "$1,500,000.50"

;; For explicit control:
(defn format-currency [amount ^String currency-code]
  (let [fmt (NumberFormat/getCurrencyInstance (Locale. "es" "MX"))]
    (.setCurrency fmt (Currency/getInstance currency-code))
    (.format fmt (double amount))))

(format-currency 1200 "MXN")
;; => "$1,200.00"

(format-currency 99.99 "USD")
;; => "USD99.99"
```

### Pattern 3: Exception handling with Java exceptions

```clojure
;; try/catch works with Java exception hierarchy:
(defn parse-amount [^String s]
  (try
    (let [amount (Long/parseLong s)]
      (when (neg? amount)
        (throw (IllegalArgumentException. "Amount cannot be negative")))
      amount)
    (catch NumberFormatException e
      (throw (ex-info "Invalid amount format"
                      {:input s :cause (.getMessage e)})))
    (catch IllegalArgumentException e
      (throw (ex-info "Invalid amount"
                      {:input s :cause (.getMessage e)})))))

(parse-amount "1200")
;; => 1200

;; (parse-amount "abc")
;; => ExceptionInfo: Invalid amount format {:input "abc", :cause "For input string: \"abc\""}

;; (parse-amount "-500")
;; => ExceptionInfo: Invalid amount {:input "-500", :cause "Amount cannot be negative"}
```

### Pattern 4: UUID generation

```clojure
;; Java's UUID — already available:
(java.util.UUID/randomUUID)
;; => #uuid "a1b2c3d4-..."

;; Or use Clojure's built-in:
(random-uuid)
;; => #uuid "e5f6a7b8-..."

;; They're the same type:
(type (random-uuid))
;; => java.util.UUID

;; Parse from string:
(java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")
;; => #uuid "550e8400-e29b-41d4-a716-446655440000"
```

---

# KATA

---

## 1.8 — Java Interop Kata (15 min, individual)

> **Format:** Fill in the blanks (`__`) so every `assert` passes. Evaluate each block in your REPL.
> This kata covers ALL the concepts from today's class.

### Kata 1 — Instance methods

```clojure
(let [s "spei-transfer-001"]
  (assert (= (__ s) "SPEI-TRANSFER-001"))         ;; uppercase the string
  (assert (= (__ s 0 4) "spei"))                   ;; first 4 characters
  (assert (__ (.toUpperCase s) "SPEI")))            ;; does it contain "SPEI"?
```

### Kata 2 — Static methods

```clojure
(assert (= (__ "42") 42))                         ;; parse "42" as an integer
(assert (= (Math/__ 2 8) 256.0))                  ;; 2 to the power of 8
(assert (= (__ 100) 10.0))                        ;; square root of 100
```

### Kata 3 — Constructors and doto

Build a Java ArrayList using `doto`, then check its size.

```clojure
(let [list (doto (__)                              ;; create an empty ArrayList
              (.add "SPEI")
              (.add "Visa")
              (.add "Mastercard"))]
  (assert (= (__ list) 3))                         ;; how many elements?
  (assert (= (.get list 0) __)))                   ;; what's the first element?
```

### Kata 4 — java.time basics

```clojure
(import '[java.time LocalDate])

(let [today    (LocalDate/__)                      ;; get today's date
      tomorrow (__ today 1)]                       ;; add 1 day
  (assert (.__ today tomorrow))                    ;; today is before tomorrow
  (assert (= 1 (__ (.toEpochDay tomorrow) (.toEpochDay today)))))  ;; difference in days
```

### Kata 5 — java.time settlement

```clojure
(import '[java.time LocalDate DayOfWeek])

(let [friday (LocalDate/of 2026 3 13)              ;; a Friday
      saturday (.plusDays friday 1)
      dow-sat (.getDayOfWeek saturday)]
  (assert (= dow-sat DayOfWeek/__))                ;; what day is saturday?
  (assert (= (str (.plusDays friday 3)) __)))       ;; Friday + 3 = ?
```

### Kata 6 — reify Comparator

```clojure
(import '[java.util Collections ArrayList])

(let [payments [{:id "a" :amount 500}
                {:id "b" :amount 100}
                {:id "c" :amount 300}]
      jlist    (ArrayList. payments)
      cmp      (reify java.util.Comparator
                 (compare [_ a b]
                   (__ (:amount a) (:amount b))))]  ;; compare amounts
  (Collections/sort jlist cmp)
  (assert (= (:id (.get jlist 0)) __))              ;; which payment is first?
  (assert (= (:id (.get jlist 2)) __)))             ;; which is last?
```

### Kata 7 — Type hints

```clojure
(set! *warn-on-reflection* true)

;; This function has a reflection warning. Add a type hint to fix it:
(defn fast-len [__ s]                ;; add hint here
  (.length s))

(assert (= (fast-len "SPEI") 4))

(set! *warn-on-reflection* false)
```

### Kata 8 — Hashing

```clojure
(import '[java.security MessageDigest])

(defn md5-hex [^String input]
  (let [digest (MessageDigest/getInstance __)      ;; which algorithm?
        hash-bytes (.digest digest (.getBytes input "UTF-8"))]
    (apply str (map #(format "%02x" %) hash-bytes))))

(let [h1 (md5-hex "payment-001")
      h2 (md5-hex "payment-001")
      h3 (md5-hex "payment-002")]
  (assert (= h1 h2))                               ;; same input -> same hash
  (assert (not= h1 h3)))                           ;; different input -> different hash
```

### Kata 9 — Exception handling

```clojure
(defn safe-parse [^String s]
  (try
    (Long/parseLong s)
    (catch __ e                                     ;; which exception class?
      :invalid)))

(assert (= (safe-parse "123") 123))
(assert (= (safe-parse "abc") :invalid))
```

### Kata 10 — Collections round trip

```clojure
;; Clojure vector implements Java List:
(assert (instance? __ [1 2 3]))                    ;; what Java interface?

;; Java ArrayList can become a Clojure vector:
(let [jlist (doto (java.util.ArrayList.)
              (.add 10) (.add 20) (.add 30))]
  (assert (= (__ jlist) [10 20 30])))              ;; convert to Clojure vector
```

---

### Answer key (for teacher)

```
Kata 1:  .toUpperCase / .substring / .contains
Kata 2:  Integer/parseInt / pow / Math/sqrt
Kata 3:  java.util.ArrayList. / .size / "SPEI"
Kata 4:  now / .plusDays / isBefore / -
Kata 5:  SATURDAY / "2026-03-16"
Kata 6:  Long/compare (or (- (:amount a) (:amount b))) / "b" / "a"
Kata 7:  ^String
Kata 8:  "MD5"
Kata 9:  NumberFormatException
Kata 10: java.util.List / vec
```

---

## Wrap-up & Key Takeaways (5 min)

### The rules of Java interop

1. **Prefer Clojure idioms** — only reach for Java when Clojure doesn't have it (dates, crypto, formatting)
2. **Use `reify`** for Java interfaces, **`proxy`** for extending classes (prefer `reify`)
3. **Type hint** only where `*warn-on-reflection*` warns AND the code is performance-critical
4. **Clojure collections work as Java collections** — no conversion needed for reading
5. **`doto`** is your friend for builder-pattern Java APIs
6. **Wrap Java exceptions** with `ex-info` to add Clojure-style context data

### Quick reference

```
Task                            Clojure form
──────────────────────────────  ──────────────────────────────────────
Call instance method             (.method obj args)
Call static method               (Class/method args)
Read static field                Class/FIELD
Create object                    (ClassName. args)
Fluent builder setup             (doto (ClassName.) (.setX v) (.setY v))
Chain methods                    (.. obj (method1) (method2))
Implement interface              (reify Interface (method [this args] body))
Extend class                     (proxy [Class] [ctor-args] (method [args] body))
Type hint parameter              (defn f [^Type x] ...)
Type hint binding                (let [^Type x expr] ...)
Suppress reflection              (set! *warn-on-reflection* true)
Clojure -> Java array            (into-array Type coll) / (int-array coll)
Java -> Clojure                  (vec java-list) / (into {} java-map) / (seq java-coll)
```

> **Tease for next class:** *"You've built macros, you've used Java. Class 10 combines both: we'll debug macros with `macroexpand`, prevent variable capture with `gensym`, and build a DSL macro for your final project. Come with subjects 1-9 implemented — the macro is the capstone."*
