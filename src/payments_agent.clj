(ns payments-agent)

;; Create an Agent with an initial value:
(def audit-log (agent []))

;; Dereference to read (same as Atoms and Refs):
@audit-log
;; => []

;; Send an update function (asynchronous — returns immediately):
(send audit-log conj {:event :payment-authorized :time (System/currentTimeMillis)})
;; => #<Agent [...]>  (returns the agent, NOT the new value)

;; The update happens asynchronously:
@audit-log
;; => [{:event :payment-authorized, :time 1234567890}]


;; send: uses a FIXED thread pool (CPU-bound work)
;;   Good for: computations, data transformations, in-memory updates
;;   Bad for: I/O (would block a pool thread)
(send audit-log conj {:event :payment-captured})

;; send-off: uses a GROWING thread pool (I/O-bound work)
;;   Good for: file writes, HTTP calls, database queries
;;   Bad for: nothing — but wastes threads on CPU-bound work
(send-off audit-log
  (fn [log]
    ;; Simulate writing to a file:
    (spit "/tmp/audit.log"
      (str (pr-str (last log)) "\n")
      :append true)
    (conj log {:event :written-to-disk})))

@audit-log


;; Send several actions:
(send audit-log conj {:event :a})
(send audit-log conj {:event :b})
(send audit-log conj {:event :c})

;; Wait for all pending actions to complete:
(await audit-log)

;; Now guaranteed to have all three entries:
(count @audit-log)
;; => 3+ (at least a, b, c)


;; All sends to the SAME agent are processed IN ORDER:
(def ordered-log (agent []))

(dotimes [i 10000]
  (send ordered-log conj i))

(await ordered-log)
@ordered-log
;; => [0 1 2 3 4 5 6 7 8 9]  — always in order!

;; But sends to DIFFERENT agents are independent:
(def log-a (agent []))
(def log-b (agent []))

(send log-a conj :first-to-a)
(send log-b conj :first-to-b)
;; No ordering guarantee between log-a and log-b

[@log-a @log-b]


(def balance-agent
  (agent {:balance 1000}
    :validator (fn [{:keys [balance]}] (>= balance 0))))

(send balance-agent update :balance - 600)
(await balance-agent)
@balance-agent                                              ;; => {:balance 500}

(send balance-agent update :balance - 600)
;; Agent enters ERROR state (validator failed)


(def risky-agent (agent 0))

;; Send an action that throws:
(send risky-agent (fn [_] (throw (ex-info "Boom!" {}))))

;; Wait a moment...
(agent-error risky-agent)
;; => #error {:cause "Boom!" ...}

;; The agent is now STUCK — all future sends are rejected:
(send risky-agent inc)
;; => throws: Agent has errors


;; Reset the agent to a known good state:
(restart-agent risky-agent 0)

;; Now it works again:
(send risky-agent inc)
(await risky-agent)
@risky-agent                                                ;; => 1


;; :fail (default) — agent stops on first error
(def fail-agent (agent 0 :error-mode :fail))

(send fail-agent (fn [_] (throw (ex-info "Payment rejected" {}))))
(Thread/sleep 100)
(agent-error fail-agent)                                    ;; => #error {:cause "Payment rejected" ...}
(send fail-agent inc)                                       ;; => throws: Agent is failed, needs restart
;; Agent is STUCK until you explicitly restart-agent

(restart-agent fail-agent 0)
(send fail-agent inc)
(await fail-agent)
@fail-agent                                                 ;; => 1 — back to normal


;; :continue — agent keeps processing, errors go to handler
(def continue-agent
  (agent 0
    :error-mode :continue
    :error-handler (fn [ag ex]
                     (println "Agent error:" (.getMessage ex))
                     (println "Agent value still:" @ag))))

(send continue-agent (fn [_] (throw (ex-info "Oops!" {}))))
;; Agent error: Oops!
;; Agent value still: 0

;; Agent is NOT in error state — it continues working:
(send continue-agent inc)
(await continue-agent)
@continue-agent                                             ;; => 1


;; Pattern: Agent with built-in error resilience
(defn make-resilient-agent [initial-value on-error]
  (agent initial-value
    :error-mode :fail
    :error-handler (fn [ag ex]
                     (on-error ag ex))))

(def tx-log
  (make-resilient-agent []
    (fn [ag ex]
      (println "WARN: Failed to log transaction:" (.getMessage ex))
      ;; Could also: send to error queue, increment error counter, etc.
      )))

(send tx-log (fn [_] (throw (ex-info "Payment rejected" {}))))

(agent-error tx-log)


;; A realistic payment system uses MULTIPLE reference types:

(def payment-counter (atom 0))                              ;; Atom: simple counter
(def accounts (ref {:victor (ref {:balance 0})
                    :foo    (ref {:balance 0})}))           ;; Ref: coordinated balances
(def audit-trail (agent []))                                ;; Agent: async logging
(def ^:dynamic *request-id* nil)                            ;; Var: per-request context

(defn process-payment! [from to amount]
  (binding [*request-id* (str "req-" (random-uuid))]
    ;; 1. Coordinated state change (synchronous)
    (let [result (dosync
                   (alter (get @accounts from) update :balance - amount)
                   (alter (get @accounts to) update :balance + amount)
                   {:status :transferred :amount amount})]
      ;; 2. Simple counter (synchronous)
      (swap! payment-counter inc)
      ;; 3. Audit log (asynchronous — doesn't slow down the response)
      (send audit-trail conj
        {:request-id *request-id*
         :from       from :to to :amount amount
         :time       (System/currentTimeMillis)})
      result)))


(process-payment! :victor :to 10)



;; Step 1: Define the Agent state
;; State: {:pending [] :written 0 :errors 0}
(def audit-agent
  (agent {:pending [] :written 0 :errors 0}
    :error-mode :continue
    :error-handler (fn [ag ex]
                     (println "Audit error:" (.getMessage ex)))))

;; Step 2: Add an event (fast — just appends to pending)
(defn log-event! [event]
  (send audit-agent
    (fn [state]
      (update state :pending conj
        (assoc event :timestamp (System/currentTimeMillis))))))

;; Step 3: Flush to "disk" (simulated — uses send-off for I/O)
(defn flush-audit! []
  (send-off audit-agent
    (fn [{:keys [pending written] :as state}]
      (if (seq pending)
        (do
          ;; Simulate writing to file/database:
          (println (str "[FLUSH] Writing " (count pending) " events"))
          (doseq [event pending]
            (println "  →" (:type event) (:details event)))
          {:pending [] :written (+ written (count pending)) :errors (:errors state)})
        state))))

(defn process-with-audit! [payment]
  (let [result {:status :authorized :method (:method payment)}]
    ;; Log start (non-blocking):
    (log-event! {:type :payment-started :details payment})
    ;; ... process payment ...
    (Thread/sleep 10)                                       ;; simulate work
    ;; Log completion (non-blocking):
    (log-event! {:type :payment-completed :details result})
    result))

;; Process several payments:
(doseq [p [{:method :spei :amount 1200}
           {:method :credit-card :amount 500}
           {:method :debit-card :amount 350}]]
  (process-with-audit! p))

;; Flush the buffer:
(flush-audit!)
(await audit-agent)

;; Check stats:
@audit-agent
;; => {:pending [], :written 6, :errors 0}

(defn make-rate-limiter [max-tokens refill-rate-per-sec]
  (let [limiter (agent {:tokens      max-tokens
                        :max-tokens  max-tokens
                        :last-refill (System/currentTimeMillis)})
        ;; Start a refill loop:
        refill-fn (fn refill [state]
                    (let [now (System/currentTimeMillis)
                          elapsed-sec (/ (- now (:last-refill state)) 1000.0)
                          new-tokens (min (:max-tokens state)
                                       (+ (:tokens state)
                                         (* elapsed-sec refill-rate-per-sec)))]
                      (assoc state :tokens new-tokens :last-refill now)))]
    ;; Periodic refill:
    (future
      (while true
        (Thread/sleep 800)
        (send limiter refill-fn)))
    limiter))

(defn try-acquire! [limiter]
  ;; This is a simplification — in production you'd use an atom for sync check
  ;; For demo purposes, we check the current state:
  (let [state @limiter]
    (if (>= (:tokens state) 1)
      (do
        (send limiter update :tokens dec)
        :allowed)
      :rate-limited)))

;; Test:
(def my-limiter (make-rate-limiter 5 2))                    ;; 5 max, 2 per second

;; Rapid-fire requests:
(dotimes [i 8]
  (println (str "Request " i ": " (try-acquire! my-limiter))))
;; Request 0: :allowed
;; Request 1: :allowed
;; ...
;; Request 5: :rate-limited
;; Request 6: :rate-limited

;; Wait for refill:
(Thread/sleep 2000)
(println "After 2 seconds:" (try-acquire! my-limiter))
;; After 2 seconds: :allowed


;; Reference types:
(def counter (atom 0))
(def acc-a (ref {:balance 10000}))
(def acc-b (ref {:balance 5000}))
(def audit (agent []))
(def ^:dynamic *req-id* "unknown")

(defn transfer! [amount]
  (binding [*req-id* (str "tx-" (swap! counter inc))]
    ;; Coordinated state change:
    (dosync
      (alter acc-a update :balance - amount)
      (alter acc-b update :balance + amount))
    ;; Async audit:
    (send audit conj {:req *req-id* :amount amount
                       :time (System/currentTimeMillis)})
    {:req *req-id* :status :ok}))

;; Run 10 transfers:
(doseq [_ (range 10)]
  (transfer! (+ 50 (rand-int 200))))

(await audit)

;; Verify:
(println "Counter:" @counter)              ;; => 10
(println "Total:" (+ (:balance @acc-a)
                      (:balance @acc-b)))   ;; => 15000
(println "Audit entries:" (count @audit))   ;; => 10

(assert (= 10 @counter))
(assert (= 15000 (+ (:balance @acc-a) (:balance @acc-b))))
(assert (= 10 (count @audit)))
(println "All assertions passed!")
