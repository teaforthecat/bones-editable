(ns bones.runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [bones.editable-test]
            [bones.editable.request-test]
            [bones.editable.response-test]
            [bones.editable.subs-test]
            [bones.editable.forms-test]))

(doo-tests 'bones.editable-test
           'bones.editable.request-test
           'bones.editable.response-test
           'bones.editable.subs-test
           'bones.editable.forms-test)
