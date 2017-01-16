# bones-editable
_This project is in review state. Please check it out and leave comments._

A Clojure library that is part of the bones project. It helps to keep reagent
form components concise when using re-frame. 

## About

The core of this library is a standardized event vector that maps to a path in
the app-db (e.g.: using (get-in app-db path)). An event can be dispatched with
this path to get data in and a subscription can be setup with this path to get
data out. This path holds things that is meant to be edited. These edits are
meant to be sent to a server and persisted which entails a life cycle of events.
The state of this life cycle is store in the `:state` key which is next to
`:inputs` and `:errors`. `:errors` can be populated from the client when inputs
don't conform to a spec, or in a response handler when the server responds with
an error code. It is then up to the component to subscribe to these errors to
render to the user.

This encapsulation of a standardized event vector gives us the ability to take
short cuts when building events to dispatch and queries to subscribe to. This
will help us build forms (I hope). Here is what an "editable" form looks
like with these short cuts (which are closures):

    (let [{:keys [reset save errors]} (e/form :login :new)]
      [:span.errors (errors :username)]
      [e/input :login :new :username :class "form-control"] ;;=> <input ...
      [:button {:on-click save}] ;;=> submit to server
      ) 

This library does not generate markup except for the input elements. These
closures are just the plumbing; the data flow.

## Usage

coming soon...



## License

Copyright © 2016 teaforthecat

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
