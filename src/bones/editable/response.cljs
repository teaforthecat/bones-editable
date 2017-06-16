(ns bones.editable.response
  (:require [re-frame.core :as re-frame :refer [reg-event-fx inject-cofx]]
            [bones.editable.helpers :as h]))

;; TODO: provide api for configuring these (in addidion to defmethod)
;; something like (add-error-handler 500 :error-message "The server blew up on that request")
;; these are workable (if not sensible) defaults
;; they are meant to be overridden by redefining a method using defmethod

(def debug (if js/goog.DEBUG re-frame/debug))

;; the api for receiving responses and events with one function
;; the api for routing responses and events
(defmulti handler
   (fn [db [channel revent & [status tap]]]
    (condp = (namespace channel)
      "response" [channel status]
      "event"    channel)))

(defmethod handler :event/message
  [{:keys [db]} [channel message]]
  ;; you probably mostly want to write to the database here
  ;; and have subscribers reacting to changes
  ;;  {:db (other-multi-method db message) }
  {:db (update db :messages conj message)})

(defmethod handler :event/client-status
  [{:keys [db]} [channel message]]
  (if (contains? message :bones/logged-in?)
    {:db (assoc db :bones/logged-in? (:bones/logged-in? message))}
    {:log (str "unknown :event/client-status: " message)}))

(defn tap-success [{:keys [e-scope]} response]
  (if e-scope
    (let [[_ e-type identifier] e-scope
          inputs (:args response)]
      ;; assuming inputs should be set to args - this closes the loop
      ;; the data is that which has been persisted, in theory
      (if inputs
        {:dispatch (h/editable-response e-type identifier response inputs)}
        {:dispatch (h/editable-response e-type identifier response)}))
    {:log "missing e-scope in tap! it is needed to update the form"}))

(defn tap-error [{:keys [e-scope]} response]
  (if e-scope
    (let [[_ e-type identifier] e-scope]
      ;; assuming the whole response body is ok for :errors property
      {:dispatch (h/editable-error e-type identifier response)})
    {:log "missing e-scope in tap! it is needed to update the form"}))

(defmethod handler [:response/login 200]
  [{:keys [db]} [channel response status tap]]
  (merge {:db (assoc db :bones/logged-in? true)}
         (tap-success tap response)))

(defmethod handler [:response/login 401]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

(defmethod handler [:response/login 500]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

;; 0 provided by the browser, it is when the server isn't even running
(defmethod handler [:response/login 0]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

(defmethod handler [:response/logout 200]
  [{:keys [db]} [channel response status tap]]
  (merge {:db (assoc db :bones/logged-in? false)}
         (tap-success tap response)))

(defmethod handler [:response/logout 500]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

(defmethod handler [:response/command 200]
  [{:keys [db]} [channel response status tap]]
  (tap-success tap response))

(defmethod handler [:response/command 401]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

(defmethod handler [:response/command 400]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

(defmethod handler [:response/command 403]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

(defmethod handler [:response/command 500]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

(defmethod handler [:response/query 200]
  [{:keys [db]} [channel response status tap]]
  (tap-success tap response))

(defmethod handler [:response/query 401]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

(defmethod handler [:response/query 403]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

(defmethod handler [:response/query 500]
  [{:keys [db]} [channel response status tap]]
  (tap-error tap response))

(defn handler-channels []
  (set (map (fn [[dispatch-value]]
              (if (vector? dispatch-value)
                ;; response
                (first dispatch-value)
                ;; event
                dispatch-value))
            (methods handler))))

;; hook up the response handlers
(doseq [channel (handler-channels)]
  ;; set up handler for dispatching
  ;; tell reframe to dispatch these events to handler:
  ;; :response/login
  ;; :response/logout
  ;; :response/command
  ;; :response/query
  ;; :event/client-status
  ;; :event/message
  ;; client to start and stop it on login and logout
  (reg-event-fx channel [debug (inject-cofx :client)] handler))
