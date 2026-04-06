(ns restaurant.cocina
  "Multimétodos para la lógica de preparación en cocina.
   Cada categoría tiene su propia implementación SIN modificar código existente.
   Agregar una categoría nueva = solo agregar un defmethod."
  (:require [restaurant.dominio :refer [tiempo-preparacion]]))

;; Subjeto 1 — multimétodos
;;
;; Función de despacho: :categoria (keyword que extrae el valor del mapa).
;; Principio Open/Closed: agregar :desayuno no requiere editar este archivo.

(defmulti preparar-platillo
  "Despacha la preparación de una orden según su :categoria.
   Cualquiera puede agregar nuevas categorías con defmethod desde cualquier namespace."
  :categoria)

(defmethod preparar-platillo :entrada [orden]
  "Entradas: preparación rápida en área de cocina fría."
  (println (str "  🥗 [ENTRADA]      "
                (:nombre-platillo orden)
                " — " (tiempo-preparacion (:platillo orden)) " min"))
  (assoc orden :estado :cocinando :area :cocina-fria))

(defmethod preparar-platillo :plato-fuerte [orden]
  "Platos fuertes: requieren parrilla o estufa principal."
  (println (str "  🍖 [PLATO FUERTE] "
                (:nombre-platillo orden)
                " — " (tiempo-preparacion (:platillo orden)) " min en parrilla"))
  (assoc orden :estado :cocinando :area :parrilla))

(defmethod preparar-platillo :postre [orden]
  "Postres: área de repostería separada de la cocina caliente."
  (println (str "  🍰 [POSTRE]       "
                (:nombre-platillo orden)
                " — " (tiempo-preparacion (:platillo orden)) " min"))
  (assoc orden :estado :cocinando :area :reposteria))

(defmethod preparar-platillo :bebida [orden]
  "Bebidas: barra de bebidas, casi instantáneo."
  (println (str "  🥤 [BEBIDA]       "
                (:nombre-platillo orden)
                " — " (tiempo-preparacion (:platillo orden)) " min en barra"))
  (assoc orden :estado :cocinando :area :barra))

(defmethod preparar-platillo :combo [orden]
  "Combos: coordinación entre múltiples áreas de la cocina."
  (println (str "  🍽️  [COMBO]        "
                (:nombre-platillo orden)
                " — " (tiempo-preparacion (:platillo orden)) " min (varias áreas)"))
  (assoc orden :estado :cocinando :area :coordinacion))

;; Categoría desconocida: el chef principal decide
(defmethod preparar-platillo :default [orden]
  "Para cualquier categoría no registrada."
  (println (str "  ❓ [ESPECIAL]     " (:nombre-platillo orden)))
  (assoc orden :estado :cocinando :area :chef-principal))
