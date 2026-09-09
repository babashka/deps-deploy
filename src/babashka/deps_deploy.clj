(ns babashka.deps-deploy
  "Deploys a jar and its POM to a Maven repository, or installs them in
  ~/.m2. Plain HTTP with basic auth, no Maven, so it runs on the JVM and in
  babashka. A stand-in for slipset/deps-deploy with its options, so a
  build.clj moves by changing one symbol."
  (:require [babashka.deps-deploy.gpg :as gpg]
            [babashka.deps-deploy.settings :as settings]
            [babashka.http-client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(defn- clojars []
  {:id "clojars"
   :url (or (System/getenv "CLOJARS_URL") "https://repo.clojars.org/")})

;;;; POM

(defn coordinates
  "group, artifact and version from POM text, the parent's when the POM
  inherits them."
  [pom-text]
  (let [root (settings/parse pom-text)
        parent (settings/child root "parent")
        get (fn [tag] (or (settings/child-text root tag)
                          (some-> parent (settings/child-text tag))))]
    {:group (get "groupId")
     :artifact (settings/child-text root "artifactId")
     :version (get "version")}))

(defn classifier
  "The classifier in a jar file name, artifact-version-classifier.jar; nil
  for artifact-version.jar or any other name."
  [{:keys [artifact version]} file]
  (let [name (.getName (io/file (str file)))
        prefix (str artifact "-" version "-")]
    (when (and (str/starts-with? name prefix) (str/ends-with? name ".jar"))
      (let [c (subs name (count prefix) (- (count name) 4))]
        (when (re-matches #"[\p{Alnum}_.-]+" c) c)))))

;;;; checksums

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- digest [algorithm ^bytes bs]
  (hex (.digest (MessageDigest/getInstance algorithm) bs)))

;;;; HTTP

(defn- opts [auth]
  (cond-> {:throw false}
    auth (assoc :basic-auth auth)))

(defn- put!
  "PUTs bytes to url. Throws with the status and body on anything but 2xx."
  [url auth ^bytes body]
  (let [{:keys [status] :as resp} (http/request (assoc (opts auth)
                                                       :method :put
                                                       :uri url
                                                       :body body
                                                       :headers {"Content-Type" "application/octet-stream"}))]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "Could not transfer " url ": HTTP " status
                           (let [b (str/trim (str (:body resp)))] (when-not (str/blank? b) (str " " b))))
                      {:url url :status status :body (:body resp)})))
    url))

(defn- get-text
  "The body at url, nil when the repository has nothing there."
  [url auth]
  (let [{:keys [status body]} (http/get url (opts auth))]
    (cond (= 200 status) body
          (#{404 410} status) nil
          :else (throw (ex-info (str "Could not read " url ": HTTP " status) {:url url :status status})))))

;;;; metadata

(defn- stamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn updated-metadata
  "maven-metadata.xml with version added: the existing versions kept in
  order, version appended once, release set to it. The shape is Aether's,
  which writes no latest for releases."
  [existing group artifact version]
  (let [old (when existing (map second (re-seq #"<version>([^<]+)</version>" existing)))
        versions (distinct (concat old [version]))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<metadata>\n"
         "  <groupId>" group "</groupId>\n"
         "  <artifactId>" artifact "</artifactId>\n"
         "  <versioning>\n"
         "    <release>" version "</release>\n"
         "    <versions>\n"
         (apply str (map #(str "      <version>" % "</version>\n") versions))
         "    </versions>\n"
         "    <lastUpdated>" (stamp) "</lastUpdated>\n"
         "  </versioning>\n"
         "</metadata>\n")))

;;;; repositories and credentials

(defn- repository
  "A flat map with :id and :url from the :repository option: nil for
  Clojars, a URL, a flat map, or deps-deploy's {\"id\" {:url ...}}."
  [r]
  (cond (nil? r) (clojars)
        (string? r) (if (re-find #"^[a-z0-9+]+://" r)
                      {:url r}
                      (throw (ex-info (str "Repository " r " needs a URL: {\"" r "\" {:url ...}}") {:repository r})))
        (and (map? r) (:url r)) r
        (and (map? r) (= 1 (count r)) (string? (key (first r))) (:url (val (first r))))
        (let [[id m] (first r)] (assoc m :id id))
        :else (throw (ex-info (str "Unknown :repository " (pr-str r)) {:repository r}))))

(defn- present [s]
  (when-not (str/blank? s) s))

(defn- credentials
  "username and password: the repository's own, then for Clojars
  CLOJARS_USERNAME and CLOJARS_PASSWORD, then the <server> with the
  repository's id in settings.xml. Throws when none of them has both."
  [{:keys [id url username password]} settings-file]
  (or (when (and (present username) (present password)) {:username username :password password})
      (when (= "clojars" id)
        (let [u (present (System/getenv "CLOJARS_USERNAME")) p (present (System/getenv "CLOJARS_PASSWORD"))]
          (when (and u p) {:username u :password p})))
      (when id (get (settings/servers (or settings-file (settings/user-settings-file))) id))
      (throw (ex-info (str "No credentials for " (or id url) ": give :username and :password"
                           (when (= "clojars" id) ", set CLOJARS_USERNAME and CLOJARS_PASSWORD")
                           (if id (str " or add a <server> with id " id " to settings.xml") " or give the repository an :id for settings.xml"))
                      {:repository (or id url)}))))

(defn- with-slash [url]
  (if (str/ends-with? url "/") url (str url "/")))

;;;; files to publish

(defn- read-bytes [file]
  (with-open [in (io/input-stream file)] (.readAllBytes in)))

(defn- artifacts
  "The :artifact option as a sequence: one jar or several."
  [artifact]
  (if (or (string? artifact) (instance? java.io.File artifact)) [artifact] (seq artifact)))

(defn- files
  "The files to publish as [name bytes] pairs: each jar under its Maven
  name, the POM, and with :sign-releases? a gpg signature for each."
  [{:keys [artifact sign-releases? sign-key-id read-passphrase?]} coords pom-text]
  (let [{:keys [artifact-id version]} coords
        jar-name (fn [jar] (str artifact-id "-" version (some->> (classifier coords jar) (str "-")) ".jar"))
        pom-name (str artifact-id "-" version ".pom")
        plain (conj (mapv (fn [jar] [(jar-name jar) (read-bytes jar)]) (artifacts artifact))
                    [pom-name (.getBytes ^String pom-text "UTF-8")])
        signed (when sign-releases?
                 (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "deps-deploy-" (System/nanoTime)))
                       gpg-opts {:key-id sign-key-id
                                 :passphrase (when read-passphrase? (gpg/read-passphrase))}]
                   (.mkdirs dir)
                   (mapv (fn [[name ^bytes bytes]]
                           (let [copy (io/file dir name)]
                             (io/copy bytes copy)
                             [(str name ".asc") (read-bytes (gpg/sign! copy gpg-opts))]))
                         plain)))]
    (into plain signed)))

(defn- version-path [{:keys [group artifact-id version]}]
  (str (str/replace group "." "/") "/" artifact-id "/" version "/"))

;;;; remote

(defn- deploy-remote [{:keys [settings] :as options} coords pom-text]
  (let [repo (repository (:repository options))
        {:keys [username password]} (credentials repo settings)
        auth [username password]
        {:keys [group artifact-id version]} coords
        base (str (with-slash (:url repo)) (version-path coords))
        artifact-base (str (with-slash (:url repo)) (str/replace group "." "/") "/" artifact-id "/")
        upload! (fn [url ^bytes body]
                  [(put! url auth body)
                   (put! (str url ".md5") auth (.getBytes ^String (digest "MD5" body) "UTF-8"))
                   (put! (str url ".sha1") auth (.getBytes ^String (digest "SHA-1" body) "UTF-8"))])
        to-upload (files options coords pom-text)
        metadata-url (str artifact-base "maven-metadata.xml")
        metadata (updated-metadata (get-text metadata-url auth) group artifact-id version)]
    (println "Deploying" (str group "/" artifact-id "-" version) "to" (or (:id repo) (:url repo)) "as" username)
    (into []
          (concat (mapcat (fn [[name bytes]] (upload! (str base name) bytes)) to-upload)
                  (upload! metadata-url (.getBytes ^String metadata "UTF-8"))))))

;;;; local

(defn- local-repository
  "~/.m2/repository, or the maven.repo.local property as Maven honours it."
  []
  (if-let [p (System/getProperty "maven.repo.local")]
    (io/file p)
    (io/file (System/getProperty "user.home") ".m2" "repository")))

(defn- deploy-local [options coords pom-text]
  (let [{:keys [group artifact-id version]} coords
        dir (io/file (local-repository) (version-path coords))
        artifact-dir (.getParentFile dir)
        metadata (io/file artifact-dir "maven-metadata-local.xml")]
    (println "Installing" (str group "/" artifact-id "-" version) "in" (str (local-repository)))
    (.mkdirs dir)
    (let [written (mapv (fn [[name ^bytes bytes]]
                          (let [f (io/file dir name)]
                            (io/copy bytes f)
                            (str f)))
                        (files options coords pom-text))]
      (spit metadata (updated-metadata (when (.exists metadata) (slurp metadata)) group artifact-id version))
      (conj written (str metadata)))))

;;;; entry points

(defn deploy
  "Deploys a jar and its POM to a Maven repository, or installs them in
  ~/.m2/repository. The options are deps-deploy's:

    :artifact         path to the jar, required; uploaded as
                      artifact-version.jar, or artifact-version-classifier.jar
                      when its name has that shape. A vector of paths
                      publishes several jars, a -sources one say, in one go
    :pom-file         path to the POM, default \"pom.xml\"
    :installer        :remote, the default, or :local
    :repository       nil for Clojars; a URL; {:url :id :username :password};
                      or {\"id\" {:url ...}} as deps-deploy has it
    :sign-releases?   sign the jar and POM with gpg and publish the .asc files
    :sign-key-id      the gpg key to sign with, the default key otherwise
    :read-passphrase? ask for the gpg passphrase on the console; without it
                      gpg-agent supplies it
    :settings         settings.xml to read credentials from, default ~/.m2/settings.xml

  Credentials: the repository's :username and :password, for Clojars
  then CLOJARS_USERNAME and CLOJARS_PASSWORD, a deploy token as the
  password, then the <server> in settings.xml whose id is the repository's.
  CLOJARS_URL replaces the Clojars URL. Blank values count as unset.

  Uploads each file with an md5 and sha1 next to it, then the artifact's
  maven-metadata.xml with the version added. Returns the URLs uploaded, or
  the files written, in order. Throws on the first failure, with the URL
  and status."
  [{:keys [artifact pom-file installer] :or {pom-file "pom.xml" installer :remote} :as options}]
  (when-not artifact (throw (ex-info "Missing :artifact, the jar to deploy" {})))
  (when-not (#{:remote :local} installer)
    (throw (ex-info (str "Unknown :installer " (pr-str installer) ", use :remote or :local") {:installer installer})))
  (doseq [jar (artifacts artifact)]
    (when-not (.exists (io/file jar)) (throw (ex-info (str "No such file: " jar) {:artifact jar}))))
  (when-not (.exists (io/file pom-file)) (throw (ex-info (str "No such file: " pom-file) {:pom-file pom-file})))
  (let [pom-text (slurp pom-file)
        {:keys [group artifact version] :as coords} (coordinates pom-text)
        coords (assoc coords :artifact-id artifact)
        options (assoc options :pom-file pom-file :installer installer)]
    (when (some str/blank? [group artifact version])
      (throw (ex-info (str "No coordinates in " pom-file) {:pom-file pom-file :coordinates coords})))
    (when (str/ends-with? version "-SNAPSHOT")
      (throw (ex-info "SNAPSHOT versions are not supported yet" {:version version})))
    (if (= :local installer)
      (deploy-local options coords pom-text)
      (deploy-remote options coords pom-text))))

(defn -main
  "deploy or install, the jar, then optionally true to sign and a key id,
  as deps-deploy's main."
  [deploy-or-install artifact & [sign-releases sign-key-id]]
  (deploy {:installer (case deploy-or-install
                        "deploy" :remote
                        "install" :local
                        (throw (ex-info (str "Expected deploy or install, got " deploy-or-install) {})))
           :sign-releases? (= "true" sign-releases)
           :sign-key-id sign-key-id
           :artifact artifact}))
