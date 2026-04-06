(ns restaurant.core
  "Punto de entrada del Sistema de Gestión del Restaurante.
   Coordina el arranque: workers de mensajería → servidor HTTP."
  (:require [restaurant.mensajeria :refer [iniciar-mensajeria!]]
            [restaurant.api       :refer [iniciar-servidor!]])
  (:gen-class))

;; defonce: el servidor sobrevive un reload del namespace en el REPL
(defonce ^:private servidor (atom nil))

(defn start!
  "Inicia el sistema completo. Seguro llamar desde el REPL."
  ([] (start! 8080))
  ([puerto]
   (when @servidor
     (throw (ex-info "Ya hay un servidor corriendo. Llama (stop!) primero." {})))
   (println "\n═══════════════════════════════════════════")
   (println "  🍽️   SISTEMA DE GESTIÓN DEL RESTAURANTE")
   (println "═══════════════════════════════════════════")
   ;; 1. Workers de mensajería PRIMERO (antes de aceptar peticiones HTTP)
   (iniciar-mensajeria!)
   ;; 2. Servidor HTTP
   (reset! servidor (iniciar-servidor! puerto))
   (println "\n📋 Endpoints disponibles:")
   (println "  POST /orden          — Registrar nueva orden")
   (println "  GET  /inventario     — Ver inventario y caja")
   (println "  POST /mesa/liberar   — Liberar una mesa")
   (println "  GET  /reporte        — Reporte del día")
   (println "  POST /alerta         — Enviar alerta al gerente")
   (println "\n✅ Sistema listo!\n")))

(defn stop!
  "Detiene el servidor HTTP."
  []
  (when-let [srv @servidor]
    (.stop srv 0)
    (reset! servidor nil)
    (println "🛑 Servidor detenido.")))

(defn -main
  "Ejecución desde terminal: clj -m restaurante.core [puerto]"
  [& args]
  (let [puerto (if (first args) (Integer/parseInt (first args)) 8080)]
    (start! puerto)
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop!))
    (Thread/sleep Long/MAX_VALUE)))
