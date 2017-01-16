(ns bones.editable.request-test
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.test :as t :refer-macros [deftest testing is async]]
            [cljs.spec :as s]
            [re-frame.core :as re-frame :refer [dispatch dispatch-sync reg-event-db reg-event-fx reg-fx]]
            [bones.editable :as e]
            [bones.editable.request :as request]
            [bones.editable.protocols :as p]
            [bones.editable.helpers :as h]
            [devtools.core :as devtools]
            [cljs.core.async :as a]))

;; a configurable mock client
(defrecord TestClient [login-fn logout-fn command-fn query-fn]
  ;; e/Client
  p/Client
  (login   [client args tap] (login-fn args tap))
  (logout  [client tap] (logout-fn tap))
  (command [client cmd args tap] (command-fn cmd args tap))
  (query   [client args tap] (query-fn args tap)))

;; this client instance gets its attributes swapped out depending on the needs
;; of the test
(def client (map->TestClient {:login-fn identity
                              :logout-fn identity
                              :command-fn identity
                              :query-fn identity}))

;; This is an easy way to inject the client into the event-fx handlers.
;; It ensures the protocol is satisfied.
;; This could also be done by registering a cofx named :client like so:
;;  (reg-cofx :client #(assoc % :client some-client-instance))
(request/set-client client)

;; this is an example of the handlers provided by this library
(reg-event-fx
 :test-event-fx
 [(re-frame/inject-cofx :client)]
 (fn [{:keys [db client] :as cofx} [_ done]]
   (done)
   ;; must return a map to re-frame
   {}))

(deftest client-cofx
  (testing "a client is accessible on the :client cofx"
    (async done
           (dispatch [:test-event-fx done]))))

(def xyz-db
  {:editable {:x {:y {:inputs {:b 1 :z 1}}
                  :_meta {:defaults {:z 2 :c 3}}}}})

(deftest resolve-args
  ;; there are three sources of args to send to the client:
  ;;  - :_meta :defaults
  ;;  - :inputs of thing
  ;;  - provide args directly
  ;; they are merged by giving a :merge option
  (testing "resolve-args with args and no merge option"
    (let [cofx {:db {}}
          event-vec [:event-name :login :new {:args {:x true}}]]
      ;; simplest of all possible args
      (is (= {:x true} (request/resolve-args cofx event-vec)))))
  (testing "resolve-args with args and declared merge :defaults"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x :y {:args {:a true} :merge :defaults}]]
      (is (= {:a true :z 2 :c 3} (request/resolve-args cofx event-vec)))))
  (testing "resolve-args with args and declared merge :inputs"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x :y {:args {:a true} :merge :inputs}]]
      (is (= {:a true :b 1 :z 1} (request/resolve-args cofx event-vec)))))
  (testing "resolve-args with args and declared merge both [:inputs :defaults]"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x :y {:args {:a true} :merge [:defaults :inputs]}]]
      ;; all three sources
      (is (= {:a true :b 1 :z 1 :c 3} (request/resolve-args cofx event-vec)))))
  (testing "resolve-args with no args and declared merge :defaults and inputs win"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x :y {:merge :defaults}]]
      (is (= {:b 1 :z 1 :c 3} (request/resolve-args cofx event-vec)))))
  (testing "resolve-args with no args and no merge only inputs are resolved"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x :y]]
      (is (= {:b 1 :z 1} (request/resolve-args cofx event-vec)))))
  (testing "resolve args that are nil into an empty map"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :something :else]]
      (is (= {} (request/resolve-args cofx event-vec))))))

(defn add-inputs [db event-vec]
  (assoc-in db (h/e-scope event-vec :inputs) (:args (last event-vec))))

(deftest login-handler
  (testing "create dispatch and effect"
    (let [new-db (add-inputs {} [:event-name :login :new {:args {:d 4 :e 5}}])
          cofx {:db new-db :client {}}
          event-vec [:request/login :login :new]
          result (request/login-handler cofx event-vec)]
      (is (= [:editable :login :new :state :pending true]
             (:dispatch result)))
      (let [fct (:request/login result)]
        ;; :client for the `fct' to make a request
        (is (= {} (:client fct)))
        ;; :command for cqrs client
        (is (= :request/login (:command fct)))
        ;; :args are inputs from db
        (is (= {:d 4 :e 5} (:args fct)))
        ;; :tap for updating the appropriate thing in the db in the response
        (is (= {:command :request/login
                :args {:d 4 :e 5}
                :e-scope [:editable :login :new]} (:tap fct)))
        ;; setup for the below test
        (dispatch (h/e-scope event-vec :inputs {:d 4 :e 5}))
        (async done
               ;;; testing that the same values actually make it to the client call
               (let [login-fn (fn [args tap]
                                (is (= (:args fct) args))
                                (is (= (:tap fct) tap))
                                (done))
                     new-client (assoc client :login-fn login-fn)
                     _ (request/set-client new-client)]
                 (dispatch event-vec)))))))

(deftest command-handler
  (testing "the values actually make it to the client call"
    (async done
           (let [event-vec [:request/command :x/create :new {:args {:f 6}}]
                 command-fn (fn [cmd args tap]
                              (is (= :x/create cmd))
                              (is (= {:f 6} args))
                              (is (= {:command :x/create
                                      :args {:f 6}
                                      :e-scope [:editable "x" :new]} tap))
                              (done))
                 new-client (assoc client :command-fn command-fn)
                 _ (request/set-client new-client)]
             (dispatch event-vec)))))


(deftest long-query-handler
  (testing "values are resolved from db"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x/ask :y]
          result (request/long-query-handler cofx event-vec)]
      (is (= [:editable :x :y :state :pending true]
             (:dispatch result)))
      ;; inputs are taken from e-type :x identifier :y
      (is (= {:b 1 :z 1} (get-in result [:request/query :args]))))))

(deftest query-handler
  (testing "the values actually make it to the client call - short-query-handler"
    (async done
           (let [event-vec [:request/query {:g 7}]
                 query-fn (fn [args tap]
                              (is (= {:g 7} args))
                              (is (= nil tap))
                              (done))
                 new-client (assoc client :query-fn query-fn)
                 _ (request/set-client new-client)]
             (dispatch event-vec))))
  (testing "the values actually make it to the client call - short-query-handler - with tap"
    (async done
           (let [event-vec [:request/query {:g 7} {:h 8}]
                 query-fn (fn [args tap]
                            (is (= {:g 7} args))
                            (is (= {:h 8} tap))
                            (done))
                 new-client (assoc client :query-fn query-fn)
                 _ (request/set-client new-client)]
             (dispatch event-vec))))
  (testing "the values actually make it to the client call - long-query-handler"
    ;; here we insert into the db the previously mocked attributes
    (dispatch (h/e-scope [:_ :x :y] :inputs {:i 9}))
    (async done
           (let [event-vec [:request/query :x/ask :y {:args {:g 7} :merge :inputs}]
                 query-fn (fn [args tap]
                            (is (= {:i 9 :g 7} args))
                            (is (= {:args {:i 9 :g 7}
                                    :query :x/ask
                                    :e-scope [:editable :x :y]} tap))
                            (done))
                 new-client (assoc client :query-fn query-fn)
                 _ (request/set-client new-client)]
             (dispatch event-vec)))))

(deftest logout-handler
  (testing "the request gets to the client"
    (async done
           (let [event-vec [:request/logout]
                 logout-fn (fn [tap]
                             ;; getting here is enough
                             (is (= nil tap))
                             (done))
                 new-client (assoc client :logout-fn logout-fn)
                 _ (request/set-client new-client)]
             (dispatch event-vec))))
  (testing "the request gets to the client with tap"
    (async done
           (let [event-vec [:request/logout :x]
                 logout-fn (fn [tap]
                             (is (= :x tap))
                             (done))
                 new-client (assoc client :logout-fn logout-fn)
                 _ (request/set-client new-client)]
             (dispatch event-vec)))))
