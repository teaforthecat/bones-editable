# bones-editable
_This project is in review state. Please check it out and leave comments._

A ClojureScript library that is part of the [bones](https://github.com/teaforthecat/bones) project. It helps to keep reagent
form components concise when using re-frame. Here is a demo:
https://youtu.be/FGH2KlN0wrg

## About

The core of this library is a standardized event vector that maps to a path in
the app-db, e.g.: 

    (get-in app-db path)
    
An event can be dispatched with this path to get data in, also, a subscription
can be registered with this path to get data out. This path holds things that are
meant to be edited. These edits are meant to be sent to a server and persisted
which entails a lifecycle of events. The state of this lifecycle is stored in
the `:state` key which is next to `:inputs` and `:errors`. Together they look
like this in the db:

    {:editable {:typeX {"id-of-X" {:inputs {}
                                   :errors {}
                                   :state  {}}}}}


`:errors` can be populated when inputs don't conform to a spec, or when the
server responds with an error code. It is then up to the component to subscribe
to these errors and render them to the user.

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
      [:button {:on-click #(dispatch [:request/command :login :new {:args @inputs}])}])


The `:login` key here is serving as the type and the `:new` key is serving as an
identifier. This identifier doesn't need to be a number because there is
generally never more than one login form. 

## Configure the Client

- using local storage

In early development a web app can be built with full interaction without a
server by using Local Storage. There are two ways to hook up the Local Storage client:

     (require '[bones.editable :as e])
     (require '[bones.editable.local-storage :as e.ls])

     ;; 1) on initialize
     (re-frame/reg-event-db
       :initialise-db
       (fn [_ _]
         (e/set-client (e.ls/LocalStorage. "app-name-prefix") )
         default-value))

     ;; 2) override cofx
    (re-frame/reg-cofx 
      :client
      #(assoc % :client (e.ls/LocalStorage. "app-name-prefix"))) 
    
    
     
- swapping out bones.client or other

Then when the app has blossomed it can receive a real client to talk to a
server.

First extend the Client:

    (extend-type other/Client
      e/Client
      (e/login [cmp args tap]
        (other/login cmp args tap))
      (e/logout [cmp tap]
        (other/logout cmp tap))
      (e/command [cmp cmd args tap]
        (other/command cmp cmd args tap))
      (e/query [cmp args tap]
        (other/query cmp args tap)))
   
    ;; then on initialize
    (e/set-client (other/Client. some-config))
    

## Responses

Response handlers are a very important part of an application and can often hold
a lot of system logic that is unique to the application. This isn't business
logic but system logic. Ideally this would be abstracted away so that even form
submission and success or errors could be a matter of whether there is data
present in the app-db. We are not there yet though, and I'm sure you will need
to customize the system logic that runs from a server response. That is why they
have been implemented as a multi-method. This means you can override any
response per status code that you need to. Mostly I think it will be
`:response/command 200` and `:event/message` . These are the two events that
occur from a positive response from the server. `:event/message` is a message
from a WebSocket connection or SSE channel. The other responses will result
in data being created in the app-db under the `:error` key in the editable
thing, which can be subscribed to and rendered.


- `(defmethod e/handler [:response/command 200])`

## Events

- `(defmethod e/handler :event/message)`



## Requests

Requests can be created separately from forms.
Let's create a function that will send data to the server. It will draw data
from three sources. 
  
  - user inputs (app-db)
  - e-type defaults (:_meta in app-db)
  - parameter args when called (direct)
  
The merge will need to be declared in the dispatch call.

    (defn create [e-type attrs]
      (let [command (keyword e-type :create)
            identifier :new]
        (dispatch [:request/command

                   ;; the namespace command is the e-type which holds
                      :_meta (which holds :defaults)

                   command
                   ;; :new is part of e-type and holds :inputs (user inputs)
                   identifier 
                   ;; with this the client will receive:
                   ;; {:command command :args (merge defaults attrs inputs)}
                   {:args attrs :merge [:defaults :inputs]}])))

This function doesn't actually perform the request, the registered effect
`request/command`: does. That effect will call the client that was registered as
a cofx above.
  
- `(dispatch [:request/command command-name args])`


## License

Copyright © 2016 teaforthecat

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
