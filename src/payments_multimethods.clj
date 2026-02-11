(ns payments-multimethods)

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

(comment                                                    ;; Rich Comments

  (process-payment {:method     :spei
                    :spei-clabe "032180000118359719"
                    :amount     -1200.0}))

(def p1 {:method     :spei
         :spei-clabe "032180000118359719"
         :amount     1200.0})

(def p2 {:method         :oxxo
         :oxxo-reference "1234567890123456"
         :amount         350.0})

(def p3 {:method     :credit-card
         :spei-clabe "032180000118359719"
         :amount     -1200.0})

(:method p1 :default)
(:metho2 p1 :fault)

(defmulti process :method)

(defn proces-dispatcher [payment]
  (get payment :method))

(defmulti process proces-dispatcher)


(proces-dispatcher p1)
(proces-dispatcher p2)
(proces-dispatcher p3)

(defmulti validate!
  "Validates a payment map. Dispatches on the :method key."
  :method) ;; ← this is the dispatch function (a keyword IS a function in Clojure)

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

(validate! p1)

(validate! p2)

(validate! {})

(ns-unmap *ns* 'validate!)


(defn payment-tier
  "Computes a risk/routing tier based on business rules."
  [{:keys [method amount]}]
  (cond
    (and (= method :credit-card) (> amount 500)) :3ds
    (and (= method :spei) (> amount 100000)) :manual-review
    (and (= method :oxxo) (> amount 20000)) :high-risk
    :else :standard))

(defmulti process2
  "Processes payment. Dispatches on [method tier] vector."
  (fn [payment]
    [(:method payment) (payment-tier payment)]))

(def p11 {:method     :spei
          :spei-clabe "032180000118359719"
          :amount     1000000})

(process2 p11)

;; --- Credit Card ---
(defmethod process2 [:credit-card :standard] [payment]
  (validate! payment)
  {:status :approved :flow :regular :provider :mx-acquirer})

(defmethod process2 [:credit-card :3ds] [payment]
  (validate! payment)
  {:status   :requires-action
   :flow     :3ds
   :provider :mx-acquirer
   :next     {:type :redirect :url "https://3ds.example/checkout"}})

;; --- SPEI ---
(defmethod process2 [:spei :standard] [payment]
  (validate! payment)
  {:status :confirmed :flow :bank-transfer :provider :spei-rails})

(defmethod process2 [:spei :manual-review] [payment]
  (validate! payment)
  {:status   :pending
   :flow     :manual-review
   :provider :spei-rails
   :reason   :amount-threshold})

;; --- OXXO ---
(defmethod process2 [:oxxo :standard] [payment]
  (validate! payment)
  {:status :pending :flow :cash-reference :provider :oxxo-network})

(defmethod process2 [:oxxo :high-risk] [payment]
  (validate! payment)
  {:status   :pending
   :flow     :cash-reference
   :provider :oxxo-network
   :flags    #{:high-risk}})

;; --- Safety net ---
(defmethod process2 :default [payment]
  (throw (ex-info "No processing rule for dispatch value"
           {:dispatch [(:method payment) (payment-tier payment)]
            :payment  payment})))

(defmulti process2
  "Processes payment. Dispatches on [method tier] vector."
  (fn [payment user db]
    [user db (:method payment) (payment-tier payment)]))

(defmethod process2 :default [payment user db]
  (throw (ex-info "No processing rule for dispatch value"
           {:dispatch [(:method payment) (payment-tier payment)]
            :payment  payment})))
