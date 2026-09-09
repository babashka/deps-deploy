(ns babashka.deps-deploy.aether-test
  "Deploys the test library with slipset/deps-deploy, Aether underneath,
  and with babashka.deps-deploy, both into a fake repository, and compares
  what arrived. JVM only: Aether does not run in babashka."
  (:require [babashka.deps-deploy :as dd]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as slipset]
            [org.httpkit.server :as server])
  (:import [java.util Arrays]))

;; A repository: every PUT is remembered by path as bytes, GET serves it.
(defn- fake-repo []
  (let [store (atom {})
        handler (fn [{:keys [request-method uri headers body]}]
                  (cond
                    ;; the challenge header makes Aether retry with credentials
                    (not= "Basic dXNlcjpzZWNyZXQ=" (get headers "authorization"))
                    {:status 401 :headers {"WWW-Authenticate" "Basic realm=\"fake\""} :body "who?"}
                    (= :put request-method) (do (swap! store assoc uri (with-open [in (io/input-stream body)] (.readAllBytes in)))
                                                {:status 201})
                    (= :get request-method) (if-let [b (get @store uri)] {:status 200 :body (io/input-stream b)} {:status 404})
                    :else {:status 405}))
        srv (server/run-server handler {:port 0 :legacy-return-value? false})]
    {:store store :stop #(server/server-stop! srv) :url (str "http://localhost:" (server/server-port srv) "/")}))

(def ^:private root "test-resources/test-lib")

(defn- build-test-lib
  "Builds the test library and returns absolute :jar, :sources and :pom."
  [version]
  (binding [b/*project-root* root]
    (load-file (str (io/file root "build.clj")))
    (let [built ((resolve 'build/jar) {:version version :sources true})
          absolute (fn [p] (str (.getAbsoluteFile (io/file (str p)))))]
      (-> built
          (update :jar #(absolute (io/file root %)))
          (update :sources #(absolute (io/file root %)))
          (update :pom absolute)))))

(def ^:private metadata-path "/io/github/babashka/deps-deploy-test-lib/maven-metadata.xml")

(defn- same-uploads?
  "Every path Aether PUT, we PUT with the same bytes; the metadata's
  lastUpdated and the checksums of it excepted."
  [theirs ours]
  (is (= (set (keys theirs)) (set (keys ours))) "the same paths")
  (let [timeless (fn [^bytes b] (str/replace (String. b "UTF-8") #"<lastUpdated>\d+</lastUpdated>" ""))]
    (doseq [[path bytes] theirs]
      (cond (= path metadata-path)
            (is (= (timeless bytes) (timeless (get ours path))) "the same metadata")
            (str/starts-with? path metadata-path) nil
            :else (is (Arrays/equals ^bytes bytes ^bytes (get ours path)) (str "same bytes at " path))))))

(defn- versions [^bytes bytes]
  (map second (re-seq #"<version>([^<]+)</version>" (String. bytes "UTF-8"))))

(deftest same-uploads-as-aether-test
  (let [{:keys [jar pom]} (build-test-lib "0.0.1")
        theirs (fake-repo)
        ours (fake-repo)]
    (try
      (testing "the jar and the POM"
        (slipset/deploy {:installer :remote :artifact jar :pom-file pom
                         :repository {"fake" {:url (:url theirs) :username "user" :password "secret"}}})
        (dd/deploy {:installer :remote :artifact jar :pom-file pom
                    :repository {"fake" {:url (:url ours) :username "user" :password "secret"}}})
        (same-uploads? @(:store theirs) @(:store ours)))
      (testing "a second version and a -sources jar"
        (let [{:keys [jar sources pom]} (build-test-lib "0.0.2")]
          (slipset/deploy {:installer :remote :artifact jar :pom-file pom
                           :repository {"fake" {:url (:url theirs) :username "user" :password "secret"}}})
          (slipset/deploy {:installer :remote :artifact sources :pom-file pom
                           :repository {"fake" {:url (:url theirs) :username "user" :password "secret"}}})
          (dd/deploy {:installer :remote :artifact [jar sources] :pom-file pom
                      :repository {"fake" {:url (:url ours) :username "user" :password "secret"}}})
          (same-uploads? @(:store theirs) @(:store ours))
          (is (contains? @(:store ours) "/io/github/babashka/deps-deploy-test-lib/0.0.2/deps-deploy-test-lib-0.0.2-sources.jar.sha1"))
          (is (= ["0.0.1" "0.0.2"] (versions (get @(:store ours) metadata-path))))))
      (finally ((:stop theirs)) ((:stop ours))
               ;; slipset's deploy leaves nothing behind, but clean up on failure too
               (.delete (io/file "deps-deploy-test-lib-0.0.1.pom"))
               (.delete (io/file "deps-deploy-test-lib-0.0.2.pom"))))))

(defn- sha1 [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest (java.security.MessageDigest/getInstance "SHA-1") bytes))))

(defn- timeless
  "Snapshot uploads compared with their timestamps blanked: the file
  names, the metadata's timestamp, lastUpdated and updated."
  [store]
  (into {}
        (for [[path ^bytes bytes] store
              :let [blank #(-> % (str/replace #"\d{8}\.\d{6}" "TS") (str/replace #"\d{14}" "TS"))]
              :when (not (re-find #"maven-metadata\.xml\.(md5|sha1)$" path))]
          [(blank path) (if (str/ends-with? path "maven-metadata.xml")
                          (blank (String. bytes "UTF-8"))
                          (sha1 bytes))])))

(deftest same-snapshot-uploads-as-aether-test
  (let [{:keys [jar sources pom]} (build-test-lib "0.0.4-SNAPSHOT")
        theirs (fake-repo)
        ours (fake-repo)
        repo (fn [r] {"fake" {:url (:url r) :username "user" :password "secret"}})]
    (try
      (testing "build 1: the jar"
        (slipset/deploy {:installer :remote :artifact jar :pom-file pom :repository (repo theirs)})
        (dd/deploy {:installer :remote :artifact jar :pom-file pom :repository (repo ours)})
        (is (= (timeless @(:store theirs)) (timeless @(:store ours)))))
      (testing "build 2: the sources jar, the jar entry of build 1 kept"
        (Thread/sleep 1100)
        (slipset/deploy {:installer :remote :artifact sources :pom-file pom :repository (repo theirs)})
        (dd/deploy {:installer :remote :artifact sources :pom-file pom :repository (repo ours)})
        (is (= (timeless @(:store theirs)) (timeless @(:store ours))))
        (is (str/includes? (String. ^bytes (get @(:store ours) "/io/github/babashka/deps-deploy-test-lib/0.0.4-SNAPSHOT/maven-metadata.xml") "UTF-8")
                           "<buildNumber>2</buildNumber>")))
      (finally ((:stop theirs)) ((:stop ours))
               (.delete (io/file "deps-deploy-test-lib-0.0.4-SNAPSHOT.pom"))))))
