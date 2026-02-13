(ns macros)

(+ 1 2)
(quote (+ 1 2))


(type '(+ 1 2))


(first '(+ 1 2))   ;; => +
(second '(+ 1 2))  ;; => 1
(count '(+ 1 2))

(type '+);; => 3

`(+ x 1)

(first `(+ 1 2))   ;; => +
(second `(+ x 2))  ;; => 1



(let [method :spei]
  `(process {:method method}))


(let [method :spei]
  (def method :spei)
  `(process {:method ~method}))


(eval (let [forms ['(println "step 1")
             '(println "step 2")
             '(println "step 3")]]
  `(do ~@forms)))

(+ [1 2 3])
(apply + [1 2 3])

(+ 1 2 3)
(apply + [1 2 3])


(let [items [1 2 3]]
  `(+ ~items))

(let [items [1 2 3]]
  `(+ ~@items))

;; Build a function call from parts:
(let [op '+
      args [10 20 30]]
  `(~op ~@args))
;; => (+ 10 20 30)

;; Build a let binding:
(let [var-name 'amount
      value    1200.0
      body     '(println amount)]
  `(let [~var-name ~value] ~body))
;; => (clojure.core/let [amount 1200.0] (println amount))

;; Evaluate the generated code:
(eval `(+ ~@[10 20 30]))

(defmacro my-when
  "Like when, but we built it ourselves."
  [condition & body]
  `(if ~condition
     (do ~@body)
     nil))

(macroexpand-1
  '(my-when (> amount 500)
     (println "High value!")
     (flag-for-review! payment)))

(macroexpand
  '(my-when (> amount 500)
     (println "High value!")
     (my-when true
       (println "Nested Stuff"))
     (flag-for-review! payment)))

(macroexpand-1
  '(my-when (> amount 500)
     (println "High value!")
     (my-when true
       (println "Nested Stuff"))
     (flag-for-review! payment)))


(if
  (> amount 500)
  (do
    (println "High value!")
    (flag-for-review! payment)) nil)


(def amount 1000)
(defn flag-for-review! [& args])
(defn payment [& args])

;; You write:
(my-when (> amount 500)
  (println "High value!")
  (println "High value!")
  (println "High value!")
  (println "High value!")
  (println "High value!")
  (println "High value!")
  (println "High value!")
  (println "High value!")
  (flag-for-review! payment))

;; The macro receives (NOT evaluated):
;;   condition = '(> amount 500)        — a list (symbol > , symbol amount, number 500)
;;   body      = ['(println "High value!") '(flag-for-review! payment)]

;; The macro returns this code:
(if (> amount 500)
  (do
    (println "High value!")
    (flag-for-review! payment))
  nil)


(defn log-result [label value]
  (println label "=>" value)
  value)

(log-result "sum" (+ 1 2))


(defmacro log-expr [expr]
  `(let [result# ~expr]
     (println '~expr "=>" result#)
     result#))

(log-expr (+ 1 2))

(macroexpand-1 '(log-expr (+ 1 2)))

(clojure.core/let
 [result__2901__auto__ (+ 1 2)]
 (clojure.core/println (quote (+ 1 2)) "=>" result__2901__auto__)
 result__2901__auto__)



(macroexpand-1
  '(and
     (valid-card? p)
     (check-balance! p)
     (authorize! p)))

(clojure.core/let
 [and__5598__auto__ (valid-card? p)]
 (if and__5598__auto__
   (clojure.core/and
     (check-balance! p)
     (authorize! p))
   and__5598__auto__))

(macroexpand
  '(and
     (valid-card? p)
     (check-balance! p)
     (authorize! p)))


;; Without threading:
(send-to-provider!
  (apply-fee
    (validate!
      (normalize-amount payment))))

(macroexpand-1
  '(-> &
     {:P payment}
      normalize-amount
      validate!
      apply-fee
      send-to-provider!))

(send-to-provider!
  (apply-fee
    (validate!
      (normalize-amount
        {:P payment}))))

(as-> & {}
     (filter :approved?)
     (map :amount)
     (reduce +))

(defmacro with-timing
  "Executes body and returns a map with :result and :elapsed-ms."
  [label & body]
  `(let [start#  (System/nanoTime)
         result# (do ~@body)
         end#    (System/nanoTime)
         ms#     (/ (- end# start#) 1e6)]
     (println (str ~label " took " ms# " ms"))
     {:result result# :elapsed-ms ms#}))

(with-timing
  (dotimes [execution-order 10]
    (println "Done " execution-order))
  "return val")


(defmacro with-validation
  "Validates payment. If valid, runs body. Otherwise returns error map."
  [payment validations & body]
  `(let [p# ~payment
         errors# (remove nil?
                   (map (fn [[check# msg#]]
                          (when-not (check# p#) msg#))
                        ~validations))]
     (if (seq errors#)
       {:ok? false :errors (vec errors#)}
       (do ~@body))))

(let [payment {:method :spei :spei-clabe "032180000118359719" :amount 1200.0}]
(with-validation
  payment
  [[#(pos? (:amount %))            "Amount must be positive"]
   [#(string? (:spei-clabe %))     "CLABE must be a string"]
   [#(re-matches #"\d{18}" (str (:spei-clabe %))) "CLABE must be 18 digits"]]
  (assoc payment :status :authorized :method :spei)))

(macroexpand
  '(with-validation
    payment
    [[#(pos? (:amount %)) "Amount must be positive"]
     [#(string? (:spei-clabe %)) "CLABE must be a string"]
     [#(re-matches #"\d{18}" (str (:spei-clabe %))) "CLABE must be 18 digits"]]
    (assoc payment :status :authorized :method :spei)))
