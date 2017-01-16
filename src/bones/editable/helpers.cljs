(ns bones.editable.helpers)

(defn editable-reset [etype identifier inputs]
  [:editable
   [:editable etype identifier :inputs inputs]
   [:editable etype identifier :errors {}]
   [:editable etype identifier :state {}]])

(defn editable-error [etype identifier error]
  [:editable
   [:editable etype identifier :errors error]
   [:editable etype identifier :state {}]])

(defn editable-response
  ([etype identifier response]
   (editable-response etype identifier response {}))
  ([etype identifier response inputs]
   (conj (editable-reset etype identifier inputs)
         [:editable etype identifier :response response])))

(defn editable-transform
  "transforms an event vector that will update the db"
  [evec & attrs]
  (let [[channel form-type identifier] evec]
    (into [:editable form-type identifier] attrs)))

(defn e-scope [event-vec & sub]
  ;; standard event-vec structure
  (let [[event-name e-type identifier opts] event-vec]
    (into [:editable e-type identifier] sub)))

(defn sorting  [form-type [sort-key order]]
  [:editable form-type :_meta [sort-key order]])

(defn filtering [form-type [filter-key value]]
  [:editable form-type :_meta [filter-key value]])
