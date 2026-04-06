(ns restaurant.api
  "API HTTP del restaurante usando el servidor HTTP del JDK (sin dependencias extra).

   Java Interop utilizado:
   - com.sun.net.httpserver.HttpServer  → servidor HTTP nativo del JDK
   - java.time.LocalDateTime            → timestamps en tickets
   - java.time.format.DateTimeFormatter → formatear fechas
   - java.io.BufferedReader/InputStreamReader → leer body de peticiones
   - reify HttpHandler                  → implementar interfaz Java en Clojure"
  (:require [clojure.edn :as edn]
            [restaurant.dominio   :refer [->Entrada ->PlatoFuerte ->Postre
                                          ->Bebida ->Combo
                                          precio describir tiempo-preparacion]]
            [restaurant.estado    :refer [inventario caja mesas-disponibles
                                          log-ventas asignar-mesa! liberar-mesa!]]
            [restaurant.mensajeria :refer [enviar-orden! enviar-alerta!]]
            [restaurant.reportes  :refer [reporte-completo]])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.io InputStreamReader BufferedReader]))

;; Sujeto 9 — java interop

;; contador de IDs (atom: simple, independiente, síncrono)
(def ^:private orden-counter (atom 0))

;; formatter de java.time para timestamps en tickets
(def ^:private ^DateTimeFormatter fmt-datetime
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn- timestamp-actual
  "Obtiene la fecha/hora actual formateada con java.time (Java Interop)."
  []
  (.format (LocalDateTime/now) fmt-datetime))

(defn- leer-body
  "Lee el body de la petición HTTP y lo parsea como EDN.
   Usa BufferedReader e InputStreamReader de java.io."
  [^com.sun.net.httpserver.HttpExchange exchange]
  (let [reader (BufferedReader.
                (InputStreamReader.
                 (.getRequestBody exchange) "UTF-8"))]
    (try (edn/read-string (slurp reader)) (catch Exception _ nil))))

(defn- enviar-respuesta
  "Envía una respuesta HTTP con cuerpo en formato EDN."
  [^com.sun.net.httpserver.HttpExchange exchange status body]
  (let [respuesta (pr-str body)
        bytes     (.getBytes ^String respuesta "UTF-8")]
    (.add (.getResponseHeaders exchange)
          "Content-Type" "application/edn; charset=utf-8")
    (.sendResponseHeaders exchange status (count bytes))
    (doto (.getResponseBody exchange) (.write bytes) (.close))))

(defn- construir-platillo
  "Crea la instancia del record de platillo. Los records fueron generados por defplatillo."
  [{:keys [tipo nombre]}]
  (let [id (swap! orden-counter inc)]
    (case tipo
      :entrada      (->Entrada     id (or nombre "Entrada del Chef")   [] true)
      :plato-fuerte (->PlatoFuerte id (or nombre "Plato del Día")      [] true)
      :postre       (->Postre      id (or nombre "Postre de la Casa")   [] true)
      :bebida       (->Bebida      id (or nombre "Bebida Natural")      [] true)
      :combo        (->Combo       id (or nombre "Combo Especial")      [] true)
      (->Entrada    id (or nombre "Especial") [] true))))

;; Handlers HTTP

(defn- handler-nueva-orden
  "POST /orden — Registra una orden y la envía al pipeline de mensajería."
  [exchange]
  (try
    (let [body     (leer-body exchange)
          platillo (construir-platillo (:platillo body))
          mesa     (or (:mesa body) (asignar-mesa!))
          orden    {:id              (swap! orden-counter inc)
                    :mesa            mesa
                    :platillo        platillo
                    :nombre-platillo (describir platillo)
                    :categoria       (or (:tipo (:platillo body)) :entrada)
                    :total           (precio platillo)
                    :hora            (timestamp-actual)
                    :estado          :recibida}]
      ;; publicar en el bus de eventos -> cocina y caja la reciben por pub/sub
      (enviar-orden! orden)
      (enviar-respuesta exchange 200
                        {:status          :ok
                         :orden-id        (:id orden)
                         :mesa            mesa
                         :platillo        (describir platillo)
                         :precio          (precio platillo)
                         :tiempo-estimado (str (tiempo-preparacion platillo) " min")
                         :hora            (:hora orden)}))
    (catch Exception e
      (enviar-respuesta exchange 400 {:status :error :mensaje (.getMessage e)}))))

(defn- handler-inventario
  "GET /inventario — Estado actual del inventario, caja y mesas."
  [exchange]
  (enviar-respuesta exchange 200
                    {:inventario        @inventario
                     :caja-total        @caja
                     :mesas-disponibles (sort @mesas-disponibles)
                     :hora              (timestamp-actual)}))

(defn- handler-liberar-mesa
  "POST /mesa/liberar — Libera una mesa al terminar el servicio."
  [exchange]
  (try
    (let [body (leer-body exchange)]
      (liberar-mesa! (:mesa body))
      (enviar-respuesta exchange 200
                        {:status :ok :mesa-liberada (:mesa body)}))
    (catch Exception e
      (enviar-respuesta exchange 400 {:status :error :mensaje (.getMessage e)}))))

(defn- handler-reporte
  "GET /reporte — Reporte del día usando transductores sobre el log del agent."
  [exchange]
  ;; await: espera que el agent termine operaciones pendientes
  (await log-ventas)
  (enviar-respuesta exchange 200
                    (assoc (reporte-completo @log-ventas)
                           :hora-reporte (timestamp-actual)
                           :caja-total   @caja)))

(defn- handler-alerta
  "POST /alerta — Envía alerta al gerente vía pub/sub."
  [exchange]
  (let [body (leer-body exchange)]
    (enviar-alerta! (or (:mensaje body) "Alerta sin mensaje"))
    (enviar-respuesta exchange 200 {:status :alerta-enviada})))

;; Servidor HTTP

(defn- crear-handler
  "Convierte una función Clojure en un HttpHandler de Java usando reify.
   reify implementa interfaces Java sin necesidad de definir una clase."
  [handler-fn]
  (reify HttpHandler
    (handle [_ exchange]
      (handler-fn exchange))))

(defn iniciar-servidor!
  "Inicia el servidor HTTP usando com.sun.net.httpserver del JDK.
   Sin dependencias extra: Jetty, Netty, Pedestal, etc."
  [puerto]
  (let [servidor (HttpServer/create
                  (InetSocketAddress. ^int puerto) 0)]
    (.createContext servidor "/orden"        (crear-handler handler-nueva-orden))
    (.createContext servidor "/inventario"   (crear-handler handler-inventario))
    (.createContext servidor "/mesa/liberar" (crear-handler handler-liberar-mesa))
    (.createContext servidor "/reporte"      (crear-handler handler-reporte))
    (.createContext servidor "/alerta"       (crear-handler handler-alerta))
    (.setExecutor servidor nil)  ;; usa el executor por defecto del JDK
    (.start servidor)
    (println (str "🌐 API HTTP corriendo en http://localhost:" puerto))
    servidor))
