(ns payments-protocols)


(defprotocol PaymentGateway
  (authorize [this payment])
  (capture   [this payment])
  (refund    [this payment]))


(class PaymentGateway)

(type authorize)

(:on-interface PaymentGateway)

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


(= (->SpeiGateway "BANORTE" 5000)
   (map->SpeiGateway {:bank-code "BANORTE" :timeout-ms 5000}))

(map->SpeiGateway {:bank-code "BANORTE" :timeout-ms 5000 :else 123})


;;;; EXTEND TYPE

(def legacy-payment {:method     :spei
                     :spei-clabe "032180000118359719"
                     :amount     1200.0
                     :k4 4
                     :k5 5
                     :k6 6
                     :k7 7
                     :k8 8
                     :k9 9})

(authorize legacy-payment {:amount 100})


(type legacy-payment)


(extend-type clojure.lang.PersistentArrayMap
  PaymentGateway
  (authorize [this payment]
    {:status :authorized-via-map
     :method (:method this)
     :keys-count (-> this keys count)
     :token  (str "MAP-" (random-uuid))})
  (capture [this payment]
    {:status :captured-via-map
     :method (:method this)})
  (refund [this payment]
    {:status :refund-via-map
     :method (:method this)}))

(extend-type clojure.lang.PersistentHashMap
  PaymentGateway
  (authorize [this payment]
    {:status :authorized-via-map
     :method (:method this)
     :keys-count (-> this keys count)
     :token  (str "MAP-" (random-uuid))})
  (capture [this payment]
    {:status :captured-via-map
     :method (:method this)})
  (refund [this payment]
    {:status :refund-via-map
     :method (:method this)}))


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


(audit-entry {:method :crypto} :authorize)
(audit-entry {:method :main-frame} :authorize)



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
(time (dotimes [_ 10000000] (process-mm test-map)))
;; ~150-300ms (multimethod: dispatch fn + map lookup + cache check)

(time (dotimes [_ 10000000] (process-fast test-rec)))
;; ~30-60ms (protocol: direct JVM interface call)
