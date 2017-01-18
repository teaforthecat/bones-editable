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

(reg-event-fx
 :test-cofx
 [(re-frame/inject-cofx :client)]
 (fn [cofx [_ done]]
   (is (= client (:client cofx)))
   (done)
   ;; must return a map
   {}))

(deftest client-cofx
  (testing "a client is accessible on the :client cofx"
    (async done
           (dispatch [:test-cofx done]))))

(def xyz-db
  ;; fixture data, a fake app-db
  ;; :editable is the top level "mount" for this library
  ;; :x is a class or type of editable thing, it holds things and may describe them with :_meta
  ;; :y is an identifier, it can be anything but is probably ":new" or matches the ":id" attribute of the thing
  ;; :inputs are the attributes of the thing, they get entered by the user and sent to the server
  ;; :_meta can hold :defaults that can be merged into the inputs
  {:editable {:x {:y {:inputs {:b 1 :z 1}}
                  :_meta {:defaults {:z 2 :c 3}}}}})

(deftest resolve-args
  ;; there are three sources of args to send to the client:
  ;;  - :_meta :defaults
  ;;  - :inputs of thing
  ;;  - provide args directly
  ;; they are merged by giving a :merge option
  (testing " args and no merge option"
    (let [cofx {:db {}}
          event-vec [:event-name :login :new {:args {:a true}}]]
      ;; simplest of all possible args
      (is (= {:a true} (request/resolve-args cofx event-vec)))))
  (testing " args and merge :defaults"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x :y {:args {:a true} :merge :defaults}]]
      (is (= {:a true :z 2 :c 3} (request/resolve-args cofx event-vec)))))
  (testing " args and merge :inputs"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x :y {:args {:a true} :merge :inputs}]]
      (is (= {:a true :b 1 :z 1} (request/resolve-args cofx event-vec)))))
  (testing " args and merge [:inputs :defaults]; merges all three"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x :y {:args {:a true} :merge [:defaults :inputs]}]]
      ;; all three sources
      (is (= {:a true :b 1 :z 1 :c 3} (request/resolve-args cofx event-vec)))))
  (testing " no args and merge :defaults; inputs win"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x :y {:merge :defaults}]]
      (is (= {:b 1 :z 1 :c 3} (request/resolve-args cofx event-vec)))))
  (testing " no args and no merge; only inputs are resolved"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :x :y]]
      (is (= {:b 1 :z 1} (request/resolve-args cofx event-vec)))))
  (testing " no sources is an empty map"
    (let [cofx {:db xyz-db}
          event-vec [:event-name :something :else]]
      (is (= {} (request/resolve-args cofx event-vec)))))
  (testing " no opts and identifier is a map; identifier is used as args"
    (let [cofx {:db {}}
          event-vec [:event-name :x {:a true}]]
      (is (= {:a true} (request/resolve-args cofx event-vec))))))

(defn add-inputs [db event-vec]
  (assoc-in db (h/e-scope event-vec :inputs) (:args (last event-vec))))

(deftest login-handler
  (testing "create dispatch and effect"
    (let [ ;; fake event to insert data, user inputs :d 4 :e 5
          new-db (add-inputs {} [:event-name :login :new {:args {:d 4 :e 5}}])
          cofx {:db new-db :client {}}
          event-vec [:request/login :login :new]
          result (request/login-handler cofx event-vec)]
      (is (= [:editable :login :new :state :pending true]
             (:dispatch result)))
      (let [fct (:request/login result)]
        ;; :client is required by the request effect
        (is (= {} (:client fct)))
        ;; :args are user inputs
        (is (= {:d 4 :e 5} (:args fct)))
        ;; :tap is for updating the appropriate thing in the db in the response
        ;; it is extra information that might be helpful
        ;; it is up to the client to actually send it along to the response handler
        (is (= {:args {:d 4 :e 5}
                :e-scope [:editable :login :new]} (:tap fct)))
        ;; setup for the below test; same fake event above, but using the real app-db
        (dispatch (h/e-scope event-vec :inputs {:d 4 :e 5}))
        (async done
               ;;; testing that the same values (args and tap) actually make it to the client
               (let [login-fn (fn [args tap]
                                (is (= (:args fct) args))
                                (is (= (:tap fct) tap))
                                (done))
                     new-client (assoc client :login-fn login-fn)
                     _ (request/set-client new-client)]
                 (dispatch event-vec)))))))

(deftest command-handler
  (testing "the values (command,args,tap) actually make it to the client"
    (async done
           ;; request/command is both an event handler and an fx handler
           ;; the event handler will create the fx call

           ;; :x/create is the command, the convention of the namespace is used
           ;; as the e-type, the type of thing to look for inputs and defaults
           ;; for in the db

           ;; :new is the identifer which resolves no inputs because there is
           ;; no :x > :new > :inputs path in the db

           ;; and tap is information that might be useful, but
           ;; it is up to the client to actually send it along to the response handler
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
      ;; inputs are taken from the db using path :x > :y > :inputs
      (is (= {:b 1 :z 1} (get-in result [:request/query :args]))))))

(deftest query-handler
  (testing " with only args given; the client receives them"
    (async done
           (let [event-vec [:request/query {:g 7}]
                 query-fn (fn [args tap]
                              (is (= {:g 7} args))
                              (is (= nil tap))
                              (done))
                 new-client (assoc client :query-fn query-fn)
                 _ (request/set-client new-client)]
             (dispatch event-vec))))
  (testing " with args and tap given; the client receives them"
    (async done
           (let [event-vec [:request/query {:g 7} {:h 8}]
                 query-fn (fn [args tap]
                            (is (= {:g 7} args))
                            (is (= {:h 8} tap))
                            (done))
                 new-client (assoc client :query-fn query-fn)
                 _ (request/set-client new-client)]
             (dispatch event-vec))))
  (testing " with an identifier and args and :merge :inputs; the client receives both as args"
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
  (testing " with no args, the client receives the call"
    (async done
           (let [event-vec [:request/logout]
                 logout-fn (fn [tap]
                             ;; getting here is enough
                             (is (= nil tap))
                             (done))
                 new-client (assoc client :logout-fn logout-fn)
                 _ (request/set-client new-client)]
             (dispatch event-vec))))
  (testing " with some args, the client receives the call with the args as tap"
    ;; just in case someone wants to send something in a logout request
    (async done
           (let [event-vec [:request/logout :x]
                 logout-fn (fn [tap]
                             (is (= :x tap))
                             (done))
                 new-client (assoc client :logout-fn logout-fn)
                 _ (request/set-client new-client)]
             (dispatch event-vec)))))
