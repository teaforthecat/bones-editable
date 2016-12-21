(ns bones.editable-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [re-frame.core :as re-frame :refer [dispatch reg-event-db reg-event-fx reg-fx]]
            [bones.editable :as editable]
            [cljs.core.async :as a]) )

(def sys (atom {}))

(defrecord TestClient [login-fn logout-fn command-fn query-fn]
  editable/Client
  (login   [client args tap] (login-fn args tap))
  (logout  [client tap] (logout-fn tap))
  (command [client command args tap] (command-fn command args tap))
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
   (is (= identity (:login-fn client)))
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
    (is (= {} (editable/request {} []))))
  (testing "if no identifier, an empty(no-op) event is returned"
    (is (= {} (editable/request {:client {}} [:x :y nil {}]))))
  (testing "if no form-type, an empty(no-op) event is returned"
    (is (= {} (editable/request {:client {}} [:x nil :y {}]))))
  (testing (str "if there is no data in the db for form-type/identifier,"
                " an empty(no-op) event is returned")
    (is (= {} (editable/request {:client {} :db {}} [:x :y :z]))))

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
    ;; :x is the form-type
    ;; :y is the identifier
    ;; :z is the attribute getting updated with value 123
    (is (= [:editable/update :x :y :z 123]
           (editable/editable-update [:w :x :y :something-else] :z 123)))))

(s/def ::username string?)
(s/def ::password string?)
(s/def ::login (s/keys :req-un [::username
                                ::password]))

(deftest request-login
  (testing "will return no-op event if missing data"
    (is (= {} (editable/request-login {} []))))
  (testing "will return error if inputs invalid"
    (let [cofx {:client {} :db {:editable {:login {:spec ::login
                                                   :new {:inputs {:bob :jones}}}}}}]
      (is (= {:dispatch [:editable/update :login :new :state :pending true], :request/login {:error {:cljs.spec/problems '({:path [], :pred (contains? % :username), :val {:bob :jones}, :via [:bones.editable-test/login], :in []} {:path [], :pred (contains? % :password), :val {:bob :jones}, :via [:bones.editable-test/login], :in []})}}}
             (editable/request-login cofx [:request/login :login :new])))))
  (testing "will return request if inputs valid"
    (let [cofx {:client {} :db {:editable {:login {:spec ::login
                                                   :new {:inputs {:username "bob"
                                                                  :password "jones"}}}}}}]
      (is (= {:dispatch [:editable/update :login :new :state :pending true],
              :request/login {:client {}, :command nil, :args {:username "bob", :password "jones"}, :tap nil}}
             (editable/request-login cofx [:request/login :login :new]))))))


(deftest call-the-client
  (testing :request/login
    (async done
           (swap! sys assoc-in [:client :login-fn] #((is (= %1 {:u 1})) (done)))
           ;; (editable/login (:client @sys) {:u 1} {})

           (dispatch [:request/login])
           )))
