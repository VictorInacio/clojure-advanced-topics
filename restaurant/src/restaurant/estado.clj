(ns restaurant.estado
  "Estado mutable del restaurante.

   Refs + dosync: para cambios coordinados (inventario + caja deben cambiar juntos).
   Agent: para el log de ventas asíncrono (el mesero no espera que se escriba el log)."
  (:import [java.time LocalTime]
           [java.time.format DateTimeFormatter]))

;; Sujeto 4 — REFS / STM
;;
;; Problema que resuelven:
;;   Al cobrar una orden, deben ocurrir dos cosas juntas:
;;     1. Descontar ingredientes del inventario
;;     2. Sumar el total a la caja
;;   Si falla una, la otra NO debe aplicarse. Esto es atomicidad.
;;   Con dos swap! independientes, podríamos quedar en estado inconsistente.
;;   dosync garantiza que ambos cambios ocurren juntos o ninguno.

;; Inventario de ingredientes clave
;; :validator garantiza que NUNCA llegue a negativo — si lo intenta, el STM lo rechaza
(def inventario
  (ref {:tortillas    200
        :carne-kg      50.0
        :verduras-kg   30.0
        :refrescos    100
        :cafe          80}
       :validator
       (fn [inv]
         (every? #(>= % 0) (vals inv)))))

;; Caja registradora: acumulado de ventas del día
(def caja
  (ref 0.0
       :validator (fn [monto] (>= monto 0.0))))

;; Mesas disponibles (1 a 10)
(def mesas-disponibles
  (ref (set (range 1 11))))

(defn- consumir-ingredientes!
  "Descuenta ingredientes según categoría. DEBE llamarse dentro de dosync."
  [categoria]
  (case categoria
    :entrada      (do (alter inventario update :verduras-kg - 0.1)
                      (alter inventario update :tortillas   - 2))
    :plato-fuerte (do (alter inventario update :carne-kg    - 0.3)
                      (alter inventario update :verduras-kg - 0.2))
    :postre       nil   ;; postres no afectan inventario principal
    :bebida       (do (alter inventario update :refrescos   - 1)
                      (alter inventario update :cafe        - 1))
    :combo        (do (alter inventario update :carne-kg    - 0.2)
                      (alter inventario update :tortillas   - 1)
                      (alter inventario update :refrescos   - 1))
    nil))

(defn procesar-pago!
  "Registra el pago de una orden de forma ATÓMICA.

   Garantía STM:
   - Si el inventario NO alcanza → el STM lanza excepción, caja NO cambia.
   - Si alcanza → inventario y caja cambian juntos en una sola transacción.
   - Nunca hay un estado intermedio (inventario descontado, caja sin sumar)."
  [total categoria]
  (dosync
    ;; alter solo funciona dentro de dosync
   (consumir-ingredientes! categoria) ;; Paso 1: puede fallar
   (alter caja + total)               ;; Paso 2: solo ocurre si paso 1 tuvo éxito
   :ok))

(defn asignar-mesa!
  "Asigna la mesa de número más bajo disponible de forma atómica."
  []
  (dosync
   (when-let [mesa (first (sort @mesas-disponibles))]
     (alter mesas-disponibles disj mesa)
     mesa)))

(defn liberar-mesa!
  "Devuelve una mesa al pool de disponibles."
  [num-mesa]
  (dosync
   (alter mesas-disponibles conj num-mesa)
   num-mesa))

;; Sujeto 5 — agentes
;;
;; El mesero cobra y registra la venta.
;; Escribir el log no debe frenar al mesero — con agent ocurre en background.
;; :error-mode :continue → si falla una escritura, el agent sigue vivo.

(def ^:private fmt-hora
  (DateTimeFormatter/ofPattern "HH:mm:ss"))

(def log-ventas
  (agent []
         :error-mode    :continue
         :error-handler (fn [_ag ex]
                          (println "⚠️  Error en log-ventas:" (.getMessage ex)))))

(defn registrar-venta!
  "Agrega una entrada al log de ventas de forma asíncrona (no bloquea al mesero)."
  [orden]
  ;; send usa el thread pool fijo (CPU-bound: solo manipulación de datos)
  (send log-ventas conj
        {:orden-id  (:id orden)
         :mesa      (:mesa orden)
         :platillo  (:nombre-platillo orden)
         :categoria (:categoria orden)
         :total     (:total orden)
         :estado    (:estado orden)
         :hora      (.format (LocalTime/now) fmt-hora)}))
