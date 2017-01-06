(ns bones.editable-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [re-frame.core :as re-frame :refer [dispatch dispatch-sync reg-event-db reg-event-fx reg-fx]]
            [bones.editable :as editable]
            [devtools.core :as devtools]
            [cljs.core.async :as a]) )


(defn app-db []
  @re-frame.db/app-db)

(defrecord TestClient [login-fn logout-fn command-fn query-fn]
  editable/Client
  (login   [client args tap] (login-fn args tap))
  (logout  [client tap] (logout-fn tap))
  (command [client cmd args tap] (command-fn cmd args tap))
  (query   [client args tap] (query-fn args tap)))

(def client (map->TestClient {:login-fn identity
                              :logout-fn identity
                              :command-fn identity
                              :query-fn identity}))


(editable/set-client client)

(reg-event-fx
 :test-event-fx
 [(re-frame/inject-cofx :client)]
 (fn [{:keys [db client] :as cofx} [_ done]]
   ;; the client here is what has been setup in the sys
   (done)
   {}))

(deftest client-cofx
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
    ;; in theory, you could have a command with empty args I guess (?)
    ;; the form-type and identifier can be used to update the editable thing
    (is (= {:client {}, :command :y, :args nil, :tap {:form-type :y :identifier :z}}
           (editable/request {:client {} :db {}} [:x :y :z]))))

  (testing (str "if there is data in the db(the component has input data),"
                "  the event will have :client, :command, :args, :tap")
    (let [cofx {:client "[the client]" :db {:editable {:y {:z {:inputs {:shaft :triangle}}}}}}]
      ;; command will default to form-type even though login, logout, query don't use it
      (is (= {:client "[the client]", :command :y, :args {:shaft :triangle}, :tap {:form-type :y :identifier :z}}
             (editable/request cofx [:x :y :z {}])))))
  (testing "if there is a spec in the editable component,"
    (let [cofx {:client "[the client]" :db {:editable {:y {:_meta {:spec ::ulna}
                                                           ;; v is the invalid one
                                                           :v {:inputs {:shaft :unicycle}}
                                                           ;; z conforms
                                                           :z {:inputs {:shaft :triangle}}}}}}]
      (testing "and the inputs conform, the inputs will be passed as the args"
        (is (= {:client "[the client]", :command :y/w, :args {:shaft :triangle, :id :z}, :tap {:form-type :y :identifier :z}}
               (editable/request cofx [:x :y/w {:id :z} {}]))))
      (testing "and if the inputs DONT conform, there will be errors"
        (is (= {:error {:cljs.spec/problems '({:path [:shaft], :pred #{:triangle}, :val :unicycle, :via [:bones.editable-test/ulna :bones.editable-test/shaft], :in [:shaft]})}
                :tap {:form-type :y :identifier :v}}
               (editable/request cofx [:x :y/w {:id :v} {}]))))))
  (testing "added command and tap values get passed along"
    (let [cofx {:client "[the client]" :db {:editable {:y {:z {:inputs {:shaft :triangle}}}}}}]
      (is (= {:client "[the client]", :command :connect, :args {:shaft :triangle :id :z}, :tap {:form-type :y :identifier :z :n 5}}
             (editable/request cofx [:x :connect {:id :z} {:form-type :y :tap {:n 5}}])))))
  (testing "if tap has :defaults they merge into inputs"
    (let [cofx {:client "[the client]" :db {:editable {:y {:z {:inputs {:shaft :triangle}}}}}}]
      (is (= {:client "[the client]", :command :connect, :args {:shaft :triangle :m 5 :id :z}, :tap {:defaults {:m 5} :form-type :y :identifier :z}}
             (editable/request cofx [:x :connect {:id :z} {:form-type :y :tap {:defaults {:m 5}}}])))))
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
    (let [cofx {:client {} :db {:editable {:login {:_meta {:spec ::login}
                                                   :new {:inputs {:bob :jones}}}}}}]
      (is (= {:dispatch [:editable :login :new :state :pending true], :request/login {:error {:cljs.spec/problems '({:path [], :pred (contains? % :username), :val {:bob :jones}, :via [:bones.editable-test/login], :in []} {:path [], :pred (contains? % :password), :val {:bob :jones}, :via [:bones.editable-test/login], :in []})} :tap {:form-type :login :identifier :new}}}
             (editable/process-request cofx [:request/login :login :new])))))
  (testing "will return request if inputs valid"
    (let [cofx {:client {} :db {:editable {:login {:_meta {:spec ::login}
                                                   :new {:inputs {:username "bob"
                                                                  :password "jones"}}}}}}]
      (is (= {:dispatch [:editable :login :new :state :pending true],
              ;; command will default to form-type if not given
              :request/login {:client {}, :command :login, :args {:username "bob", :password "jones"}, :tap {:form-type :login :identifier :new}}}
             (editable/process-request cofx [:request/login :login {} {:identifier :new :form-type :login}]))))))

(defn update-client [attr func]
  (swap! editable/client-atom assoc attr func))

(deftest call-the-client
  (testing :request/login
    (async done
           ;; the client will expect args {:u 1} to send to the server
           (update-client :login-fn #((is (= %1 {:u 1})) (done)))

           ;; this is what gets called in the dispatched fx
           ;; (editable/login (:client @sys) {:u 1} {})

           ;; the inputs of the form are the args to send to the client
           (dispatch [:editable :login-form :new :inputs {:u 1}])
           ;; this is the integration with reframe test
           (dispatch [:request/login :login-form :new])))
  (testing :request/logout
    (async done
           (update-client :logout-fn (fn [tap]
                                       (is (= {:form-type :some-type
                                               :identifier :number123
                                               :anything "this is"} tap))
                                       (done)))
           ;; all buttons have life-cycles (and this is the required shape of the event)
           (dispatch [:request/logout :buttons :logout {:tap {:anything "this is"}}])))
  (testing :request/command
    (async done
           (update-client :command-fn (fn [command args tap]
                                        (is (= :some-command command))
                                        (is (= {:calcium 123} args))
                                        (is (= {:form-type :some-type
                                                :identifier :number123
                                                :anything "this is"} tap))
                                        (done)
                                        ))
           (dispatch [:editable :some-type :number123 :inputs {:calcium 123}])
           (dispatch [:request/command :some-command :number123 {:form-type :some-type :tap {:anything "this is"}}]))))


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
  (testing "emits broken events sometimes - "
    (is (= {:dispatch [:editable
                       [:editable nil nil :inputs {}]
                       [:editable nil nil :errors {}]
                       [:editable nil nil :state {}]]}
           (editable/handler {} [:response/login {} 200 {}])))

    )
  (testing "all combinations of channels and status codes at least return a :dispatch without blowing up"
    (doseq [combo all-combinations]
      (is (contains? (editable/handler {} (interleave combo [{} {}])) :dispatch))))
  (let [tap {:form-type "x"
             :identifier "y"}]
    (testing "login 200"
      (is (= {:dispatch [:editable
                         [:editable "x" "y" :inputs {}]
                         [:editable "x" "y" :errors {}]
                         [:editable "x" "y" :state {}]]}
             (editable/handler {} [:response/login {"token" "ok"} 200 tap]))))
    (testing "login 401"
      (async done
       (dispatch (:dispatch
                  (editable/handler {} [:response/login {:args "something"} 401 tap])))
       (go (<! (a/timeout 100))
           (is (= "something" (get-in (app-db) [:editable "x" "y" :errors :args])))
           (done))))
    (testing "command 200"
      (async done
             (dispatch (:dispatch
                        (editable/handler {} [:response/command {:a 1} 200 tap])))
             (go (<! (a/timeout 100))
                 (is (= {:a 1} (get-in (app-db) [:editable "x" "y" :response])))
                 (done))))
    (testing "etc...")) )


(deftest subscription
  (testing "subscribe to single editable thing"
    (let [db (atom {:editable {:x {:y {:inputs {:z 123}}}}})
          result (editable/single db [:editable :x :y])]
      (is (= {:inputs {:z 123}} @result))))
  (testing "sortable empty list is nil"
    (is (empty? @(editable/sortable (atom {}) [:editable :x]))))

  ;; NOTE: set sorting with (dispatch [:editable :x :_meta :sort [:z :asc]])
  (testing "sortable things without a sort attribute set"
    (let [db (atom {:editable {:x {:_meta {:sort [:z :asc]}
                                   1 {:inputs {:z 3 :id 1}}
                                   2 {:inputs {:z 1 :id 2}}
                                   3 {:inputs {:z 2 :id 3}}}}})
          result (vec @(editable/sortable db [:editable :x]))]
      ;; the order is by :z not :id
      (is (= 2 (get-in result [0 :inputs :id])))
      (is (= 3 (get-in result [1 :inputs :id])))
      (is (= 1 (get-in result [2 :inputs :id]))))))
