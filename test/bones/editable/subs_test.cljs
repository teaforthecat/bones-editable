(ns bones.editable.subs-test
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [bones.editable.subs :as subs]))

(deftest subscription
  (testing "subscribe to single editable thing"
    (let [db (atom {:editable {:x {:y {:inputs {:z 123}}}}})
          result (subs/single db [:editable :x :y])]
      (is (= {:inputs {:z 123}} @result))))
  (testing "sortable empty list is nil"
    (is (empty? @(subs/sortable (atom {}) [:editable :x]))))

  ;; NOTE: set sorting with (dispatch [:editable :x :_meta :sort [:z :asc]])
  (testing "sortable things without a sort attribute set"
    (let [db (atom {:editable {:x {:_meta {:sort [:z :asc]}
                                   1 {:inputs {:z 3 :id 1}}
                                   2 {:inputs {:z 1 :id 2}}
                                   3 {:inputs {:z 2 :id 3}}}}})
          result (vec @(subs/sortable db [:editable :x]))]
      ;; the order is by :z not :id
      (is (= 2 (get-in result [0 :inputs :id])))
      (is (= 3 (get-in result [1 :inputs :id])))
      (is (= 1 (get-in result [2 :inputs :id]))))))
