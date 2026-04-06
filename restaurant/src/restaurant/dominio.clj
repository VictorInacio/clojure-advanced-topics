(ns restaurant.dominio
  "Dominio principal: define el protocolo Servible y la macro defplatillo.
   La macro genera automáticamente tipos de platillos con su implementación.")

;; Sujeto 2 — protocolo
;;
;; defprotocol genera una interfaz Java real en el JVM.
;; Cualquier ítem del menú DEBE implementar estas tres funciones.
;; Dispatch es por tipo del primer argumento → velocidad JVM.

(defprotocol Servible
  "Contrato común para todos los ítems del menú del restaurante."
  (precio [this]
    "Devuelve el precio base en pesos MXN.")
  (describir [this]
    "Devuelve descripción legible para el ticket o pantalla.")
  (tiempo-preparacion [this]
    "Devuelve tiempo estimado de preparación en minutos."))

;; Sujetos 3 y 10 — macro
;;
;; Por qué macro y no función:
;;   - Necesita crear un defrecord en tiempo de compilación (tipos no existen en runtime).
;;   - Necesita extender el protocolo Servible para ese tipo nuevo.
;;   - Una función no puede generar tipos nuevos idiomáticamente.
;;
;; Uso:
;;   (defplatillo PlatoFuerte :plato-fuerte 185.0 25)
;;
;; Genera automáticamente:
;;   1. (defrecord PlatoFuerte [id nombre ingredientes disponible])
;;   2. (extend-type PlatoFuerte Servible (precio ...) (describir ...) ...)

(defmacro defplatillo
  "Genera un tipo de platillo con su implementación del protocolo Servible.

   Parámetros:
   - nombre-tipo : símbolo PascalCase (ej. PlatoFuerte)
   - categoria   : keyword (:entrada, :plato-fuerte, :postre, :bebida, :combo)
   - precio-base : precio en MXN (double)
   - minutos     : tiempo de preparación (int)"
  [nombre-tipo categoria precio-base minutos]
  ;; El backtick (`) abre un syntax-quote: todo queda sin evaluar excepto lo marcado.
  ;; El ~ (tilde/unquote) evalúa la expresión inmediata.
  ;; El # al final de variables internas (result#) genera nombres únicos
  ;; para evitar colisiones con variables del código que usa la macro (higiene).
  `(do
     ;; 1. Crear el record con los campos de cualquier platillo
     (defrecord ~nombre-tipo
                [~'id           ;; Identificador único de esta instancia
                 ~'nombre       ;; Nombre comercial ("Tacos de Pastor")
                 ~'ingredientes ;; Vector de ingredientes usados
                 ~'disponible]) ;; ¿Está disponible en el menú?

     ;; 2. Extender el protocolo Servible para este tipo recién creado
     (extend-type ~nombre-tipo
       Servible
       (precio [_this#]
         ~precio-base)   ;; valor capturado en tiempo de expansión de macro

       (describir [this#]
         (str ~(str nombre-tipo) " — " (:nombre this#)
              (when-not (:disponible this#) " [NO DISPONIBLE]")))

       (tiempo-preparacion [_this#]
         ~minutos))))

;; Tipos generados por la macro
;;
;; Cada línea genera: defrecord + extend-type Servible

(defplatillo Entrada      :entrada       65.0   8)
(defplatillo PlatoFuerte  :plato-fuerte 185.0  25)
(defplatillo Postre       :postre        85.0  10)
(defplatillo Bebida       :bebida        45.0   3)
(defplatillo Combo        :combo        250.0  30)

;; Para ver la expansión en el REPL:
;; (macroexpand-1 '(defplatillo Taco :entrada 35.0 5))
