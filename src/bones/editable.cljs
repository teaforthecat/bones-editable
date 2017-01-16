(ns bones.editable
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [cljs.reader :refer [read-string]]
            [re-frame.core :refer [reg-event-db]]
            [bones.editable.protocols :as p]))

(defn editable-update
  "update the editable thing in the db,
   the attribute is second to last in the event vector,
   the value is last"
  [db [channel form-type id & args]]
  (assoc-in db (into [:editable form-type id] (butlast args)) (last args)))

(defn editable-update-multi
  "update the db multiple times if a vector of events are given"
  [db [channel & events]]
  (if (iterable? (first events))
    ;; we have multiple events
    (reduce editable-update db events)
    ;; single event, reconstruct the event vector
    (editable-update db (into [channel] events))))

(reg-event-db :editable [] editable-update-multi)

;; helpers
;; forms
;; request
;; response
;; subs
