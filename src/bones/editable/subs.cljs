(ns bones.editable.subs
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [re-frame.core :refer [reg-sub reg-sub-raw]]))

;; consider simple filter k/v function

(reg-sub
 :bones/logged-in?
 (fn [db _]
   (:bones/logged-in? db)))

(defn sort-fn [sorting coll]
  ;; coll is a map of editable things
  ;; sort must be an array of :key,comparator like:
  ;; [:id >] or [:id :asc] or [:abc :desc]
  (let [[sort-key order] sorting
        ;; provide sort-cut, or use given fn
        sort-order (get {:asc < :desc >} order order)
        sort-key-fn (comp sort-key :inputs)]
    (sort-by sort-key-fn sort-order (vals coll))))

(defn sortable [db event-vec]
  (let [form-type (second event-vec)
        sorting (reaction (get-in @db (conj event-vec :_meta :sort)))
        things (reaction (dissoc (get-in @db event-vec)
                                 :_meta
                                 :new))]
    (reaction (if @sorting
                (sort-fn @sorting @things)
                @things))))
(defn single [db event-vec]
  (reaction (get-in @db event-vec)))

(reg-sub-raw
 :editable
 (fn [db event-vec]
   ;; [channel form-type identifier attribute]
   ;; [:editable form-type identifier :inputs attribute]
   ;; [:editable form-type]
   (let [identifier (get event-vec 2)])
   (if (= 2 (count event-vec))
     ;; sorted list of the inputs of editable things - the inputs are the attributes of interest
     (sortable db event-vec)
     ;; get the specific thing or attribute of thing
     (single db event-vec))))
