(ns bones.editable.subs-test
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec.alpha :as s]
            [bones.editable.subs :as subs]))

(deftest subscription
  (testing "subscribe to single editable thing"
    (let [db (atom {:editable {:x {:y {:inputs {:z 123}}}}})
          result (subs/single db [:editable :x :y])]
      (is (= {:inputs {:z 123}} @result))))

  (testing "sortable empty list is an empty map"
    (is (= {} @(subs/sortable (atom {}) [:editable :x]))))

  (testing "editable things with sorting set"
    (let [db (atom {:editable {:x {:_meta {:sort [:z :asc]}
                                   1 {:inputs {:z 3 :id 1}}
                                   2 {:inputs {:z 1 :id 2}}
                                   3 {:inputs {:z 2 :id 3}}}}})
          result (vec @(subs/sortable db [:editable :x]))]
      ;; the order is by [:z :asc] not :id
      ;; the numbers access the vector of key/value vectors
      (is (= 2 (get-in result [0 1 :inputs :id])))
      (is (= 3 (get-in result [1 1 :inputs :id])))
      (is (= 1 (get-in result [2 1 :inputs :id])))))

  (testing "editable things _without_ sorting set"
    (let [db (atom {:editable {:x {
                                   1 {:inputs {:z 3 :id 1}}
                                   2 {:inputs {:z 1 :id 2}}
                                   3 {:inputs {:z 2 :id 3}}}}})
          result (vec @(subs/sortable db [:editable :x]))]
      ;; full results shown here for documentation
      ;; vector of tuples that are (identifier, thing(inputs,state,errors))
      (is (= [[1 {:inputs {:z 3, :id 1}}]
              [2 {:inputs {:z 1, :id 2}}]
              [3 {:inputs {:z 2, :id 3}}]]
             result))
      ;; the order is by identifier
      ;; the numbers access the vector of key/value vectors
      (is (= 1 (get-in result [0 1 :inputs :id])))
      (is (= 2 (get-in result [1 1 :inputs :id])))
      (is (= 3 (get-in result [2 1 :inputs :id]))))))
