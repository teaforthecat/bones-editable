(ns bones.editable
  (:require [re-frame.core :as re-frame :refer [reg-event-db reg-event-fx reg-fx reg-cofx]]
            [cljs.spec :as s]))

(defprotocol Client
  (login   [client args tap])
  (logout  [client tap])
  (command [client cmd args tap])
  (query   [client args tap]))

;; helpers
(defn editable-reset [etype identifier value]
  [:editable
   [:editable etype identifier :inputs value]
   [:editable etype identifier :errors {}]
   [:editable etype identifier :state {}]])

(defn editable-error [etype identifier error]
  [:editable
   [:editable etype identifier :errors error]
   [:editable etype identifier :state {}]])

(defn editable-response [etype identifier response]
  (conj (editable-reset etype identifier {})
        [:editable etype identifier :response response]))

(defn editable-transform
  "transforms an event vector that will update the db"
  [evec & attrs]
  (let [[channel form-type identifier] evec]
    (into [:editable form-type identifier] attrs)))

(defn editable-update
  "update the db "
  [db [channel form-type id & args]]
  (assoc-in db (into [:editable form-type id] (butlast args)) (last args)))

(defn editable-update-multi
  "update the db multiple times if a vector of events are given"
  [db [channel & events]]
  (if (iterable? (first events))
    ;; we have multiple events
    (reduce editable-update db events)
    ;; single event, reconstruct the event vector
    (editable-update db (into [channel] events))))

(reg-event-db
 :editable
 []
 editable-update-multi)

(reg-event-db
 :initialize-db
 (fn [_ [channel sys default-db]]
   (merge default-db
          {:sys sys})))

;; it is important to discern between this top-level "compile-time" vs
;; the "runtime" of the `deref'. This means the page can load, but the first
;; event that happens needs be the one that initializes the sys in the db,
;; the :initialize-db. Maybe this event could belong to the user
(reg-cofx
 :client
 (fn [{:keys [db] :as cofx} _]
   (assoc cofx :client (:client @(:sys db)))))

(reg-fx
 :log
 println)

;; start request

(reg-fx
 :request/login
 (fn request-login
   [{:keys [client args tap]}]
   (login client args tap)))

(reg-fx
 :request/logout
 (fn request-logout
   [{:keys [client tap]}]
   (logout client tap)))

(reg-fx
 :request/command
 (fn request-command
   [{:keys [client command args tap]}]
   ;; unfortunate name collision here
   (bones.editable/command client command args tap)))

(reg-fx
 :request/query
 (fn request-query
   [{:keys [client params tap]}]
   (query client params tap)))

(defn request
"builds a data structure to send to :request/* fx"
  [{:keys [db client] :as cofx} [channel form-type identifier opts]]
  ;; TODO: add pre condition
  (if client
    (if (= :request/logout channel)
      ;; there is nothing to send to the server here
      (let [{:keys [command tap]} opts]
        {:client client
         :command (or command :logout)
         :args {}
         :tap tap})
      (if form-type
        (if identifier
          (let [{:keys [command tap]} opts
                ;; this is the editable type specification:
                {:keys [spec outgoing-spec]
                 :or {outgoing-spec spec}
                 :as editable-type} (get-in db [:editable form-type])
                ;; this is the user input:
                inputs (get-in editable-type [identifier :inputs])
                ]
            (if (empty? editable-type)
              {:log "editable-type is empty"} ;; no-op
              (let [args (s/conform outgoing-spec inputs)]
                (if (= :cljs.spec/invalid args)
                  ;; put this in the database:
                  {:error (s/explain-data outgoing-spec inputs)}
                  ;; this goes to the server:
                  {:client client
                   :command command
                   :args args
                   :tap tap}))
              )
            )
          {:log "identifier is nil"} ;; no-op
          )
        {:log "form-type is nil"} ;; no-op
        ))
    {:log "client is nil"} ;; no-op
    ))

(defn dispatch-request
  "dispatch error or make a request and set form state to pending"
  [channel result form-type identifier]
  (cond
    (empty? result)
    {:log "empty result from `request'"}

    (:errors result)
    {:dispatch (editable-error form-type identifier (:errors result))}

    (:log result)
    result

    :default
    {:dispatch [:editable form-type identifier :state :pending true]
     channel result}))

(defn process-request [cofx event-vec]
  (let [result (request cofx event-vec)
        [channel form-type identifier] event-vec
        rrr (dispatch-request channel result form-type identifier)]
    rrr))

(def middleware [(re-frame/inject-cofx :client)])

;; non-lazy way to register events handlers
(doseq [channel [:request/login
                 :request/logout
                 :request/command
                 :request/query]]
 (reg-event-fx channel middleware process-request))

;; start response

(defmulti handler
   (fn [db [channel revent & [status tap]]]
    (condp = (namespace channel)
      "response" [channel status]
      "event"    channel)))

(defmethod handler :event/message
  [{:keys [db]} [channel message]]
  ;; you probably mostly want to write to the database here
  ;; and have subscribers reacting to changes
  ;;  {:db (other-multi-method db message) }
  {:db (update db :messages conj message)})

(defmethod handler :event/client-status
  [{:keys [db]} [channel message]]
  (if (contains? message :bones/logged-in?)
    {:db (assoc db :bones/logged-in? (:bones/logged-in? message))}
    ;; no other client-status events exist yet
    {}))

(defmethod handler [:response/login 200]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-reset form-type identifier {})}))

(defmethod handler [:response/login 401]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/login 500]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/login 0]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch [:editable form-type identifier :state :connection "failed to connect"]}))

(defmethod handler [:response/logout 200]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-reset form-type identifier {})}))

(defmethod handler [:response/logout 500]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/command 200]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-response form-type identifier response)}))

(defmethod handler [:response/command 401]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/command 403]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/command 500]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/query 200]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-response form-type identifier response)}))

(defmethod handler [:response/query 401]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/query 403]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/query 500]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))
