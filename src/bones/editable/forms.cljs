(ns bones.editable.forms
  (:require [re-frame.core :as re-frame :refer [dispatch dispatch-sync subscribe]]
            [bones.editable.helpers :as h]))

(defn detect-controls [{:keys [enter escape]}]
  (fn [keypress]
    (case (.-which keypress)
      13 (enter keypress)
      ;; chrome won't fire 27, so use on-blur instead
      27 (escape keypress)
      nil)))

(defn field [e-type identifier attr html-attrs]
  (let [path [:editable e-type identifier :inputs attr]
        value (subscribe path)
        input-type (or (:input-type html-attrs) :input)
        value-attr (or (:value-attr html-attrs) :value)
        opts (dissoc html-attrs :value-attr :input-type)]
    (fn []
      [input-type (merge {:on-change #(dispatch-sync (conj path (-> % .-target .-value)))
                          value-attr @value}
                         opts)])))

(defn checkbox [e-type identifier attr & {:as html-attrs}]
  (field e-type identifier attr (merge {:type "checkbox"
                                           :value-attr :checked}
                                          html-attrs)))

(defn input [e-type identifier attr & {:as html-attrs}]
  (field e-type identifier attr html-attrs))

(defn conventional-command-event
  ([e-type identifier]
   (let [action (if (= :new identifier) :new :update)]
     (conventional-command-event e-type identifier action)))
  ([e-type identifier action]
   (let [cmd (keyword (name e-type) (name action))]
     [:request/command cmd identifier])))

(defn save-fn
  ([e-type identifier event-or-opts]
   ;; is it a button click event? or opts map?
   (if (aget event-or-opts "target")
     ;; it's an event so act on it
     (dispatch (conj (conventional-command-event e-type identifier) {}))
     ;; else its opts so return another function to handle the click event
     (fn [event]
       (let [opts (if (fn? event-or-opts) (event-or-opts) event-or-opts)]
         (dispatch (conj (conventional-command-event e-type identifier) opts)))))))

(defn form
  "returns function as closures around subscriptions to a single 'editable' thing in the
  db. The thing has attributes, whose current value is accessed by calling `inputs' e.g., with arguments. No arguments will return all the attributes"
  [e-type identifier]
  (let [inputs-atom (subscribe [:editable e-type identifier :inputs])
        state-atom (subscribe [:editable e-type identifier :state])
        errors-atom (subscribe [:editable e-type identifier :errors])
        defaults-atom (subscribe [:editable e-type identifier :defaults])
        inputs (fn [& args] (get-in @inputs-atom args))
        state (fn [& args] (get-in @state-atom args))
        errors (fn [& args] (get-in @errors-atom args))
        defaults (fn [& args] (get-in @defaults-atom args))]
    {:inputs inputs
     :state  state
     :errors errors
     :defaults defaults

     ;; save comes loaded with conventions
     :save (partial save-fn e-type identifier)
     :delete #(dispatch (conventional-command-event e-type identifier :delete))
     :reset  #(dispatch (h/editable-reset e-type identifier (state :reset)))
     :edit   (fn [attr]
               #(dispatch [:editable
                           [:editable e-type identifier :state :reset (inputs)]
                           [:editable e-type identifier :state :editing attr true]]))}))
