(ns advanced-cases
  (:require [clojure.core.async :as async :refer [go <! >! chan timeout alts!]]))


(declare advanced-cases)

(defmacro with-audit [action & body]
  `(let [result# (do ~@body)]
     (println "AUDIT:" ~action "=>" result#)
     result#))

(macroexpand-1 '(with-audit "authorize" (do-thing)))
(macroexpand-1 '(with-audit "authorize" (inc 0)))
;; => (clojure.core/let [result__auto__ (do (do-thing))]
;;      (clojure.core/println "AUDIT:" "authorize" "=>" result__auto__)
;;      result__auto__)


(with-audit "authorize" (inc 0))


;; BROKEN — `amount` in macro shadows user's `amount`:
(defmacro with-check-BAD [payment & body]
  `(let [amount (:amount ~payment)]   ;; ← BUG: bare `amount`
     (when (pos? amount) ~@body)))

;; The bug in action:
(let [amount 999]
  (with-check-BAD {:amount 50}
    (println "User's amount:" amount)))

(macroexpand-1 '(with-check-BAD {:amount 50}
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

(macroexpand-1 '(with-check {:amount 50}
    (println "User's amount:" amount)))


;; DELIBERATE capture — when user SHOULD see the binding:
(defmacro with-order [order-expr & body]
  `(let [~'order ~order-expr]          ;; ← user can reference `order`
     ~@body))


;; The user is MEANT to use `order` — it's part of the macro's contract:
(with-order {:id 42 :item "Coffin" :qty 1}
  (println "Order #" (:id order) "for" (:qty order) (:item order)))
;; => "Order # 42 for 1 Coffin"


(require '[clojure.core.async :as async :refer [go <! >! chan timeout alts!]])

(defmacro go-with-timeout [timeout-ms & body]
  `(go
     (let [result-ch# (go ~@body)
           timeout-ch# (timeout ~timeout-ms)
           [val# port#] (alts! [result-ch# timeout-ch#])]
       (if (= port# timeout-ch#) :timeout val#))))

(set! *warn-on-reflection* true)

(defn slow-upper [s]         (.toUpperCase s))         ;; REFLECTION WARNING
(defn fast-upper [{:tag String} s] (.toUpperCase s))         ;; no warning

;; Benchmark (1M calls):
(time (dotimes [_ 1000000] (slow-upper "hello")))      ;; ~1900 ms
(time (dotimes [_ 1000000] (fast-upper "hello")))      ;; ~20 ms  (96x faster!)

(defmacro defbean-getter [fn-name class-sym method-sym]
  `(defn ~fn-name [~(with-meta 'obj {:tag class-sym})]
     (~method-sym ~'obj)))

(defbean-getter get-amount java.math.BigDecimal .doubleValue)
(get-amount (BigDecimal. "199.99"))  ;; => 199.99, zero reflection

;; Lazy: 4 intermediate sequences
(->> data (filter p1?) (filter p2?) (map f1) (map f2) (into []))

;; Transducer: ZERO intermediate collections, single pass
(into [] (comp (filter p1?) (filter p2?) (map f1) (map f2)) data)


(chan 1024 (comp (filter p1?) (map f1)))   ;; filtered + transformed on read



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


(.stop hotel-server 0)





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


