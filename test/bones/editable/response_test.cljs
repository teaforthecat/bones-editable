(ns bones.editable.response-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [cljs.core.async :as a]
            [re-frame.core :as re-frame :refer [dispatch dispatch-sync]]
            [bones.editable.response :as response]))

(defn app-db
  "shortcut"
  []
  @re-frame.db/app-db)

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
           (response/handler {} [:response/login {} 200 {}]))))
  (testing "all combinations of channels and status codes at least return a :dispatch without blowing up"
    (doseq [combo all-combinations]
      (is (contains? (response/handler {} (interleave combo [{} {}])) :dispatch))))
  (let [tap {:form-type "x"
             :identifier "y"}]
    (testing "login 200"
      (is (= {:dispatch [:editable
                         [:editable "x" "y" :inputs {}]
                         [:editable "x" "y" :errors {}]
                         [:editable "x" "y" :state {}]]}
             (response/handler {} [:response/login {"token" "ok"} 200 tap]))))
    (testing "login 401"
      (async done
       (dispatch (:dispatch
                  (response/handler {} [:response/login {:args "something"} 401 tap])))
       (go (<! (a/timeout 100))
           (is (= "something" (get-in (app-db) [:editable "x" "y" :errors :args])))
           (done))))
    (testing "command 200"
      (async done
             (dispatch (:dispatch
                        (response/handler {} [:response/command {:a 1} 200 tap])))
             (go (<! (a/timeout 100))
                 (is (= {:a 1} (get-in (app-db) [:editable "x" "y" :response])))
                 (done))))
    (testing "etc...")) )
