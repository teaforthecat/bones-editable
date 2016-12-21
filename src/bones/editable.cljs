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

(defn command-event
  "handle form submissions or any event to extract data from the editable
  components in the db and send it as a command to the server via the
  client (which is in a co-fx)"
  [{:keys [db client] :as cofx} [channel form-type identifier opts]]
  (let [{:keys [spec outgoing-spec]
         :or {outgoing-spec spec}
         :as editable-type} (get-in db [:editable form-type])
        inputs (get-in editable-type [identifier :inputs])
        args (s/conform outgoing-spec inputs)
        command (or (:command opts) form-type)] ;; to tricky?
    (if (= :cljs.spec/invalid args)
      {:dispatch [:editable/update form-type identifier :state :error (pr-str (:cljs.spec/problems (s/explain-data outgoing-spec inputs)))]}
      {:request/command {:client client
                         :command command
                         :args args
                         :tap {:form-type form-type
                               :identifier identifier}}
       :dispatch [:editable/update form-type identifier :state :sending true]})))

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

(reg-event-fx
 :request/login
 [(re-frame/inject-cofx :client)]
 request-login)
