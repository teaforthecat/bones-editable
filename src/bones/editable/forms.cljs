(ns bones.editable.forms
  (:require [re-frame.core :as re-frame :refer [dispatch dispatch-sync subscribe]]
            [bones.editable.helpers :as h]))

(defn detect-controls [{:keys [enter escape]}]
  (fn [keypress]
    (case (.-which keypress)
      13 (enter)
      ;; chrome won't fire 27, so use on-blur instead
      27 (escape)
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


(defn calculate-command [e-type action]
  (keyword (name e-type) (name action)))

(defn save-fn
  ;; the event is the button click
  ([form-type identifier event]
   ;; call it, save all the inputs. this way it can be used without parens in hiccup, which
   ;; is kind of neat
   (let [action (if (= :new identifier) :new :update)
         ;; convention for commands here e.g.: :todos/update
         calculated-command (calculate-command form-type action)]
     (dispatch [:request/command calculated-command identifier]))))

(defn submit
  ([command args]
   (submit command args {}))
  ([command args opts]
   (fn []
     (dispatch [:request/command
                command
                (if (fn? args) (args) args)
                opts]))))

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

     ;; use submit for customizations
     :submit submit
     ;; save comes loaded with conventions
     :save (partial save-fn form-type identifier)
     :delete #(dispatch [:request/command (calculate-command form-type :delete) {:id identifier}])
     :reset-event reset-event
     :reset  #(dispatch reset-event)
     :edit   (fn [attr]
               #(dispatch [:editable
                           [:editable e-type identifier :state :reset (inputs)]
                           [:editable e-type identifier :state :editing attr true]]))}))
