(ns build
  "Builds babashka.deps-deploy and deploys it with itself. The tasks in
  bb.edn call these functions; the Clojure CLI can too:

     clojure -T:build jar
     clojure -T:build deploy :repository central"
  (:require [clojure.edn :as edn]
            [clojure.tools.build.api :as b]))

(def lib 'io.github.babashka/deps-deploy)
(def class-dir "target/classes")

(defn- version []
  (let [{:keys [major minor release]} (edn/read-string (slurp "version.edn"))]
    (format "%s.%s.%s" major minor release)))

(defn- jar-file [classifier]
  (format "target/%s-%s%s.jar" (name lib) (version) (if classifier (str "-" classifier) "")))

(defn clean
  "Remove target"
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Build the jar, a sources jar and the POM"
  [_]
  (clean nil)
  (let [version (version)]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis (b/create-basis {:project "deps.edn"})
                  :src-dirs ["src"]
                  :scm {:url "https://github.com/babashka/deps-deploy"
                        :connection "scm:git:git://github.com/babashka/deps-deploy.git"
                        :developerConnection "scm:git:ssh://git@github.com/babashka/deps-deploy.git"
                        :tag (str "v" version)}
                  :pom-data [[:description "Deploys jars to Clojars, Maven Central or any Maven repository, from babashka or the JVM"]
                             [:url "https://github.com/babashka/deps-deploy"]
                             [:licenses [:license
                                         [:name "Eclipse Public License 1.0"]
                                         [:url "https://www.eclipse.org/legal/epl-v10.html"]]]
                             [:developers [:developer [:name "Michiel Borkent"]]]]})
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/jar {:class-dir class-dir :jar-file (jar-file nil)})
    (b/jar {:class-dir "src" :jar-file (jar-file "sources")})
    {:jar (jar-file nil)
     :sources (jar-file "sources")
     :pom (b/pom-path {:lib lib :class-dir class-dir})}))

(defn install
  "Install the jar in ~/.m2"
  [_]
  (let [built (jar nil)]
    (b/install {:basis (b/create-basis {:project "deps.edn"})
                :lib lib
                :version (version)
                :jar-file (:jar built)
                :class-dir class-dir})))

(defn deploy
  "Build and deploy to Clojars or Maven Central, signed"
  {:org.babashka/cli {:spec {:repository {:desc "Where to deploy"
                                          :enum ["clojars" "central"]
                                          :default "clojars"}
                             :publish {:coerce :boolean
                                       :desc "Central: publish once validated, without the portal's Publish button"}}}}
  [{:keys [repository publish] :or {repository "clojars"}}]
  (let [{:keys [jar sources pom]} (jar nil)
        deploy (requiring-resolve 'babashka.deps-deploy/deploy)]
    (deploy {:installer :remote
             :artifact [jar sources]
             :pom-file pom
             :repository (when (= "central" (str repository)) :central)
             :sign-releases? true
             :auto-publish (boolean publish)})))
