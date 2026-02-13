# Clojure Advanced Topics — Class 3 (2h)

## Macros: Extending the Language

> **Prerequisite:** Class 1 — Multimethods, Class 2 — Protocols (`payments-mm` project).
> All examples continue with the Mexican payment rails (SPEI, credit card, debit card).

---

## Agenda

By the end of this class, students will be able to:

- Explain homoiconicity and why "code is data" in Clojure
- Use `quote`, syntax-quote, `unquote`, and `unquote-splicing` to build code as data
- Write macros with `defmacro` and debug them with `macroexpand-1`
- Identify language constructs that can ONLY exist as macros (not functions)
- Build practical domain macros for payment processing
- (Bonus) Wire protocols to Apache Kafka via `extend-type`

## Timeline

```
 0:00 ┬─ Part 0 — Recap & Motivation ·················· 10 min
      │   Quick review of protocols
      │   "What if you could add new syntax to the language?"
      │
 0:10 ┬─ Part 1 — Code is Data ························ 20 min
      │   Homoiconicity, quote, syntax-quote
      │   unquote, unquote-splicing
      │
 0:30 ┬─ Part 2 — Your First Macro ···················· 20 min
      │   defmacro, macroexpand-1, macro vs function
      │
 0:50 ┬─ Part 3 — Language Constructs That REQUIRE      20 min
      │   Macros ·······································
      │   when, and, or, ->, ->>, with-open, cond->
      │   "Why can't these be functions?"
      │
 1:10 ┬─── 5 min break ───
      │
 1:15 ┬─ Part 4 — Building Payment Macros ············· 20 min
      │   with-timing, with-validation, with-retry
      │   Practical domain macros
      │
 1:35 ┬─ Part 5 — Fixation Exercises ·················· 15 min
      │   Students build their own macros
      │
 1:50 ┬─ Part 6 — Bonus: Kafka with Protocols ········· optional / overflow
      │   extend-type with KafkaProducer/KafkaConsumer
      │   Real Java interop from Class 2
      │
 1:55 ┬─ Wrap-up & Key Takeaways ······················  5 min
      │   When (and when NOT) to write macros
 2:00 ┴─ End
```

> **Note:** Parts 0–5 are the core curriculum (1h50). Part 6 (Kafka) is overflow material — demo it live if time allows, or assign as homework. The Appendix has extra examples if any section finishes early.

---

## Part 0 — Recap & Motivation (10 min)

### Quick review

In Class 1 we built **multimethods** — open dispatch on any computed value.
In Class 2 we built **protocols** — type-based contracts with JVM-speed dispatch.

Both let you extend *behavior*. But neither lets you extend the *language itself*.

### The provocation

> 🎓 **SOCRATIC:** *"Look at this code. What does `when` do?"*

```clojure
(when (> amount 500)
  (println "High-value payment")
  (flag-for-review! payment))
```

> *"Could you write `when` as a regular function?"*
>
> Try it:

```clojure
;; Attempt: when as a function
(defn my-when-fn [condition & body]
  (if condition
    (last body)   ;; ← doesn't work like you'd expect
    nil))

(my-when-fn false
  (println "This STILL prints!")  ;; ← uh oh
  (launch-missiles!))             ;; ← evaluated BEFORE my-when-fn runs
```

> → **All function arguments are evaluated BEFORE the function is called.** There is no way around this with functions. `when` must be a macro — it needs to control *whether* its body executes at all.

**Macros** operate on code *before* it runs. They receive unevaluated code as data, transform it, and return new code for the compiler. This is what makes Clojure a **programmable programming language**.

---

## Part 1 — Code is Data (20 min)

> **Goal:** Build intuition for homoiconicity — the foundation that makes macros possible.

### What does "code is data" mean?

In most languages, source code is just text that a parser turns into a tree the programmer never sees:

```
Java:    "if (x > 5) { ... }"  →  [opaque AST]  →  bytecode
Python:  "if x > 5: ..."       →  [opaque AST]  →  bytecode
```

In Clojure, source code **is already a data structure** — the same lists, vectors, and maps you manipulate every day:

```clojure
;; This code:
(+ 1 2)

;; IS a list with three elements:
;;   1. the symbol +
;;   2. the number 1
;;   3. the number 2
```

This property is called **homoiconicity** — "same representation." The code your program manipulates has the same shape as the code the compiler reads.

> 🎓 **SOCRATIC:** *"In Java, can you write a program that receives a Java `if` statement as data and rearranges it? What would you need?"*
> → A parser, an AST library, code generation… In Clojure, it's just list manipulation.

### 1.1 `quote` — Prevent evaluation

`quote` tells Clojure: "Don't evaluate this — give me the raw data structure."

```clojure
;; Without quote: Clojure evaluates and returns the sum
(+ 1 2)
;; => 3

;; With quote: Clojure returns the list itself
(quote (+ 1 2))
;; => (+ 1 2)

;; Shorthand: the ' character
'(+ 1 2)
;; => (+ 1 2)

;; What type is it?
(type '(+ 1 2))
;; => clojure.lang.PersistentList

;; It's a regular list. You can manipulate it:
(first '(+ 1 2))   ;; => +
(second '(+ 1 2))  ;; => 1
(count '(+ 1 2))   ;; => 3
```

> 🎓 **SOCRATIC:** *"What is `'+`? What type is it?"*

```clojure
(type '+)
;; => clojure.lang.Symbol

;; Symbols are names that refer to things. When evaluated, they resolve to values.
;; When quoted, they're just names — data.
'+       ;; => + (the symbol)
+        ;; => #function[clojure.core/+] (the function it refers to)
```

### 1.2 Syntax-quote, unquote, unquote-splicing — Building code templates

`quote` is all-or-nothing — nothing inside gets evaluated. For macros, we need **templates** where most things are literal but some parts are filled in.

**Syntax-quote** (backtick `` ` ``):

```clojure
;; Regular quote — symbols stay as-is:
'(+ x 1)
;; => (+ x 1)

;; Syntax-quote — symbols get fully qualified:
`(+ x 1)
;; => (clojure.core/+ user/x 1)
```

> 🎓 **SOCRATIC:** *"Why does syntax-quote resolve the namespace? Why is `+` becoming `clojure.core/+`?"*
> → To avoid name collisions. When a macro expands inside a different namespace,
> `clojure.core/+` will always mean the same `+`. A bare `+` could be shadowed by a local binding.

**Unquote** (`~`) — "Evaluate THIS part inside the template":

```clojure
(let [method :spei]
  `(process {:method ~method}))
;; => (user/process {:method :spei})
;;                          ↑ evaluated!
```

**Unquote-splicing** (`~@`) — "Evaluate and splice the elements in":

```clojure
(let [forms ['(println "step 1")
             '(println "step 2")
             '(println "step 3")]]
  `(do ~@forms))
;; => (do (println "step 1") (println "step 2") (println "step 3"))
;;        ↑ three separate forms, NOT a nested list
```

Compare `~` vs `~@`:

```clojure
(let [items [1 2 3]]
  `(+ ~items))    ;; => (clojure.core/+ [1 2 3])     — inserts the vector
                  ;;    (won't work: + doesn't take a vector)

(let [items [1 2 3]]
  `(+ ~@items))   ;; => (clojure.core/+ 1 2 3)       — splices the elements
                  ;;    (works: + gets three arguments)
```

### 1.3 Hands-on: building code as data

```clojure
;; Build a function call from parts:
(let [op '+
      args [10 20 30]]
  `(~op ~@args))
;; => (+ 10 20 30)

;; Build a let binding:
(let [var-name 'amount
      value    1200.0
      body     '(println amount)]
  `(let [~var-name ~value] ~body))
;; => (clojure.core/let [amount 1200.0] (println amount))

;; Evaluate the generated code:
(eval `(+ ~@[10 20 30]))
;; => 60
```

> 🎓 **SOCRATIC:** *"We just built a `let` expression as data and could `eval` it. Why is this powerful?"*
> → Because macros do exactly this. They receive code, rearrange it, and return new code.
> The compiler then compiles the result. You're programming the compiler.

### Cheat sheet

```
Syntax          Name                 What it does
──────────────  ───────────────────  ──────────────────────────────────────────
'x              quote                Returns x as data (not evaluated)
`x              syntax-quote         Like quote, but resolves namespaces
~x              unquote              Evaluate x inside a syntax-quote
~@xs            unquote-splicing     Evaluate xs and splice elements in
```

---

## Part 2 — Your First Macro (20 min)

> **Goal:** Write, expand, and debug macros.

### 2.1 `defmacro` — basic structure

A macro is defined like a function, but it receives **unevaluated forms** and returns **code as data**:

```clojure
(defmacro my-when
  "Like when, but we built it ourselves."
  [condition & body]
  `(if ~condition
     (do ~@body)
     nil))
```

Let's trace what happens:

```clojure
;; You write:
(my-when (> amount 500)
  (println "High value!")
  (flag-for-review! payment))

;; The macro receives (NOT evaluated):
;;   condition = '(> amount 500)        — a list (symbol > , symbol amount, number 500)
;;   body      = ['(println "High value!") '(flag-for-review! payment)]

;; The macro returns this code:
(if (> amount 500)
  (do
    (println "High value!")
    (flag-for-review! payment))
  nil)

;; THEN the compiler compiles and runs the returned code.
```

### 2.2 `macroexpand-1` — Your debugging microscope

**Always** use `macroexpand-1` when writing macros. It shows you what the macro produces without executing it:

```clojure
(macroexpand-1
  '(my-when (> amount 500)
     (println "High value!")
     (flag-for-review! payment)))
;; => (if (> amount 500)
;;      (do (println "High value!") (flag-for-review! payment))
;;      nil)
```

> 🎓 **SOCRATIC:** *"Why `macroexpand-1` and not `macroexpand`?"*
> → `macroexpand-1` expands ONE level. `macroexpand` keeps expanding until the result is no longer a macro call.
> When debugging your OWN macro, you want one level — you want to see YOUR output, not the recursive expansion of `if` and `do`.

```clojure
;; macroexpand goes deeper:
(macroexpand '(my-when true (println "hi")))
;; => (if true (do (println "hi")) nil)
;; (in this case they look the same because `if` is a special form, not a macro)
```

### 2.3 Macro vs function — the key difference

```clojure
;; FUNCTION: arguments are evaluated BEFORE the function body runs
(defn log-result [label value]
  (println label "=>" value)
  value)

(log-result "sum" (+ 1 2))
;; "sum" is evaluated → "sum"
;; (+ 1 2) is evaluated → 3
;; THEN log-result receives "sum" and 3
```

```clojure
;; MACRO: arguments are NOT evaluated — they arrive as raw code
(defmacro log-expr [expr]
  `(let [result# ~expr]
     (println '~expr "=>" result#)
     result#))

(log-expr (+ 1 2))
;; The macro receives the LIST '(+ 1 2), not the number 3
```

> 🎓 **SOCRATIC:** *"What is `result#`? Why the hash mark?"*

```clojure
;; Auto-gensym: result# generates a unique symbol each expansion
;; This prevents name collisions with user code:

(macroexpand-1 '(log-expr (+ 1 2)))
;; => (clojure.core/let [result__12345__auto__ (+ 1 2)]
;;      (clojure.core/println '(+ 1 2) "=>" result__12345__auto__)
;;      result__12345__auto__)
```

Without `#`, if the user had a local called `result`, the macro would shadow it. Auto-gensym makes every expansion use a unique name.

### 2.4 The three phases

```
┌───────────────────────────────────────────────────────────────┐
│                                                               │
│   1. READ TIME         2. MACRO-EXPANSION       3. RUNTIME   │
│                            (compile time)                     │
│   Text → Data          Data → Data               Data → JVM  │
│   "(+ 1 2)"           macro transforms           bytecode    │
│       ↓                  code as lists             executes   │
│   '(+ 1 2)                  ↓                                │
│   (list)               '(if ...)                             │
│                        (new list)                            │
│                                                               │
└───────────────────────────────────────────────────────────────┘

  You write code → Reader makes lists → Macros rewrite lists → Compiler emits bytecode
```

> 🎓 **SOCRATIC:** *"Functions operate at phase 3 (runtime). Macros operate at phase 2 (compile time). Why does it matter?"*
> → Macros run ONCE at compile time, then the transformed code runs at runtime.
> A macro can eliminate runtime work entirely — the decision is made at compile time.

---

## Part 3 — Language Constructs That REQUIRE Macros (20 min)

> **Goal:** Understand *why* certain Clojure constructs are macros, not functions.

### The rule: if it controls evaluation, it MUST be a macro

Functions evaluate all arguments eagerly. If a construct needs to:
- **Skip** evaluating some arguments → macro
- **Evaluate arguments in a specific order** → macro
- **Introduce new bindings** (local names) → macro
- **Restructure code** (rewrite the shape of expressions) → macro

### 3.1 `when` — conditional body execution

```clojure
;; when is a macro:
(macroexpand-1 '(when (paid? payment) (send-receipt!) (update-ledger!)))
;; => (if (paid? payment) (do (send-receipt!) (update-ledger!)))
```

**Why not a function?** The body forms `(send-receipt!)` and `(update-ledger!)` must NOT execute if the condition is false. A function would execute them before it even sees the condition's value.

### 3.2 `and` / `or` — short-circuit evaluation

```clojure
(macroexpand-1 '(and (valid-card? p) (check-balance! p) (authorize! p)))
;; => (let [and__XXX (valid-card? p)]
;;      (if and__XXX
;;        (and (check-balance! p) (authorize! p))   ;; ← recursive expansion
;;        and__XXX))
```

**Why not a function?**

```clojure
;; If `and` were a function:
(and (valid-card? payment)
     (call-external-api! payment)    ;; ← this ALWAYS runs, even if card is invalid
     (authorize! payment))           ;; ← this too — wasted API call + wrong behavior

;; As a macro, it stops at the first falsy value. (call-external-api!) never runs
;; if (valid-card?) returns false.
```

> 🎓 **SOCRATIC:** *"Java has `&&` and `||` which short-circuit. Are those also special?"*
> → Yes! Java's `&&` and `||` are built into the compiler — you can't create new short-circuit operators. In Clojure, `and` and `or` are macros in `clojure.core` — no compiler magic. YOU could write them yourself.

### 3.3 `->` and `->>` — threading macros

Threading macros restructure code. They don't change what runs — they change how you *write* it:

```clojure
;; Without threading:
(send-to-provider!
  (apply-fee
    (validate!
      (normalize-amount payment))))

;; With -> (thread-first):
(-> payment
    normalize-amount
    validate!
    apply-fee
    send-to-provider!)

(macroexpand-1 '(-> payment normalize-amount validate! apply-fee send-to-provider!))
;; => (send-to-provider! (apply-fee (validate! (normalize-amount payment))))
```

**Why not a function?** The macro rearranges the SOURCE CODE at compile time. `->` takes *symbols* (function names), not function values. It nests them syntactically:

```clojure
;; -> also works with extra arguments:
(-> payment
    (assoc :currency :MXN)      ;; becomes (assoc payment :currency :MXN)
    (update :amount * 1.16))    ;; becomes (update <previous> :amount * 1.16)

(macroexpand-1 '(-> payment (assoc :currency :MXN) (update :amount * 1.16)))
;; => (update (assoc payment :currency :MXN) :amount * 1.16)
```

```clojure
;; ->> threads as the LAST argument (common with sequences):
(->> payments
     (filter :approved?)
     (map :amount)
     (reduce +))

(macroexpand-1 '(->> payments (filter :approved?) (map :amount) (reduce +)))
;; => (reduce + (map :amount (filter :approved? payments)))
```

> 🎓 **SOCRATIC:** *"Could you write `->` as a function that takes a value and a list of functions?"*
> → You could write `(reduce #(%2 %1) payment [normalize-amount validate!])`, but you lose
> the ability to pass extra arguments like `(assoc :currency :MXN)`. The macro rewrites syntax,
> which is strictly more powerful than function composition.

### 3.4 `cond->` — conditional threading

```clojure
(defn enrich-payment [payment]
  (cond-> payment
    (:high-value? payment) (assoc :requires-review true)
    (= :spei (:method payment)) (assoc :provider :spei-rails)
    (:international? payment) (assoc :fee-multiplier 1.5)))

(macroexpand-1
  '(cond-> payment
     (:high-value? payment) (assoc :requires-review true)
     (= :spei (:method payment)) (assoc :provider :spei-rails)))
;; Each condition is evaluated, and only when truthy does the
;; corresponding form get threaded. Impossible as a function.
```

### 3.5 `with-open` — resource management (like Java's try-with-resources)

```clojure
(macroexpand-1
  '(with-open [rdr (clojure.java.io/reader "/data/transactions.csv")]
     (doall (line-seq rdr))))
;; => (let [rdr (clojure.java.io/reader "/data/transactions.csv")]
;;      (try
;;        (doall (line-seq rdr))
;;        (finally
;;          (.close rdr))))
```

**Why not a function?** The macro introduces a **binding** (`rdr`) that the body uses by name, and wraps the body in `try/finally`. A function can't introduce bindings into the caller's scope.

### 3.6 `when-let` and `if-let` — bind + test in one step

```clojure
(when-let [clabe (:spei-clabe payment)]
  (println "Processing CLABE:" clabe)
  (route-to-spei! clabe))

(macroexpand-1
  '(when-let [clabe (:spei-clabe payment)]
     (println "Processing CLABE:" clabe)
     (route-to-spei! clabe)))
;; => (let [temp (:spei-clabe payment)]
;;      (when temp
;;        (let [clabe temp]
;;          (println "Processing CLABE:" clabe)
;;          (route-to-spei! clabe))))
```

**Why not a function?** Same reason — it introduces a binding (`clabe`) visible in the body.

### Summary: why each construct requires macros

```
Construct    Macro power used                       Function alternative?
───────────  ─────────────────────────────────────  ─────────────────────────
when         Skip body evaluation                   No — args always evaluated
and / or     Short-circuit (stop early)             No — all args evaluated
-> / ->>     Restructure source code (nesting)      Partial — lose extra args
cond->       Conditional code restructuring          No
with-open    Introduce binding + try/finally wrap   No — can't introduce bindings
when-let     Introduce binding + conditional body   No — can't introduce bindings
if-let       Introduce binding + two-branch cond    No
defn         Intern a Var + build fn                No — def is a special form
```

> 🎓 **SOCRATIC:** *"If Clojure didn't have macros, would Rich Hickey have to bake all of these into the compiler?"*
> → Yes. In Java, `if`, `for`, `try`, `synchronized` are all compiler built-ins. In Clojure,
> only a handful of **special forms** are built into the compiler (`if`, `do`, `let*`, `fn*`, `def`, `quote`, etc.).
> Everything else — `when`, `and`, `or`, `cond`, `->`, `defn`, `defmacro` itself — is a macro
> written in Clojure. The language bootstraps itself.

---

## Part 4 — Building Payment Macros (20 min)

> **Goal:** Build practical macros for the payment domain.

### 4.1 `with-timing` — measure any block of code

```clojure
(defmacro with-timing
  "Executes body and returns a map with :result and :elapsed-ms."
  [label & body]
  `(let [start#  (System/nanoTime)
         result# (do ~@body)
         end#    (System/nanoTime)
         ms#     (/ (- end# start#) 1e6)]
     (println (str ~label " took " ms# " ms"))
     {:result result# :elapsed-ms ms#}))
```

Verify with `macroexpand-1`:

```clojure
(macroexpand-1
  '(with-timing "SPEI authorize"
     (Thread/sleep 100)
     {:status :authorized}))
;; => (let [start__X (System/nanoTime)
;;          result__X (do (Thread/sleep 100) {:status :authorized})
;;          end__X (System/nanoTime)
;;          ms__X (/ (- end__X start__X) 1e6)]
;;      (println (str "SPEI authorize" " took " ms__X " ms"))
;;      {:result result__X :elapsed-ms ms__X})
```

Try it:

```clojure
(with-timing "SPEI authorize"
  (Thread/sleep 50)
  {:status :authorized :method :spei})
;; SPEI authorize took 50.xxx ms
;; => {:result {:status :authorized, :method :spei}, :elapsed-ms 50.xxx}
```

> 🎓 **SOCRATIC:** *"Could `with-timing` be a function that takes a thunk (zero-arg function)?"*
>
> ```clojure
> (defn with-timing-fn [label f]
>   (let [start (System/nanoTime)
>         result (f)
>         ms (/ (- (System/nanoTime) start) 1e6)]
>     (println label "took" ms "ms")
>     {:result result :elapsed-ms ms}))
>
> ;; Usage — notice the #() wrapper:
> (with-timing-fn "authorize" #(do (Thread/sleep 50) {:status :ok}))
> ```
>
> → Yes! And this is often the BETTER choice. The macro version reads more naturally
> (no `#()` wrapper), but the function version is simpler to write and debug.
> **Rule of thumb:** write a function first. Only reach for a macro when a function can't do the job.

### 4.2 `with-validation` — validate-or-abort pattern

```clojure
(defmacro with-validation
  "Validates payment. If valid, runs body. Otherwise returns error map."
  [payment validations & body]
  `(let [p# ~payment
         errors# (remove nil?
                   (map (fn [[check# msg#]]
                          (when-not (check# p#) msg#))
                        ~validations))]
     (if (seq errors#)
       {:ok? false :errors (vec errors#)}
       (do ~@body))))
```

```clojure
(with-validation {:method :spei :spei-clabe "032180000118359719" :amount 1200.0}
  [[#(pos? (:amount %))            "Amount must be positive"]
   [#(string? (:spei-clabe %))     "CLABE must be a string"]
   [#(re-matches #"\d{18}" (str (:spei-clabe %))) "CLABE must be 18 digits"]]

  {:status :authorized :method :spei})
;; => {:status :authorized, :method :spei}

;; With bad data:
(with-validation {:method :spei :spei-clabe "short" :amount -5}
  [[#(pos? (:amount %))            "Amount must be positive"]
   [#(re-matches #"\d{18}" (str (:spei-clabe %))) "CLABE must be 18 digits"]]

  {:status :authorized})
;; => {:ok? false, :errors ["Amount must be positive" "CLABE must be 18 digits"]}
```

### 4.3 `with-retry` — retry on failure

```clojure
(defmacro with-retry
  "Retries body up to n times. Returns last result or throws last exception."
  [n & body]
  `(loop [attempts# ~n
          last-err#  nil]
     (if (pos? attempts#)
       (let [result# (try
                       {:ok (do ~@body)}
                       (catch Exception e#
                         {:err e#}))]
         (if (:ok result#)
           (:ok result#)
           (recur (dec attempts#) (:err result#))))
       (throw (ex-info (str "Failed after " ~n " attempts")
                       {:cause last-err#})))))
```

```clojure
;; Simulating a flaky payment provider:
(def call-count (atom 0))

(with-retry 3
  (swap! call-count inc)
  (if (< @call-count 3)
    (throw (ex-info "Provider timeout" {}))
    {:status :authorized :attempts @call-count}))
;; => {:status :authorized, :attempts 3}
```

### 4.4 `defpayment-handler` — eliminate boilerplate

In Class 1 (Part 7), every Pedestal handler had the same structure: parse body, call multimethod, wrap response. A macro can eliminate that boilerplate:

```clojure
(defmacro defpayment-handler
  "Defines a Pedestal handler that parses the payment and calls process-fn."
  [handler-name process-fn]
  `(defn ~handler-name [request#]
     (try
       (let [payment# (or (read-edn-body request#)
                          (payment-from-query (:query-params request#)))
             result#  (~process-fn payment#)]
         (edn-response {:payment payment# :result result#}))
       (catch clojure.lang.ExceptionInfo ex#
         (edn-response 400 {:error (.getMessage ex#) :data (ex-data ex#)})))))

(macroexpand-1 '(defpayment-handler pay-v2-handler process2))
;; => (defn pay-v2-handler [request__X]
;;      (try
;;        (let [payment__X (or (read-edn-body request__X)
;;                             (payment-from-query (:query-params request__X)))
;;              result__X  (process2 payment__X)]
;;          (edn-response {:payment payment__X :result result__X}))
;;        (catch clojure.lang.ExceptionInfo ex__X
;;          (edn-response 400 {:error (.getMessage ex__X) :data (ex-data ex__X)}))))
```

> 🎓 **SOCRATIC:** *"We went from a 10-line handler to a 1-line declaration. But is this a good idea?"*
>
> → **It depends.** If you have 20 handlers with the same shape: yes, the macro eliminates
> repetition and makes the pattern explicit. If you have 3 handlers and they're slightly different:
> no, a helper function is simpler and more transparent.
>
> **Macro rule of thumb:** Don't write a macro for a pattern until you've seen it at least three times.

---

## Part 5 — Fixation Exercises (15 min)

> Students do these themselves. Teacher walks around and helps.

### Exercise 1: `when-positive` — only run body if a value is positive

Write a macro `when-positive` that binds a value to a name and only runs the body if it's positive:

```clojure
(when-positive amount (:amount payment)
  (println "Processing" amount "MXN")
  {:status :ok :amount amount})

;; Should expand to something like:
;; (let [amount (:amount payment)]
;;   (when (and (number? amount) (pos? amount))
;;     (println "Processing" amount "MXN")
;;     {:status :ok :amount amount}))
```

```clojure
;; Student solution (reveal after they try):

(defmacro when-positive [name expr & body]
  `(let [~name ~expr]
     (when (and (number? ~name) (pos? ~name))
       ~@body)))

;; Test it:
(when-positive amount (:amount {:amount 1200.0})
  (str "Processing " amount " MXN"))
;; => "Processing 1200.0 MXN"

(when-positive amount (:amount {:amount -5})
  (str "Processing " amount " MXN"))
;; => nil

;; Verify expansion:
(macroexpand-1
  '(when-positive amount (:amount payment)
     (println amount)))
;; => (let [amount (:amount payment)]
;;      (when (and (number? amount) (pos? amount))
;;        (println amount)))
```

### Exercise 2: `with-log` — wrap any expression with entry/exit logging

```clojure
(with-log "authorize"
  (authorize spei-gw {:spei-clabe "032180000118359719" :amount 100}))
;; [authorize] START
;; [authorize] END (took 0.5 ms) => {:status :authorized, ...}
;; => {:status :authorized, ...}
```

```clojure
;; Student solution:

(defmacro with-log [label & body]
  `(do
     (println (str "[" ~label "] START"))
     (let [t0#     (System/nanoTime)
           result# (do ~@body)
           ms#     (/ (- (System/nanoTime) t0#) 1e6)]
       (println (str "[" ~label "] END (took " ms# " ms) => " result#))
       result#)))

;; Test it:
(with-log "authorize"
  (Thread/sleep 10)
  {:status :authorized})
;; [authorize] START
;; [authorize] END (took 10.xxx ms) => {:status :authorized}
;; => {:status :authorized}
```

### Exercise 3: `unless` — inverse of `when`

```clojure
;; Student solution:

(defmacro unless [condition & body]
  `(when-not ~condition
     ~@body))

(unless (= :approved (:status payment))
  (println "Payment not approved!")
  {:action :retry})

(macroexpand-1 '(unless false (println "hi")))
;; => (clojure.core/when-not false (println "hi"))
```

### Exercise 4 (bonus): `defvalidator` — generate a validation function from a spec

```clojure
;; Given:
(defvalidator valid-spei?
  [:spei-clabe string? "CLABE must be a string"]
  [:spei-clabe #(re-matches #"\d{18}" %) "CLABE must be 18 digits"]
  [:amount pos? "Amount must be positive"])

;; Should generate:
;; (defn valid-spei? [m]
;;   (and (string? (:spei-clabe m))
;;        (#(re-matches #"\d{18}" %) (:spei-clabe m))
;;        (pos? (:amount m))))
```

```clojure
;; Student solution:

(defmacro defvalidator [name & rules]
  (let [m (gensym "m")]
    `(defn ~name [~m]
       (and ~@(map (fn [[field pred _msg]]
                     `(~pred (~field ~m)))
                   rules)))))

(defvalidator valid-spei-v2?
  [:spei-clabe string?               "CLABE must be a string"]
  [:spei-clabe #(re-matches #"\d{18}" %) "CLABE must be 18 digits"]
  [:amount     pos?                   "Amount must be positive"])

(valid-spei-v2? {:spei-clabe "032180000118359719" :amount 100})
;; => true

(valid-spei-v2? {:spei-clabe "short" :amount 100})
;; => nil (falsy — regex failed)
```

> 🎓 **SOCRATIC (after exercises):** *"In Exercise 4, the error messages aren't used. How would you extend the macro to return error messages for failures?"*
> → This is where macros get more complex. You'd generate code that checks each rule and collects error messages. At some point, a data-driven approach (like Spec from the next class) becomes better than a macro.

---

## Part 6 — Bonus: Kafka with Protocols (15 min, overflow)

> **Goal:** Make the `extend-type` / `extend-protocol` patterns from Class 2 concrete with a real-world Java library — Apache Kafka.

### Why Kafka?

In a real Mexican fintech, payment events (authorized, captured, refunded) need to flow to downstream systems: fraud detection, accounting, notifications. Apache Kafka is the standard message bus.

Kafka's Java client gives you `KafkaProducer` and `KafkaConsumer` — classes you don't own and can't modify. Protocols let you wrap them cleanly.

### Add the dependency

Update `deps.edn`:

```clojure
{:deps {org.clojure/clojure                {:mvn/version "1.12.0"}
        org.apache.kafka/kafka-clients      {:mvn/version "3.7.0"}

        ;; JSON serialization for messages
        cheshire/cheshire                   {:mvn/version "5.13.0"}}

 :aliases
 {:nrepl
  {:extra-deps {nrepl/nrepl {:mvn/version "1.3.0"}}
   :main-opts  ["-m" "nrepl.cmdline"
                "--port" "7888"
                "--bind" "127.0.0.1"]}}}
```

Restart your REPL after changing `deps.edn`.

### Define a protocol for event publishing

```clojure
(defprotocol EventBus
  "Contract for publishing payment lifecycle events."
  (publish!   [this topic event] "Publish an event to a topic. Returns delivery metadata.")
  (close-bus! [this]             "Release resources."))
```

### Extend to Kafka's `KafkaProducer`

```clojure
(import '[org.apache.kafka.clients.producer
          KafkaProducer ProducerRecord ProducerConfig]
        '[org.apache.kafka.common.serialization StringSerializer])

(require '[cheshire.core :as json])

(extend-type KafkaProducer
  EventBus
  (publish! [this topic event]
    (let [key     (or (:id event) (str (random-uuid)))
          payload (json/generate-string event)
          record  (ProducerRecord. topic key payload)
          future  (.send this record)]
      ;; .get blocks until the broker acknowledges (sync for demo purposes)
      (let [metadata (.get future)]
        {:topic     (.topic metadata)
         :partition (.partition metadata)
         :offset    (.offset metadata)
         :key       key})))

  (close-bus! [this]
    (.close this)
    :closed))
```

> 🎓 **SOCRATIC:** *"We just made `KafkaProducer` — an Apache Java class — satisfy our Clojure protocol. How many lines of `KafkaProducer.java` did we edit?"*
> → Zero. This is `extend-type` from Class 2 with a real library.

### Create a Kafka producer

```clojure
(defn make-kafka-producer
  "Creates a KafkaProducer with sensible defaults."
  [bootstrap-servers]
  (KafkaProducer.
    {ProducerConfig/BOOTSTRAP_SERVERS_CONFIG bootstrap-servers
     ProducerConfig/KEY_SERIALIZER_CLASS_CONFIG   (.getName StringSerializer)
     ProducerConfig/VALUE_SERIALIZER_CLASS_CONFIG (.getName StringSerializer)
     ProducerConfig/ACKS_CONFIG                   "all"}))
```

### Wire it into the payment flow

```clojure
;; Create the producer (requires a running Kafka broker on localhost:9092)
(def kafka-bus (make-kafka-producer "localhost:9092"))

;; Publish a payment event:
(publish! kafka-bus "payment.authorized"
  {:id       "pay_abc123"
   :method   :spei
   :amount   1200.0
   :currency :MXN
   :status   :authorized})
;; => {:topic "payment.authorized", :partition 0, :offset 42, :key "pay_abc123"}

;; Clean up:
(close-bus! kafka-bus)
```

### Adding a consumer: `extend-type` on `KafkaConsumer`

Define a second protocol for consuming:

```clojure
(defprotocol EventConsumer
  "Contract for consuming events from an event bus."
  (subscribe!     [this topics]        "Subscribe to one or more topics.")
  (poll-events!   [this timeout-ms]    "Poll for new events. Returns a seq of maps.")
  (close-consumer! [this]              "Release resources."))
```

```clojure
(import '[org.apache.kafka.clients.consumer
          KafkaConsumer ConsumerConfig ConsumerRecords]
        '[org.apache.kafka.common.serialization StringDeserializer])

(extend-type KafkaConsumer
  EventConsumer
  (subscribe! [this topics]
    (.subscribe this (if (coll? topics) topics [topics]))
    :subscribed)

  (poll-events! [this timeout-ms]
    (let [records (.poll this (java.time.Duration/ofMillis timeout-ms))]
      (mapv (fn [record]
              {:topic     (.topic record)
               :partition (.partition record)
               :offset    (.offset record)
               :key       (.key record)
               :value     (json/parse-string (.value record) true)})
            records)))

  (close-consumer! [this]
    (.close this)
    :closed))
```

```clojure
(defn make-kafka-consumer
  "Creates a KafkaConsumer with sensible defaults."
  [bootstrap-servers group-id]
  (KafkaConsumer.
    {ConsumerConfig/BOOTSTRAP_SERVERS_CONFIG  bootstrap-servers
     ConsumerConfig/GROUP_ID_CONFIG           group-id
     ConsumerConfig/KEY_DESERIALIZER_CLASS_CONFIG   (.getName StringDeserializer)
     ConsumerConfig/VALUE_DESERIALIZER_CLASS_CONFIG (.getName StringDeserializer)
     ConsumerConfig/AUTO_OFFSET_RESET_CONFIG  "earliest"}))

;; Usage:
(def consumer (make-kafka-consumer "localhost:9092" "payment-processor"))

(subscribe! consumer ["payment.authorized" "payment.captured"])
;; => :subscribed

(poll-events! consumer 1000)
;; => [{:topic "payment.authorized"
;;      :partition 0
;;      :offset 42
;;      :key "pay_abc123"
;;      :value {:id "pay_abc123" :method "spei" :amount 1200.0 ...}}]

(close-consumer! consumer)
```

### In-memory implementation for testing (using `reify` from Class 2)

You don't need a running Kafka to test your code. Use `reify` to create a fake in-memory bus:

```clojure
(defn make-test-bus []
  (let [store (atom [])]
    (reify EventBus
      (publish! [_ topic event]
        (let [entry {:topic topic :event event :offset (count @store)}]
          (swap! store conj entry)
          entry))
      (close-bus! [_]
        (reset! store [])
        :closed))))

(def test-bus (make-test-bus))

(publish! test-bus "payment.authorized"
  {:id "pay_test_1" :method :spei :amount 500.0})
;; => {:topic "payment.authorized", :event {...}, :offset 0}

(publish! test-bus "payment.captured"
  {:id "pay_test_1" :method :spei :amount 500.0})
;; => {:topic "payment.captured", :event {...}, :offset 1}
```

> 🎓 **SOCRATIC:** *"The real Kafka producer and the test bus satisfy the same protocol. What does this buy us?"*
> → **Substitutability.** Your payment processing code calls `publish!` — it doesn't know or care
> whether it's talking to Kafka, an in-memory store, or a future RabbitMQ implementation.
> This is the same benefit Java interfaces give you, but without needing the original class author's cooperation.

### Full picture: protocols + macros + Kafka

Combining everything from all three classes:

```clojure
;; From Class 1: multimethod for routing
(defmulti resolve-gateway (juxt :method :country))
(defmethod resolve-gateway [:spei :MX] [_] spei-gw)
(defmethod resolve-gateway [:credit-card :MX] [_] card-gw)
(defmethod resolve-gateway :default [_] card-gw)

;; From Class 2: protocol for gateway operations
;; (authorize, capture, refund — defined on records)

;; From Class 3: macro for timing + Kafka for events
(defn pay! [event-bus payment]
  (let [gateway (resolve-gateway payment)]
    (with-timing (str "pay:" (:method payment))
      (let [auth-result (authorize gateway payment)]
        (publish! event-bus "payment.authorized"
          (merge payment auth-result))
        auth-result))))

;; In production:
;; (pay! kafka-bus {:method :spei :country :MX
;;                  :spei-clabe "032180000118359719" :amount 1200.0})

;; In tests:
;; (pay! test-bus {:method :spei :country :MX
;;                 :spei-clabe "032180000118359719" :amount 1200.0})
```

---

## Wrap-up & Key Takeaways (5 min)

### When to write a macro

```
                    ┌─────────────────────────────┐
                    │ Can a function do this?      │
                    └─────────────┬───────────────┘
                                  │
                    ┌─────────────┴──────────────┐
                    │                            │
                   YES                           NO
                    │                            │
                    ▼                            ▼
            ┌──────────────┐           ┌───────────────┐
            │ Write a      │           │ Do you need    │
            │ FUNCTION     │           │ to control     │
            │              │           │ evaluation?    │
            │ (always      │           │                │
            │  prefer this)│           │ Or introduce   │
            └──────────────┘           │ bindings?      │
                                       │                │
                                       │ Or restructure │
                                       │ code shape?    │
                                       └───────┬───────┘
                                               │
                                              YES
                                               │
                                               ▼
                                       ┌───────────────┐
                                       │ Write a       │
                                       │ MACRO         │
                                       │               │
                                       │ (then test it │
                                       │  with         │
                                       │  macroexpand) │
                                       └───────────────┘
```

### The rules

1. **Functions first.** Always try a function before reaching for a macro.
2. **Macros receive code, return code.** They run at compile time, not runtime.
3. **`macroexpand-1` is mandatory.** Never ship a macro you haven't expanded.
4. **Use auto-gensym (`name#`)** to avoid capturing the caller's bindings.
5. **Three times rule.** Don't write a macro for a pattern until you've seen it at least three times.

### Summary of all three classes

```
Class    Tool            Dispatch on        Extend from anywhere?   Enforced contract?
───────  ──────────────  ─────────────────  ─────────────────────   ──────────────────
1        Multimethods    Any computed value  Yes                    No
2        Protocols       Type of 1st arg    Yes                    Yes
3        Macros          (compile time)     N/A — extends syntax   N/A
```

```
Class    Key insight
───────  ──────────────────────────────────────────────────────────────────────
1        You can extend BEHAVIOR without editing existing code (open dispatch)
2        You can extend CONTRACTS to types you don't own (even Java classes)
3        You can extend the LANGUAGE itself (new syntax, new control flow)
```

> 🎓 **Final question for the class:** *"We extended behavior (multimethods), contracts (protocols), and syntax (macros). What's left to extend? What about the SHAPE of data itself — can we define what valid data looks like and have Clojure check it automatically?"*
> → Tease for the next class: **Spec** — describing, validating, and generating data.

---

## Appendix — Extra Examples (Backup)

> Use if you finish early.

### A) `assert-keys` — compile-time key presence check

```clojure
(defmacro assert-keys
  "Throws at MACRO-EXPANSION TIME if required keys are missing from a literal map."
  [m & required-keys]
  (let [missing (remove (set (keys m)) required-keys)]
    (when (seq missing)
      (throw (ex-info (str "Missing required keys: " missing)
                      {:missing missing}))))
  m)

;; Caught at compile time (when you evaluate this form):
;; (assert-keys {:method :spei :amount 100} :method :amount :spei-clabe)
;; => ExceptionInfo: Missing required keys: (:spei-clabe)

;; Passes:
(assert-keys {:method :spei :amount 100 :spei-clabe "032"} :method :amount :spei-clabe)
;; => {:method :spei, :amount 100, :spei-clabe "032"}
```

> **Note:** This only works with literal maps (written directly in code). If the map is a variable, the macro can't see its contents. This is a fundamental macro limitation — they see code, not runtime values.

### B) `defcommand` — generate multimethod + validation in one declaration

```clojure
(defmacro defcommand
  "Generates a defmethod for process2 with built-in validation."
  [dispatch-val bindings validations & body]
  `(defmethod process2 ~dispatch-val [payment#]
     (let [~bindings payment#]
       (with-validation payment# ~validations
         ~@body))))

;; Usage:
(defcommand [:spei :standard]
  {:keys [spei-clabe amount]}
  [[#(re-matches #"\d{18}" (str (:spei-clabe %))) "Invalid CLABE"]
   [#(pos? (:amount %))                            "Amount must be positive"]]
  {:status :confirmed :provider :spei-rails :amount amount})
```

### C) `spy->` — threading with debug printing at every step

```clojure
(defmacro spy->
  "Like ->, but prints the intermediate value at each step."
  [expr & forms]
  (let [steps (reduce
                (fn [acc form]
                  (let [threaded (if (seq? form)
                                  `(~(first form) ~acc ~@(rest form))
                                  `(~form ~acc))]
                    `(let [v# ~threaded]
                       (println '~form "=>" v#)
                       v#)))
                expr
                forms)]
    steps))

(spy-> {:amount 100 :method :spei}
       (assoc :currency :MXN)
       (update :amount * 1.16))
;; (assoc :currency :MXN) => {:amount 100, :method :spei, :currency :MXN}
;; (update :amount * 1.16) => {:amount 116.0, :method :spei, :currency :MXN}
;; => {:amount 116.0, :method :spei, :currency :MXN}
```

### D) Comparing three approaches to the same problem

Logging every payment operation — function, macro, and protocol:

```clojure
;; 1. FUNCTION approach (simple, explicit)
(defn log-and-process [payment]
  (println "Processing:" (:method payment))
  (let [result (process2 payment)]
    (println "Result:" result)
    result))

;; 2. MACRO approach (transparent, wraps any code)
(defmacro with-payment-log [& body]
  `(do
     (println "Payment operation starting...")
     (let [r# (do ~@body)]
       (println "Payment operation complete:" r#)
       r#)))

(with-payment-log
  (process2 {:method :spei :spei-clabe "032180000118359719" :amount 100}))

;; 3. PROTOCOL approach (per-type behavior)
(defprotocol Loggable
  (log-entry [this]))

(extend-protocol Loggable
  SpeiGateway
  (log-entry [this] (str "SPEI via " (:bank-code this)))

  CardGateway
  (log-entry [this] (str "Card via " (:acquirer-id this))))

;; Each approach has its sweet spot:
;; Function: when you know what you're wrapping
;; Macro: when you want to wrap ARBITRARY code transparently
;; Protocol: when the log format depends on the TYPE
```

---

## Teacher Reference: Macro Debugging Checklist

```
Problem                           Likely cause                    Fix
────────────────────────────────  ──────────────────────────────  ──────────────────────
Symbol not resolved               Missing ~ (unquote)             Add ~ before the symbol
"Can't take value of macro"       Using macro as a value          Macros aren't first-class — wrap in fn
Double evaluation                 ~expr appears twice in template  Bind with let + gensym
Name collision                    Forgot # (auto-gensym)          Add # suffix: result#
Weird namespace prefix            Syntax-quote resolves symbols   Use ~'name to prevent resolution
List vs vector confusion          Syntax-quote makes lists        Use (vec ...) if you need a vector
"Wrong number of args"            Macro args are forms, not vals  Check macroexpand-1 output
```

### Key REPL tools for macros

```clojure
;; See one level of expansion:
(macroexpand-1 '(your-macro ...))

;; See full recursive expansion:
(macroexpand '(your-macro ...))

;; Pretty-print the expansion:
(clojure.pprint/pprint (macroexpand-1 '(your-macro ...)))

;; Check if something is a macro:
(:macro (meta #'when))   ;; => true
(:macro (meta #'map))    ;; => nil (it's a function)
```