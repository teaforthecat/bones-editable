(ns bones.editable
  (:require [re-frame.core :as re-frame :refer [reg-event-db reg-event-fx reg-fx reg-cofx]]))


(reg-event-db
 :initialize-db
 (fn [_ [channel sys default-db]]
   (merge default-db
          {:sys sys})))

(reg-cofx
 :client
 (fn [{:keys [db] :as cofx} _]
   (assoc cofx :client (:client @(:sys db)))))

(reg-fx
 :request/login
 (fn request-login
   [{:keys [client args tap]}]
   (.login client args tap)))

(reg-fx
 :request/logout
 (fn request-logout
   [{:keys [client tap]}]
   (.logout client tap)))

(reg-fx
 :request/command
 (fn request-command
   [{:keys [client command args tap]}]
   (.command client command args tap)))

(reg-fx
 :request/query
 (fn request-query
   [{:keys [client params tap]}]
   (.query client params tap)))
