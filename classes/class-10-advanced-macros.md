# Clojure Advanced Topics — Class 10 (2h)

## Advanced Macros & Performance — All Tools United

> **Prerequisite:** Class 3 — Macros intro. Classes 4–8 — STM, Agents, Transducers, core.async.
> **Project:** Teams should have subjects 1–9 underway. Today is the last teaching content.

---

## Agenda

By the end of this class, students will be able to:

- Debug macros with `macroexpand-1` and prevent variable capture with auto-gensym
- Know when a macro is justified over a higher-order function
- Identify and eliminate reflection with type hints
- See how ALL subjects (macros, protocols, multimethods, STM, agents, transducers, channels, java interop) compose in a single system
- **Use 3 complete domain cases as inspiration for their own final project**

## Timeline

```
 0:00 ┬─ Part 1 — Macros Essentials ··················· 25 min
      │   macroexpand-1, hygiene (#, gensym, ~'),
      │   macro vs HOF decision, core.async proof
      │
 0:25 ┬─ Part 2 — Performance Essentials ··············· 15 min
      │   Reflection & type hints
      │   Lazy vs transducer (benchmark)
      │
 0:40 ┬─── 5 min break ───
      │
 0:45 ┬─ Part 3 — Case Studies: All Tools, One Domain ··
      │
 0:45 ├─ Case 1 — Haunted Hotel & Spa ··················· 35 min
      │   Luxury hotel for supernatural guests
      │   All 10 subjects + HTTP API with curl tests
      │
 1:20 ├─ Case 2 — Wizard Potion Factory ················ 30 min
      │   Magical potion brewing facility
      │   All 10 subjects + HTTP API with curl tests
      │
 1:55 ┬─ Wrap-up ······································  5 min
 2:00 ┴─ End
```

---

# PART 1 — MACROS ESSENTIALS (25 min)

---

### The three things you must know

**1) Debug with `macroexpand-1`** — always your first step:

```clojure
(defmacro with-audit [action & body]
  `(let [result# (do ~@body)]
     (println "AUDIT:" ~action "=>" result#)
     result#))

(macroexpand-1 '(with-audit "authorize" (do-thing)))
;; => (clojure.core/let [result__auto__ (do (do-thing))]
;;      (clojure.core/println "AUDIT:" "authorize" "=>" result__auto__)
;;      result__auto__)
```

**2) Prevent variable capture** — use `#` for internal vars, `~'symbol` for deliberate bindings:

```clojure
;; BROKEN — `amount` in macro shadows user's `amount`:
(defmacro with-check-BAD [payment & body]
  `(let [amount (:amount ~payment)]   ;; ← BUG: bare `amount`
     (when (pos? amount) ~@body)))

;; The bug in action:
(let [amount 999]
  (with-check-BAD {:amount 50}
    (println "User's amount:" amount)))
;; Expected: "User's amount: 999"
;; Actual:   "User's amount: 50"   ← CAPTURED! macro's `amount` shadowed the user's

;; FIXED — auto-gensym:
(defmacro with-check [payment & body]
  `(let [amount# (:amount ~payment)]  ;; ← SAFE: unique name
     (when (pos? amount#) ~@body)))

;; Now the user's variable survives:
(let [amount 999]
  (with-check {:amount 50}
    (println "User's amount:" amount)))
;; => "User's amount: 999"          ← correct! macro uses amount__auto__ internally

;; DELIBERATE capture — when user SHOULD see the binding:
(defmacro with-order [order-expr & body]
  `(let [~'order ~order-expr]          ;; ← user can reference `order`
     ~@body))

;; The user is MEANT to use `order` — it's part of the macro's contract:
(with-order {:id 42 :item "Coffin" :qty 1}
  (println "Order #" (:id order) "for" (:qty order) (:item order)))
;; => "Order # 42 for 1 Coffin"
```

**3) Macro vs function — the decision:**

```
Write a FUNCTION unless you need to:
  ✓ Control evaluation       (body shouldn't run eagerly)
  ✓ Introduce bindings       (create variables for the caller)
  ✓ Inject compile-time info (type hints, `<!` inside go blocks)
```

The canonical proof — `<!` must be inside a `go` block at compile time, so only a macro can wrap it:

```clojure
(require '[clojure.core.async :as async :refer [go <! >! chan timeout alts!]])

(defmacro go-with-timeout [timeout-ms & body]
  `(go
     (let [result-ch# (go ~@body)
           timeout-ch# (timeout ~timeout-ms)
           [val# port#] (alts! [result-ch# timeout-ch#])]
       (if (= port# timeout-ch#) :timeout val#))))
```

A function literally cannot do this — `<!` inside a `fn` boundary breaks `go`'s code walker.

---

# PART 2 — PERFORMANCE ESSENTIALS (15 min)

---

### Reflection: the silent 100x penalty

```clojure
(set! *warn-on-reflection* true)

(defn slow-upper [s]         (.toUpperCase s))         ;; REFLECTION WARNING
(defn fast-upper [^String s] (.toUpperCase s))         ;; no warning

;; Benchmark (1M calls):
(time (dotimes [_ 1000000] (slow-upper "hello")))      ;; ~1900 ms
(time (dotimes [_ 1000000] (fast-upper "hello")))      ;; ~20 ms  (96x faster!)
```

A macro can generate type-hinted code — because hints must exist at compile time:

```clojure
(defmacro defbean-getter [fn-name class-sym method-sym]
  `(defn ~fn-name [~(with-meta 'obj {:tag class-sym})]
     (~method-sym ~'obj)))

(defbean-getter get-amount java.math.BigDecimal .doubleValue)
(get-amount (BigDecimal. "199.99"))  ;; => 199.99, zero reflection
```

### Transducers vs lazy: the benchmark

```clojure
;; Lazy: 4 intermediate sequences
(->> data (filter p1?) (filter p2?) (map f1) (map f2) (into []))

;; Transducer: ZERO intermediate collections, single pass
(into [] (comp (filter p1?) (filter p2?) (map f1) (map f2)) data)
```

On 500k records: lazy ~220ms, transducers ~38ms. **6x faster.**

Transducers also work on channels — attach them at channel creation:

```clojure
(chan 1024 (comp (filter p1?) (map f1)))   ;; filtered + transformed on read
```

---

# PART 3 — CASE STUDIES: ALL TOOLS, ONE DOMAIN

---

> **Format:** Teacher walks through each case live in the REPL. Students follow along, ask questions, take notes for their own project. Each case shows how ALL subjects fit naturally into one funny domain.

---

## Case 1 — Haunted Hotel & Spa

*A luxury hotel for supernatural guests. Vampires, werewolves, and ghosts each need different room setups. Room assignments must atomically reserve the room AND deduct amenities. Housekeeping is notified through channels. Every booking is audited by an agent. A macro generates room type definitions. An HTTP API wraps the whole thing.*

### Architecture map

```
Subject              Component                     Why this tool
───────────────────  ────────────────────────────  ──────────────────────────────────
Multimethods         prepare-room                  Each guest species needs different
                     (dispatch on :species)         setup — open for new species

Protocols            Bookable (for CryptSuite,      Common interface: rate, describe,
                     MoonlitRoom, PhantomSuite)     validate — fast type dispatch

Macros               defroom                        Generates record + Bookable impl
                                                    + amenity list from a spec

Refs / STM           amenity-stock (4 refs:         Booking must atomically reserve
                     coffins, moonstone-lamps,       ALL amenities or fail entirely
                     ectoplasm, garlic-free-mints)

Agents               booking-log agent              Front desk shouldn't wait for the
                                                    audit log to be written

Transducers          nightly-report pipeline        Filter tonight's bookings →
                     (comp filter map)              calculate revenue → format report

core.async basic     housekeeping-ch                Front desk puts booking on channel,
                     (producer/consumer)            housekeeping reads and prepares

core.async adv       pub/sub on event-bus           Booking events fan out to:
                     (pub by :event-type)           housekeeping, billing, concierge

Java Interop         com.sun.net.httpserver +       Built-in JDK HTTP server for the API,
                     java.time for timestamps       java.time for receipt formatting
```

### The code

```clojure
(require '[clojure.core.async :as async
           :refer [go go-loop <! >! >!! <!! chan pub sub timeout close!]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])
(import '[java.time LocalDateTime]
        '[java.time.format DateTimeFormatter]
        '[com.sun.net.httpserver HttpServer HttpHandler]
        '[java.net InetSocketAddress]
        '[java.io InputStreamReader BufferedReader])

;; ── PROTOCOLS ──────────────────────────────────────────────────────
(defprotocol Bookable
  (rate [this])
  (describe [this])
  (validate [this]))

;; ── MACRO: defroom ─────────────────────────────────────────────────
;; WHY a macro: introduces a defrecord + extend-type at compile time,
;; a function can't generate new types.

(defmacro defroom
  "Generates a room type with Bookable protocol implementation.
   (defroom CryptSuite [bed-type lighting extras] 250.0)"
  [name fields base-rate]
  `(do
     (defrecord ~name ~fields)
     (extend-type ~name
       Bookable
       (rate [this#] (+ ~base-rate (* 30.0 (count (:extras this#)))))
       (describe [this#]
         (str ~(str name) ": " (:bed-type this#) " with " (:lighting this#)
              (when (seq (:extras this#))
                (str " + " (clojure.string/join ", " (:extras this#))))))
       (validate [this#]
         (and (some? (:bed-type this#))
              (some? (:lighting this#)))))))

(defroom CryptSuite  [bed-type lighting extras] 250.0)
(defroom MoonlitRoom [bed-type lighting extras] 180.0)
(defroom PhantomSuite [bed-type lighting extras] 320.0)

;; ── MULTIMETHODS: species-specific room preparation ───────────────
(defmulti prepare-room :species)

(defmethod prepare-room :vampire [booking]
  (println "  Blackout curtains installed! Extra coffin padding!")
  (assoc (:room booking) :extras
         (conj (or (:extras (:room booking)) []) "blackout-curtains")))

(defmethod prepare-room :werewolf [booking]
  (println "  Reinforced walls — full moon is tomorrow!")
  (assoc (:room booking) :extras
         (conj (or (:extras (:room booking)) []) "reinforced-walls")))

(defmethod prepare-room :ghost [booking]
  (println "  Walls made semi-permeable. No mirrors.")
  (:room booking))

(defmethod prepare-room :default [booking]
  (println "  Unknown species — generic supernatural setup!")
  (:room booking))

;; ── STM: amenity stock ──────────────────────────────────────────
(def coffins            (ref 50))
(def moonstone-lamps    (ref {:dim 40 :bright 30 :strobe 10}))
(def ectoplasm-reserves (ref 200))
(def garlic-free-mints  (ref 500))

(defn consume-amenities! [bed-type lighting-type]
  (dosync
    (when (< @coffins 1)
      (throw (ex-info "Out of coffins!" {})))
    (when (< (get @moonstone-lamps lighting-type 0) 1)
      (throw (ex-info (str "Out of " lighting-type " moonstone lamps!") {})))
    (alter coffins dec)
    (alter moonstone-lamps update lighting-type dec)
    (alter ectoplasm-reserves - 5)
    (alter garlic-free-mints - 2)
    :ok))

;; ── AGENTS: booking audit log ───────────────────────────────────
(def booking-log (agent []))

(defn log-booking! [booking result]
  (send booking-log conj
        {:booking (dissoc booking :room)  ;; don't log the full record
         :result  result
         :at (.format (LocalDateTime/now)
                      (DateTimeFormatter/ofPattern "HH:mm:ss"))}))

;; ── CHANNELS: pub/sub ───────────────────────────────────────────
(def event-bus (chan 16))
(def event-pub (pub event-bus :event-type))

(def housekeeping-sub (chan 16))
(sub event-pub :new-booking housekeeping-sub)

(def billing-sub (chan 16))
(sub event-pub :new-booking billing-sub)

;; Housekeeping go-loop
(go-loop []
  (when-let [evt (<! housekeeping-sub)]
    (let [booking (:payload evt)]
      (println "\nHOUSEKEEPING booking #" (:id booking) "for" (:species booking))
      (try
        (consume-amenities! (:bed-type (:room booking))
                            (:lighting (:room booking)))
        (let [prepared (prepare-room booking)]
          (log-booking! booking {:status :prepared :room-desc (describe prepared)
                                 :rate (rate prepared)})
          (println "  Ready!" (describe prepared) "| $" (rate prepared) "/night"))
        (catch Exception e
          (log-booking! booking {:status :failed :reason (.getMessage e)})
          (println "  FAILED:" (.getMessage e)))))
    (recur)))

;; Billing go-loop
(go-loop []
  (when-let [evt (<! billing-sub)]
    (let [booking (:payload evt)]
      (println "BILLING: $" (rate (:room booking))
               "at" (.format (LocalDateTime/now)
                             (DateTimeFormatter/ofPattern "HH:mm:ss"))))
    (recur)))

;; ── TRANSDUCERS: nightly report ─────────────────────────────────
(defn nightly-report []
  (let [entries @booking-log
        xf (comp
             (filter #(= :prepared (get-in % [:result :status])))
             (map (fn [e] {:id   (get-in e [:booking :id])
                           :room (get-in e [:result :room-desc])
                           :rate (get-in e [:result :rate])})))]
    {:bookings (into [] xf entries)
     :revenue  (transduce
                 (comp (filter #(= :prepared (get-in % [:result :status])))
                       (map #(get-in % [:result :rate])))
                 + 0.0 entries)
     :stock {:coffins @coffins
             :lamps @moonstone-lamps
             :ectoplasm @ectoplasm-reserves}}))

;; ── JAVA INTEROP: HTTP API ──────────────────────────────────────
;; WHY Java interop: com.sun.net.httpserver is built into the JDK —
;; zero dependencies for a fully functional HTTP server.

(def booking-counter (atom 0))

(defn read-body [exchange]
  (let [reader (BufferedReader. (InputStreamReader. (.getRequestBody exchange)))]
    (edn/read-string (slurp reader))))

(defn send-response [exchange status body]
  (let [response (pr-str body)
        bytes    (.getBytes ^String response "UTF-8")]
    (.sendResponseHeaders exchange status (count bytes))
    (doto (.getResponseBody exchange)
      (.write bytes)
      (.close))))

(defn make-room [{:keys [type bed-type lighting extras]}]
  (case type
    :crypt   (->CryptSuite   (or bed-type :coffin) (or lighting :dim) (or extras []))
    :moonlit (->MoonlitRoom  (or bed-type :king)   (or lighting :bright) (or extras []))
    :phantom (->PhantomSuite (or bed-type :none)   (or lighting :strobe) (or extras []))
    (->CryptSuite :coffin :dim [])))

(defn start-hotel-api! [port]
  (let [server (HttpServer/create (InetSocketAddress. ^int port) 0)]

    ;; POST /book — book a room
    (.createContext server "/book"
      (reify HttpHandler
        (handle [_ exchange]
          (let [body    (read-body exchange)
                id      (swap! booking-counter inc)
                room    (make-room (:room body))
                booking {:id id :species (:species body) :room room}]
            (>!! event-bus {:event-type :new-booking :payload booking})
            (send-response exchange 200
              {:status :queued :booking-id id :species (:species body)})))))

    ;; GET /report — nightly report
    (.createContext server "/report"
      (reify HttpHandler
        (handle [_ exchange]
          (await booking-log)
          (send-response exchange 200 (nightly-report)))))

    ;; GET /stock — amenity stock
    (.createContext server "/stock"
      (reify HttpHandler
        (handle [_ exchange]
          (send-response exchange 200
            {:coffins @coffins
             :moonstone-lamps @moonstone-lamps
             :ectoplasm @ectoplasm-reserves
             :garlic-free-mints @garlic-free-mints}))))

    (.setExecutor server nil)
    (.start server)
    (println "Haunted Hotel API running on port" port)
    server))

;; Start the server
(def hotel-server (start-hotel-api! 8081))
```

### Testing with curl

```bash
# Book a vampire into a crypt suite
curl -X POST http://localhost:8081/book \
  -d '{:species :vampire :room {:type :crypt :lighting :dim}}'

# Book a werewolf into a moonlit room with soundproofing
curl -X POST http://localhost:8081/book \
  -d '{:species :werewolf :room {:type :moonlit :extras ["soundproofing"]}}'

# Book a ghost into a phantom suite
curl -X POST http://localhost:8081/book \
  -d '{:species :ghost :room {:type :phantom}}'

# Book a mummy (unknown species — hits :default multimethod)
curl -X POST http://localhost:8081/book \
  -d '{:species :mummy :room {:type :crypt :extras ["humidifier" "bandage-warmer"]}}'

# Wait a moment for async processing, then check stock
curl http://localhost:8081/stock

# Nightly revenue report (transducers process the log)
curl http://localhost:8081/report
```

### Expected output (server console)

```
Haunted Hotel API running on port 8081

HOUSEKEEPING booking # 1 for :vampire
  Blackout curtains installed! Extra coffin padding!
  Ready! CryptSuite: :coffin with :dim + blackout-curtains | $ 280.0 /night
BILLING: $ 250.0 at 21:00:01

HOUSEKEEPING booking # 2 for :werewolf
  Reinforced walls — full moon is tomorrow!
  Ready! MoonlitRoom: :king with :bright + soundproofing, reinforced-walls | $ 240.0 /night
BILLING: $ 210.0 at 21:00:02

HOUSEKEEPING booking # 3 for :ghost
  Walls made semi-permeable. No mirrors.
  Ready! PhantomSuite: :none with :strobe | $ 320.0 /night
BILLING: $ 320.0 at 21:00:03

HOUSEKEEPING booking # 4 for :mummy
  Unknown species — generic supernatural setup!
  Ready! CryptSuite: :coffin with :dim + humidifier, bandage-warmer | $ 310.0 /night
BILLING: $ 310.0 at 21:00:04
```

### Expected curl responses

```clojure
;; POST /book → each returns:
{:status :queued, :booking-id 1, :species :vampire}

;; GET /stock → amenities atomically decremented by STM:
{:coffins 46, :moonstone-lamps {:dim 38, :bright 29, :strobe 9},
 :ectoplasm 180, :garlic-free-mints 492}

;; GET /report → transducer-built report:
{:bookings [{:id 1, :room "CryptSuite: :coffin with :dim + blackout-curtains", :rate 280.0}
            {:id 2, :room "MoonlitRoom: :king with :bright + ...", :rate 240.0}
            {:id 3, :room "PhantomSuite: :none with :strobe", :rate 320.0}
            {:id 4, :room "CryptSuite: :coffin with :dim + ...", :rate 310.0}],
 :revenue 1150.0,
 :stock {:coffins 46, :lamps {:dim 38, :bright 29, :strobe 9}, :ectoplasm 180}}
```

> **SOCRATIC:** *"Count the subjects in one curl request: the POST hits the HTTP server (Java interop), puts a message on a channel (core.async), which fans out via pub/sub (core.async adv), triggers a multimethod (prepare-room), consumes amenities atomically (STM), logs via agent, and later the GET /report uses transducers to build the response. That's 8 subjects in 2 HTTP calls."*

```clojure
;; Shutdown when done:
(.stop hotel-server 0)
```

---

## Case 2 — Wizard Potion Factory

*A factory that brews magical potions. Different potion types need different brewing processes. Ingredient supply is tracked atomically. Quality alerts go through pub/sub. Brew timing uses java.time. A macro generates potion recipes. An HTTP API lets the guild master control the factory remotely.*

### Architecture map

```
Subject              Component                     Why this tool
───────────────────  ────────────────────────────  ──────────────────────────────────
Multimethods         brew-potion                   Each category brews differently —
                     (dispatch on :category)        :healing, :combat, :utility

Protocols            Brewable (for HealthPotion,     Common interface: cost, describe,
                     FireElixir, InvisibilityDraft)  quality-check — per type

Macros               defpotion                      Generates record + Brewable impl
                                                    + ingredient reqs from a spec

Refs / STM           ingredient-vault (4 refs)      Brewing must decrement ALL
                                                    ingredients atomically

Agents               quality-log                    Brewmasters shouldn't stop stirring
                                                    to write paperwork

Transducers          production-report pipeline     Filter successful brews → format
                     (comp filter map)              for guild report

core.async basic     brew-queue-ch                  Apprentices grab next recipe from
                     (producer/consumer)            queue — prevents double-brewing

core.async adv       pub/sub on factory-bus         CONTAMINATION events → safety,
                     (pub by :alert-type)           guildmaster, warehouse

Java Interop         com.sun.net.httpserver +       Built-in JDK HTTP server for API,
                     java.time for brew timing      Duration for steeping time
```

### The code

```clojure
(require '[clojure.core.async :as async
           :refer [go go-loop <! >! >!! <!! chan pub sub timeout close!]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])
(import '[java.time LocalTime Duration]
        '[java.time.format DateTimeFormatter]
        '[com.sun.net.httpserver HttpServer HttpHandler]
        '[java.net InetSocketAddress]
        '[java.io InputStreamReader BufferedReader])

;; ── PROTOCOLS ──────────────────────────────────────────────────────
(defprotocol Brewable
  (cost [this])
  (describe-potion [this])
  (quality-check [this]))

;; ── MACRO: defpotion ──────────────────────────────────────────────
(defmacro defpotion
  "Generates a potion type with Brewable impl and brew metadata.
   (defpotion HealthPotion :healing 45.0 :standard)"
  [name category base-cost potency]
  `(do
     (defrecord ~name [~'id ~'recipe-name ~'cauldron-id ~'brewed-at ~'status])
     (extend-type ~name
       Brewable
       (cost [this#] ~base-cost)
       (describe-potion [this#]
         {:name (:recipe-name this#)
          :category ~category
          :potency ~potency
          :hours-since-brewed
            (if (:brewed-at this#)
              (.toHours (Duration/between ^LocalTime (:brewed-at this#) (LocalTime/now)))
              0)})
       (quality-check [this#]
         {:potency ~potency
          :brewed? (some? (:brewed-at this#))
          :category ~category}))))

(defpotion HealthPotion      :healing  45.0  :standard)
(defpotion FireElixir        :combat   120.0 :volatile)
(defpotion InvisibilityDraft :utility  200.0 :delicate)

;; ── MULTIMETHODS: brewing by category ────────────────────────────
(defmulti brew-potion (fn [potion _vault] (:category (quality-check potion))))

(defmethod brew-potion :healing [potion vault]
  (dosync
    (let [{:keys [moonwater phoenix-feathers]} vault]
      (when (< @moonwater 5)
        (throw (ex-info (str (:recipe-name potion) " — out of moonwater!") {})))
      (alter moonwater - 5)
      (alter phoenix-feathers - 1)
      (println "  Brewed" (:recipe-name potion) "— gentle simmer, golden glow."))))

(defmethod brew-potion :combat [potion vault]
  (dosync
    (let [{:keys [dragon-scales stardust]} vault]
      (when (< @dragon-scales 3)
        (throw (ex-info (str (:recipe-name potion) " — out of dragon scales!") {})))
      (alter dragon-scales - 3)
      (alter stardust - 10)
      (println "  Brewed" (:recipe-name potion) "— STAND BACK! Violent bubbling!"))))

(defmethod brew-potion :utility [potion vault]
  (dosync
    (let [{:keys [moonwater stardust phoenix-feathers]} vault]
      (alter moonwater - 8)
      (alter stardust - 15)
      (alter phoenix-feathers - 2)
      (println "  Brewed" (:recipe-name potion) "— shimmering, nearly invisible already."))))

;; ── STM: ingredient vault ───────────────────────────────────────
(def ingredient-vault
  {:dragon-scales    (ref 100)
   :moonwater        (ref 200)
   :phoenix-feathers (ref 80)
   :stardust         (ref 300)})

;; ── AGENTS: quality log ─────────────────────────────────────────
(def quality-log (agent []))

(defn log-brew! [type recipe-name details]
  (send quality-log conj
        {:type type :recipe recipe-name :details details
         :at (.format (LocalTime/now) (DateTimeFormatter/ofPattern "HH:mm:ss"))}))

;; ── CHANNELS: brew queue + pub/sub ──────────────────────────────
(def brew-queue-ch (chan 32))
(def factory-bus   (chan 32))
(def factory-pub   (pub factory-bus :alert-type))

(def safety-alerts (chan 16))
(sub factory-pub :contamination safety-alerts)

(def guild-alerts (chan 16))
(sub factory-pub :contamination guild-alerts)
(sub factory-pub :quality guild-alerts)

;; Safety team
(go-loop []
  (when-let [alert (<! safety-alerts)]
    (println "SAFETY TEAM:" (:recipe (:payload alert))
             "contaminated in" (:location (:payload alert)) "!!!")
    (log-brew! :contamination (:recipe (:payload alert)) "Safety dispatched")
    (recur)))

;; Guildmaster dashboard
(go-loop []
  (when-let [alert (<! guild-alerts)]
    (println "GUILDMASTER:" (:alert-type alert) "-" (:recipe (:payload alert)))
    (recur)))

;; Apprentice worker
(go-loop []
  (when-let [potion (<! brew-queue-ch)]
    (println "\nAPPRENTICE picked up:" (:recipe-name potion))
    (try
      (brew-potion potion ingredient-vault)
      (log-brew! :brewed (:recipe-name potion) "Brewed successfully")
      (catch Exception e
        (println "  BREWING FAILED:" (.getMessage e))
        (log-brew! :brew-failed (:recipe-name potion) (.getMessage e))))
    (recur)))

;; ── TRANSDUCERS: production report ──────────────────────────────
(defn production-report []
  (let [entries @quality-log
        xf (comp
             (filter #(= :brewed (:type %)))
             (map #(select-keys % [:recipe :at])))]
    {:brewed       (into [] xf entries)
     :total-brewed (transduce (filter #(= :brewed (:type %))) (completing (fn [c _] (inc c))) 0 entries)
     :failures     (transduce (filter #(= :brew-failed (:type %))) (completing (fn [c _] (inc c))) 0 entries)
     :vault (into {} (map (fn [[k v]] [k @v])) ingredient-vault)}))

;; ── JAVA INTEROP: HTTP API ──────────────────────────────────────
(def brew-counter (atom 0))

(defn read-body [exchange]
  (let [reader (BufferedReader. (InputStreamReader. (.getRequestBody exchange)))]
    (edn/read-string (slurp reader))))

(defn send-response [exchange status body]
  (let [response (pr-str body)
        bytes    (.getBytes ^String response "UTF-8")]
    (.sendResponseHeaders exchange status (count bytes))
    (doto (.getResponseBody exchange) (.write bytes) (.close))))

(defn make-potion [{:keys [type recipe-name]}]
  (let [id (swap! brew-counter inc)]
    (case type
      :health (->HealthPotion      id (or recipe-name "Health Potion") :cauldron-A nil :queued)
      :fire   (->FireElixir        id (or recipe-name "Fire Elixir")   :cauldron-B nil :queued)
      :invis  (->InvisibilityDraft id (or recipe-name "Invis Draft")   :cauldron-C nil :queued)
      (->HealthPotion id "Generic Potion" :cauldron-A nil :queued))))

(defn start-factory-api! [port]
  (let [server (HttpServer/create (InetSocketAddress. ^int port) 0)]

    ;; POST /brew — queue a potion
    (.createContext server "/brew"
      (reify HttpHandler
        (handle [_ exchange]
          (let [body   (read-body exchange)
                potion (make-potion body)]
            (>!! brew-queue-ch potion)
            (send-response exchange 200
              {:status :queued :brew-id (:id potion)
               :recipe (:recipe-name potion)})))))

    ;; POST /alert — contamination alert
    (.createContext server "/alert"
      (reify HttpHandler
        (handle [_ exchange]
          (let [body (read-body exchange)]
            (>!! factory-bus {:alert-type :contamination :payload body})
            (send-response exchange 200 {:status :alert-sent})))))

    ;; GET /report — production report
    (.createContext server "/report"
      (reify HttpHandler
        (handle [_ exchange]
          (await quality-log)
          (send-response exchange 200 (production-report)))))

    ;; GET /vault — ingredient stock
    (.createContext server "/vault"
      (reify HttpHandler
        (handle [_ exchange]
          (send-response exchange 200
            (into {} (map (fn [[k v]] [k @v])) ingredient-vault)))))

    (.setExecutor server nil)
    (.start server)
    (println "Wizard Potion Factory API running on port" port)
    server))

;; Start the server
(def factory-server (start-factory-api! 8082))
```

### Testing with curl

```bash
# Brew a healing potion
curl -X POST http://localhost:8082/brew \
  -d '{:type :health :recipe-name "Elixir of Mending"}'

# Brew a combat elixir
curl -X POST http://localhost:8082/brew \
  -d '{:type :fire :recipe-name "Dragon Breath"}'

# Brew an invisibility draft
curl -X POST http://localhost:8082/brew \
  -d '{:type :invis :recipe-name "Vanishing Vapor"}'

# Check the ingredient vault (STM-consistent)
curl http://localhost:8082/vault

# Contamination alert! (pub/sub → safety + guildmaster)
curl -X POST http://localhost:8082/alert \
  -d '{:recipe "Vanishing Vapor" :location "Cauldron C"}'

# Production report (transducer pipeline)
curl http://localhost:8082/report
```

### Expected output (server console)

```
Wizard Potion Factory API running on port 8082

APPRENTICE picked up: Elixir of Mending
  Brewed Elixir of Mending — gentle simmer, golden glow.

APPRENTICE picked up: Dragon Breath
  Brewed Dragon Breath — STAND BACK! Violent bubbling!

APPRENTICE picked up: Vanishing Vapor
  Brewed Vanishing Vapor — shimmering, nearly invisible already.

SAFETY TEAM: Vanishing Vapor contaminated in Cauldron C !!!
GUILDMASTER: :contamination - Vanishing Vapor
```

### Expected curl responses

```clojure
;; POST /brew →
{:status :queued, :brew-id 1, :recipe "Elixir of Mending"}

;; GET /vault → atomically consistent via STM:
{:dragon-scales 97, :moonwater 187, :phoenix-feathers 77, :stardust 275}

;; POST /alert →
{:status :alert-sent}

;; GET /report → transducer-built:
{:brewed [{:recipe "Elixir of Mending", :at "14:30:01"}
          {:recipe "Dragon Breath", :at "14:30:01"}
          {:recipe "Vanishing Vapor", :at "14:30:01"}],
 :total-brewed 3,
 :failures 0,
 :vault {:dragon-scales 97, :moonwater 187, :phoenix-feathers 77, :stardust 275}}
```

> **SOCRATIC:** *"Trace one curl request through the system: POST /brew hits the HttpServer (Java interop), creates a record (macro-generated), puts it on a channel (core.async), the apprentice go-loop consumes it, calls a multimethod (brew-potion), which uses dosync to atomically decrement refs (STM), then sends to an agent (quality-log). GET /report later uses transducers to build the response. That's ALL 10 subjects activated by 2 HTTP calls."*

```clojure
;; Shutdown when done:
(.stop factory-server 0)
```

---

## Wrap-up (5 min)

### The composition principle

Both cases prove the same thing: **every Clojure tool does ONE thing, and the art is composing them.**

```
Tool              Does ONE thing              Doesn't do
────────────────  ────────────────────────    ──────────────────────
Multimethods      Open dispatch               Fast type dispatch
Protocols         Fast type contracts         Open value dispatch
Macros            Compile-time codegen        Runtime flexibility
Refs / STM        Coordinated atomic state    Async side-effects
Agents            Async fire-and-forget       Coordinated state
Transducers       Efficient transforms        Communication
Channels          Decoupled communication     State management
Java Interop      JVM ecosystem access        Clojure idioms
```

### What you just saw

A single `curl` request flows through **8 subjects** before a response comes back. That's not forced — it's how these tools naturally compose in a real system.

### For your project

Pick a domain. Map every tool to a real problem. Add an HTTP API so you can demo it with curl. If the mapping feels forced, **change the domain, not the architecture.**

> *"Next class: each team presents their project. 10 minutes. Every member speaks. Demo it live with curl — show us the requests flowing through your system."*
