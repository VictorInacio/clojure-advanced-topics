(ns java-interop)

;; You've been using Java interop since Class 1:
(println "Hello")                                           ;; calls System.out.println
(System/currentTimeMillis)                                  ;; static method call
(Thread/sleep 100)

(def my-thread (new Thread))
(.getName my-thread)                                        ;; static method call
(.getName (Thread/currentThread))                           ;; instance method call
(str "abc")


;; .method on an object — the dot comes FIRST:
(.toUpperCase "hello")
;; => "HELLO"

(.substring "SPEI-Transfer-001" 0 4)
;; => "SPEI"

;; Chain methods — read inside-out (like function composition):
(.contains (.toUpperCase "spei-transfer") "SPEI")
;; => true

;; Get the class of any value:
(.getClass 42)
(.getClass 42.1)
;; => java.lang.Long

(.getClass "hello")
;; => java.lang.String

(.getClass {:a 1})
;; clojure.lang.PersistentArrayMap


;; Static method — Class/method:
(Math/sqrt 144)
;; => 12.0

(Math/pow 2 10)
;; => 1024.0

(Integer/parseInt "42")
;; => 42

(Long/parseLong "1234567890")
;; => 1234567890

;; Static fields — Class/FIELD (no parentheses!):
Math/PI
;; => 3.141592653589793

Integer/MAX_VALUE
;; => 2147483647

Long/MAX_VALUE
;; => 9223372036854775807


;; ClassName. — note the trailing dot:
(java.util.Date.)
(new java.util.Date)
;; => #inst "2026-..."

(java.util.ArrayList.)
;; => []

(java.util.ArrayList. [1 2 3])
;; => [1 2 3]

(StringBuilder. "Payment: ")
;; => #object[java.lang.StringBuilder "Payment: "]

;; String constructor with bytes:
(String. (.getBytes "Hola SPEI" "UTF-8") "UTF-8")
;; => "Hola SPEI"
(.getBytes "Hola SPEI" "UTF-8")


;; Without doto (repetitive):
(let [sb (StringBuilder.)]
  (.append sb "Payment: ")
  (.append sb "SPEI")
  (.append sb " $1,200")
  (.toString sb))
;; => "Payment: SPEI $1,200"

;; With doto (clean):

(def my-sb (doto (StringBuilder.)
             (.append "Payment: ")
             (.append "SPEI")
             (.append " $1,200")))
(-> (doto (StringBuilder.)
      (.append "Payment: ")
      (.append "SPEI")
      (.append " $1,200"))
  (.toString))


(.toString my-sb)
;; => "Payment: SPEI $1,200"

;; doto returns the OBJECT, not the return value of the last method.
;; That's why we use -> to chain .toString at the end.

;; Build a Java ArrayList:
(doto (java.util.ArrayList.)
  (.add {:id "s1" :method :spei :amount 1200})
  (.add {:id "c1" :method :credit-card :amount 500})
  (.add {:id "d1" :method :debit-card :amount 350}))
;; => [{:id "s1", ...} {:id "c1", ...} {:id "d1", ...}]

;; Without .. (nested, hard to read):
(.toString (.append (.append (StringBuilder.) "SPEI") "-001"))
;; => "SPEI-001"

;; With .. (reads left to right):
(.. (StringBuilder.) (append "SPEI") (append "-001") (toString))
;; => "SPEI-001"

;; Real example — get system properties:
(.. System (getProperties) #_(get "java.version"))
;; => "17.0.6" (or your Java version)

(java.time.Instant/now)

;; With import:
(import '[java.time Instant LocalDate Duration])

(Instant/now)


(import '[java.time Instant LocalDate LocalDateTime ZonedDateTime
                     Duration Period ZoneId]
        '[java.time.format DateTimeFormatter]
        '[java.time.temporal ChronoUnit])

;; Current instant:
(Instant/now)
;; => #object[java.time.Instant "2026-03-12T21:56:51Z"]

;; From epoch millis (like System/currentTimeMillis):
(Instant/ofEpochMilli 1710288000000)
;; => #object[java.time.Instant "2024-03-13T00:00:00Z"]

;; Parse an ISO string:
(Instant/parse "2026-03-12T15:30:00Z")
;; => #object[java.time.Instant "2026-03-12T15:30:00Z"]

;; Compare instants:
(let [now   (Instant/now)
      later (.plusSeconds now 60)]
  (.isBefore now later))
;; => true

;; Millis between two instants:
(let [start (Instant/now)
      _     (Thread/sleep 10)
      end   (Instant/now)]
  (.between ChronoUnit/MILLIS start end))
;; => ~10 (approximately)


;; Today:
(LocalDate/now)
;; => #object[java.time.LocalDate "2026-03-12"]

;; Specific date:
(LocalDate/of 2026 3 15)
;; => #object[java.time.LocalDate "2026-03-15"]

;; Parse:
(LocalDate/parse "2026-03-15")
;; => #object[java.time.LocalDate "2026-03-15"]

;; Date arithmetic:
(let [today       (LocalDate/now)
      settlement  (.plusDays today 3)
      due-date    (.plusMonths today 1)]
  {:today      (str today)
   :settlement (str settlement)
   :due-date   (str due-date)})
;; => {:today "2026-03-12", :settlement "2026-03-15", :due-date "2026-04-12"}

;; Day of week:
(.getDayOfWeek (LocalDate/of 2026 3 15))
;; => #object[java.time.DayOfWeek "SUNDAY"]



(import '[java.util Comparator Collections ArrayList])

;; Sort payments by amount (ascending):
(def by-amount
  (reify Comparator
    (compare [_ a b]
      (Long/compare (:amount a) (:amount b)))))

(let [payments [{:id "s1" :amount 1200}
                {:id "c1" :amount 500}
                {:id "d1" :amount 8000}
                {:id "s2" :amount 350}]
      java-list (ArrayList. payments)]
  (Collections/sort java-list by-amount)
  (vec java-list))
;; => [{:id "s2", :amount 350} {:id "c1", :amount 500}
;;     {:id "s1", :amount 1200} {:id "d1", :amount 8000}]

;; Or simply use Clojure's sort-by (which uses Comparator under the hood):
(sort-by :amount [{:id "s1" :amount 1200}
                  {:id "c1" :amount 500}])
;; => ({:id "c1", :amount 500} {:id "s1", :amount 1200})


(import '[java.util.concurrent Callable Executors Future])

;; Runnable — no return value:
(let [task (reify Runnable
             (run [_]
               (println "Processing payment on thread:"
                        (.getName (Thread/currentThread)))))]
  (.start (Thread. task)))
;; Prints: "Processing payment on thread: Thread-N"

;; Callable — returns a value:
(let [task (reify Callable
             (call [_]
               {:status :authorized
                :token  (str "T-" (random-uuid))
                :thread (.getName (Thread/currentThread))}))]
  (.call task))
;; => {:status :authorized, :token "T-...", :thread "..."}

;; Thread pool for payment processing:
(let [pool  (Executors/newFixedThreadPool 3)
      tasks (mapv (fn [payment]
                    (reify Callable
                      (call [_]
                        (Thread/sleep 2000)  ;; simulate work
                        (assoc payment :status :authorized
                                       :thread (.getName (Thread/currentThread))))))
                  [{:id "s1" :method :spei :amount 1200}
                   {:id "c1" :method :credit-card :amount 500}
                   {:id "d1" :method :debit-card :amount 350}
                   {:id "s1" :method :spei :amount 1200}
                                      {:id "c1" :method :credit-card :amount 500}
                                      {:id "d1" :method :debit-card :amount 350}
                   {:id "s1" :method :spei :amount 1200}
                                      {:id "c1" :method :credit-card :amount 500}
                                      {:id "d1" :method :debit-card :amount 350}
                   {:id "s1" :method :spei :amount 1200}
                                      {:id "c1" :method :credit-card :amount 500}
                                      {:id "d1" :method :debit-card :amount 350}])
      futures (.invokeAll pool tasks)]
  (mapv #(.get ^Future %) futures))
;; => [{:id "s1", :method :spei, :amount 1200, :status :authorized, :thread "pool-N-thread-1"}
;;     {:id "c1", :method :credit-card, :amount 500, :status :authorized, :thread "pool-N-thread-2"}
;;     {:id "d1", :method :debit-card, :amount 350, :status :authorized, :thread "pool-N-thread-3"}]


;; Implement both Runnable and Object:
(let [task (reify
             Runnable
             (run [_]
               (println "Running payment batch..."))
             (run2 [_]
                            (println "Running payment batch..."))
             (run3 [_]
                            (println "Running payment batch..."))

             Object
             (toString [_]
               "PaymentBatchTask[pending]"))]
  (println "Task:" (.toString task))
  (.run task))

;; Task: PaymentBatchTask[pending]
;; Running payment batch...


;; Extend Thread (a class, not an interface):
(let [t (proxy [Thread] []
          (run []
            (println "Payment worker running on:"
                     (.getName (Thread/currentThread)))))]
  (.start t)
  #_(.join t 1000))
;; Prints: "Payment worker running on: Thread-N"


;; Extend java.io.FilterInputStream to log reads:
(import '[java.io ByteArrayInputStream FilterInputStream])

(defn logging-input-stream [^bytes data]
  (let [inner (ByteArrayInputStream. data)]
    (proxy [FilterInputStream] [inner]
      (read
        ([]
         (let [b (proxy-super read)]
           (when (>= b 0)
             (print (char b)))
           b))))))

(let [stream (logging-input-stream (.getBytes "SPEI" "UTF-8"))]
  (while (>= (.read stream) 0)))
;; Prints: SPEI


;; Enable reflection warnings:
(set! *warn-on-reflection* true)

;; This triggers a reflection warning:
(defn slow-upper [s]
  (.toUpperCase s))
;; WARNING: reference to field toUpperCase can't be resolved.

;; Clojure doesn't know `s` is a String, so it uses reflection
;; to find .toUpperCase at RUNTIME — every single call.


;; Add a type hint with ^:
(defn fast-upper [^String s]
  (.toUpperCase s))
;; No warning! Clojure generates a direct method call.

;; On return values:
(defn ^String payment-id [^String prefix ^long seq-num]
  (str prefix "-" (format "%06d" seq-num)))

(defn ^String payment-id [^String prefix ^long seq-num]
  (str prefix "-" (format "%06d" seq-num)))

(payment-id "SPEI" 42)
;; => "SPEI-000042"


;; Without type hints (reflection):
(defn slow-length [s]
  (.length s))

;; With type hints (direct call):
(defn fast-length [^String s]
  (.length s))

;; Benchmark:
(let [s "SPEI-Payment-Authorization-Token-ABC123"]
  (time (dotimes [_ 1000000] (slow-length s)))
  (time (dotimes [_ 1000000] (fast-length s))))
;; slow: ~1500ms (with reflection)
;; fast: ~5ms (direct method call)
;; ~300x speedup!


;; Clojure collections implement Java interfaces automatically:
(instance? java.util.List [1 2 3])          ;; => true
(instance? java.util.Map {:a 1 :b 2})      ;; => true
(instance? java.util.Set #{1 2 3})          ;; => true
(instance? Iterable [1 2 3])               ;; => true

;; So you can pass them to Java methods directly:
(Collections/unmodifiableList [1 2 3])
;; => [1 2 3]

(Collections/max [3 1 4 1 5 9 2 6])
;; => 9


;; ArrayList from Clojure vector:
(let [payments (ArrayList. [{:id "s1" :amount 1200}
                             {:id "c1" :amount 500}])]
  (.add payments {:id "d1" :amount 350})
  (.size payments))
;; => 3

;; HashMap from Clojure map:
(let [m (java.util.HashMap. {:name "SPEI" :code "SP" :active true})]
  (.put m :version 2)
  [(.get m :version) (.get m :name)])
;; => "SPEI"


;; Java Streams are like lazy sequences with parallel support:
(import '[java.util.stream Collectors])

(let [payments [{:id "s1" :amount 1200}
                {:id "c1" :amount 500}
                {:id "d1" :amount 8000}
                {:id "s2" :amount 350}]
      total (-> payments
                (.stream)
                (.filter (reify java.util.function.Predicate
                           (test [_ p] (> (:amount p) 1000))))
                (.mapToLong (reify java.util.function.ToLongFunction
                              (applyAsLong [_ p] (:amount p))))
                (.sum))]
  total)

;; LINQ C#
;; => 9200v

;; Java's UUID — already available:
(java.util.UUID/randomUUID)
;; => #uuid "a1b2c3d4-..."

;; Or use Clojure's built-in:
(random-uuid)
;; => #uuid "e5f6a7b8-..."

;; They're the same type:
(type (random-uuid))
;; => java.util.UUID

;; Parse from string:
(java.util.UUID/fromString "550e8400-e29b-41d4-a716-446655440000")
;; => #uuid "550e8400-e29b-41d4-a716-446655440000"

