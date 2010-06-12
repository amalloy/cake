(ns bake
  (:require clojure.main
            [cake.swank :as swank]
            [cake.server :as server]))

(defn eval-multi [form]
  (clojure.main/with-bindings
    (if (vector? form)
      (doseq [f form] (eval f))
      (eval form))))

(defn quit []
  (when (= 0 (swank/num-connections))
    (server/quit)))

(defn start-server [port]
  (server/create port eval-multi :quit quit)
  (when-let [opts (swank/config)]
    (when-not (= false (:auto-start opts))
      (swank/start opts)))
  nil)
