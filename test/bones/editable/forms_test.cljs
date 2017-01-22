(ns bones.editable.forms-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [re-frame.core :as re-frame :refer [dispatch dispatch-sync reg-event-db reg-event-fx reg-fx]]
            [bones.editable :as e]
            [bones.editable.forms :as forms]
            [bones.editable.protocols :as p]
            [bones.editable.helpers :as h]
            [devtools.core :as devtools]
            [cljs.core.async :as a]))

(deftest reset-closure
  (testing "reset upserts empty maps to path :errors,:state,:inputs"
    (let [{:keys [reset]} (e/form :todos :new)]
      #_(reset)
      #_(is (= {:errors {}, :state {}, :inputs nil}
             (get-in @re-frame.db/app-db [:editable :todos :new])))))
  (testing "reset will use :state :reset if it exists"
    (let [
          {:keys [reset]} (e/form :todos :new)]

      (dispatch [:editable :todos :new :state :reset {:x 1}])
      (async done
             (go (<! (a/timeout 500))
                 (reset))
             (go (<! (a/timeout 2000)
                 (is (= {:errors {}, :state {}, :inputs {:x 1}}
                        (get-in @re-frame.db/app-db [:editable :todos :new])))
                 (done)))))))
