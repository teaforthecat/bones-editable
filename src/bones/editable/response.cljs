(ns bones.editable.response
  (:require [re-frame.core :as re-frame :refer [reg-event-fx inject-cofx]]
            [bones.editable.helpers :refer [editable-reset editable-error]]
            [bones.editable.helpers :as h]))

;; these are workable (if not sensible) defaults
;; they are meant to be overridden by redefining a method using defmethod

(def debug (if js/goog.DEBUG re-frame/debug))

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

(defmethod handler [:response/login 200]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-reset form-type identifier {})}))

(defmethod handler [:response/login 401]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/login 500]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/login 0]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch [:editable form-type identifier :state :connection "failed to connect"]}))

(defmethod handler [:response/logout 200]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-reset form-type identifier {})
     :db (assoc db :bones/logged-in? false)}))

(defmethod handler [:response/logout 500]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/command 200]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    ;; (response-command response status tap) ;;......multi
    {:dispatch (h/editable-response form-type identifier response)}))

(defmethod handler [:response/command 401]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/command 400]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/command 403]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/command 500]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/query 200]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (h/editable-response form-type identifier response)}))

(defmethod handler [:response/query 401]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/query 403]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defmethod handler [:response/query 500]
  [{:keys [db]} [channel response status tap]]
  (let [{:keys [form-type identifier]} tap]
    {:dispatch (editable-error form-type identifier response)}))

(defn handler-channels []
  (set  (map (fn [[dispatch-value]]
               (if (vector? dispatch-value)
                 ;; response
                 (first dispatch-value)
                 ;; event
                 dispatch-value))
             (methods handler))))

;; hook up the response handlers
(doseq [channel (handler-channels)]
  ;; client to start and stop it on login and logout
  (reg-event-fx channel [debug (inject-cofx :client)] handler))
