(ns restaurant.reportes
  "Pipeline de reportes usando transductores.

   Beneficio clave: cero colecciones intermedias.
   Un solo paso sobre los datos, sin importar cuántos pasos haya en el pipeline.
   El mismo transductor puede usarse con transduce, into, sequence, o en canales.")

;; Sujeto 6 — transductores
;;
;; Compara seq vs transductor para el mismo pipeline:
;;
;;   SEQ (crea 2 colecciones intermedias):
;;   (->> ventas
;;        (filter completada?)   ;; colección 1
;;        (map :total)           ;; colección 2
;;        (reduce + 0.0))        ;; resultado
;;
;;   TRANSDUCTOR (cero colecciones intermedias):
;;   (transduce (comp (filter completada?) (map :total)) + 0.0 ventas)

;; Transductor reutilizable: filtra completadas y extrae total.
;; Definido UNA VEZ — reusable en transduce, into, sequence, canales.
(def xf-completadas-totales
  (comp
   (filter #(= :completada (:estado %)))  ;; Paso 1: solo órdenes completadas
   (map :total)))                          ;; Paso 2: extraer el monto

(defn reporte-ingresos
  "Suma el total de ingresos de ventas completadas. Un solo paso, sin colecciones intermedias."
  [ventas]
  (transduce xf-completadas-totales + 0.0 ventas))

(defn reporte-por-categoria
  "Suma ingresos agrupados por categoría: {:entrada 500.0, :bebida 180.0, ...}"
  [ventas]
  (transduce
   (comp
    (filter #(= :completada (:estado %)))
    (map (fn [v] [(:categoria v) (:total v)])))
    ;; Reducing function con las 3 aridades del protocolo de transductores
   (fn
     ([]      {})    ;; 0-arity: valor inicial
     ([r]     r)     ;; 1-arity: completar (retornar acumulado final)
     ([acc [cat monto]]
      (update acc cat (fnil + 0.0) monto)))
   ventas))

(defn top-platillos
  "Devuelve los N platillos más pedidos con su frecuencia."
  [ventas n]
  (->> (into []
             (comp
              (filter #(= :completada (:estado %)))
              (map :platillo))
             ventas)
       frequencies
       (sort-by val >)
       (take n)
       (into {})))

(defn reporte-completo
  "Genera el reporte completo del día combinando múltiples pipelines de transductores."
  [ventas]
  {:total-ingresos  (reporte-ingresos ventas)
   :por-categoria   (reporte-por-categoria ventas)
   :total-ordenes   (count ventas)
   :completadas     (transduce
                     (filter #(= :completada (:estado %)))
                     (completing (fn [c _] (inc c)))
                     0 ventas)
   :top-5-platillos (top-platillos ventas 5)})
