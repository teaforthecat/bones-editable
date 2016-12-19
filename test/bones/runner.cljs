(ns bones.runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [bones.editable-test]
            ))

(doo-tests 'bones.editable-test)
