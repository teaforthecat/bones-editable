(ns bones.editable-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [re-frame.core :as re-frame :refer [dispatch reg-event-db reg-event-fx reg-fx]]
            [bones.editable :as editable]
            [devtools.core :as devtools]
            [cljs.core.async :as a]) )

;; (when js/goog.DEBUG
;;   (devtools/install!))

(def sys (atom {}))

(defrecord TestClient [login-fn logout-fn command-fn query-fn]
  editable/Client
  (login   [client args tap] (login-fn args tap))
  (logout  [client tap] (logout-fn tap))
  (command [client cmd args tap] (command-fn cmd args tap))
  (query   [client args tap] (query-fn args tap)))

(swap! sys assoc :client (map->TestClient {:login-fn identity
                                           :logout-fn identity
                                           :command-fn identity
                                           :query-fn identity}))

;; is there a better way to make the client available via cofx than this?
(re-frame/dispatch-sync [:initialize-db sys {}])

(reg-event-fx
 :test-event-fx
 [(re-frame/inject-cofx :client)]
 (fn [{:keys [db client] :as cofx} [_ done]]
   ;; the client here is what has been setup in the sys
   (done)
   {}))

(deftest build-system
  (testing "a client is accessible on the :client cofx"
    ;; initialize-db sets the sys, which should contain the :client
    (async done
           (dispatch [:test-event-fx done]))))

;; TODO: spec out :editable in the default app-db
(s/def ::shaft #{:triangle})
(s/def ::ulna (s/keys :req-un [::shaft]))

(deftest request
  (testing "if no client, an empty(no-op) event is returned"
    (is (= {:log "client is nil"} (editable/request {} []))))
  (testing "if no identifier, an empty(no-op) event is returned"
    (is (= {:log "identifier is nil"} (editable/request {:client {}} [:x :y nil {}]))))
  (testing "if no form-type, an empty(no-op) event is returned"
    (is (= {:log "form-type is nil"} (editable/request {:client {}} [:x nil :y {}]))))
  (testing (str "if there is no data in the db for form-type/identifier,"
                " an empty(no-op) event is returned")
    (is (= {:log "editable-type is empty"} (editable/request {:client {} :db {}} [:x :y :z]))))

  (testing (str "if there is data in the db(the component has input data),"
                "  the event will have :client, :command, :args, :tap")
    (let [cofx {:client {:femur 1} :db {:editable {:y {:z {:inputs {:shaft :triangle}}}}}}]
      ;; it is ok to not have a command - login, logout, query don't use it
      (is (= {:client {:femur 1}, :command nil, :args {:shaft :triangle}, :tap nil}
             (editable/request cofx [:x :y :z {}])))))
  (testing "if there is a spec in the editable component,"
    (let [cofx {:client {:femur 1} :db {:editable {:y {:spec ::ulna
                                                       ;;v is the invalid one
                                                       :v {:inputs {:shaft :unicycle}}
                                                       :z {:inputs {:shaft :triangle}}}}}}]
      (testing "and the inputs conform, the inputs will be passed as the args"
        (is (= {:client {:femur 1}, :command nil, :args {:shaft :triangle}, :tap nil}
               (editable/request cofx [:x :y :z {}]))))
      (testing "and the inputs DONT conform, there will be errors"
        (is (= {:error {:cljs.spec/problems '({:path [:shaft], :pred #{:triangle}, :val :unicycle, :via [:bones.editable-test/ulna :bones.editable-test/shaft], :in [:shaft]})}}
               (editable/request cofx [:x :y :v {}]))))))
  (testing "adding command and tap"
    (let [cofx {:client {:femur 1} :db {:editable {:y {:z {:inputs {:shaft :triangle}}}}}}]
      (is (= {:client {:femur 1}, :command :connect, :args {:shaft :triangle}, :tap {:n 5}}
             (editable/request cofx [:x :y :z {:command :connect :tap {:n 5}}])))))
  )

(deftest editable-update
  (testing "transforms an event vector that will update the db"
    ;; :w is the channel i.e.: :editable
    ;; :x is the form-type
    ;; :y is the identifier
    ;; :z is the attribute getting updated with value 123
    (is (= [:editable :x :y :z 123]
           (editable/editable-transform [:w :x :y :something-else] :z 123))))
  (testing "updates the db from an event vector"
    (is (= {:editable {:x {:y {:z 123}}}}
           (editable/editable-update {} [:w :x :y :z 123]))))
  (testing "update mulitple times"
    ;; both :z and :a are updated
    (is (= {:editable {:x {:y {:z 123 :a 5}}}})
        (editable/editable-update-multi {} [:w [:w :x :y :z 123]
                                               [:w :x :y :a 5]]))))

(s/def ::username string?)
(s/def ::password string?)
(s/def ::login (s/keys :req-un [::username
                                ::password]))

(deftest process-request
  (testing "will return no-op event if missing data"
    (is (= {:log "client is nil"} (editable/process-request {} []))))
  (testing "will return error if inputs invalid"
    (let [cofx {:client {} :db {:editable {:login {:spec ::login
                                                   :new {:inputs {:bob :jones}}}}}}]
      (is (= {:dispatch [:editable :login :new :state :pending true], :request/login {:error {:cljs.spec/problems '({:path [], :pred (contains? % :username), :val {:bob :jones}, :via [:bones.editable-test/login], :in []} {:path [], :pred (contains? % :password), :val {:bob :jones}, :via [:bones.editable-test/login], :in []})}}}
             (editable/process-request cofx [:request/login :login :new])))))
  (testing "will return request if inputs valid"
    (let [cofx {:client {} :db {:editable {:login {:spec ::login
                                                   :new {:inputs {:username "bob"
                                                                  :password "jones"}}}}}}]
      (is (= {:dispatch [:editable :login :new :state :pending true],
              :request/login {:client {}, :command nil, :args {:username "bob", :password "jones"}, :tap nil}}
             (editable/process-request cofx [:request/login :login :new]))))))


(deftest call-the-client
  (testing :request/login
    (async done
           ;; the client will expect args {:u 1} to send to the server
           (swap! sys assoc-in [:client :login-fn] #((is (= %1 {:u 1})) (done)))
           ;; this is what gets called in the dispatched fx via the the middleware
           ;; (editable/login (:client @sys) {:u 1} {})

           ;; the inputs of the form are the args to send to the client
           (dispatch [:editable :login-form :new :inputs {:u 1}])
           ;; this is the integration with reframe test
           (dispatch [:request/login :login-form :new])))
  (testing :request/logout
    (async done
           (swap! sys assoc-in [:client :logout-fn] (fn [tap]
                                                      (is (= {:anything "this is"} tap))
                                                      (done)))
           ;; all buttons have life-cycles (and this is the required shape of the event)
           (dispatch [:request/logout :buttons :logout {:tap {:anything "this is"}}])))
  (testing :request/command
    (async done
           (swap! sys assoc-in [:client :command-fn] (fn [command args tap]
                                                       (is (= :some-command command))
                                                       (is (= {:calcium 123} args))
                                                       (is (= {:anything "this is"} tap))
                                                       (done)
                                                       ))
           (dispatch [:editable :some-type :number123 :inputs {:calcium 123}])
           (dispatch [:request/command :some-type :number123 {:command :some-command :tap {:anything "this is"}}]))))


(def all-combinations [[:response/login 200]
                       [:response/login 401]
                       [:response/login 500]
                       [:response/login 0]
                       [:response/logout 200]
                       [:response/logout 500]
                       [:response/command 200]
                       [:response/command 401]
                       [:response/command 403]
                       [:response/command 500]
                       [:response/query 200]
                       [:response/query 401]
                       [:response/query 403]
                       [:response/query 500]])

(deftest response-handler
  (testing "emits broken events sometimes - fix me"
    (is (= {:dispatch [:editable
                       [:editable nil nil :inputs {}]
                       [:editable nil nil :errors {}]
                       [:editable nil nil :state {}]]}
           (editable/handler {} [:response/login {} 200 {}])))

    )
  (testing "all combinations of channels and status codes at least return a :dispatch without blowing up"
    (doseq [combo all-combinations]
      (is (contains? (editable/handler {} (interleave combo [{} {}])) :dispatch)))))
