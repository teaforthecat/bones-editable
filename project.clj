(defproject bones/editable "0.1.0-SNAPSHOT"
  :description "lifecycle events for forms using re-frame"
  :url "https://github.com/teaforthecat/bones-editable"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.229"]
                 [re-frame "0.8.0"]]

  :profiles {:dev {
                   :source-paths ["src" "test" "dev"]
                   :dependencies [[binaryage/devtools "0.8.3"]
                                  [figwheel-sidecar "0.5.4-3"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  ]
                   :plugins [[lein-cljsbuild "1.1.4"]
                             [lein-figwheel "0.5.4-3"]
                             [lein-doo "0.1.7"]]}}

  :source-paths ["src"]
  :test-paths ["test"]

  :doo {:debug true
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
                               :main          bones.runner
                               :optimizations :none}}
               ]}


  )
