(ns bones.editable-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [re-frame.core :as re-frame :refer [dispatch reg-event-db reg-event-fx reg-fx]]
            [bones.editable :as editable]
            [cljs.core.async :as a]) )

(def sys (atom {}))


(swap! sys assoc :client {:abc 123})

(re-frame/dispatch-sync [:initialize-db sys {}])

(reg-event-fx
 :test-event-fx
 [(re-frame/inject-cofx :client)]
 (fn [{:keys [db client] :as cofx} [_ done-fn]]
   (is (= {:abc 123} client))
   (done-fn)
   {}))


(deftest build-system
  (testing "a client is accessible on the :client cofx"
    ;; initialize-db sets the sys, which should contain the :client
    (async done
           (dispatch [:test-event-fx done]))))
