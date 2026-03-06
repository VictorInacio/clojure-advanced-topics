(ns channels-kitchen
  (:require [clojure.core.async :as async
             :refer [chan go go-loop >! <! >!! <!! close!
                     buffer sliding-buffer dropping-buffer
                     timeout thread]]))



(def ch2 (chan 3))
(>!! ch2 :a)
(<!! ch2)



(def result-ch
  (thread
    (Thread/sleep 5000)
    (println "Processing on thread:" (.getName (Thread/currentThread)))
    (Thread/sleep 5000)
    {:status :done}))

(<!! result-ch)


(def result-ch
  (go
    (println "Begin go block:" (.getName (Thread/currentThread)))
    (<! (timeout 5000))  ;; "sleep" without blocking a thread
    (println "End go block:" (.getName (Thread/currentThread)))
    {:status :authorized :method :spei}))

(<!! result-ch)


;; >! and <! MUST be used inside go blocks (they "park" instead of blocking)
(def ch (chan))

;; Producer go block:
(go
  (>! ch {:method :spei :amount 1200})
  (>! ch {:method :credit-card :amount 500})
  (close! ch))

;; Consumer go block:
(go
  (loop []
    (when-let [payment (<! ch)]
      (println "Received:" (:method payment) (:amount payment))
      (recur))))
;; Received: :spei 1200
;; Received: :credit-card 500

(def payment-ch (chan 100))

(go-loop []
  (when-let [payment (<! payment-ch)]
    (println "Processing:" (:method payment) (:amount payment))
    (recur)))


;; Setup: a channel with one good and one bad payment
(def error-demo-ch (chan 2))
(>!! error-demo-ch {:method :spei :amount 500})
(>!! error-demo-ch {:method :spei :amount -200})
(close! error-demo-ch)

;; Consumer WITH try/catch — errors are caught and logged:
(def error-result-ch
  (go-loop []
    (when-let [payment (<! error-demo-ch)]
      (try
        (if (neg? (:amount payment))
          (throw (ex-info "Negative amount" {:payment payment}))
          (println "OK:" (:amount payment)))
        (catch Exception e
          (println "ERROR in go block:" (.getMessage e))))
      (recur))))

(<!! error-result-ch)
;; OK: 500
;; ERROR in go block: Negative amount


(def response-ch (chan))

(go
  (let [result (async/alt!
                 response-ch ([v] {:ok v})
                 (timeout 10000) ([_] {:error :timeout}))]
    (println "Timeout path:" result)))

(>!! response-ch {:a 1})


(def payment-events (chan 50))


(defn start-consumer! [ch consumer-id]
  (go-loop []
    (when-let [event (<! ch)]
      (let [start (System/nanoTime)
            ;; Simulate processing time
            ;;_ (<! (timeout (rand-int 500)))
            elapsed (/ (- (System/nanoTime) start) 1e6)]
        (println (str "[Consumer " consumer-id "] "
                      (:method event) " $" (:amount event)
                      " (" (.format (java.text.DecimalFormat. "#.#") elapsed) "ms)")))
      (recur))))

(start-consumer! payment-events "A")

(defn start-producer! [ch n]
  (go
    (doseq [i (range n)]
      (let [payment {:id     (str "pay-" i)
                     :method (rand-nth [:spei :credit-card :debit-card])
                     :amount (+ 100 (rand-int 10000))
                     :time   (System/currentTimeMillis)}]
        (>! ch payment)
        (println "[Produced] " n )
        #_(<! (timeout (rand-int 20)))))  ;; variable rate
    (println "[Producer] Done — sent" n "events")))

(start-producer! payment-events 200)



(def unified-ch (chan 100))

(defn spei-producer [ch n]
  (go
    (dotimes [i n]
      (<! (timeout 100))
      (>! ch {:source :spei :id (str "spei-" i) :amount (+ 500 (rand-int 50000))}))
    (println "[SPEI] Done")))

(defn card-producer [ch n]
  (go
    (dotimes [i n]
      (<! (timeout 50))
      (>! ch {:source :card :id (str "card-" i) :amount (+ 100 (rand-int 20000))}))
    (println "[Card] Done")))

(defn debit-producer [ch n]
  (go
    (dotimes [i n]
      (<! (timeout 200))
      (>! ch {:source :debit-card :id (str "debit-" i) :amount (+ 50 (rand-int 5000))}))
    (println "[Debit] Done")))

;; Single consumer:
(defn unified-consumer [ch]
  (go-loop [counts {}]
    (if-let [event (<! ch)]
      (let [new-counts (update counts (:source event) (fnil inc 0))]
        (println (str "[" (:source event) "] " (:id event) " $" (:amount event)))
        (recur new-counts))
      (do
        (println "=== Final counts ===" counts)
        counts))))

;; Start consumer FIRST (it parks on <! waiting for events):
(unified-consumer unified-ch)

;; Start producers:
(spei-producer unified-ch 5)
(card-producer unified-ch 10)
(debit-producer unified-ch 3)



;; Experiment 1: Unbuffered channel
(let [ch (chan)]
  (go
    (dotimes [i 10]
      (println "Putting" i "at" (System/currentTimeMillis))
      (>! ch i))
    (close! ch))
  (go-loop []
    (when-let [v (<! ch)]
      (println "  Got" v "at" (System/currentTimeMillis))
      (<! (timeout 1000))  ;; slow consumer
      (recur))))
;; Observe: producer is forced to wait for consumer on each put


;; Experiment 2: Buffered channel (size 5)
(let [ch (chan 5)]
  (go
    (dotimes [i 10]
      (println "Putting" i "at" (System/currentTimeMillis))
      (>! ch i))
    (println "Producer done")
    (close! ch))
  (go-loop []
    (when-let [v (<! ch)]
      (println "  Got" v "at" (System/currentTimeMillis))
      (<! (timeout 1000))
      (recur))))
;; Observe: first 5 puts are instant (buffer absorbs), then producer slows
