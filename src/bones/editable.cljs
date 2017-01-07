(ns bones.editable
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [cljs.reader :refer [read-string]]
            [re-frame.core :as re-frame]
            [cljs.spec :as s]))

(def debug (if js/goog.DEBUG re-frame/debug))

(defn reg-event-db [name interceptors func]
  (re-frame/reg-event-db name (into [debug] interceptors) func))

(defn reg-event-fx [name interceptors func]
  (re-frame/reg-event-fx name (into [debug] interceptors) func))

(defn reg-fx [name func]
  (re-frame/reg-fx name func))

(defn reg-cofx [name func]
  (re-frame/reg-cofx name func))

(defn dispatch [eventv]
  (re-frame/dispatch eventv))

(comment
;; start editable
(s/def ::inputs map?)
(s/def ::errors map?)
(s/def ::state map?)
(s/def ::defaults map?)
(s/def ::response map?)
(s/def ::formable (s/keys :opt-un [::inputs ::errors ::state ::response ::defaults]))
(s/def ::unique-thing-id (s/or :s string? :k keyword? :i integer? :u uuid?))
(s/def ::identifier (s/every-kv ::unique-thing-id ::formable))
(s/def ::form-type (s/or :s string? :k keyword? :i integer?))
(s/def ::editable (s/nilable (s/every-kv ::form-type ::identifier )))

  (s/exercise ::unique-thing-id)
  ;; top level is nilable so no data is required to start
  ;; goes like this:
  ;; get-in db [editable form-type identifier :inputs]
  ;; get-in db [:editable :x :y :inputs :z]
  (s/conform ::editable {:x {:y {:inputs {:z 123}}}})
  (s/explain ::editable {:x {:y nil}})

  )
;; end editable



(defprotocol Client
  (login   [client args tap])
  (logout  [client tap])
  (command [client cmd args tap])
  (query   [client args tap]))

(defn local-key [prefix form-type]
  ;; use name in case keywords are provided
  (str (name prefix) "-" (name form-type)))

(defn local-get-item [prefix form-type]
  (if-let [result (.getItem js/localStorage (local-key prefix form-type))]
    (read-string result)))

(defn local-set-item [prefix form-type value]
  (.setItem js/localStorage (local-key prefix form-type) (pr-str value)))

(defrecord LocalStorage [prefix]
  Client
  (login [client args tap]
    (dispatch [:response/login {:fake true} 200 tap]))
  (logout [client tap]
    (dispatch [:response/logout {:fake true} 200 tap]))
  (command [client cmd args tap]
    (let [thing {:inputs (update args :id (fnil identity (random-uuid)))}
          id (get-in thing [:inputs :id]) ;; used as identifier _and_ attribute
          cmdspace (or (namespace cmd) (:form-type tap))
          action (name cmd)
          things (local-get-item prefix cmdspace)]

      (local-set-item prefix cmdspace
                      (condp = (name action)
                        "new"
                        (assoc things id thing)
                        "update"
                        (update-in things [id :inputs] merge args)
                        "delete"
                        (dissoc things id)))
      ;; (go (<! (a/timeout 10)))
      (dispatch [:response/command
                 ;; args in the response means update the editable thing's inputs
                 {:args (:inputs thing)
                  :command cmd}
                 ;; response status
                 200
                 tap])))
  (query [client args tap]
    (let [{:keys [form-type]} args
          things (local-get-item prefix form-type)]
      (dispatch [:response/query {:results things} 200 tap]))))


;; forms
(defn detect-controls [{:keys [enter escape]}]
  (fn [keypress]
    (case (.-which keypress)
      13 (enter)
      ;; chrome won't fire 27, so use on-blur instead
      27 (escape)
      nil)))

(defn field [form-type identifier attr html-attrs]
  (let [path [:editable form-type identifier :inputs attr]
        value (subscribe path)
        input-type (or (:input-type html-attrs) :input)
        value-attr (or (:value-attr html-attrs) :value)
        opts (dissoc html-attrs :value-attr :input-type)]
    (fn []
      [input-type (merge {:on-change #(dispatch-sync (conj path (-> % .-target .-value)))
                          value-attr @value}
                         opts)])))

(defn checkbox [form-type identifier attr & {:as html-attrs}]
  (field form-type identifier attr (merge {:type "checkbox"
                                           :value-attr :checked}
                                          html-attrs)))

(defn input [form-type identifier attr & {:as html-attrs}]
  (field form-type identifier attr html-attrs))

(defn calculate-command [form-type action]
  (keyword (name form-type) (name action)))

(defn save-fn
  ([[form-type id]]
   ;; call it, save all the inputs. this way it can be used without parens, which
   ;; is kind of neat
   ((apply save-fn [form-type id] {} {})))
  ([[form-type id] args]
   ;; in case you don't want to pass opts
   (save-fn [form-type id] args {}))
  ([[form-type id] args opts]
   ;; opts can be options like :solo "don't merge values from :inputs"
   (fn []
     (let [new-args (if (fn? args) (args) args)
           action (if (= :new id) :new :update)
           ;; args are attributes/inputs of the thing
           more-args (merge (if (= :new id) nil {:id id}) new-args)
           ;; opts are logistical things like how to update the form from the
           ;; response
           ;; we can't send :id equal to :new because :id is probably an
           ;; attribute that will be generated
           more-opts (merge (if (= :new id) {:identifier :new} ) opts)
           ;; convention for commands here e.g.: :todos/update
           calculated-command (calculate-command form-type action)]
       (dispatch [:request/command calculated-command more-args more-opts])))))

(defn form
  "returns function as closures around subscriptions to a single 'editable' thing in the
  db. The thing has attributes, whose current value is accessed by calling `inputs' e.g., with arguments. No arguments will return all the attributes"
  [form-type identifier]
  (let [inputs-atom (subscribe [:editable form-type identifier :inputs])
        state-atom (subscribe [:editable form-type identifier :state])
        errors-atom (subscribe [:editable form-type identifier :errors])
        defaults-atom (subscribe [:editable form-type identifier :defaults])
        inputs (fn [& args] (get-in @inputs-atom args))
        state (fn [& args] (get-in @state-atom args))
        errors (fn [& args] (get-in @errors-atom args))
        defaults (fn [& args] (get-in @defaults-atom args))]
    {:inputs inputs
     :state  state
     :errors errors
     :defaults defaults

     :save (partial save-fn [form-type identifier])
     :delete #(dispatch [:request/command (calculate-command form-type :delete) {:id identifier}])
     :reset  #(dispatch (e/editable-reset :todos identifier (state :reset)))
     :edit   #(dispatch [:editable
                         [:editable :todos identifier :state :reset (inputs)]
                         [:editable :todos identifier :state :editing true]])
     }))


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
  [{:keys [db client] :as cofx} [channel command partial-args opts]]
  (cond
    (nil? client)
    {:log "client is nil"} ;; no-op

    (nil? (or (and command (namespace command))
              (:form-type opts)
              command))
    {:log "form-type is nil"} ;; no-op

    (nil? (or (and (map? partial-args) (:id partial-args))
              (:identifier opts)
              ;; must be an :identifier like :new or just an id
              partial-args))
    {:log "identifier is nil"} ;; no-op

    ;; maybe this can be in another function
    (= :request/logout channel)
    (let [{:keys [command tap]} opts]
      {:client client
       :command (or command :logout)
       :args {};; there are no args to send to the server
       :tap tap})

    :ok
    ;; maybe if form-type has a namespace, use that as the command
    ;; and the namespace as the form-type
    ;; maybe merge the defaults from the tap here???????
    (let [{:keys [tap]} opts
          form-type (keyword (or (and command (namespace command))
                                 (:form-type opts)
                                 command))
          identifier (or (and (map? partial-args) (:id partial-args))
                         (:identifier opts)
                         ;; must be an :identifier like :new or just an id
                         partial-args)
          solo (:solo opts)
          ;; form-type and identifier are needed by the response handler to
          ;; update the thing in the db
          more-tap (merge tap
                          {:form-type form-type :identifier identifier}
                          {:solo solo})
          ;; this is the specification for the editable thing:
          {:keys [spec outgoing-spec]
           :or {outgoing-spec spec}
           :as list-meta} (get-in db [:editable form-type :_meta])
          defaults (:defaults tap)
          ;; this is the user input:
          inputs (get-in db [:editable form-type identifier :inputs])
          ;; partial-args will needlessly overwrite the :id in :inputs, but oh well
          ;; partial-args should overwrite what is in the db, and update the db
          ;; in the response handler
          ;; don't merge inputs from the if :solo - only send the given
          ;; attributes in the event vector
          inputs-defaults (merge defaults (if solo {} inputs) (if (map? partial-args) partial-args))
          ]
      ;; it seems weird but a request with empty args is possible
      ;; both outgoing-spec and inputs can be nil
      (let [args (s/conform outgoing-spec inputs-defaults)]
        (if (= :cljs.spec/invalid args)
          ;; put this in the database:
          {:error (s/explain-data outgoing-spec inputs-defaults)
           :tap more-tap}
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
        {:keys [form-type identifier]} (get-in result [:tap])
        [channel] event-vec]
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
    ;; (response-command response status tap) ;;......multi
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
  (reg-event-fx channel [debug] handler))


;; subscriptions

;; consider simple filter k/v function

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
