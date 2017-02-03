(ns bones.editable.helpers
  "helpers to provide closures around a path to insert into the db without
  having to provide the whole path")

(defn scope-with [scope]
  (fn [& args] (into scope args)))

(defn e-scope
  "builds a standard event-vec form another event-vec.
  eases the transitions from :request/commnd to :editable event for example"
  [event-vec & sub]
  ;; standard event-vec structure
  (let [[event-name e-type identifier opts] event-vec
        _identifier (if (map? identifier) (:identifier opts) identifier)]
    (into [:editable e-type _identifier] sub)))

(defn editable-reset
  ([[_ etype identifier inputs]]
   (editable-reset etype identifier (or inputs {})))
  ([etype identifier inputs]
   [:editable
    [:editable etype identifier :inputs inputs]
    [:editable etype identifier :errors {}]
    [:editable etype identifier :state {}]]))

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

(defn sorting  [form-type [sort-key order]]
  [:editable form-type :_meta [sort-key order]])

(defn filtering [form-type [filter-key value]]
  [:editable form-type :_meta [filter-key value]])

;; spec conformer
(defn parse-int [x]
  (if (integer? x)
    x
    (if (string? x)
      (let [n (js/parseInt x)]
        (if (integer? n)
          n
          :cljs.spec/invalid))
      :cljs.spec/invalid)))
