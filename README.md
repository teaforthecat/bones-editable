# bones-editable
_This project is in review state. Please check it out and leave comments._

A Clojure library that is part of the bones project. It helps to keep reagent
form components concise when using re-frame. 

## About

The core of this library is a standardized event vector that maps to a path in
the app-db (e.g.: using `(get-in app-db path)`). An event can be dispatched with
this path to get data in and a subscription can be setup with this path to get
data out. This path holds things that are meant to be edited. These edits are
meant to be sent to a server and persisted which entails a lifecycle of events.
The state of this lifecycle is store in the `:state` key which is next to
`:inputs` and `:errors`. `:errors` can be populated from the client when inputs
don't conform to a spec, or in a response handler when the server responds with
an error code. It is then up to the component to subscribe to these errors and 
render them to the user.

We can encapsulate this standardized event vector in closure functions, which
look pretty in hiccup. Here is what an "editable" form looks like with these
closures:

    (let [{:keys [reset save errors]} (e/form :login :new)]
      [:span.errors (errors :username)]
      [e/input :login :new :username :class "form-control"] ;;=> <input class="form-control"...
      [:button {:on-click save}] ;;=> submit to server
      ) 

Nice and tidy right? Here is what the component would look like without the
encapsulation. This also shows the paths that were mentioned above.

    (let [inputs (subscribe [:editable :login :new :inputs])
          errors (subscribe [:editable :login :new :errors])]
      [:span.errors (:username @errors)]
      [:input :class "form-control"
              :value (:username @inputs)
              :on-change #(dispatch-sync [:editable :login :new :inputs :username ( -> % .-target .-value)])]
      [:button {:on-click #(dispatch [:request/command :login :new {:args @inputs}])}]
    
    )


more to come...


## Requests

- `(dispatch [:request/command ...])`

## Responses

- `(defmethod handler [:response/login 200])`

## Events

- `(defmethod handler :event/message)`


## Configure the Client

- using local storage

- swapping out bones.client or other

## License

Copyright © 2016 teaforthecat

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
