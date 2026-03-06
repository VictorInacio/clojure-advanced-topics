(ns transduce-kitchen)


;; Each ingredient is a map
(def kitchen-ingredients
  [{:item "🥔" :name "potato"}
   {:item "🐄" :name "cow"}
   {:item "🥔" :name "potato"}
   {:item "🌽" :name "corn"}
   {:item "🐄" :name "cow"}
   {:item "🥔" :name "potato"}
   {:item "🌽" :name "corn"}
   {:item "🥔" :name "potato"}])




(mapv :item kitchen-ingredients)
;; => [🥔 🐄 🥔 🌽 🐄 🥔 🌽 🥔]
;; 4 potatoes, 2 cows, 2 corn


;; Our transformation functions
(defn potato? [x] (= "potato" (:name x)))
(defn peel-it [x] (assoc x :item "🥔✨"))
(defn cut-it [x] (assoc x :item "🔪🥔"))
(defn fry-it [x] (assoc x :item "🍟"))


(def bowl-1 (filter potato? kitchen-ingredients))
(mapv :item bowl-1)


;; 👩‍🍳 Chef 2 (PEEL): "I peel everything in Bowl 1!"
(def bowl-2 (map peel-it bowl-1))
(mapv :item bowl-2)
;; => [🥔✨ 🥔✨ 🥔✨ 🥔✨]  📦 Bowl 2 ALLOCATED

;; 👨‍🍳 Chef 3 (CUT): "I cut everything in Bowl 2!"
(def bowl-3 (map cut-it bowl-2))
(mapv :item bowl-3)
;; => [🔪🥔 🔪🥔 🔪🥔 🔪🥔]  📦 Bowl 3 ALLOCATED

;; 👩‍🍳 Chef 4 (FRY): "I fry everything in Bowl 3!"
(def bowl-4 (map fry-it bowl-3))
(mapv :item bowl-4)
;; => [🍟 🍟 🍟 🍟]          📦 Bowl 4 ALLOCATED

(->> kitchen-ingredients
  (filter potato?)                                          ;; 📦 bowl 1
  (map peel-it)                                             ;; 📦 bowl 2
  (map cut-it)                                              ;; 📦 bowl 3
  (map fry-it))                                             ;; 📦 bowl 4
;; => ({:item "🍟", :name "potato"} ...)
;; 4 intermediate collections, even though we only needed the final 🍟!

(type (filter potato? kitchen-ingredients))
(type (filter potato?))


(def xf-make-fries
  (comp
    (filter potato?)                                        ;; step 1 (runs first)
    (map peel-it)                                           ;; step 2
    (map cut-it)                                            ;; step 3
    (map fry-it)))                                          ;; step 4 (runs last)

(type xf-make-fries)

(into [] xf-make-fries kitchen-ingredients)

(def xf-fries (comp (filter #(= "potato" (:name %))) (map (fn [_] "🍟"))))
(def xf-burgers (comp (filter #(= "cow" (:name %))) (map (fn [_] "🍔"))))
(def xf-popcorn (comp (filter #(= "corn" (:name %))) (map (fn [_] "🍿"))))

;; Same ingredients, 3 different recipes:
(into [] xf-fries kitchen-ingredients)                      ;; => [🍟 🍟 🍟 🍟]
(into [] xf-burgers kitchen-ingredients)                    ;; => [🍔 🍔]
(into [] xf-popcorn kitchen-ingredients)                    ;; => [🍿 🍿]


(def truck-crate-1
  [{:item "🥔" :name "potato" :source "truck"}
   {:item "🥔" :name "potato" :source "truck"}
   {:item "🐄" :name "cow" :source "truck"}
   {:item "🥔" :name "potato" :source "truck"}
   {:item "🌽" :name "corn" :source "truck"}])

(def truck-crate-2
  [{:item "🐄" :name "cow" :source "truck"}
   {:item "🥔" :name "potato" :source "truck"}
   {:item "🥔" :name "potato" :source "truck"}
   {:item "🌽" :name "corn" :source "truck"}
   {:item "🥔" :name "potato" :source "truck"}])

;; Same recipe, different crates:
(mapv :item (into [] xf-make-fries truck-crate-1))          ;; => [🍟 🍟 🍟]
(mapv :item (into [] xf-make-fries truck-crate-2))          ;; => [🍟 🍟 🍟]


(def bike-delivery-1 [{:item "🥔" :name "potato" :source "bike"}])
(def bike-delivery-2 [{:item "🐄" :name "cow" :source "bike"}])
(def bike-delivery-3 [{:item "🥔" :name "potato" :source "bike"}
                      {:item "🌽" :name "corn" :source "bike"}])

(mapv :item (into [] xf-make-fries bike-delivery-1))        ;; => [🍟]
(mapv :item (into [] xf-make-fries bike-delivery-2))        ;; => [] (cow filtered out!)
(mapv :item (into [] xf-make-fries bike-delivery-3))        ;; => [🍟] (only potato passed)


(def market-inventory
  [{:item "🥔" :name "potato" :source "market" :price 5}
   {:item "🐄" :name "cow" :source "market" :price 500}
   {:item "🌽" :name "corn" :source "market" :price 8}
   {:item "🥔" :name "potato" :source "market" :price 4}
   {:item "🥔" :name "potato" :source "market" :price 6}])

;; Same recipe:
(mapv :item (into [] xf-make-fries market-inventory))       ;; => [🍟 🍟 🍟]

;; We can also aggregate — how much did the potatoes cost?
(transduce
  (comp (filter potato?) (map :price))
  + 0
  market-inventory)
;; => 15


;; In a real system, events arrive from ALL sources mixed:
(def event-stream
  [{:item "🥔" :name "potato" :source "🚚 truck" :time "10:00"}
   {:item "🐄" :name "cow" :source "🚚 truck" :time "10:00"}
   {:item "🥔" :name "potato" :source "🚚 truck" :time "10:00"}
   {:item "🥔" :name "potato" :source "🚲 bike" :time "10:01"}
   {:item "🌽" :name "corn" :source "🚲 bike" :time "10:02"}
   {:item "🥔" :name "potato" :source "🏪 market" :time "10:03"}
   {:item "🐄" :name "cow" :source "🏪 market" :time "10:03"}
   {:item "🌽" :name "corn" :source "🚚 truck" :time "10:04"}
   {:item "🥔" :name "potato" :source "🚚 truck" :time "10:04"}
   {:item "🥔" :name "potato" :source "🚲 bike" :time "10:05"}])

;; The transducer doesn't care about the source:
(into [] xf-make-fries event-stream)
;; => 6 fries [🍟 🍟 🍟 🍟 🍟 🍟]

;; How many fries per source?
(transduce
  (filter potato?)
  (fn ([] {}) ([r] r) ([acc item] (update acc (:source item) (fnil inc 0))))
  event-stream)
;; => {"🚚 truck" 3, "🚲 bike" 2, "🏪 market" 1}


;; Same pattern with real payment data:
(def xf-spei-revenue
  (comp
    (filter #(= :spei (:method %)))
    (filter #(= :approved (:status %)))
    (map :amount)
    (map #(* % 1.16))))

;; Apply to ANY source — the transducer doesn't care:
(transduce xf-spei-revenue + 0 kafka-batch)                 ;; 🚚 Kafka
(transduce xf-spei-revenue + 0 api-payment)                 ;; 🚲 REST API
(transduce xf-spei-revenue + 0 db-results)                  ;; 🏪 Database
(into [] xf-spei-revenue future-source)                     ;; 🆕 Any new source!


;; This creates 4 intermediate collections:
(->> transactions
  (filter #(= :spei (:method %)))                           ;; collection #1
  (map :amount)                                             ;; collection #2
  (filter #(> % 1000))                                      ;; collection #3
  (map #(* % 1.16))                                         ;; collection #4
  (reduce + 0))                                             ;; final result


(def payments (vec (repeatedly 1000000
                     #(hash-map :method (rand-nth [:spei :credit-card :debit-card])
                        :amount (+ 100 (rand-int 50000))
                        :currency :MXN))))

;; Sequence version:
(time
  (->> payments
    (filter #(= :spei (:method %)))
    (map :amount)
    (filter #(> % 1000))
    (reduce + 0)))
;; ~200-400ms

(def xf-pay (comp (filter #(= :spei (:method %)))
              (map :amount)
              (filter #(> % 1000))))
;; Transducer version (preview — we'll explain the syntax next):
(time
  (transduce
    xf-pay
    + 0 payments))
;; ~100-200ms (typically 1.5-3x faster)


;; transduce xf f init coll
(transduce
  (comp (filter #(= :spei (:method %)))
        (map :amount))
  +        ;; reducing function
  0        ;; initial value
  payments)
;; => sum of all SPEI amounts
;; Count high-value SPEI payments:
(transduce
  (comp (filter #(= :spei (:method %)))
        (filter #(> (:amount %) 10000)))
  (fn
    ([] 0)              ;; 0-arity: init value
    ([result] result)   ;; 1-arity: completion
    ([result _input]    ;; 2-arity: step
     (inc result)))
  payments)



(def lazy-amounts
  (sequence
    (comp (filter #(= :spei (:method %)))
          (map :amount))
    payments))

(take 5 lazy-amounts)
;; => (1200 2500 800 15000 350)

;; eduction creates a reducible/iterable view — not realized until consumed
(def spei-amounts
  (eduction
    (comp (filter #(= :spei (:method %)))
          (map :amount))
    payments))

;; Every time you reduce it, it re-processes from scratch:
(reduce + 0 spei-amounts)
(reduce max 0 spei-amounts)
(into [] spei-amounts)


;; take and drop work as transducers:
(into []
  (comp (filter #(= :spei (:method %)))
        (take 5))
  payments)
;; => first 5 SPEI payments (stops processing after 5!)

;; This is MORE efficient than the seq version because it short-circuits:
;; The transducer signals "reduced" after 5 elements — no more processing.

(into []
  (comp (map :amount)
        (drop 100)
        (take 10))
  payments)
;; => amounts 101-110


;; dedupe — removes consecutive duplicates (stateful)
(into []
  (dedupe)
  [:spei :spei :debit-card :debit-card :spei])
;; => [:spei :debit-card :spei]

;; partition-all — groups elements (stateful)
(into []
  (comp (filter #(= :spei (:method %)))
        (partition-all 3))
  payments)
;; => [[p1 p2 p3] [p4 p5 p6] ...]  (groups of 3)

(def sample-payments
  (vec (repeatedly 100000
         (fn []
           {:id     (str "pay-" (random-uuid))
            :method (rand-nth [:spei :credit-card :debit-card])
            :amount (+ 50 (rand-int 50000))
            :status (rand-nth [:pending :approved :rejected])
            :currency :MXN}))))

(count sample-payments)
;; => 100000
(frequencies (map :method sample-payments))
;; => {:spei ~33333, :credit-card ~33333, :debit-card ~33334}

(defn total-spei-revenue-seq [payments]
  (->> payments
       (filter #(= :spei (:method %)))       ;; keep SPEI only
       (filter #(= :approved (:status %)))   ;; keep approved only
       (map :amount)                          ;; extract amount
       (map #(* % 1.16))                      ;; add IVA (16%)
       (reduce +)))                           ;; sum


(defn total-spei-revenue-xf [payments]
  (transduce
    (comp
      (filter #(= :spei (:method %)))
      (filter #(= :approved (:status %)))
      (map :amount)
      (map #(* % 1.16)))
    + 0
    payments))

(time (total-spei-revenue-xf sample-payments))


(dotimes [i 5]
  (let [t0 (System/nanoTime)
        _  (total-spei-revenue-seq sample-payments)
        t1 (System/nanoTime)
        _  (total-spei-revenue-xf sample-payments)
        t2 (System/nanoTime)
        ms-seq (/ (- t1 t0) 1e6)
        ms-xf  (/ (- t2 t1) 1e6)]
    (println (format "Run %d — SEQ: %.1fms | XF: %.1fms | speedup: %.1fx"
                     (inc i) ms-seq ms-xf (/ ms-seq ms-xf)))))

(def my-box (volatile! 0))
@my-box                     ;; => 0  (read)
(vreset! my-box 42)         ;; set to 42
@my-box                     ;; => 42
(vswap! my-box inc)         ;; apply inc
@my-box                     ;; => 43
(vswap! my-box + 10)        ;; apply (+ current 10)
@my-box                     ;; => 53
;; volatile! = create, @v = read, vreset! = set, vswap! = update


(defn batch-by-method []
  (fn [rf]
    (let [batch (volatile! [])
          current-method (volatile! nil)]
      (fn
        ([] (rf))
        ([result]
         (let [final (if (seq @batch)
                       (rf result {:method   @current-method
                                   :payments @batch
                                   :total    (reduce + (map :amount @batch))})
                       result)]
           (rf final)))
        ([result input]
         (let [m (:method input)]
           (if (= m @current-method)
             ;; SAME method → add to current batch
             (do (vswap! batch conj input) result)
             ;; DIFFERENT method → emit old batch, start new one
             (let [prev-result (if (seq @batch)
                                 (rf result {:method   @current-method
                                             :payments @batch
                                             :total    (reduce + (map :amount @batch))})
                                 result)]
               (vreset! batch [input])
               (vreset! current-method m)
               prev-result))))))))


(into []
  (batch-by-method)
  [{:method :spei       :amount 100}
   {:method :spei       :amount 200}
   {:method :debit-card :amount 50}
   {:method :debit-card :amount 75}
   {:method :spei       :amount 300}])
