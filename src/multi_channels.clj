(ns multi-channels
  (:require [clojure.core.async :as async
             :refer [chan go go-loop >! <! >!! <!! close!
                     alt! alt!! alts! alts!!
                     timeout thread
                     merge mult tap untap
                     pub sub unsub
                     pipeline pipeline-async]]))


(comment

  (def spei-ch (chan 10))
  (def card-ch (chan 10))
  (def alert-ch (chan 10))

  (go-loop []
    (alt!
      (timeout 5000) ([_] (println "No events for 5 seconds"))
      spei-ch ([event] (println "SPEI event:" event))
      card-ch ([event] (println "Card event:" event))
      alert-ch ([alert] (println "ALERT:" alert)))
    (recur))

  (dotimes [i 100]
    (dotimes [si 100] (>!! spei-ch {:i i :si si}))
    (dotimes [ci 100] (>!! card-ch {:i i :si ci}))
    )

  (go
    (dotimes [i 100]
      (dotimes [si 100] (>! spei-ch {:i i :si si}))
      (dotimes [ci 100] (>! card-ch {:i i :si ci}))
      ))


  (go
    (alt!
      spei-ch ([v] (println "SPEI:" v))
      card-ch ([v] (println "Card:" v))
      :priority true))


  (def delivery-ch (chan 1))
  (def delivery-val {:status :authorized :id "x1"})

  (go
    (alt!
      [[delivery-ch delivery-val]]  ;; try to put `delivery-val` on delivery-ch
      ([delivered?] (println "Delivered?" delivered?))

      (timeout 1000)
      ([_] (println "Timeout — couldn't deliver in 1 second"))))
  ;; Delivered? true

  (<!! delivery-ch)
  ;; => {:status :authorized, :id "x1"}


  (def channels [spei-ch card-ch alert-ch])

  (go
    (let [[value port] (alts! channels)]
      (condp = port
        spei-ch  (println "From SPEI:" value)
        card-ch  (println "From Card:" value)
        alert-ch (println "Alert:" value))))

  (>!! spei-ch "SPEI")
  (>!! card-ch "CARD")


  ;; Simulate a gateway that takes ~500ms to respond:
  (defn authorize [gateway payment]
    (Thread/sleep 500)
    {:status :authorized :id (:id payment) :gateway (:name gateway)})

  (def spei-gw {:name "SPEI Gateway"})

  (defn authorize-with-timeout! [gateway payment timeout-ms]
    (go
      (let [result-ch (thread (authorize gateway payment))  ;; blocking I/O
            [result port] (alts! [result-ch (timeout timeout-ms)])]
        (if (= port result-ch)
          {:ok result}
          {:error :timeout :after-ms timeout-ms}))))

  ;; Success path (500ms < 3000ms):
  (<!! (authorize-with-timeout! spei-gw
         {:id "s1" :spei-clabe "032180000118359719" :amount 1200}
         3000))
  ;; => {:ok {:status :authorized, :id "s1", :gateway "SPEI Gateway"}}

  ;; Timeout path (500ms > 100ms):
  (<!! (authorize-with-timeout! spei-gw
         {:id "s2" :spei-clabe "032180000118359719" :amount 1200}
         100))
  ;; => {:error :timeout, :after-ms 100}




  ;; Three source channels:
  (def spei-events (chan 10))
  (def card-events (chan 10))
  (def debit-events (chan 10))

  ;; Merge into a single channel:
  (def all-events (merge [spei-events card-events debit-events] 100))

  ;; Single consumer:
  (go-loop []
    (when-let [event (<! all-events)]
      (println "Event:" (:source event) (:id event))
      (recur)))

  ;; Feed from different sources:
  (go (>! spei-events {:source :spei :id "s1" :amount 1200}))
  (go (>! card-events {:source :card :id "c1" :amount 500}))
  (go (>! debit-events {:source :debit-card :id "o1" :amount 350}))



  ;; Source channel:
  (def events (chan 10))

  ;; Create a mult (multiplexer):
  (def events-mult (mult events))

  ;; Create tap channels (subscribers):
  (def audit-ch (chan 10))
  (def notification-ch (chan 10))
  (def analytics-ch (chan 10))

  (tap events-mult audit-ch)
  (tap events-mult notification-ch)
  (tap events-mult analytics-ch)

  ;; Each subscriber gets EVERY event:
  (go-loop [] (when-let [e (<! audit-ch)]
                (println "[Audit]" e) (recur)))
  (go-loop [] (when-let [e (<! notification-ch)]
                (println "[Notify]" e) (recur)))
  (go-loop [] (when-let [e (<! analytics-ch)]
                (println "[Analytics]" e) (recur)))

  ;; One put → three consumers see it:
  (>!! events {:type :payment-authorized :method :spei :amount 1200})
  ;; [Audit] {...}
  ;; [Notify] {...}
  ;; [Analytics] {...}



  ;; Source channel:
  (def event-ch (chan 100))

  ;; Create a publication that routes by :method
  (def event-pub (pub event-ch :method))

  ;; Subscribe channels to specific topics:
  (def spei-sub (chan 10))
  (def card-sub (chan 10))
  (def debit-sub (chan 10))

  (sub event-pub :spei spei-sub)
  (sub event-pub :credit-card card-sub)
  (sub event-pub :debit-card debit-sub)


  ;; Consumers:
  (go-loop [] (when-let [e (<! spei-sub)]
                (println "[SPEI Handler]" (:id e) "$" (:amount e))
                (recur)))

  (go-loop [] (when-let [e (<! card-sub)]
                (println "[Card Handler]" (:id e) "$" (:amount e))
                (recur)))

  (go-loop [] (when-let [e (<! debit-sub)]
                (println "[Debit Handler]" (:id e) "$" (:amount e))
                (recur)))

  ;; Publish events — each goes to the RIGHT handler:
  (>!! event-ch {:method :spei :id "s1" :amount 1200})
  ;; [SPEI Handler] s1 $ 1200

  (>!! event-ch {:method :credit-card :id "c1" :amount 500})
  ;; [Card Handler] c1 $ 500

  (>!! event-ch {:method :debit-card :id "o1" :amount 350})
  ;; [Debit Handler] o1 $ 350


  ;; Route by risk level (separate channel — a pub needs its OWN source):
  (def risk-event-ch (chan 100))

  (def risk-pub
    (pub risk-event-ch
         (fn [{:keys [amount]}]
           (cond
             (> amount 50000)  :high-risk
             (> amount 10000)  :medium-risk
             :else             :low-risk))))

  (def high-risk-ch (chan 10))
  (def low-risk-ch (chan 10))
  (sub risk-pub :high-risk high-risk-ch)
  (sub risk-pub :low-risk low-risk-ch)

  ;; Test:
  (>!! risk-event-ch {:id "r1" :amount 60000 :method :spei})
  (>!! risk-event-ch {:id "r2" :amount 500 :method :card})

  (<!! high-risk-ch)  ;; => {:id "r1", :amount 60000, :method :spei}
  (<!! low-risk-ch)   ;; => {:id "r2", :amount 500, :method :card}


  ;; Apply a transducer in parallel across N threads:
  (def input-ch (chan 100))
  (def output-ch (chan 100))

  ;; 4 parallel workers applying the transducer:
  (pipeline 4 output-ch
    (comp
      (filter #(> (:amount %) 1000))
      (map #(assoc % :status :approved)))
    input-ch)

  ;; Feed input:
  (go (doseq [p [{:id 1 :amount 500}
                 {:id 2 :amount 2000}
                 {:id 3 :amount 150}
                 {:id 4 :amount 8000}]]
        (>! input-ch p))
      (close! input-ch))

  ;; Read output:
  (go-loop []
    (when-let [result (<! output-ch)]
      (println "Approved:" result)
      (recur)))
  ;; Approved: {:id 2, :amount 2000, :status :approved}
  ;; Approved: {:id 4, :amount 8000, :status :approved}


  (def in-ch (chan 10))
  (def out-ch (chan 10))

  (pipeline-async 4 out-ch
    (fn [payment result-ch]
      (go
        (let [;; simulate async authorization
              _ (<! (timeout (rand-int 100)))
              result (assoc payment :status :authorized :token (str "T-" (random-uuid)))]
          (>! result-ch result)
          (close! result-ch))))
    in-ch)


  (go (doseq [p [{:id 1 :amount 500}
                 {:id 2 :amount 2000}
                 {:id 3 :amount 150}
                 {:id 4 :amount 8000}]]
        (>! in-ch p))
      (close! in-ch))


  ;; Channel with a transducer — transforms every value put into it:
  (def filtered-ch
    (chan 100
         (comp (filter #(= :spei (:method %)))
               (map #(select-keys % [:id :amount])))))

  (>!! filtered-ch {:method :spei :id "s1" :amount 1200 :extra :data})
  (>!! filtered-ch {:method :credit-card :id "c1" :amount 500})  ;; filtered out!
  (>!! filtered-ch {:method :spei :id "s2" :amount 800 :extra :more})

  (<!! filtered-ch)  ;; => {:id "s1", :amount 1200}
  (<!! filtered-ch)  ;; => {:id "s2", :amount 800}
  ;; The credit-card event was silently dropped by the filter transducer


  ;; Source of authorized payments:
  (def authorized-ch (chan 50))
  (def auth-mult (mult authorized-ch))

  ;; Email: gets every event
  (def email-ch (chan 50))
  (tap auth-mult email-ch)

  ;; SMS: only high-value (use a filtered channel)
  (def sms-raw-ch (chan 50))
  (tap auth-mult sms-raw-ch)

  ;; Audit: gets everything
  (def audit-ch (chan 50))
  (tap auth-mult audit-ch)

  ;; Email handler
  (go-loop []
    (when-let [event (<! email-ch)]
      (println "[EMAIL] Receipt sent for" (:id event) "- $" (:amount event))
      (recur)))

  ;; SMS handler (with client-side filtering)
  (go-loop []
    (when-let [event (<! sms-raw-ch)]
      (when (> (:amount event) 5000)
        (println "[SMS] High-value alert:" (:id event) "- $" (:amount event)))
      (recur)))

  ;; Audit handler
  (go-loop []
    (when-let [event (<! audit-ch)]
      (println "[AUDIT]" (pr-str event))
      (recur)))

  ;; Test it:
  (go
    (doseq [p [{:id "p1" :amount 1200 :method :spei}
                {:id "p2" :amount 8000 :method :credit-card}
                {:id "p3" :amount 300 :method :debit-card}
                {:id "p4" :amount 25000 :method :spei}]]
      (>! authorized-ch p)))

  ;; Expected output:
  ;; [EMAIL] Receipt sent for p1 - $ 1200
  ;; [AUDIT] {:id "p1", ...}
  ;; [EMAIL] Receipt sent for p2 - $ 8000
  ;; [SMS] High-value alert: p2 - $ 8000
  ;; [AUDIT] {:id "p2", ...}
  ;; etc.

  ;; Input channel with transducer (pre-filter):
  (def raw-events
    (chan 100 (filter #(= :approved (:status %)))))

  ;; Processing pipeline:
  (def totals (atom {}))

  (go-loop []
    (when-let [{:keys [method amount]} (<! raw-events)]
      (swap! totals update method (fnil + 0) amount)
      (println "Running totals:" @totals)
      (recur)))

  ;; Simulate events:
  (go
    (doseq [e [{:method :spei :amount 1200 :status :approved}
                {:method :credit-card :amount 500 :status :rejected}   ;; filtered out
                {:method :spei :amount 800 :status :approved}
                {:method :debit-card :amount 350 :status :approved}
                {:method :credit-card :amount 2000 :status :approved}
                {:method :spei :amount 5000 :status :pending}]]        ;; filtered out
      (>! raw-events e)
      (<! (timeout 200))))

  ;; Expected output:
  ;; Running totals: {:spei 1200}
  ;; Running totals: {:spei 2000}
  ;; Running totals: {:spei 2000, :debit-card 350}
  ;; Running totals: {:spei 2000, :debit-card 350, :credit-card 2000}

  )
