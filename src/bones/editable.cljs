(ns bones.editable
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require
   ;; [re-frame.core :as re-frame :refer [reg-event-db reg-event-fx reg-fx reg-cofx]]
   [re-frame.core :as re-frame]
            [cljs.spec :as s]))

(defprotocol Client
  (login   [client args tap])
  (logout  [client tap])
  (command [client cmd args tap])
  (query   [client args tap]))


(def debug (if js/goog.DEBUG re-frame/debug))

(defn reg-event-db [name interceptors func]
  (re-frame/reg-event-db name (into [debug] interceptors) func))

(defn reg-event-fx [name interceptors func]
  (re-frame/reg-event-fx name (into [debug] interceptors) func))

(defn reg-fx [name func]
  (re-frame/reg-fx name func))

(defn reg-cofx [name func]
  (re-frame/reg-cofx name func))

;; helpers
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

(defn editable-update
  "update the editable thing in the db,
   the attribute is second to last in the event vector,
   the value is last"
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

(reg-event-db :editable [] editable-update-multi)

(def client-atom (atom {}))

(defn set-client [client]
  (if (satisfies? Client client)
    (reset! client-atom client)
    (throw (ex-info "client does not satisfy bones.editable/Client" {:client client}))))

(defn client-cofx [{:keys [db] :as cofx} _]
  (assoc cofx :client @client-atom))

(reg-cofx :client client-cofx)

(reg-fx :log println)

;; start request

(defn request-login
  [{:keys [client args tap]}]
  (login client args tap))

(reg-fx :request/login request-login)

(defn request-logout
  [{:keys [client tap]}]
  (logout client tap))

(reg-fx :request/logout request-logout)

(defn request-command
  [{:keys [client command args tap]}]
  ;; unfortunate name collision here
  (bones.editable/command client command args tap))

(reg-fx :request/command request-command)

(defn request-query
  [{:keys [client params tap]}]
  (query client params tap))

(reg-fx :request/query request-query)

(defn request
  "get the inputs from the db,
   conform to spec if there is one,
   and build a data structure to send to :request/* fx"
  [{:keys [db client] :as cofx} [channel form-type identifier opts]]
  (cond
    (nil? client)
    {:log "client is nil"} ;; no-op

    (nil? form-type)
    {:log "form-type is nil"} ;; no-op

    (nil? identifier)
    {:log "identifier is nil"} ;; no-op

    (= :request/logout channel)
    (let [{:keys [command tap]} opts]
      {:client client
       :command (or command :logout)
       :args {};; there are no args to send to the server
       :tap tap})

    :ok
    (let [{:keys [command tap]
           :or {command form-type}} opts
          ;; form-type and identifier are needed by the response handler to
          ;; update the thing in the db
          more-tap (merge tap {:form-type form-type :identifier identifier})
          ;; this is the specification for the editable thing:
          {:keys [spec outgoing-spec]
           :or {outgoing-spec spec}
           :as list-meta} (get-in db [:editable form-type :_meta])
          ;; this is the user input:
          inputs (get-in db [:editable form-type identifier :inputs])
          ]
      ;; it seems weird but a request with empty args is possible
      ;; both outgoing-spec and inputs can be nil
      (let [args (s/conform outgoing-spec inputs)]
        (if (= :cljs.spec/invalid args)
          ;; put this in the database:
          {:error (s/explain-data outgoing-spec inputs)}
          ;; this goes to the server:
          {:client client
           :command command
           :args args
           :tap more-tap})))))

(defn dispatch-request
  "dispatch error or make a request and set form state to pending"
  [channel result form-type identifier]
  (cond
    (:errors result)
    {:dispatch (editable-error form-type identifier (:errors result))}

    (:log result)
    result

    :ok
    {:dispatch [:editable form-type identifier :state :pending true]
     channel result}))

(defn process-request [cofx event-vec]
  (let [result (request cofx event-vec)
        [channel form-type identifier] event-vec]
    (dispatch-request channel result form-type identifier)))

(def request-interceptors [(re-frame/inject-cofx :client)])

;; non-lazy way to register events handlers
(doseq [channel [:request/login
                 :request/logout
                 :request/command
                 :request/query]]
 (reg-event-fx channel request-interceptors process-request))

;; start response

;; these are workable (if not sensible) defaults
;; they are meant to be overridden by redefining a method using defmethod


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
    {:log (str "unknown :event/client-status: " message)}))

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


(defn handler-channels []
  (set  (map (fn [[dispatch-value]]
               (if (vector? dispatch-value)
                 ;; response
                 (first dispatch-value)
                 ;; event
                 dispatch-value))
             (methods handler))))

;; hook up the response handlers
(doseq [channel (handler-channels)]
  (reg-event-fx channel [] handler))


;; subscriptions

(re-frame/reg-sub-raw
 :bones/logged-in?
 (fn [db _]
   (reaction (:bones/logged-in? @db))))

(defn sort-fn [sorting coll]
  ;; coll is a map of editable things
  ;; sort must be an array of :key,comparator like:
  ;; [:id >] or [:id :asc] or [:abc :desc]
  (let [[sort-key order] sorting
        ;; provide sort-cut, or use given fn
        sort-order (get {:asc < :desc >} order order)
        sort-key-fn (comp sort-key :inputs)]
    (sort-by sort-key-fn sort-order (vals coll))))

(defn sortable [db event-vec]
  (let [form-type (second event-vec)
        sorting (reaction (get-in @db (conj event-vec :_meta :sort)))
        things (reaction (dissoc (get-in @db event-vec)
                                 :_meta
                                 :new))]
    (reaction (if @sorting
                (sort-fn @sorting @things)
                @things))))

(defn single [db event-vec]
  (reaction (get-in @db event-vec)))

(re-frame/reg-sub-raw
 :editable
 (fn [db event-vec]
   ;; [channel form-type identifier attribute]
   ;; [:editable form-type identifier :inputs attribute]
   ;; [:editable form-type]
   (let [identifier (get event-vec 2)])
   (if (= 2 (count event-vec))
     ;; sorted list of the inputs of editable things - the inputs are the attributes of interest
     (sortable db event-vec)
     ;; get the specific thing or attribute of thing
     (single db event-vec))))

(comment

  (conj [:editable :accounts] :_meta :sort)

  )
