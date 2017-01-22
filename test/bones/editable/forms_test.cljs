(ns bones.editable.forms-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [re-frame.core :as re-frame :refer [dispatch dispatch-sync reg-event-db reg-event-fx reg-fx]]
            [day8.re-frame.test :refer [run-test-sync* run-test-async wait-for]]
            [bones.editable :as e]
            [bones.editable.forms :as forms]
            [bones.editable.protocols :as p]
            [bones.editable.helpers :as h]
            [devtools.core :as devtools]
            [cljs.core.async :as a]))

(deftest reset-closure
  (testing "reset upserts empty maps to path :errors,:state,:inputs"
    (let [{:keys [reset]} (e/form :todos :new)]
      (run-test-async
       (reset)
       (wait-for [:editable event]
                 (is (= {:errors {}, :state {}, :inputs nil}
                        (get-in @re-frame.db/app-db [:editable :todos :new])))))))
  (testing "reset will use :state :reset if it exists"
    (run-test-async
     (let [
           {:keys [reset]} (e/form :todos :new)]

       (dispatch [:editable :todos :new :state :reset {:x 1}])
       (wait-for [:editable event]
                 (is (= [:editable :todos :new :state :reset {:x 1}]
                        event))

                 (reset)
                 (wait-for [:editable event]
                           (is (= [:editable
                                   [:editable :todos :new :inputs {:x 1}]
                                   [:editable :todos :new :errors {}]
                                   [:editable :todos :new :state {}]]
                                  event))))))))

(deftest save-closure
  (testing "calling save with a js/Event (a click) will dispatch an event"
    (let [{:keys [save]} (e/form :todos :new)]
      (run-test-async
       ;; usage:
       ;;  :on-click save
       (save #js{"target" "xyz"})
       (wait-for [:request/command event]
                 ;; this event will resolve inputs from the db
                 (is (= [:request/command :todos/new :new {}]
                        event))))))
  (testing "calling save with a map returns a save-fn with options"
    (let [{:keys [save]} (e/form :todos :new)]
      (run-test-async
       ;; usage:
       ;;  :on-click (save {:args {:b 2}})

       ;; fake click
       (apply (save {:args {:b 2}}) [#js{"target" "xyz"}])
       (wait-for [:request/command event]
                 ;; this event will resolve inputs from the db
                 (is (= [:request/command :todos/new :new {:args {:b 2}}]
                        event)))))))
