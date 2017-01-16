(ns bones.editable
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [cljs.reader :refer [read-string]]
            [re-frame.core :as re-frame]
            [bones.editable.protocols :as p]
            [cljs.spec :as s]))

(def debug (if js/goog.DEBUG re-frame/debug))

(defn reg-event-db [name interceptors func]
  (re-frame/reg-event-db name (into [debug] interceptors) func))

(defn reg-event-fx [name interceptors func]
  (re-frame/reg-event-fx name (into [debug] interceptors) func))

(defn reg-fx [name func]
  (re-frame/reg-fx name func))

(defn reg-cofx [name func]
  (re-frame/reg-cofx name func))

(defn dispatch [eventv]
  (re-frame/dispatch eventv))

(defn dispatch-sync [eventv]
  (re-frame/dispatch-sync eventv))

(defn subscribe [eventv]
  (re-frame/subscribe eventv))

(comment
;; start editable
(s/def ::inputs map?)
(s/def ::errors map?)
(s/def ::state map?)
(s/def ::defaults map?)
(s/def ::response map?)
(s/def ::formable (s/keys :opt-un [::inputs ::errors ::state ::response ::defaults]))
(s/def ::unique-thing-id (s/or :s string? :k keyword? :i integer? :u uuid?))
(s/def ::identifier (s/every-kv ::unique-thing-id ::formable))
(s/def ::form-type (s/or :s string? :k keyword? :i integer?))
(s/def ::editable (s/nilable (s/every-kv ::form-type ::identifier )))

  (s/exercise ::unique-thing-id)
  ;; top level is nilable so no data is required to start
  ;; goes like this:
  ;; get-in db [editable form-type identifier :inputs]
  ;; get-in db [:editable :x :y :inputs :z]
  (s/conform ::editable {:x {:y {:inputs {:z 123}}}})
  (s/explain ::editable {:x {:y nil}})

  )
;; end editable

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
;; subscriptions
