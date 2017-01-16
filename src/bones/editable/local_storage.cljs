(ns bones.editable.local-storage
  (:require [bones.editable.protocols :as p]
            [cljs.reader :refer [read-string]]
            [re-frame.core :refer [dispatch]]))

(defn local-key [prefix form-type]
  ;; use name in case keywords are provided
  (str (name prefix) "-" (name form-type)))

(defn local-get-item [prefix form-type]
  (if-let [result (.getItem js/localStorage (local-key prefix form-type))]
    (read-string result)))

(defn local-set-item [prefix form-type value]
  (.setItem js/localStorage (local-key prefix form-type) (pr-str value)))

;; this can be used for testing or developing a ui without a server
;; but it will be weird if the commands used here(new,update, etc.) don't exist on the server
;; any command will store the e-type(given or derived) collection under it's name
;; serialized with pr-str and read-string
(defrecord LocalStorage [prefix]
  p/Client
  (login [client args tap]
    (dispatch [:response/login {:fake true} 200 tap]))
  (logout [client tap]
    (dispatch [:response/logout {:fake true} 200 tap]))
  (command [client cmd args tap]
    (let [ ;; all args get an id because this is the happy path :)
          args-given-id (update args :id (fnil identity (random-uuid)))
          thing {:inputs args-given-id}
          id (get-in thing [:inputs :id]) ;; used as identifier _and_ attribute
          cmdspace (or (namespace cmd) (:form-type tap))
          action (name cmd)
          things (local-get-item prefix cmdspace)]

      (local-set-item prefix cmdspace
                      (condp = (name action)
                        "new"
                        (assoc things id thing)
                        "update"
                        (update-in things [id :inputs] merge args)
                        "delete"
                        (dissoc things id)
                        "delete-many"
                        (reduce dissoc things (:ids args))))
      (dispatch [:response/command
                 ;; args in the response means update the editable thing's inputs
                 ;; it will be weird to see that the delete-many args have been
                 ;; assigned an id, but oh well - it won't be used
                 {:args args-given-id :command cmd}
                 ;; response status
                 200
                 tap])))
  (query [client args tap]
    (let [{:keys [form-type]} args
          things (local-get-item prefix form-type)]
      (dispatch [:response/query {:results things} 200 tap]))))
