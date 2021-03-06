(defproject bones/editable "0.1.5"
  :description "lifecycle events for forms using re-frame"
  :url "https://github.com/teaforthecat/bones-editable"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.10.516"]
                 [re-frame "0.10.6"]]

  :profiles {:dev {
                   :source-paths ["src" "test" "dev"]
                   :dependencies [[binaryage/devtools "0.8.3"]
                                  [figwheel-sidecar "0.5.4-3"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  [day8.re-frame/test "0.1.3"]
                                  ]
                   :plugins [[lein-cljsbuild "1.1.4"]
                             [lein-figwheel "0.5.4-3"]
                             [lein-doo "0.1.8"]]}}

  :source-paths ["src"]
  :test-paths ["test"]

  :doo {;:debug true
        :alias {:default [:firefox]}
        :build "test"}

  :cljsbuild {:builds
              [{:id           "dev"
                 :figwheel {}
                 :source-paths ["src"]
                 :compiler     {:asset-path "js/compiled/out"
                                :output-to "resources/public/js/compiled/bones/editable.js"
                                :output-dir "resources/public/js/compiled/out"
                                :source-map-timestamp true
                                :main          bones.editable
                                :optimizations :none}}
               {:id           "test"
                :figwheel {}
                :source-paths ["src" "test"]
                :compiler     {:output-to     "out/test/out.js"
                               :output-dir    "out/test/"
                               :closure-defines {"goog.DEBUG" false} ;too noisy
                               :main          bones.runner
                               :optimizations :none}}
               ]}
  )
