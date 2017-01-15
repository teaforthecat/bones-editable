(ns bones.editable.request
  (:require [cljs.reader :refer [read-string]]
            [re-frame.core :as re-frame]
            [bones.editable.protocols :as p]
            [cljs.spec :as s]))


(def debug (if js/goog.DEBUG re-frame/debug))

(defn reg-fx [name func]
  (re-frame/reg-fx name func))

(def interceptors [debug (re-frame/inject-cofx :client)])

(defn reg-event-fx [event-name intercptrs func]
  (re-frame/reg-event-fx event-name intercptrs func))

(defn login-effect
  "makes the actual request using the injected client.
  the response is handled with a response handler"
  [{:keys [client args tap]}]
  (p/login client args tap))

(reg-fx :request/login login-effect)

(defn command-effect
  "call the client"
  [{:keys [client command args tap]}]
  (p/command client command args tap))

(reg-fx :request/command command-effect)

(defn query-effect
  "call the client"
  [{:keys [client args tap]}]
  (p/query client args tap))

(reg-fx :request/query query-effect)


(defn handler-wrapper [handler]
  (fn [cofx event-vec]
    (let [[event-name e-type identifier-or-args opts] event-vec
          dispatch-data (handler cofx event-vec)]
      (merge
       dispatch-data
       (if (:debug opts) {:log event-vec})))))

(defn resolve-args
  "merge data from three sources:
   - data sent in the options of the event
   - data from the inputs of the thing
   - data form the defaults of the thing
  conditionally, merging can be prevented with the :solo option
  In the case below, the resolved args would be only `{:x true}'
  (dispatch [X E-TYPE ID {:args {:x true} :solo true}])
  In this next case, all data will come from the inputs in the db
  (dispatch [X E-TYPE ID])"
  [cofx event-vec]
  (let [db (:db cofx)
        [event-name e-type identifier opts] event-vec ;; standard event-vec structure
        {:keys [args solo]} opts ; :merge is in opts but we don't want to overwrite the function
        merge-opt (:merge opts)
        merger (if (coll? merge-opt) merge-opt [merge-opt])
        defaults (get-in db [:editable e-type :_meta :defaults])
        inputs (get-in db [:editable e-type identifier :inputs])]
    ;; double arrow means reverse merge so top one wins
    (cond->> args
      (some #{:inputs} merger) (merge inputs)
      ;; only an identifier was given, options may still contain {:merge :defaults}
      (nil? args) (merge inputs)
      (some #{:defaults} merger) (merge defaults))))

(defn e-scope [event-vec & sub]
  ;; standard event-vec structure
  (let [[event-name e-type identifier opts] event-vec]
    (into [:editable e-type identifier] sub)))

(defn login-handler [cofx event-vec]
  (let [cmd :request/login
        args (resolve-args cofx event-vec)
        scope (e-scope event-vec)
        tap {:command cmd
             :args args
             :e-scope scope}
        ;; maybe make each attribute able to be pending?
        pending-event-vec (into scope [:state :pending true])]
    {:dispatch pending-event-vec
     ;; trigger the fct
     :request/login {:command cmd
                     :args args
                     :tap tap
                     :client (:client cofx)}}))

(reg-event-fx
 :request/login
 interceptors
 login-handler)


(defn command-handler [cofx event-vec]
  (let [[event-name e-type identifier opts] event-vec ;; standard event-vec structure
        cmd e-type ;; misnomer here, hmmm
        e-type (if (namespace e-type) (namespace e-type) e-type)
        ;; use updated event-vec with namespace as e-type
        new-event-vec [event-name e-type identifier opts]
        args (resolve-args cofx new-event-vec)
        scope (e-scope new-event-vec)
        tap {:command cmd
             :args args
             :e-scope scope}
        ;; maybe make each attribute able to be pending?
        pending-event-vec (into scope [:state :pending true])]
    {:dispatch pending-event-vec
     ;; trigger the fct
     :request/command {:command cmd
                       :args args
                       :tap tap
                       :client (:client cofx)}}))

(reg-event-fx
 :request/command
 interceptors
 command-handler)

(defn short-query-handler
  "simple query using args given in event"
  [cofx event-vec]
  (let [[event-name args tap] event-vec]
    {:request/query {:args args
                     :tap tap
                     :client (:client cofx)}}))

(defn long-query-handler [cofx event-vec]
  (let [[event-name e-type identifier opts] event-vec ;; standard event-vec structure
        query e-type ;; misnomer here, hmmm
        e-type (if (namespace e-type) (keyword (namespace e-type)) e-type)
        ;; use updated event-vec with namespace as e-type
        new-event-vec [event-name e-type identifier opts]
        _ (println new-event-vec)
        _ (println (:db cofx))
        args (resolve-args cofx new-event-vec)
        scope (e-scope new-event-vec)
        tap {:args args
             :query query
             :e-scope scope}
        ;; maybe make each attribute able to be pending?
        pending-event-vec (into scope [:state :pending true])]
    {:dispatch pending-event-vec
     ;; trigger the fct
     :request/query {:args args
                     :tap tap
                     :client (:client cofx)}}))

(defn query-handler [cofx event-vec]
  ;; the event args may or may not be the standard event-vec structure
  (let [[event-name e-type identifier opts] event-vec]
    (if (not (map? e-type)) ;;(= 4 (count event-vec))
      (if (map? identifier)
        (throw "non-standard event-vec, we have a problem")
        (long-query-handler cofx event-vec))
      (short-query-handler cofx event-vec))))

(reg-event-fx
 :request/query
 interceptors
 query-handler)
