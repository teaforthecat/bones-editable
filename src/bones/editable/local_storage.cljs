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

;; this is where we establish the action-command naming convention
(defmulti take-action identity)

(defmethod take-action "new"
  [action args things]
  (assoc things (:id args) {:inputs args}))

(defmethod take-action "delete"
  [action args things]
  (dissoc things (:id args)))

(defmethod take-action "update"
  [action args things]
  (update-in things [(:id args) :inputs] merge args))

(defmethod take-action "delete-many"
  [action args things]
  (reduce dissoc things (:ids args)))

;; this can be used for testing or developing a ui without a server
;; but it will be weird if the commands used here(new,update, etc.) don't exist on the server
;; any command will store the e-type(given or derived) collection under it's name
;; serialized with pr-str and read-string
;; this would be a good place for internationalization
(defrecord LocalStorage [prefix]
  p/Client
  (login [client args tap]
    (dispatch [:response/login {:fake true} 200 tap]))
  (logout [client tap]
    (dispatch [:response/logout {:fake true} 200 tap]))
  (command [client cmd args tap]
    (try
      (let [cmdspace (or (namespace cmd) (:e-type tap))
            ;; this is where we establish the action-command naming convention
            action (name cmd)
            many? (and (not (map? args)) (iterable? args))]
        (cond
          (and (some #{"update" "delete"} action)
               (nil? (:id args)))
          (throw (js/Error. (str "no :id present in args: " {:command cmd
                                                             :args args})))
          (and many? (not (every? :id args)))
          (throw (js/Error. (str "at least one thing missing an :id in: " {:command cmd
                                                                           :args args})))
          :ok
          (let [things (local-get-item prefix cmdspace)
                ;; all things get an id because this is the happy path :)
                ;; but it may also be a list of things so this variable name
                ;; still isn't the best
                args-with-id (if (= "new" action)
                               (update args :id (fnil identity (random-uuid)))
                               args)
                response-args args-with-id]
            (local-set-item prefix cmdspace (take-action action args-with-id things))
            (dispatch [:response/command
                       ;; args in the response means update the editable thing's inputs
                       ;; it will be weird to see that the delete-many args have been
                       ;; assigned an id, but oh well - it won't be used
                       {:args response-args :command cmd}
                       ;; response status
                       200
                       tap]))))
      (catch js/Error e
          (dispatch [:response/command
                     {:errors {:message (.-message e)}}
                     401
                     tap]))))
  (query [client args tap]
    (let [{:keys [e-type]} args]
      (if e-type
        (let [things (local-get-item prefix e-type)]
          (dispatch [:response/query {:results things} 200 tap]))
        (dispatch [:response/query {:error ":e-type is nil"} 401 tap])))))
