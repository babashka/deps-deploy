(ns build
  "Builds and deploys deps-deploy-test-lib, from this directory:

     bb deploy 0.0.1 sources sign
     bb deploy 0.0.1 sources sign central
     clojure -T:build deploy :version '\"0.0.1\"' :sources true :sign true

  :sources adds a -sources jar, :sign signs with gpg, :central goes to
  Maven Central's portal instead of Clojars."
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.babashka/deps-deploy-test-lib)
(def class-dir "target/classes")

(defn- jar-file [version classifier]
  (format "target/%s-%s%s.jar" (name lib) version (if classifier (str "-" classifier) "")))

(defn jar
  "The jar and its POM under target, the -sources jar too with :sources."
  [{:keys [version sources]}]
  (b/delete {:path "target"})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis (b/create-basis {:project "deps.edn"})
                :src-dirs ["src"]
                :scm {:url "https://github.com/babashka/babashka.deps-deploy"
                      :connection "scm:git:git@github.com:babashka/babashka.deps-deploy.git"
                      :developerConnection "scm:git:git@github.com:babashka/babashka.deps-deploy.git"
                      :tag (str "v" version)}
                :pom-data [[:description "A library that exists to be published; babashka.deps-deploy's deploys are tried on it"]
                           [:url "https://github.com/babashka/babashka.deps-deploy"]
                           [:licenses [:license [:name "EPL-1.0"] [:url "https://www.eclipse.org/legal/epl-v10.html"]]]
                           ;; Central wants a developer
                           [:developers [:developer [:name "Michiel Borkent"]]]]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file (jar-file version nil)})
  (when sources
    (b/jar {:class-dir "src" :jar-file (jar-file version "sources")}))
  {:jar (jar-file version nil)
   :sources (when sources (jar-file version "sources"))
   :pom (b/pom-path {:lib lib :class-dir class-dir})})

(defn deploy
  "Builds, then deploys to Clojars: the jar, and the -sources jar with it
  when :sources is set. Credentials from CLOJARS_USERNAME and
  CLOJARS_PASSWORD."
  [{:keys [sign sign-key-id central] :as opts}]
  (let [{:keys [jar sources pom]} (jar opts)
        deploy (requiring-resolve 'babashka.deps-deploy/deploy)]
    (println (deploy {:installer :remote
                      :repository (when central :central)
                      :artifact (cond-> [jar] sources (conj sources))
                      :pom-file pom
                      :sign-releases? (boolean sign)
                      :sign-key-id sign-key-id}))))
