(ns bones.editable.response-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec.alpha :as s]
            [cljs.core.async :as a]
            [re-frame.core :as re-frame :refer [dispatch dispatch-sync]]
            [bones.editable.response :as response]))

(defn app-db
  "shortcut"
  []
  @re-frame.db/app-db)

;; these are the event names and codes provided by bones.client(and bones.http).
;; More can be added and they can all be overridden.
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

(deftest registered-handlers
  (testing "all combinations of channels and status codes are handled"
    (doseq [combo all-combinations]
      (let [db {}
            [event-name status-code] combo
            ;; event-data is the response body or event message
            event-data {}
            tap {}]
        (is (contains? (response/handler db [event-name event-data status-code tap])
                       :log))))))

(deftest success-dispatch
  (testing " if :e-scope is not set; log"
    (is (= {:log "missing e-scope in tap! it is needed to update the form"}
           (response/tap-success {} {}))))
  (testing " if :args is in response; set inputs to :args"
    (is (= {:dispatch [:editable
                       [:editable "x" "y" :inputs {:b 2}]
                       [:editable "x" "y" :errors {}]
                       [:editable "x" "y" :state {}]
                       [:editable "x" "y" :response {:args {:b 2}}]]}
           (response/tap-success {:e-scope [:_ "x" "y"]} {:args {:b 2}}))))
  ;; when :args is present in tap or even better, response, is assumed to mean that the inputs
  ;; should be set to args. this is a nice easy way to close the loop, meaning the data that you see is the data that has been persisted.
  (let [tap {:e-scope [:_ "x" "y"] :args {:a true}}]
    (testing "tap-success renders viable dispatch data"
      (is (= {:dispatch [:editable
                         [:editable "x" "y" :inputs {}]
                         [:editable "x" "y" :errors {}]
                         [:editable "x" "y" :state {}]
                         [:editable "x" "y" :response {"token" "ok"}]]}
             (response/tap-success tap {"token" "ok"}))))
    (testing "login 200; uses tap-success correctly"
      (is (= {:dispatch [:editable
                         [:editable "x" "y" :inputs {}]
                         [:editable "x" "y" :errors {}]
                         [:editable "x" "y" :state {}]
                         [:editable "x" "y" :response {"token" "ok"}]]
              :db {:bones/logged-in? true}}
             (response/handler {} [:response/login {"token" "ok"} 200 tap]))))
    (testing "command 200; uses tap-success correctly and writes to the db"
      (async done
             (dispatch (:dispatch
                        (response/handler {} [:response/command {:a 1} 200 tap])))
             (go (<! (a/timeout 100))
                 (is (= {:a 1} (get-in (app-db) [:editable "x" "y" :response])))
                 (done))))))

(deftest tap-error
  (testing "login 401"
    (let [tap {:e-scope [:_ "x" "y"]}]
      (async done
             (dispatch (:dispatch
                        (response/handler {} [:response/login {:args "something"} 401 tap])))
             (go (<! (a/timeout 100))
                 (is (= "something" (get-in (app-db) [:editable "x" "y" :errors :args])))
                 (done))))))
