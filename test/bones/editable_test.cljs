(ns bones.editable-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [re-frame.core :as re-frame :refer [dispatch dispatch-sync reg-event-db reg-event-fx reg-fx]]
            [bones.editable :as e]
            [bones.editable.request :as request]
            [bones.editable.protocols :as p]
            [devtools.core :as devtools]
            [cljs.core.async :as a]) )


(defn app-db
  "shortcut"
  []
  @re-frame.db/app-db)

(deftest editable-update
  #_(testing "transforms an event vector that will update the db"
    ;; :w is the channel i.e.: :editable
    ;; :x is the form-type
    ;; :y is the identifier
    ;; :z is the attribute getting updated with value 123
    (is (= [:editable :x :y :z 123]
           (e/editable-transform [:w :x :y :something-else] :z 123))))
  (testing "updates the db from an event vector"
    (is (= {:editable {:x {:y {:z 123}}}}
           (e/editable-update {} [:w :x :y :z 123]))))
  (testing "update mulitple times"
    ;; both :z and :a are updated
    (is (= {:editable {:x {:y {:z 123 :a 5}}}})
        (e/editable-update-multi {} [:w [:w :x :y :z 123]
                                               [:w :x :y :a 5]]))))



