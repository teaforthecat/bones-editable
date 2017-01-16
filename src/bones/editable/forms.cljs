(ns bones.editable.forms
  (:require [re-frame.core :as re-frame :refer [dispatch-sync]]))

(defn detect-controls [{:keys [enter escape]}]
  (fn [keypress]
    (case (.-which keypress)
      13 (enter)
      ;; chrome won't fire 27, so use on-blur instead
      27 (escape)
      nil)))

(defn field [form-type identifier attr html-attrs]
  (let [path [:editable form-type identifier :inputs attr]
        value (subscribe path)
        input-type (or (:input-type html-attrs) :input)
        value-attr (or (:value-attr html-attrs) :value)
        opts (dissoc html-attrs :value-attr :input-type)]
    (fn []
      [input-type (merge {:on-change #(dispatch-sync (conj path (-> % .-target .-value)))
                          value-attr @value}
                         opts)])))

(defn checkbox [form-type identifier attr & {:as html-attrs}]
  (field form-type identifier attr (merge {:type "checkbox"
                                           :value-attr :checked}
                                          html-attrs)))

(defn input [form-type identifier attr & {:as html-attrs}]
  (field form-type identifier attr html-attrs))


(defn calculate-command [form-type action]
  (keyword (name form-type) (name action)))

(defn save-fn
  ([[form-type id]]
   ;; call it, save all the inputs. this way it can be used without parens in hiccup, which
   ;; is kind of neat
   ((apply save-fn [form-type id] {} {})))
  ([[form-type id] args]
   ;; in case you don't want to pass opts
   (save-fn [form-type id] args {}))
  ([[form-type id] args opts]
   ;; opts can be options like :solo "don't merge values from :inputs"
   (fn []
     (let [new-args (if (fn? args) (args) args)
           action (if (= :new id) :new :update)
           ;; args are attributes/inputs of the thing
           more-args (merge (if (= :new id) nil {:id id}) new-args)
           ;; opts are logistical things like how to update the form from the
           ;; response
           ;; we can't send :id equal to :new because :id is probably an
           ;; attribute that will be generated
           more-opts (merge (if (= :new id) {:identifier :new} ) opts)
           ;; convention for commands here e.g.: :todos/update
           calculated-command (calculate-command form-type action)]
       (dispatch [:request/command calculated-command more-args more-opts])))))

(defn form
  "returns function as closures around subscriptions to a single 'editable' thing in the
  db. The thing has attributes, whose current value is accessed by calling `inputs' e.g., with arguments. No arguments will return all the attributes"
  [form-type identifier]
  (let [inputs-atom (subscribe [:editable form-type identifier :inputs])
        state-atom (subscribe [:editable form-type identifier :state])
        errors-atom (subscribe [:editable form-type identifier :errors])
        defaults-atom (subscribe [:editable form-type identifier :defaults])
        inputs (fn [& args] (get-in @inputs-atom args))
        state (fn [& args] (get-in @state-atom args))
        errors (fn [& args] (get-in @errors-atom args))
        defaults (fn [& args] (get-in @defaults-atom args))]
    {:inputs inputs
     :state  state
     :errors errors
     :defaults defaults

     :save (partial save-fn [form-type identifier])
     :delete #(dispatch [:request/command (calculate-command form-type :delete) {:id identifier}])
     :reset  #(dispatch (editable-reset form-type identifier (state :reset)))
     :edit   (fn [attr]
               #(dispatch [:editable
                           [:editable form-type identifier :state :reset (inputs)]
                           [:editable form-type identifier :state :editing attr true]]))}))
