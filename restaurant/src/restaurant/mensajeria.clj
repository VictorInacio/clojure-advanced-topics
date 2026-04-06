(ns restaurant.mensajeria
  "Sistema de mensajería asíncrona del restaurante.

   Sujeto 7 (básico): canales, go-loops, producer/consumer.
   Sujeto 8 (avanzado): pub/sub para routing de eventos, alts! para timeout."
  (:require [clojure.core.async :as async
             :refer [chan go go-loop >! <! >!! close!
                     pub sub timeout alts!]]
            [restaurant.cocina  :refer [preparar-platillo]]
            [restaurant.estado  :refer [procesar-pago! registrar-venta!]]
            [restaurant.dominio :refer [tiempo-preparacion]]))

;; Sujeto 7 — core.async básico: canales

;; Bus central de eventos del restaurante
;; Todos los eventos (órdenes, alertas) pasan por aquí
(def ^:private bus-eventos-ch (chan 100))

;; Sujeto 8 — core.async avanzado: pub/sub
;;
;; pub despacha mensajes del bus a suscriptores según :tipo-evento.
;; Diferencia con mult/tap: pub es SELECTIVO (cada suscriptor recibe
;; solo su tópico), mult es BROADCAST (todos reciben todo).

;; Publicación que enruta por :tipo-evento
(def ^:private bus-pub (pub bus-eventos-ch :tipo-evento))

;; Canales suscriptores — cada uno recibe solo su tópico
(def ^:private cocina-sub-ch  (chan 20))  ;; :nueva-orden
(def ^:private caja-sub-ch    (chan 20))  ;; :nueva-orden
(def ^:private mesero-sub-ch  (chan 20))  ;; :orden-lista
(def ^:private gerente-sub-ch (chan 20))  ;; :alerta

;; Registrar suscripciones
(sub bus-pub :nueva-orden cocina-sub-ch)
(sub bus-pub :nueva-orden caja-sub-ch)
(sub bus-pub :orden-lista mesero-sub-ch)
(sub bus-pub :alerta      gerente-sub-ch)

;; Workers: go-loops con alts! para timeout
;;
;; Sujeto 8 — alts!:
;; En el worker de cocina usamos alts! para esperar en DOS canales a la vez:
;; 1. cocina-sub-ch → llegó una orden
;; 2. (timeout 20000) → 20 segundos sin actividad
;; Toma del primero que tenga datos, sin bloquear el thread.

(defn- iniciar-worker-cocina!
  "Go-loop que procesa órdenes de cocina usando alts! para manejo de timeout."
  []
  (go-loop []
    ;; alts! espera en múltiples canales y retorna [valor canal-que-entregó]
    (let [[evento port] (alts! [cocina-sub-ch (timeout 20000)])]
      (cond
        ;; Caso 1: llegó una orden antes del timeout
        (= port cocina-sub-ch)
        (do
          (when evento
            (let [orden (:payload evento)]
              (println (str "\n👨‍🍳 COCINA recibió orden #" (:id orden)
                            " — Mesa " (:mesa orden)))
              (try
                ;; Multimethod (sujeto 1): despacha según :categoria
                (let [orden-proc (preparar-platillo orden)
                      minutos    (tiempo-preparacion (:platillo orden))]
                  ;; Simular tiempo de preparación (200ms por minuto en demo)
                  (<! (timeout (* minutos 200)))
                  ;; Notificar que la orden está lista vía pub/sub
                  (>! bus-eventos-ch
                      {:tipo-evento :orden-lista
                       :payload     (assoc orden-proc :estado :lista)}))
                (catch Exception e
                  (println "  ❌ ERROR en cocina:" (.getMessage e))))))
          (recur))

        ;; Caso 2: timeout — cocina sin actividad por 20 segundos
        :else
        (do
          (println "👨‍🍳 COCINA: En espera de órdenes...")
          (recur))))))

(defn- iniciar-worker-caja!
  "Go-loop que procesa cobros con atomicidad STM (sujeto 4)."
  []
  (go-loop []
    (when-let [evento (<! caja-sub-ch)]
      (let [orden (:payload evento)]
        (try
          ;; procesar-pago! usa dosync internamente (sujeto 4)
          (procesar-pago! (:total orden) (:categoria orden))
          ;; registrar-venta! usa send al agent (sujeto 5)
          (registrar-venta! (assoc orden :estado :completada))
          (println (str "💰 CAJA: Orden #" (:id orden)
                        " cobrada — $" (:total orden)))
          (catch Exception e
            (println (str "  ❌ CAJA (inventario agotado?): " (.getMessage e)))
            (registrar-venta! (assoc orden :estado :fallida)))))
      (recur))))

(defn- iniciar-worker-mesero!
  "Go-loop que notifica al mesero cuando un platillo está listo."
  []
  (go-loop []
    (when-let [evento (<! mesero-sub-ch)]
      (let [orden (:payload evento)]
        (println (str "🛎️  MESERO: Orden #" (:id orden)
                      " lista → Mesa " (:mesa orden)
                      " — " (:nombre-platillo orden))))
      (recur))))

(defn- iniciar-worker-gerente!
  "Go-loop para alertas de inventario y mensajes del sistema."
  []
  (go-loop []
    (when-let [evento (<! gerente-sub-ch)]
      (println (str "⚠️  GERENTE ALERTA: " (:payload evento)))
      (recur))))

;; API pública del módulo

(defn iniciar-mensajeria!
  "Arranca todos los workers. Debe llamarse ANTES del servidor HTTP."
  []
  (println "🚀 Iniciando workers de mensajería...")
  (iniciar-worker-cocina!)
  (iniciar-worker-caja!)
  (iniciar-worker-mesero!)
  (iniciar-worker-gerente!)
  (println "✅ Workers activos: cocina, caja, mesero, gerente"))

(defn enviar-orden!
  "Publica una nueva orden en el bus de eventos. No bloquea al llamador."
  [orden]
  (>!! bus-eventos-ch {:tipo-evento :nueva-orden :payload orden}))

(defn enviar-alerta!
  "Publica una alerta para el gerente."
  [mensaje]
  (>!! bus-eventos-ch {:tipo-evento :alerta :payload mensaje}))
