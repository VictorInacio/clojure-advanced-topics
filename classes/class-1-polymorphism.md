# Clojure Advanced Topics — Class 1 (2h)

## Open Polymorphism with Multimethods

> **Teacher origin:** Brazil · **Students:** Mexico
> All examples use Mexican payment rails (SPEI, OXXO) with teacher notes mapping to Brazilian equivalents (PIX, boleto).

---

## Agenda

By the end of this class, students will be able to:

- Set up a Clojure REPL from scratch and connect it to an IDE
- Explain why `cond` chains become a maintenance problem as systems grow
- Implement multimethods with dispatch functions based on any criteria
- Extend a system's behavior by adding code, not editing existing code
- Wire multimethods into a real HTTP server

## Timeline

```
 0:00 ┬─ Part 0 — Environment Setup ··················· 15 min
      │   Java · Clojure CLI · deps.edn · nREPL · IntelliJ/Cursive
      │
 0:15 ┬─ Part 1 — Problem Provocation ················· 15 min
      │   Payment processing with cond chains
      │   "What breaks when you add Apple Pay next week?"
      │
 0:30 ┬─ Part 2 — The Concept: Open Polymorphism ······ 10 min
      │   defmulti / defmethod mental model
      │
 0:40 ┬─ Part 3 — Multimethod by :method ·············· 20 min
      │   Replace cond with keyword dispatch
      │
 1:00 ┬─── 5 min break ───
      │
 1:05 ┬─ Part 4 — Dispatch by Computed Criteria ······· 25 min
      │   Vector dispatch [method tier], business rules
      │
 1:30 ┬─ Part 5 — REPL Workflow Habits ················ 10 min
      │   Exploration, redefinition, doc, apropos
      │
 1:40 ┬─ Part 6 — Guided Exercise ····················· 15 min
      │   Add :debit-card (no edits to existing code)
      │   Bonus: juxt dispatch on [method currency]
      │
 1:55 ┬─ Part 7 — Pedestal Web Server (demo) ·········· optional / overflow
      │   HTTP endpoints calling multimethods
      │   If time is short, teacher demos live
      │
 2:00 ┴─ Wrap-up & Key Takeaways ······················  5 min
         Tease next class: Protocols
```

> **Note:** Parts 0–6 are the core curriculum (1h55). Part 7 (Pedestal) is overflow material — demo it live if time allows, or assign as homework. The Appendix has extra examples if any section finishes early.

---

## Part 0 — Environment Setup (15 min)

### 0.1 Install Java (JDK 21)

Clojure runs on the JVM. You need a JDK installed before anything else.

**macOS (Homebrew):**

```bash
brew install openjdk@21
```

**Linux (apt):**

```bash
sudo apt install openjdk-21-jdk
```

**Windows:**

Download from [Adoptium](https://adoptium.net/) (Eclipse Temurin JDK 21) and run the installer.
Make sure "Set JAVA_HOME" is checked during install.

**Verify:**

```bash
java -version
# Should print something like: openjdk version "21.x.x"
```

> 🎓 **SOCRATIC:** *"Why does Clojure need Java? Could it run on something else?"*
> (Opens discussion on JVM as a platform — Clojure also targets JS and CLR)

**Common pitfall:** If `java` isn't found, your `PATH` or `JAVA_HOME` is not set.


### 0.2 Install the Clojure CLI

The `clj` command is the standard way to start a REPL and manage dependencies.

**macOS:**

```bash
brew install clojure/tools/clojure
```

**Linux:**

```bash
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
```

**Windows:**

Follow the official guide at [clojure.org/guides/install_clojure](https://clojure.org/guides/install_clojure) — use the Windows installer (requires PowerShell).

**Verify:**

```bash
clj -Sdescribe
# Should print an EDN map with version info
```

### 0.3 Create a minimal project

```bash
mkdir payments-mm && cd payments-mm
```

Create a file called `deps.edn` with this content:

```clojure
{:deps {org.clojure/clojure {:mvn/version "1.12.0"}}

 :aliases
 {:nrepl
  {:extra-deps {nrepl/nrepl {:mvn/version "1.3.0"}}
   :main-opts  ["-m" "nrepl.cmdline"
                "--port" "7888"
                "--bind" "127.0.0.1"]}}}
```

> 🎓 **SOCRATIC:** *"This file has no JSON, no YAML. What data format is this?"*
> (Introduce EDN — Extensible Data Notation — Clojure's native data format. It IS the language's data literals.)


### 0.4 Start the REPL

**Option A — Terminal REPL (simplest):**

```bash
clj
```

You're in. Paste code. Done.

**Option B — nREPL (for IDE connection):**

```bash
clj -M:nrepl
```

This starts a networked REPL on port 7888. Keep this terminal open.


### 0.5 Connect IntelliJ + Cursive

1. Install **IntelliJ IDEA** (Community Edition is free)
2. Install the **Cursive** plugin (Settings → Plugins → Marketplace → search "Cursive")
3. Open the `payments-mm` folder as a project
4. Go to **Run → Edit Configurations…**
5. Add → **Clojure REPL → Remote**
   - Host: `127.0.0.1`
   - Port: `7888`
6. **Apply → Run**

Once connected, place your cursor at the end of any form and press the Cursive "Send to REPL" shortcut to evaluate it.

> 🎓 **SOCRATIC:** *"Why would we want the editor connected to a running program instead of just running a file?"*
> (Core idea: REPL-driven development — you shape a living program interactively, not in compile-run-debug cycles)


### 0.6 Sanity check — paste into the REPL

```clojure
(+ 2 3)
;; => 5

(str "Hello " "Clojure!")
;; => "Hello Clojure!"

;; Explore built-in docs anytime:
(doc cond)
(doc defmulti)
```

**Common issues & quick fixes:**

| Problem | Fix |
|---------|-----|
| `clj: command not found` | Clojure CLI not installed or not on PATH |
| nREPL connects but eval fails | Check IntelliJ Project SDK points to JDK 21 |
| Port already in use | Change `--port 7888` to `7889` in `deps.edn` |
| macOS/Windows firewall prompt | Allow localhost traffic |

---

## Part 1 — Problem Provocation: "Payments Are Growing" (15 min)

> **Goal:** Feel the pain of `cond` chains before seeing the solution.

### The scenario

Your fintech app processes three payment methods common in Mexico:

| Method | What it is | Brazilian equivalent (teacher ref) |
|--------|-----------|-----------------------------------|
| `:credit-card` | Visa/Mastercard via acquirer | Same |
| `:spei` | Bank transfer via SPEI (18-digit CLABE) | PIX (instant transfer rails) |
| `:oxxo` | Cash payment reference (pay at OXXO store) | Boleto (async cash/bank slip) |

> **Teacher note:** When you accidentally say "PIX", map it immediately:
> *"PIX ≈ SPEI — bank transfer rails. Boleto ≈ OXXO Pay — pay later, confirmation is async."*

### A first attempt — the `cond` approach

Paste into the REPL:

```clojure
;; --- Validation helpers ---

(defn valid-credit-card? [{:keys [card-number cvv holder amount]}]
  (and (string? holder)
       (pos? amount)
       (re-matches #"\d{16}" (str card-number))
       (re-matches #"\d{3,4}" (str cvv))))

;; SPEI: CLABE is an 18-digit standardized bank account number
(defn valid-spei? [{:keys [spei-clabe amount]}]
  (and (pos? amount)
       (re-matches #"\d{18}" (str spei-clabe))))

;; OXXO: reference number (10-30 digits for demo)
(defn valid-oxxo? [{:keys [oxxo-reference amount]}]
  (and (pos? amount)
       (re-matches #"\d{10,30}" (str oxxo-reference))))
```

> 🎓 **SOCRATIC:** *"What does `{:keys [card-number cvv holder amount]}` do? Where did the argument go?"*
> (Introduce destructuring — Clojure pulls values out of a map directly in the parameter list)

Now the "big conditional" processor:

```clojure
(defn validate-payment! [{:keys [method] :as payment}]
  (cond
    (= method :credit-card)
    (when-not (valid-credit-card? payment)
      (throw (ex-info "Invalid credit card payment" {:payment payment})))

    (= method :spei)
    (when-not (valid-spei? payment)
      (throw (ex-info "Invalid SPEI payment" {:payment payment})))

    (= method :oxxo)
    (when-not (valid-oxxo? payment)
      (throw (ex-info "Invalid OXXO payment" {:payment payment})))

    :else
    (throw (ex-info "Unknown payment method" {:payment payment}))))

(defn process-payment [{:keys [method] :as payment}]
  (validate-payment! payment)
  (cond
    (= method :credit-card)
    {:status :approved :provider :mx-acquirer :method method}

    (= method :spei)
    {:status :confirmed :provider :spei-rails :method method}

    (= method :oxxo)
    {:status :pending :provider :oxxo-network :method method}

    :else
    (throw (ex-info "Unknown payment method" {:payment payment}))))
```

### Try it

```clojure
(process-payment {:method :spei
                  :spei-clabe "032180000118359719"
                  :amount 1200.0})
;; => {:status :confirmed, :provider :spei-rails, :method :spei}

(process-payment {:method :oxxo
                  :oxxo-reference "1234567890123456"
                  :amount 350.0})
;; => {:status :pending, :provider :oxxo-network, :method :oxxo}
```

### The provocation

> 🎓 **SOCRATIC — pause and discuss (3–5 min):**
>
> 1. *"What happens when your product manager says: add Apple Pay next Monday?"*
>    → You edit TWO functions. What if there are 15 functions with these conds?
>
> 2. *"What if credit card above $500 MXN needs 3D Secure, but below doesn't?"*
>    → You nest conditionals inside conditionals. The cond grows.
>
> 3. *"What if a partner company wants to add their own payment method to your system — but they can't edit your source code?"*
>    → Impossible with cond. The dispatch logic is closed.
>
> 4. *"In object-oriented languages, what mechanism solves this?"*
>    → Polymorphism. But in OOP it's tied to class hierarchies. Clojure takes a different path.

**Key insight:** With `cond` chains, you **edit old code** (risk) instead of **adding new code** (safe). This is the Open/Closed Principle violation.

---

## Part 2 — The Concept: Open Polymorphism (10 min)

### How Java solves this (and where it hits a wall)

In Java, polymorphism works through **interfaces** and **class hierarchies**:

```java
// Java: you define an interface
interface PaymentProcessor {
    ValidationResult validate(Payment payment);
    ProcessingResult process(Payment payment);
}

// Each type implements it
class CreditCardProcessor implements PaymentProcessor {
    public ValidationResult validate(Payment p) { /* ... */ }
    public ProcessingResult process(Payment p) { /* ... */ }
}

class SpeiProcessor implements PaymentProcessor {
    public ValidationResult validate(Payment p) { /* ... */ }
    public ProcessingResult process(Payment p) { /* ... */ }
}
```

This is better than `if/else` chains, but it has constraints:

| Limitation | Why it matters |
|-----------|---------------|
| **Dispatch is always on the type of the object** | You can't dispatch on a *value inside* the object, a *combination of fields*, or a *computed rule* — only on `this.getClass()` |
| **The interface must exist first** | If you're using a library's class and it doesn't implement your interface, you're stuck (wrapper/adapter pattern overhead) |
| **Single dispatch only** | Java picks the method based on the type of ONE object (the receiver). What if the behavior depends on TWO things — the payment method AND the risk tier? You need the Visitor pattern or manual `instanceof` checks |
| **Closed to external extension** | A third-party can't add a new method to your interface without you publishing it. They can implement it, but they can't add new operations |

> 🎓 **SOCRATIC:** *"In Java, if you want to add a `fee()` operation to all payment types, what do you do?"*
> → Add it to the interface → now EVERY implementation class must be updated. This is the "expression problem."
>
> *"What if you want the processing to differ based on amount AND method AND country?"*
> → You'd need `instanceof` checks or Visitor pattern or Strategy pattern on top of the interface. Layers of indirection.

### What Clojure offers instead

**Multimethods** give you:

- A single operation name (like `validate!`, `process`)
- Multiple implementations, each handling a different case
- A **dispatch function** that decides which implementation runs
- The dispatch can be based on **anything** — a keyword, a computed value, a combination of fields

**The key differences from Java interfaces:**

| Java Interface | Clojure Multimethod |
|---------------|-------------------|
| Dispatch on **class type** | Dispatch on **any value** (keyword, vector, computed result) |
| Must implement **all methods** at once | Add **one method at a time**, from anywhere |
| Defined at **compile time** | Extended at **runtime** (even from the REPL) |
| **Single dispatch** (type of `this`) | **Multiple dispatch** (any combination of criteria) |
| Extension requires **source code access** | Extension requires **only the multimethod name** |

### The mental model

```
┌─────────────────────────┐
│   (defmulti process     │   ← "What operation?"
│     dispatch-fn)        │   ← "How to choose?" (you write this function!)
└─────────┬───────────────┘
          │ dispatch value (any value: keyword, vector, string, ...)
          ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ defmethod    │  │ defmethod    │  │ defmethod    │
│ :credit-card │  │ :spei        │  │ :oxxo        │
└──────────────┘  └──────────────┘  └──────────────┘
                                    ↑ add new ones
                                      WITHOUT editing
                                      existing code
                                      (even from a different JAR!)
```

Compare side by side — same problem, both languages:

```
Java:                                Clojure:
                                     
interface Processor {                (defmulti process :method)
  Result process(Payment p);         
}                                    (defmethod process :credit-card [p]
                                       ...)
class CreditCard                     
  implements Processor {             (defmethod process :spei [p]
  Result process(Payment p) {...}      ...)
}                                    
                                     ;; Adding from anywhere, anytime:
class Spei                           (defmethod process :apple-pay [p]
  implements Processor {               ...)
  Result process(Payment p) {...}    
}                                    
                                     
// Adding Apple Pay:                 
// 1. Create new class               ;; ← That's it. One defmethod.
// 2. Make sure it implements         ;;    No class, no interface,
//    the interface                   ;;    no compilation dependency.
// 3. Register it somewhere           
// 4. Recompile & redeploy            
```

> 🎓 **SOCRATIC:** *"In Java, where does the dispatch decision live?"*
> → In the class hierarchy (vtable). The JVM looks at the runtime type of the object.
> You can't change the dispatch criteria without changing the class tree.
>
> *"In Clojure, where does it live?"*
> → In a plain function you write. You have total freedom. Want to dispatch on
> `[method country risk-score]`? Just return that vector from your dispatch function.
> No new classes, no interfaces, no type hierarchy changes.

### Under the Hood: How `defmulti` and `defmethod` Actually Work

> This section is for building real understanding. You don't need to memorize the internals,
> but knowing what the machine does removes the "magic" feeling and makes debugging easier.

#### Are they macros or functions?

**Both are macros.** You can verify this in the REPL:

```clojure
(type #'defmulti)
;; => clojure.lang.Var  — and its value is a macro function

;; Peek at what the macro expands to:
(macroexpand-1
  '(defmulti process :method))
;; => (def process (clojure.core/new-multi ...))
;;    ↑ it defines a Var whose value is a MultiFn object
```

**`defmulti`** expands roughly to:

1. Create a `clojure.lang.MultiFn` Java object (more on this below)
2. `def` a Var in the current namespace pointing to that object

**`defmethod`** expands roughly to:

1. Call `.addMethod` on the existing `MultiFn` object
2. Pass the dispatch value and the implementation function

```clojure
(macroexpand-1
  '(defmethod process :credit-card [payment] :ok))
;; => (.addMethod process :credit-card (fn [payment] :ok))
;;    ↑ literally a Java method call on the MultiFn object
```

> 🎓 **SOCRATIC:** *"So `defmethod` doesn't create anything new — it mutates an existing object. What does that imply?"*
> → The MultiFn is **stateful**. It holds a mutable registry of dispatch-value → function mappings.
> This is one of the few places in Clojure where mutation is by design.

#### The internal registry: where methods live

A `MultiFn` object holds an internal `java.util.concurrent.atomic.AtomicReference` to an `IPersistentMap` — a dispatch-value → function mapping:

```
MultiFn "process"
├── dispatchFn: :method          (the function you pass to defmulti)
├── defaultDispatchVal: :default
├── methodTable (atomic ref to persistent map):
│   ├── :credit-card → fn#1
│   ├── :spei        → fn#2
│   ├── :oxxo        → fn#3
│   └── :default     → fn#4
├── preferTable: {}              (for resolve ambiguity with prefer-method)
└── cachedHierarchy              (for isa?-based dispatch with derive)
```

You can inspect this live in the REPL:

```clojure
;; See all registered dispatch values:
(methods process)
;; => {:credit-card #fn, :spei #fn, :oxxo #fn, :default #fn}

;; See the dispatch function:
(.dispatchFn process)
;; => :method

;; Get a specific method implementation:
(get-method process :spei)
;; => #function[...]
```

> 🎓 **SOCRATIC:** *"The method table is a persistent (immutable) map inside an atomic reference. Why not just a plain mutable HashMap?"*
> → Thread safety. Swapping an atomic reference to a new immutable map means readers never see a half-updated table. This is the same strategy Clojure uses for `atom`.

#### State lifecycle

```
1. (defmulti process :method)
   └─→ Creates MultiFn object with empty method table
       Stores it in Var #'user/process

2. (defmethod process :credit-card [p] ...)
   └─→ Calls (.addMethod multifn :credit-card fn)
       └─→ Atomically swaps method table to include new entry
           Also invalidates the method cache

3. (process {:method :credit-card ...})   — INVOCATION
   └─→ MultiFn.invoke(args)
       └─→ Calls dispatchFn(args) → gets :credit-card
           └─→ Looks up :credit-card in method table
               └─→ If found: calls that fn with original args
                   If not found: tries isa? hierarchy
                   If still not: calls :default
                   If no default: throws IllegalArgumentException

4. (remove-method process :credit-card)
   └─→ Atomically removes entry from method table

5. (ns-unmap *ns* 'process)
   └─→ Removes the Var entirely (full reset)
```

> 🎓 **SOCRATIC:** *"Step 3 mentions a 'method cache'. Why would a lookup in a persistent map need caching?"*
> → Because of hierarchies. When you use `derive`, the dispatch might need `isa?` checks,
> which walk the hierarchy tree. The cache maps dispatch values directly to the resolved function.
> It's invalidated whenever you `addMethod` or `removeMethod`.

#### Java or Clojure? Both.

The multimethod system lives at the **boundary** between Clojure and Java:

**Written in Java** (in the Clojure runtime):

- `clojure.lang.MultiFn` — the core class. Written in pure Java.
  [Source: `src/jvm/clojure/lang/MultiFn.java`](https://github.com/clojure/clojure/blob/master/src/jvm/clojure/lang/MultiFn.java)
- Implements `clojure.lang.IFn` so it's callable like any Clojure function
- Handles: method lookup, `isa?`-based hierarchy dispatch, preference resolution, caching

**Written in Clojure** (in `clojure.core`):

- `defmulti` — macro that creates the MultiFn and defs the Var
  [Source: `src/clj/clojure/core.clj`](https://github.com/clojure/clojure/blob/master/src/clj/clojure/core.clj) (search for `defmulti`)
- `defmethod` — macro that calls `.addMethod` on the MultiFn
- `remove-method`, `prefer-method`, `methods`, `get-method` — thin Clojure wrappers over Java methods

```
┌─────────────────────────────────────────┐
│         Your REPL code (Clojure)        │
│  (defmulti process :method)             │
│  (defmethod process :spei [p] ...)      │
└────────────────┬────────────────────────┘
                 │ macroexpand
                 ▼
┌─────────────────────────────────────────┐
│       clojure.core macros (Clojure)     │
│  defmulti → (def process (new MultiFn)) │
│  defmethod → (.addMethod process ...)   │
└────────────────┬────────────────────────┘
                 │ Java interop calls
                 ▼
┌─────────────────────────────────────────┐
│    clojure.lang.MultiFn (Java class)    │
│  - dispatchFn field                     │
│  - methodTable (AtomicReference)        │
│  - hierarchy, cache, preferTable        │
│  - invoke() → dispatch → lookup → call  │
└─────────────────────────────────────────┘
```

> 🎓 **SOCRATIC:** *"Why is MultiFn written in Java instead of Clojure?"*
> → **Bootstrap problem.** Clojure needs MultiFn to compile itself — `clojure.core` uses multimethods
> internally. You can't define `defmulti` in Clojure if the MultiFn class doesn't exist yet.
> This is the same reason `clojure.lang.PersistentHashMap` is written in Java — Clojure's own
> data structures must exist before the language can boot.

#### Where multimethods appear in Clojure core itself

Clojure's own standard library uses multimethods:

```clojure
;; print-method: how Clojure prints any value to a writer
;; dispatches on (class object)
(defmulti print-method (fn [x writer] (class x)))

;; That's why you can teach Clojure to print your custom types:
;; (defmethod print-method MyRecord [obj writer] ...)

;; print-dup: how Clojure prints values for read-back (serialization)
(defmulti print-dup (fn [x writer] (class x)))
```

You can see this in action:

```clojure
;; How many print-method implementations are registered?
(count (methods print-method))
;; => 50+ (one for each core type: String, Long, PersistentVector, ...)

;; What dispatch values are registered?
(keys (methods print-method))
;; => (java.lang.String java.lang.Long clojure.lang.PersistentVector ...)
```

> 🎓 **SOCRATIC:** *"So when you type `{:a 1}` in the REPL and see `{:a 1}` printed back — that's a multimethod dispatching on the class of the value?"*
> → Yes! The REPL calls `print-method`, which dispatches on `(class {:a 1})` → `PersistentArrayMap`,
> and the registered defmethod for that class knows how to print `{:a 1}`.
> You're already using multimethods every time you see output in the REPL.

---

## Part 3 — Step 1: Multimethod by `:method` (20 min)

> **Goal:** Replace the conditional validation and processing with multimethods.

### Remove the old functions first

```clojure
;; In the REPL you can just redefine. The old `validate-payment!`
;; and `process-payment` functions still exist but we'll stop using them.
```

### Multimethod validation

```clojure
(defmulti validate!
  "Validates a payment map. Dispatches on the :method key."
  :method)  ;; ← this is the dispatch function (a keyword IS a function in Clojure)
```

> 🎓 **SOCRATIC:** *"`:method` is the dispatch function. But `:method` is a keyword — how can it be a function?"*
> Try: `(:method {:method :spei :amount 100})`
> → Keywords ARE functions that look themselves up in maps. This is idiomatic Clojure.

```clojure
(defmethod validate! :credit-card [payment]
  (when-not (valid-credit-card? payment)
    (throw (ex-info "Invalid credit card payment" {:payment payment})))
  :ok)

(defmethod validate! :spei [payment]
  (when-not (valid-spei? payment)
    (throw (ex-info "Invalid SPEI payment" {:payment payment})))
  :ok)

(defmethod validate! :oxxo [payment]
  (when-not (valid-oxxo? payment)
    (throw (ex-info "Invalid OXXO cash reference" {:payment payment})))
  :ok)

;; The safety net: anything we don't recognize
(defmethod validate! :default [payment]
  (throw (ex-info "Unknown payment method" {:payment payment})))
```

### Multimethod processing

```clojure
(defmulti process
  "Processes a payment. Dispatches on the :method key."
  :method)

(defmethod process :credit-card [payment]
  (validate! payment)
  {:status :approved :provider :mx-acquirer :method (:method payment)})

(defmethod process :spei [payment]
  (validate! payment)
  {:status :confirmed :provider :spei-rails :method (:method payment)})

(defmethod process :oxxo [payment]
  (validate! payment)
  {:status :pending :provider :oxxo-network :method (:method payment)})

(defmethod process :default [payment]
  (validate! payment))  ;; will throw "Unknown payment method"
```

### Try it

```clojure
(process {:method :spei
          :spei-clabe "032180000118359719"
          :amount 1200.0})
;; => {:status :confirmed, :provider :spei-rails, :method :spei}

(process {:method :oxxo
          :oxxo-reference "1234567890123456"
          :amount 350.0})
;; => {:status :pending, :provider :oxxo-network, :method :oxxo}

;; What about an unknown method?
(process {:method :dogecoin :amount 420.0})
;; => ExceptionInfo: Unknown payment method
```

> 🎓 **SOCRATIC:** *"We ended up with roughly the same amount of code. So what did we actually gain?"*
>
> → **Openness.** Each `defmethod` is independent. You can add `:apple-pay` from a different file,
> a different namespace, even a different JAR — without touching existing code.
>
> → **Locality.** All credit-card logic lives together. All SPEI logic lives together.
> With `cond`, credit-card logic was scattered across every conditional branch.

---

## Part 4 — Step 2: Dispatch by Computed Criteria (25 min)

> **Goal:** Show the real power — dispatch on business rules, not just a type tag.

### New business rules

| Method | Condition | Tier | Behavior |
|--------|-----------|------|----------|
| `:credit-card` | amount > 500 MXN | `:3ds` | Redirect to 3D Secure |
| `:spei` | amount > 100,000 MXN | `:manual-review` | Compliance hold |
| `:oxxo` | amount > 20,000 MXN | `:high-risk` | Flag for review |
| Any | otherwise | `:standard` | Normal flow |

> 🎓 **SOCRATIC:** *"Can you solve this with the keyword-only dispatch we just built?"*
> → No. We need to dispatch on TWO things: the method AND the tier.
>
> *"What data structure could represent 'two things'?"*
> → A vector! `[:credit-card :3ds]`

### The dispatch function

```clojure
(defn payment-tier
  "Computes a risk/routing tier based on business rules."
  [{:keys [method amount]}]
  (cond
    (and (= method :credit-card) (> amount 500))   :3ds
    (and (= method :spei)        (> amount 100000)) :manual-review
    (and (= method :oxxo)        (> amount 20000))  :high-risk
    :else                                            :standard))
```

> 🎓 **SOCRATIC:** *"Wait — we just used `cond` again! Isn't that what we were trying to avoid?"*
>
> → Great observation. The `cond` is now in ONE place — the dispatch function —
> not duplicated across every operation. The dispatch function is the ONLY code
> that knows about tiers. Each `defmethod` only knows its own case.

### Define the multimethod with a composite dispatch value

```clojure
(defmulti process2
  "Processes payment. Dispatches on [method tier] vector."
  (fn [payment]
    [(:method payment) (payment-tier payment)]))
```

> 🎓 **SOCRATIC:** *"The dispatch function returns `[:credit-card :3ds]`. What type is that?"*
> → A regular Clojure vector. Multimethods use `=` to match dispatch values.
> Any value that supports equality can be a dispatch value: keywords, strings, numbers, vectors, even maps.

### Implement each combination

```clojure
;; --- Credit Card ---
(defmethod process2 [:credit-card :standard] [payment]
  (validate! payment)
  {:status :approved :flow :regular :provider :mx-acquirer})

(defmethod process2 [:credit-card :3ds] [payment]
  (validate! payment)
  {:status  :requires-action
   :flow    :3ds
   :provider :mx-acquirer
   :next    {:type :redirect :url "https://3ds.example/checkout"}})

;; --- SPEI ---
(defmethod process2 [:spei :standard] [payment]
  (validate! payment)
  {:status :confirmed :flow :bank-transfer :provider :spei-rails})

(defmethod process2 [:spei :manual-review] [payment]
  (validate! payment)
  {:status  :pending
   :flow    :manual-review
   :provider :spei-rails
   :reason  :amount-threshold})

;; --- OXXO ---
(defmethod process2 [:oxxo :standard] [payment]
  (validate! payment)
  {:status :pending :flow :cash-reference :provider :oxxo-network})

(defmethod process2 [:oxxo :high-risk] [payment]
  (validate! payment)
  {:status  :pending
   :flow    :cash-reference
   :provider :oxxo-network
   :flags   #{:high-risk}})

;; --- Safety net ---
(defmethod process2 :default [payment]
  (throw (ex-info "No processing rule for dispatch value"
                  {:dispatch [(:method payment) (payment-tier payment)]
                   :payment  payment})))
```

### Try it — observe different dispatch paths

```clojure
;; Standard credit card (below threshold)
(process2 {:method :credit-card
            :card-number "4111111111111111"
            :cvv "123"
            :holder "María"
            :amount 200.0})
;; => {:status :approved, :flow :regular, ...}

;; High-value credit card → 3DS
(process2 {:method :credit-card
            :card-number "4111111111111111"
            :cvv "123"
            :holder "María"
            :amount 700.0})
;; => {:status :requires-action, :flow :3ds, ...}

;; Large SPEI → manual review
(process2 {:method :spei
            :spei-clabe "032180000118359719"
            :amount 250000.0})
;; => {:status :pending, :flow :manual-review, ...}

;; See what the dispatch function produces:
(let [p {:method :credit-card :amount 700}]
  [(:method p) (payment-tier p)])
;; => [:credit-card :3ds]
```

> 🎓 **SOCRATIC:** *"If the compliance team changes the SPEI threshold from 100k to 50k, how many functions do you edit?"*
> → Exactly one: `payment-tier`. All defmethods are untouched.
>
> *"If you need to add a brand new tier — say `:fraud-block` that rejects the payment entirely — what do you do?"*
> → Add a clause in `payment-tier` + add new `defmethod`s. Existing methods? Untouched.

---

## Part 5 — REPL Workflow Habits (10 min)

> **Goal:** Build muscle memory for REPL-driven development.

### Exploring data

```clojure
;; What keys does this map have?
(keys {:method :spei :spei-clabe "032180000118359719" :amount 1200})

;; What type is this?
(type {:a 1})       ;; => clojure.lang.PersistentArrayMap
(type [:a :b])      ;; => clojure.lang.PersistentVector
(type :credit-card) ;; => clojure.lang.Keyword
```

### Exploring the language

```clojure
(doc defmulti)
(doc defmethod)
(doc cond)

;; Find functions by partial name:
(apropos "method")
```

### Redefining is normal

In the REPL, you can re-evaluate any `defn`, `defmulti`, or `defmethod` block.
This is not a bug — it's the workflow. You shape a running program iteratively.

```clojure
;; When you need to fully reset a multimethod (rare, but useful to know):
(ns-unmap *ns* 'process2)
;; Then re-evaluate the defmulti and all defmethods
```

> 🎓 **SOCRATIC:** *"In compiled languages, what's the feedback loop? Write → compile → run → see error → go back. Here?"*
> → Write → evaluate → see result immediately. The program is alive while you work on it.

---

## Part 6 — Guided Exercise (15 min)

> Students do these themselves. Teacher walks around and helps.

### Exercise 1: Add a new payment method (no edits to existing code)

Add `:debit-card` support. Requirements:

- Validation: 16-digit card number + 4-digit PIN + positive amount
- Processing (standard): `{:status :approved :flow :pin :provider :mx-acquirer}`

Hint: you need one new validation function, one `defmethod validate!`, and one `defmethod process2`.

```clojure
;; Student solution (reveal after they try):

(defn valid-debit-card? [{:keys [card-number pin amount]}]
  (and (pos? amount)
       (re-matches #"\d{16}" (str card-number))
       (re-matches #"\d{4}" (str pin))))

(defmethod validate! :debit-card [payment]
  (when-not (valid-debit-card? payment)
    (throw (ex-info "Invalid debit card payment" {:payment payment})))
  :ok)

(defmethod process2 [:debit-card :standard] [payment]
  (validate! payment)
  {:status :approved :flow :pin :provider :mx-acquirer})

;; Test it:
(process2 {:method :debit-card
            :card-number "5555555555554444"
            :pin "1234"
            :amount 150.0})
```

> 🎓 **SOCRATIC (after exercise):** *"How many existing lines of code did you edit?"*
> → Zero. That's the power of open polymorphism.

### Exercise 2 (bonus): Dispatch on `[method currency]`

Create a multimethod `settle` that dispatches on `[method currency]` and returns
settlement timing:

- `[:spei :MXN]` → `{:settlement :same-day}`
- `[:credit-card :MXN]` → `{:settlement :d-plus-1}`
- `[:credit-card :USD]` → `{:settlement :d-plus-2}`
- `:default` → `{:settlement :unknown}`

```clojure
;; Solution:
(defmulti settle (juxt :method :currency))

(defmethod settle [:spei :MXN] [_] {:settlement :same-day})
(defmethod settle [:credit-card :MXN] [_] {:settlement :d-plus-1})
(defmethod settle [:credit-card :USD] [_] {:settlement :d-plus-2})
(defmethod settle :default [_] {:settlement :unknown})

(settle {:method :credit-card :currency :USD})
;; => {:settlement :d-plus-2}
```

> 🎓 **SOCRATIC:** *"What is `juxt`? Try `((juxt :a :b) {:a 1 :b 2})` in the REPL."*
> → It creates a function that applies multiple functions and returns a vector of results.
> Perfect for multi-criteria dispatch.

---

## Part 7 — Making It Real: Pedestal Web Server (15 min)

> **Goal:** Show this isn't academic — a web endpoint calling the multimethod is clean and extensible.

### Add dependencies

Update `deps.edn` (then restart your REPL):

```clojure
{:deps {org.clojure/clojure           {:mvn/version "1.12.0"}
        io.pedestal/pedestal.service   {:mvn/version "0.6.3"}
        io.pedestal/pedestal.route     {:mvn/version "0.6.3"}
        io.pedestal/pedestal.jetty     {:mvn/version "0.6.3"}
        clj-http/clj-http             {:mvn/version "3.12.3"}}

 :aliases
 {:nrepl
  {:extra-deps {nrepl/nrepl {:mvn/version "1.3.0"}}
   :main-opts  ["-m" "nrepl.cmdline"
                "--port" "7888"
                "--bind" "127.0.0.1"]}}}
```

### Server code

Paste into the REPL:

```clojure
(require '[clojure.edn :as edn])
(require '[clojure.string :as str])
(require '[io.pedestal.http :as http])
(require '[io.pedestal.http.route :as route])

;; --- Helpers ---

(defn edn-response
  ([data] (edn-response 200 data))
  ([status data]
   {:status  status
    :headers {"Content-Type" "application/edn; charset=utf-8"}
    :body    (pr-str data)}))

(defn parse-double-safe [s]
  (when (some? s)
    (try (Double/parseDouble (str s)) (catch Exception _ nil))))

(defn payment-from-query [query]
  (let [{:strs [method amount holder card-number cvv
                spei-clabe oxxo-reference]} query]
    (cond-> {:method (some-> method keyword)
             :amount (or (parse-double-safe amount) 0.0)}
      holder         (assoc :holder holder)
      card-number    (assoc :card-number card-number)
      cvv            (assoc :cvv cvv)
      spei-clabe     (assoc :spei-clabe spei-clabe)
      oxxo-reference (assoc :oxxo-reference oxxo-reference))))

(defn read-edn-body [request]
  (let [body (slurp (get request :body ""))]
    (when-not (str/blank? body)
      (edn/read-string {:readers {}} body))))

;; --- Handlers ---

(defn health-handler [_]
  (edn-response {:ok true :service :payments-mm-mx}))

(defn quote-handler [{:keys [query-params]}]
  (let [payment (payment-from-query query-params)]
    (edn-response {:payment  payment
                   :dispatch [(:method payment) (payment-tier payment)]})))

(defn pay-handler [request]
  (try
    (let [payment (or (read-edn-body request)
                      (payment-from-query (:query-params request)))
          result  (process2 payment)]
      (edn-response {:payment  payment
                     :dispatch [(:method payment) (payment-tier payment)]
                     :result   result}))
    (catch clojure.lang.ExceptionInfo ex
      (edn-response 400 {:error (.getMessage ex) :data (ex-data ex)}))
    (catch Exception ex
      (edn-response 500 {:error "Unexpected server error" :message (.getMessage ex)}))))

;; --- Routes & Server ---

(def routes
  (route/expand-routes
    #{["/health" :get  health-handler :route-name :health]
      ["/quote"  :get  quote-handler  :route-name :quote]
      ["/pay"    :post pay-handler    :route-name :pay]}))

(def service
  {::http/type   :jetty
   ::http/port   8890
   ::http/host   "127.0.0.1"
   ::http/routes routes
   ::http/join?  false})

(defonce server* (atom nil))

(defn start! []
  (when-not @server*
    (reset! server* (http/start (http/create-server service))))
  :started)

(defn stop! []
  (when @server*
    (http/stop @server*)
    (reset! server* nil))
  :stopped)
```

```clojure
(start!)
```

> 🎓 **SOCRATIC:** *"Look at `pay-handler`. How much does it know about credit cards vs SPEI vs OXXO?"*
> → Nothing. It just calls `process2`. The handler is generic. All the business knowledge lives in the multimethods.
>
> *"What would this handler look like in the `cond` version?"*
> → It would either contain the cond itself, or call a function full of conds. Either way: coupled.

### Test from the browser

Open these URLs:

- `http://127.0.0.1:8890/health`
- `http://127.0.0.1:8890/quote?method=spei&amount=250&spei-clabe=032180000118359719`
- `http://127.0.0.1:8890/quote?method=credit-card&amount=700`

### Test from the REPL (HTTP client)

```clojure
(require '[clj-http.client :as client])

(def base-url "http://127.0.0.1:8890")

(defn http-get [path]
  (-> (client/get (str base-url path) {:as :text})
      :body
      edn/read-string))

(defn http-post-edn [path data]
  (-> (client/post (str base-url path)
                   {:body    (pr-str data)
                    :headers {"Content-Type" "application/edn"}
                    :as      :text})
      :body
      edn/read-string))

;; Health check
(http-get "/health")

;; Quote a SPEI payment
(http-get "/quote?method=spei&amount=250000&spei-clabe=032180000118359719")

;; Process via POST with EDN body
(http-post-edn "/pay"
  {:method     :credit-card
   :amount     700.0
   :holder     "María García"
   :card-number "4111111111111111"
   :cvv        "123"})

(http-post-edn "/pay"
  {:method         :oxxo
   :amount         999.90
   :oxxo-reference "1234567890123456"})
```

### Stop the server

```clojure
(stop!)
```

---

## Wrap-up & Key Takeaways (5 min)

1. **`defmulti` + `defmethod`** = open polymorphism. Add behavior by adding code, not editing it.
2. **Dispatch functions** can return anything: a keyword, a vector, a computed value. You control the routing logic.
3. **REPL-driven development** means you're always one evaluation away from testing your idea.
4. **The handler doesn't need to know** about payment types. Separation of concerns via dispatch, not inheritance.

> 🎓 **Final question for the class:** *"We used multimethods today. Clojure has another polymorphism mechanism called **protocols**. When would you use one vs the other?"*
> → Tease for the next class. Protocols dispatch on the type of the first argument only (like interfaces). Multimethods dispatch on anything. Protocols are faster but less flexible.

---

## Appendix — Extra Non-Trivial Examples (Backup)

> Use these if you finish early or students want more challenges.

### A) Dispatch on provider routing (country + method + risk score)

```clojure
(defn risk-level [{:keys [risk-score]}]
  (cond
    (nil? risk-score)    :unknown
    (>= risk-score 80)   :high
    (>= risk-score 50)   :medium
    :else                :low))

(defmulti route-provider
  (fn [{:keys [method country] :as p}]
    [method country (risk-level p)]))

(defmethod route-provider [:credit-card :MX :low]  [_] :acquirer-mx-prosa)
(defmethod route-provider [:credit-card :MX :high] [_] :acquirer-mx-eglobal)
(defmethod route-provider [:credit-card :US :low]  [_] :acquirer-us-stripe)
(defmethod route-provider [:spei :MX :low]         [_] :spei-banxico)
(defmethod route-provider :default                 [_] :fallback-provider)

(route-provider {:method :credit-card :country :MX :risk-score 90})
;; => :acquirer-mx-eglobal
```

> 🎓 **SOCRATIC:** *"We're dispatching on a 3-element vector now. Is there a limit?"*
> → No. But readability is. If your dispatch value is too complex, consider splitting into multiple multimethods.

### B) Hierarchies with `derive` — method families

```clojure
;; "credit-card" and "debit-card" are both "card" methods
;; "spei" and "oxxo" are both "local-mx" methods
(def payment-hierarchy
  (-> (make-hierarchy)
      (derive :credit-card :card)
      (derive :debit-card  :card)
      (derive :spei        :local-mx)
      (derive :oxxo        :local-mx)))

(defmulti fee
  (fn [p] (:method p))
  :hierarchy #'payment-hierarchy)

(defmethod fee :card     [{:keys [amount]}] (* amount 0.029))  ;; 2.9% for cards
(defmethod fee :local-mx [{:keys [amount]}] (* amount 0.01))   ;; 1% for local methods
(defmethod fee :default  [_] 0)

(fee {:method :credit-card :amount 1000})  ;; => 29.0
(fee {:method :debit-card  :amount 1000})  ;; => 29.0  (inherits :card)
(fee {:method :spei        :amount 1000})  ;; => 10.0  (inherits :local-mx)
(fee {:method :oxxo        :amount 1000})  ;; => 10.0
```

> 🎓 **SOCRATIC:** *"This looks like inheritance. How is it different from Java's class hierarchy?"*
> → It's decoupled from the data. The hierarchy is a separate, explicit structure. The data (maps) don't "know" about the hierarchy. You can have multiple hierarchies for different purposes.

### C) Return errors as data (no exceptions)

```clojure
(defmulti validate* :method)

(defmethod validate* :spei [p]
  (if (valid-spei? p)
    {:ok? true}
    {:ok? false :error :invalid-spei
     :details (select-keys p [:spei-clabe :amount])}))

(defmethod validate* :oxxo [p]
  (if (valid-oxxo? p)
    {:ok? true}
    {:ok? false :error :invalid-oxxo
     :details (select-keys p [:oxxo-reference :amount])}))

(defmethod validate* :default [p]
  {:ok? false :error :unknown-method
   :details (select-keys p [:method])})

(validate* {:method :spei :spei-clabe "short" :amount 10})
;; => {:ok? false, :error :invalid-spei, :details {:spei-clabe "short", :amount 10}}
```

> 🎓 **SOCRATIC:** *"This version returns a map instead of throwing. Which approach is better?"*
> → Neither universally. Exceptions abort the flow (good for "stop everything"). Data return lets the caller decide what to do (good for batch processing, partial results, user-facing errors). In functional programming, data is often preferred.

### D) Dispatch with `juxt` for settlement rules

```clojure
(defmulti settle (juxt :method :currency))

(defmethod settle [:spei :MXN]        [_] {:settlement :same-day})
(defmethod settle [:credit-card :MXN] [_] {:settlement :d-plus-1})
(defmethod settle [:credit-card :USD] [_] {:settlement :d-plus-2})
(defmethod settle [:oxxo :MXN]        [_] {:settlement :d-plus-3})
(defmethod settle :default            [_] {:settlement :unknown})

(settle {:method :oxxo :currency :MXN})
;; => {:settlement :d-plus-3}
```

### E) Live extensibility demo

> **Scenario for the class:** *"A partner company sends you a JAR with this code.
> You don't edit their JAR. You don't edit your processing code. You just load their namespace
> and suddenly your system supports their payment method."*

```clojure
;; Imagine this came from an external library:
(defmethod validate! :mercado-pago [{:keys [mp-token amount] :as payment}]
  (when-not (and (string? mp-token) (pos? amount))
    (throw (ex-info "Invalid MercadoPago payment" {:payment payment})))
  :ok)

(defmethod process2 [:mercado-pago :standard] [payment]
  (validate! payment)
  {:status :approved :flow :wallet :provider :mercado-pago-api})

;; Now it just works:
(process2 {:method :mercado-pago :mp-token "MP-abc123" :amount 50.0})
;; => {:status :approved, :flow :wallet, :provider :mercado-pago-api}
```

> 🎓 **SOCRATIC:** *"Could you do this in Java without the original authors providing an interface or abstract class?"*
> → Not easily. Multimethods are open by design — anyone can extend them, from anywhere.

---

## Teacher Reference: Brazil ↔ Mexico Mapping

| Brazil | Mexico | Shape of integration |
|--------|--------|---------------------|
| PIX | SPEI | Bank transfer rails, near-real-time confirmation |
| Boleto bancário | OXXO Pay / cash reference | Async: user gets reference, pays elsewhere, confirmation is pending |
| CPF (11 digits) | CURP (18 chars) or RFC (13 chars) | National ID for tax/validation |
| CLABE doesn't exist | CLABE (18-digit bank account) | Standardized interbank account number |

**Key phrase if you slip:** *"When I say PIX, think SPEI. When I say boleto, think OXXO reference."*
