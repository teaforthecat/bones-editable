# bones-editable
_This project is in review state. Please check it out and leave comments._

A ClojureScript library that is part of the [bones](https://github.com/teaforthecat/bones) project. It helps to keep reagent
form components concise when using re-frame. Here is a demo:
https://youtu.be/dGQRDSRACB4

## About

The core concept of this library is a standardized event vector that maps to a path in
the app-db, e.g.: 

    (get-in app-db path)
    
An event can be dispatched with this path to get data into the db and a
subscription can be registered with this path to get data out. This path holds
things that are meant to be edited. These edits are meant to be sent to a server
and persisted which entails a lifecycle of events. The values and meta data about
these edits are stored in the db, which looks like this:

```clojure 
{:editable {:typeX {"id-of-X" {:inputs {}
                               :errors {}
                               :state  {}}
                     ;; optional
                     :_meta {:spec ::typeX
                             :defaults {}}}}}
```

`:inputs` are the form values. This is the form state stored in the app-db.
Basically the theory is that a human moves very slow so their inputs, even when
typing, do not pose a performance concern. The goal of this project is to
support the majority of user inputs as much as possible and I believe that this
works quite well. 

`:errors` can be populated when inputs don't conform to a spec, or when the
server responds with an error code. It is then up to the component to subscribe
to these errors and render them to the user.

`:state` It would be nice to generalize the states that forms go through and provide
a customizable state machine. That hasn't been done yet though, so this key only
holds the keyword :pending during a request. This can be used to show a spinner.

`:_meta` Keeping the descriptions of the things next to the things can be nice.
If you provide a clojure.spec under `:spec`, the inputs will be conformed before
sending them to the server and spec problems will show up in `:errors`.
`:defaults` can be set to send along with form submissions, see below. 

# Forms

We can use the path mentioned above in closure functions, which
look pretty in hiccup. Here is what an "editable" form looks like with these
closures:

```clojure
 (let [{:keys [reset save errors]} (e/form :login :new)]
   [:span.errors (errors :username)]
   [e/input :login :new :username :class "form-control"] ;;=> <input class="form-control"...
   [:button {:on-click save}] ;;=> submit to server
   ) 
```

Nice and tidy right? Here is what the component would look like without the
closures. This also shows the paths that were mentioned above.

```clojure
    (let [inputs (subscribe [:editable :login :new :inputs])
          errors (subscribe [:editable :login :new :errors])]
      [:span.errors (:username @errors)]
      [:input :class "form-control"
              :value (:username @inputs)
              :on-change #(dispatch-sync [:editable :login :new :inputs :username ( -> % .-target .-value)])]
      [:button {:on-click #(dispatch [:request/command :login :new {:args @inputs}])}])
```

The `:login` key here is serving as the type and the `:new` key is serving as an
identifier. Here, the identifier can be a simple symbol because there is only
one login form, but other types could have many identifiers. 

## Client

This project does not have an HTTP client. A client must be configured, or
plugged in. (see:
[bones.client]("https://github.com/teaforthecat/bones-client")) This is done
with a protocol and a reframe-cofx handler:

```clojure
;; Extend the Client protocol like so:
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

;; then on initialization of the app, set the cofx handler:
(e/set-client (other/Client. some-config))
    
```


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

```clojure
(defmethod e/handler [:response/command 200]
  [{:keys [db]} [_ response-body]]
  (dispatch [:hurray :success response-body]))
```

## Events

```clojure 
(defmethod e/handler :event/message
  [{:keys [db]} [channel message]]
  (dispatch [:wow :another message])))
```


## Requests

The `:request/command` event handler is loaded automatically. It is a very flexible
way to send data to the server outside of the forms.

Here is a dispatch that will send only the value of the `:args` option:
```clojure
(dispatch [:request/command :command/name nil {:args {:abc 123}}])
```

Here is a dispatch that will pull data from the db, which is the form values for
the editable thing with type :abc and id 123:

```clojure
(dispatch [:request/command :abc 123])
```

Here is a dispatch that will merge the form inputs into some defaults (defaults are
in the db under the path: `[:editable :abc :_meta :defaults]`):

```clojure
(dispatch [:request/command :abc 123 {:merge [:inputs :defaults]}])

```

You may wan to send a computed value along with form inputs and defaults. To do
this use the merge option like so:
````clojure
(dispatch [:request/command :abc 123 {:args {:xyz (+ 4 5)}
                                      :merge [:inputs :defaults]}])
```


## Testing in the browser
Install karma and headless chrome

```
npm install -g karma-cli
npm install karma karma-cljs-test karma-chrome-launcher --save-dev
```

And then run your tests

```
lein clean
lein doo chrome-headless test once
```

## License

Copyright © 2016 teaforthecat

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
