(ns bones.editable.local-storage-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [re-frame.core :as re-frame :refer [dispatch dispatch-sync reg-event-db reg-event-fx reg-fx]]
            [day8.re-frame.test :refer [run-test-sync* run-test-async wait-for]]
            [bones.editable :as e]
            [bones.editable.local-storage :as e.ls]
            [bones.editable.protocols :as p]
            [bones.editable.helpers :as h]
            [cljs.core.async :as a]))


(deftest command
  (let [client (e.ls/LocalStorage. "bones")]
    (testing ":new action responds with args for insert into app-db - uses id when present"
      (run-test-async
       (p/command client :todos/new {:a 1 :id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"} {:e-scope [:editable :todos :new]})
       (wait-for [:response/command event]
                 (is (= [:response/command
                         {:args {:a 1, :id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"},
                          :command :todos/new}
                         200
                         {:e-scope [:editable :todos :new]}]
                        event)))))

    (testing " generates uuid when absent"
      (run-test-async
       (p/command client :todos/new {:a 1})
       (wait-for [:response/command event]
                 (is (uuid? (get-in event [1 :args :id]))))))

    (testing ":update action respondes with args for insert into app-db"
      (run-test-async
       (p/command client :todos/update {:a 2 :id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"})
       (wait-for [:response/command event]
                 (is (= 2 (get-in event [1 :args :a]))))))

    (testing ":update without an :id responds with error status "
      (run-test-async
       (p/command client :todos/update {:a 2})
       (wait-for [:response/command event]
                 (is (= "no :id present in args: {:command :todos/update, :args {:a 2}}"
                        (get-in event [1 :errors :message])))
                 (is (= 401 (get-in event [2]))))))

    (testing ":new action inserts data into LocalStorage"
      (run-test-async
       (p/command client :todos/new {:a 2})
       (wait-for [:response/command event]
                 (p/query client {:e-type :todos})
                 (wait-for [:response/query event]
                           ;; TODO: spec pattern matching
                           (is (= 2
                                  (-> event
                                      (get-in [1 :results])
                                      vals
                                      first
                                      :inputs
                                      :a)))))))

    (testing ":update action changes data in LocalStorage"
      (p/command client :todos/new {:a 2 :id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"})
      (run-test-async
       (p/command client :todos/update {:a 3 :id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"})
       (wait-for [:response/command event]
                 (p/query client {:e-type :todos})
                 (wait-for [:response/query event]
                           ;; TODO: spec pattern matching
                           (is (= 3
                                  (-> event
                                      (get-in [1 :results])
                                      vals
                                      first
                                      :inputs
                                      :a)))))))

    (testing ":delete action changes data in LocalStorage"
      (p/command client :todos/new {:a 2 :id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"})
      (run-test-async
       (p/command client :todos/delete {:id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"})
       (wait-for [:response/command event]
                 (p/query client {:e-type :todos})
                 (wait-for [:response/query event]
                           ;; TODO: spec pattern matching
                           (is (empty? (get-in event [1 :results])))))))

    (testing ":delete many action deletes many from LocalStorage"
      (p/command client :todos/new {:a 2 :id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"})
      (p/command client :todos/new {:b 3 :id #uuid "53340df1-24da-426d-837d-0ca22e89baf5"})
      (run-test-async
       (p/command client :todos/delete [{:id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"}
                                        {:id #uuid "53340df1-24da-426d-837d-0ca22e89baf5"}])
       (wait-for [:response/command event]
                 (p/query client {:e-type :todos})
                 (wait-for [:response/query event]
                           ;; TODO: spec pattern matching
                           (is (empty? (get-in event [1 :results])))))))

    (testing ":delete many action does not delete everything from LocalStorage"
      (p/command client :todos/new {:a 2 :id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"})
      (p/command client :todos/new {:b 3 :id #uuid "53340df1-24da-426d-837d-0ca22e89baf5"})
      (run-test-async
       (p/command client :todos/delete [{:id #uuid "a1b87f0d-db82-4850-a115-eb60782fef2f"}
                                        #_{:id #uuid "53340df1-24da-426d-837d-0ca22e89baf5"}])
       (wait-for [:response/command event]
                 (p/query client {:e-type :todos})
                 (wait-for [:response/query event]
                           ;; TODO: spec pattern matching
                           (is (not (empty? (get-in event [1 :results]))))))))

    ))
