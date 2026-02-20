(ns ledger)

(def a (atom 0))

(type a)

@a

(deref a)

(reset! a 999)
(reset! a 0)

(reset! a (inc @a))
;; 100 threads, each incrementing 1,000 times → expected: 100,000
(let [futures (doall
                (repeatedly 100
                  #(future (dotimes [_ 1000]
                             (reset! a (inc @a))))))]       ;; ← READ then WRITE: race!
  (doseq [f futures] @f))


(dotimes [_ 1000]
  (reset! a (inc @a)))

(def f (future (dotimes [_ 1000]
                 (reset! a (inc @a)))))

(deref a)


(compare-and-set! a 0 1)                                    ;; => true  (was 0, set to 1)
(compare-and-set! a 1 2)
(compare-and-set! a 0 2)


(def a (atom 0))

(deref a)
;; Manual CAS loop — retry until it works:
(let [futures (doall
                (repeatedly 100
                  #(future (dotimes [_ 1000]
                             (loop []
                               (let [old-val @a]
                                 (when-not (compare-and-set! a old-val (inc old-val))
                                   (println "ops")
                                   (recur))))))))]
  (doseq [f futures] @f))


(defn my-swap! [atom f & args]
  (loop []
    (let [old-val @atom
          new-val (apply f old-val args)]
      (if (compare-and-set! atom old-val new-val)
        new-val
        (recur)))))


(def a (atom 0))
@a

(let [futures (doall
                (repeatedly 100
                  #(future (dotimes [_ 1000]
                             (swap! a inc)))))]
  (doseq [f futures] @f))


(def account-a (atom {:balance 1000}))
(def account-b (atom {:balance 500}))



;; Transfer 200 from A to B:
(swap! account-a update :balance - 200)
;; At this EXACT moment: A has 800, B has 500
;; Total in the system = 1300 instead of 1500!
;; If the system crashes here, money is LOST.
(swap! account-b update :balance + 200)
;; Now: A=800, B=700, total=1500 (correct again)




;; Refs are created with `ref`:
(def account-a (ref {:owner "María" :balance 10000}))
(def account-b (ref {:owner "Carlos" :balance 5000}))

;; Read with @ or deref (same as atoms):
@account-a
;; => {:owner "María", :balance 10000}

(:balance @account-b)
;; => 5000


(dosync
  (alter account-a update :balance - 2000)
  (alter account-b update :balance + 2000))


(alter account-a update :balance - 100)

(update @account-a :balance - 100)


(defn make-account [owner initial-balance]
  (ref {:owner   owner
        :balance initial-balance
        :history []}
    :validator (fn [{:keys [balance]}]
                 (>= balance 0))))                          ;; ← INVARIANT: balance never negative

(def accounts
  {:maria  (make-account "María García" 10000)
   :carlos (make-account "Carlos López" 5000)
   :ana    (make-account "Ana Martínez" 8000)})

(dosync
  (alter (:maria accounts) update :balance - 50000))

@(:maria accounts)


(defn transfer! [from-account to-account amount description]
  (dosync
    (let [from-balance (:balance @from-account)
          timestamp    (System/currentTimeMillis)]
      (when (< from-balance amount)
        (throw (ex-info "Insufficient funds"
                        {:available from-balance :requested amount})))
      (alter from-account
        (fn [acc]
          (-> acc
              (update :balance - amount)
              (update :history conj
                {:type :debit :amount amount :desc description :time timestamp}))))
      (alter to-account
        (fn [acc]
          (-> acc
              (update :balance + amount)
              (update :history conj
                {:type :credit :amount amount :desc description :time timestamp}))))
      {:status      :completed
       :from        (:owner @from-account)
       :to          (:owner @to-account)
       :amount      amount
       :description description})))


(transfer! (:maria accounts) (:carlos accounts) 2000 "Dinner payment")

@(:maria accounts)
@(:carlos accounts)


(defn total-balance [account-refs]
  (dosync
    (reduce + (map #(do (ensure %) (:balance @%)) account-refs))))

(total-balance (vals accounts))


(add-watch (:maria accounts) :balance-alert
  (fn [key ref old-state new-state]
    (println "LOW BALANCE ALERT:" (:owner new-state)
                   "has only $" (:balance new-state))))

;; Transfer that triggers the alert:
(transfer! (:maria accounts) (:carlos accounts)   7500 "Large payment")
;; LOW BALANCE ALERT: María García has only $ 500

(def att (atom {}))

(add-watch att :basic-log
  (fn [key ref old-state new-state]
    (println  [old-state new-state])))

(reset! att {:a 0})


;;;;

(def stress-accounts
  (mapv #(ref {:id % :balance 10000}) (range 4)))

(defn random-transfer! []
  (let [from-idx (rand-int 4)
        to-idx   (loop [i (rand-int 4)]
                   (if (= i from-idx) (recur (rand-int 4)) i))
        amount   (inc (rand-int 500))]
    (try
      (dosync
        (let [from-balance (:balance @(stress-accounts from-idx))]
          (when (>= from-balance amount)
            (alter (stress-accounts from-idx) update :balance - amount)
            (alter (stress-accounts to-idx) update :balance + amount))))
      #_(let [from-balance (:balance @(stress-accounts from-idx))]
                (when (>= from-balance amount)
                  (swap! (stress-accounts from-idx) update :balance - amount)
                  (swap! (stress-accounts to-idx) update :balance + amount)))
      (catch Exception e
        ;; Insufficient funds — that's OK
        nil))))

;; Launch 100 concurrent transfers:
(let [futures (doall
                (repeatedly 100
                  #(future (random-transfer!))))]
  ;; Wait for all to complete:
  (doseq [f futures] @f)

  ;; Check total balance:
  (let [total (reduce + (map #(:balance @%) stress-accounts))]
    (println "Total balance:" total)
    (assert (= 40000 total) "INVARIANT VIOLATED!")
    (println "Individual balances:" (mapv #(:balance @%) stress-accounts))))

;; Total balance: 40000  ← ALWAYS 40000, guaranteed by STM
;; Individual balances: [8723 12451 9120 9706]  (varies each run)



;;;;
(def retry-count (atom 0))

(defn random-transfer-counted! []
  (let [from-idx (rand-int 4)
        to-idx   (loop [i (rand-int 4)]
                   (if (= i from-idx) (recur (rand-int 4)) i))
        amount   (inc (rand-int 500))]
    (try
      (dosync
        (swap! retry-count inc)  ;; ← counts every attempt (including retries)
        (let [from-balance (:balance @(stress-accounts from-idx))]
          (when (>= from-balance amount)
            (alter (stress-accounts from-idx) update :balance - amount)
            (alter (stress-accounts to-idx) update :balance + amount))))
      (catch Exception _ nil))))

;; Reset and run:
(reset! retry-count 0)
(let [futures (doall (repeatedly 100 #(future (random-transfer-counted!))))]
  (doseq [f futures] @f)
  (println "Attempts (incl. retries):" @retry-count)
  (println "Transactions requested: 100"))
;; Attempts: ~110-150 (some transactions retried due to conflicts)



(def safe-accounts
  (mapv (fn [id]
          (ref {:id id :balance 10000}
               :validator (fn [{:keys [balance]}]
                            (and (>= balance 0) (<= balance 100000)))))
        (range 4)))

;; Add watches:
(doseq [acc safe-accounts]
  (add-watch acc :low-balance
    (fn [_ _ old new]
      (when (and (>= (:balance old) 1000)
                 (< (:balance new) 1000))
        (println "LOW BALANCE:" (:id new) "→ $" (:balance new))))))

;; Run concurrent transfers:
(let [futures (doall
                (repeatedly 50
                  #(future
                     (try
                       (let [from (rand-int 4)
                             to (loop [i (rand-int 4)]
                                  (if (= i from) (recur (rand-int 4)) i))]
                         (dosync
                           (let [amount (min (inc (rand-int 999999999))
                                            (:balance @(safe-accounts from)))]
                             (when (pos? amount)
                               (alter (safe-accounts from) update :balance - amount)
                               (alter (safe-accounts to) update :balance + amount)))))
                       (catch Exception _ nil)))))]
  (doseq [f futures] @f)
  (println "Total:" (reduce + (map #(:balance @%) safe-accounts)))
  (println "Balances:" (mapv #(:balance @%) safe-accounts)))
