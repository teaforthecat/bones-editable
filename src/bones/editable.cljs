(ns bones.editable
  (:require [re-frame.core :as re-frame :refer [reg-event-db reg-event-fx reg-fx reg-cofx]]
            [cljs.spec :as s]))

(defprotocol Client
  (login   [client args tap])
  (logout  [client tap])
  (command [client command args tap])
  (query   [client args tap]))

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
   (command client command args tap)))

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
          (if (and editable-type inputs)
            (let [args (s/conform outgoing-spec inputs)]
              (if (= :cljs.spec/invalid args)
                ;; put this in the database:
                {:error (s/explain-data outgoing-spec inputs)}
                ;; this goes to the server:
                {:client client
                 :command command
                 :args args
                 :tap tap}))
            {} ;; no-op
            )
          )
        {} ;; no-op
        )
      {} ;; no-op
      )
    {} ;; no-op
    ))

(defn editable-update [evec & attrs]
  (let [[channel form-type identifier] evec]
    (into [:editable/update form-type identifier] attrs)))

(defn request-login [cofx event-vec]
  (let [result (request cofx event-vec)]
    (if (empty? result)
      ;; missing data, log, blow up?
      {}
      (if-let [errors (:errors result)]
        {:dispatch (editable-update event-vec :state :errors errors)}
        {:dispatch (editable-update event-vec :state :pending true) ;; multi here to reset errors?
         :request/login result}))))

(defn request-logout [cofx event-vec]
  (let [result (request cofx event-vec)]
    (if (empty? result)
      ;; missing data, log, blow up?
      {}
      (if-let [errors (:errors result)] ;; errors do not make sense here
        {:dispatch (editable-update event-vec :state :errors errors)}
        {:dispatch (editable-update event-vec :state :pending true) ;; maybe grey out the button or something
         :request/logout result}))))

(reg-event-fx
 :request/login
 [(re-frame/inject-cofx :client)]
 request-login)


(defn request-command [cofx event-vec]
  (let [result (request cofx event-vec)]
    (if (empty? result)
      ;; missing data, log, blow up?
      {}
      (if-let [errors (:errors result)]
        {:dispatch (editable-update event-vec :state :errors errors)}
        {:dispatch (editable-update event-vec :state :pending true) ;; maybe grey out the SUBMIT button or something
         :request/command result}))))

(defn request-query [cofx event-vec]
  (let [result (request cofx event-vec)]
    (if (empty? result)
      ;; missing data, log, blow up?
      {}
      (if-let [errors (:errors result)]
        {:dispatch (editable-update event-vec :state :errors errors)}
        {:dispatch (editable-update event-vec :state :pending true) ;; maybe grey out the search button or something
         :request/query result}))))


(reg-event-fx
 :request/logout
 [(re-frame/inject-cofx :client)]
 request-logout)

(reg-event-fx
 :request/command
 [(re-frame/inject-cofx :client)]
 request-command)

(reg-event-fx
 :request/query
 [(re-frame/inject-cofx :client)]
 request-query)

(defmulti handler
   (fn [db [channel & args]] 
    (if (and (second args) (int? (second args)))
      [channel (second args)] ;;response
      channel ;;event
      )))

(defmethod handler :event/message
  [db [channel message]]
  ;; you probably mostly want to write to the database here
  ;; and have subscribers reacting to changes
  ;;  {:db (other-multi-method db message) }
  {:db (update db :messages conj message)})

(defmethod handler :event/client-status 
  [db [channel message]]
  ;;maybe condp-> here
  (if (contains? message :bones/logged-in?)
    {:db (assoc db :bones/logged-in? (:bones/logged-in? message))}
    {}))   

(defmethod handler [:response/login 200]   
  [{:keys [db client] :as cofx} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch [:editable/multi
                [:editable/update form-type identifier :status :pending false]
                [:editable/update form-type identifer :response response]]}))

(defmethod handler [:response/login 401] 
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
    [:editable/update form-type identifier :status :pending false]
)
(defmethod handler [:response/login 500] 
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
    [:editable/update form-type identifier :status :pending false]
)
(defmethod handler [:response/login 0]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
    [:editable/update form-type identifier :status :pending false]
) ;;can't connect

(defmethod handler [:response/logout 200]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
    [:editable/update form-type identifier :status :pending false]
)
(defmethod handler [:response/logout 500]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
    [:editable/update form-type identifier :status :pending false]
)

(defmethod handler [:response/command 200]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
    [:editable/update form-type identifier :status :pending false]
)
(defmethod handler [:response/command 401]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
    [:editable/update form-type identifier :status :pending false]
)
(defmethod handler [:response/command 403]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
    [:editable/update form-type identifier :status :pending false]
)
(defmethod handler [:response/command 500]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
    [:editable/update form-type identifier :status :pending false]
)

(defmethod handler [:response/query 200]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
)
(defmethod handler [:response/query 401]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
)
(defmethod handler [:response/query 403]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
)
(defmethod handler [:response/query 500]
    [{:keys [db client] :as cofx} [channel form-type identifier opts]]
)
