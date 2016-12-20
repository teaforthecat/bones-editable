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

(deftest call-the-client
  (testing :request/login
    (async done
           (swap! sys assoc-in [:client :login-fn] #((is (= %1 {:u 1})) (done)))
           (editable/login (:client @sys) {:u 1} {})

           ;; (dispatch [:request/login])
           )))
