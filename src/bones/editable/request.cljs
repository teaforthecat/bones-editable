(ns bones.editable.request
  (:require [cljs.reader :refer [read-string]]
            [re-frame.core :as re-frame :refer [reg-fx reg-cofx reg-event-fx inject-cofx]]
            [bones.editable.protocols :as p]
            [bones.editable.helpers :as h]
            [cljs.spec.alpha :as s]))


(def debug (if js/goog.DEBUG re-frame/debug))

(def client-atom (atom {}))

(defn client-cofx [cofx _]
  (assoc cofx :client @client-atom))

(defn set-client
  "sets the :client co-effect as a convenience.
  the client will have functions called on it from
  bones.editable.protocols/Client. alternatively, the :client co-effect can be
  registered with:
    (reg-cofx :client #(assoc % :client my-client))"
  [client]
  (if (satisfies? p/Client client)
    (do
      (reset! client-atom client)
      (reg-cofx :client client-cofx))
    (throw (ex-info "client does not satisfy bones.editable.protocols/Client"
                    {:client (type client)}))))

(def interceptors [debug (inject-cofx :client)])

(defn login-effect
  "call the client"
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

(defn logout-effect
  "call the client"
  [{:keys [client tap]}]
  (p/logout client tap))

(reg-fx :request/logout logout-effect)

(defn conform! [spec args]
  (let [conformed (s/conform spec args)]
    (if (= :cljs.spec.alpha/invalid conformed)
      (throw (ex-info "conform! failed" {:spec spec
                                         :args args
                                         :explain-data (s/explain-data spec args)}))
      conformed)))

(defn resolve-args
  "merge data from three sources:
   - data sent in the options of the event
   - data from the inputs of the thing
   - data form the defaults of the thing
  conditionally, merging can be prevented with the :solo option
  In the case below, the resolved args would be only `{:x true}'
  (dispatch [X E-TYPE ID {:args {:x true}}])
  or
  (dispatch [X command args])
  In this next case, all data will come from the inputs in the db
  (dispatch [X E-TYPE ID])"
  [cofx event-vec]
  (let [db (:db cofx)
        [event-name e-type identifier opts] event-vec ;; standard event-vec structure
        _ (if (and (map? identifier) (map? opts)) (throw (ex-info "identifier and opts can not both be maps" {:identifier identifier :opts opts}) ))
        args (if (map? identifier) identifier (:args opts))
        merge-opt (:merge opts)
        merger (if (coll? merge-opt) merge-opt [merge-opt])
        spec (get-in db [:editable e-type :_meta :spec])
        defaults (get-in db [:editable e-type :_meta :defaults])
        inputs (get-in db [:editable e-type identifier :inputs])]
    ;; double arrow means reverse merge so top one wins
    (cond->> (or args {}) ;; if all sources are empty it'll be an empty map instead of nil
      (some #{:inputs} merger) (merge inputs)
      ;; only an identifier was given, options may still contain {:merge :defaults}
      (empty? args) (merge inputs)
      (some #{:defaults} merger) (merge defaults)
      spec (conform! spec))))

(defn login-handler
  "dispatch :request/login to call the client"
  [cofx event-vec]
  (let [scope (h/e-scope event-vec)]
    (try
      (let [;; standard event-vec structure
            args (resolve-args cofx event-vec)
            tap {:args args
                 :e-scope scope}
            ;; maybe make each attribute able to be pending?
            pending-event-vec (into scope [:state :pending true])]
        {:dispatch pending-event-vec
         ;; trigger the fx
         :request/login {:args args
                         :tap tap
                         :client (:client cofx)}})
      (catch js/Error e
        ;; this is a known spec error
        (if-let [spec-error (:explain-data (ex-data e))]
          {:dispatch (into scope [:errors {:explain-data spec-error}])}
          (throw e))))))

(reg-event-fx
 :request/login
 interceptors
 login-handler)


(defn command-handler
  "dispatch :request/command to call the client"
  [cofx event-vec]
  (let [[event-name e-type identifier opts] event-vec ;; standard event-vec structure
        cmd e-type ;; misnomer here, hmmm
        e-type (if (namespace e-type) (keyword (namespace e-type)) e-type)
        ;; use updated event-vec with namespace as e-type
        new-event-vec [event-name e-type identifier opts]
        scope (h/e-scope new-event-vec)]
    (try
      (let [args (resolve-args cofx new-event-vec)
            tap {:command cmd
                 :args args
                 :e-scope scope}
            ;; maybe make each attribute able to be pending?
            pending-event-vec (into scope [:state :pending true])]

        ;; is there an identifier?
        ;; (if (< 2 (count scope))
        ;; the request is finishing before this pending state gets run, which creates
        ;; invalid data. Will probably want to use re-frame-forward-events-fx
        ;;   (merge {:dispatch pending-event-vec}))
        {:request/command {:command cmd
                           :args args
                           :tap tap
                           :client (:client cofx)}})
      (catch js/Error e
        ;; this is a known spec error
        (if-let [spec-error (:explain-data (ex-data e))]
          {:dispatch (into scope [:errors {:explain-data spec-error}])}
          (throw e))))))

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

(defn long-query-handler
  "builds args from three sources see `resolve-args'"
  [cofx event-vec]
  (let [[event-name e-type identifier opts] event-vec ;; standard event-vec structure
        query e-type ;; misnomer here, hmmm
        e-type (if (namespace e-type) (keyword (namespace e-type)) e-type)
        ;; use updated event-vec with namespace as e-type
        new-event-vec [event-name e-type identifier opts]
        args (resolve-args cofx new-event-vec)
        scope (h/e-scope new-event-vec)
        tap {:args args
             :query query
             :e-scope scope}
        ;; maybe make each attribute able to be pending?
        pending-event-vec (into scope [:state :pending true])]
    {:dispatch pending-event-vec
     ;; trigger the fx
     :request/query {:args args
                     :tap tap
                     :client (:client cofx)}}))

(defn query-handler
  " dispatch :request/query effect to call call
  args may be resolved from `resolve-args' or sent as an event arg.
   a map as event arg will short-circuit some logic for a simpler interface"
  [cofx event-vec]
  (let [scope (h/e-scope event-vec)]
    (try
      ;; optional standard event-vec structure
      (let [[event-name e-type identifier opts] event-vec]
        (if (not (map? e-type))
          (if (map? identifier)
            (throw (ex-info "non-standard event-vec, we have a problem" {:event-vec event-vec}))
            (long-query-handler cofx event-vec))
          (short-query-handler cofx event-vec)))
      (catch js/Error e
        ;; this is a known spec error
        (if-let [spec-error (:explain-data (ex-data e))]
          {:dispatch (into scope [:errors {:explain-data spec-error}])}
          (throw e))))))

(reg-event-fx
 :request/query
 interceptors
 query-handler)

(defn logout-handler
  "dispatch :request/logout effect to call client"
  [cofx event-vec]
  (let [[_ tap] event-vec]
    {:request/logout {:tap tap
                      :client (:client cofx)}}) )

(reg-event-fx
 :request/logout
 interceptors
 logout-handler)
