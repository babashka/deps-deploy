(ns babashka.publish-jar
  "Publishes a jar and its POM to a Maven repository: the two files with
  their md5 and sha1 sidecars, then the artifact's maven-metadata.xml with
  the version added. Plain HTTP with basic auth, no Maven."
  (:require [babashka.http-client :as http]
            [babashka.publish-jar.settings :as settings]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(def repositories
  "Named repositories: the URL and the environment variables that hold the
  credentials."
  {:clojars {:id "clojars"
             :url "https://repo.clojars.org/"
             :env ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"]}})

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
  order, version appended once, latest and release set to it."
  [existing group artifact version]
  (let [old (when existing (map second (re-seq #"<version>([^<]+)</version>" existing)))
        versions (distinct (concat old [version]))]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<metadata>\n"
         "  <groupId>" group "</groupId>\n"
         "  <artifactId>" artifact "</artifactId>\n"
         "  <versioning>\n"
         "    <latest>" version "</latest>\n"
         "    <release>" version "</release>\n"
         "    <versions>\n"
         (apply str (map #(str "      <version>" % "</version>\n") versions))
         "    </versions>\n"
         "    <lastUpdated>" (stamp) "</lastUpdated>\n"
         "  </versioning>\n"
         "</metadata>\n")))

;;;; repositories and credentials

(defn- repository
  "The repository map for the :repository option: a key of `repositories`,
  a URL, or a map with :url and optionally :id, :username, :password."
  [r]
  (cond (keyword? r) (or (get repositories r)
                         (throw (ex-info (str "Unknown repository " r ", known: " (str/join ", " (map name (keys repositories))))
                                         {:repository r})))
        (string? r) {:url r}
        (map? r) r
        :else (throw (ex-info "Missing :repository" {}))))

(defn- credentials
  "username and password for a repository: its own, then the environment
  variables it names, then the <server> with its id in settings.xml."
  [{:keys [id username password env]} settings-file]
  (or (when (and username password) {:username username :password password})
      (when-let [[u p] env]
        (let [username (System/getenv u) password (System/getenv p)]
          (when (and username password) {:username username :password password})))
      (when id (get (settings/servers (or settings-file (settings/user-settings-file))) id))))

(defn- with-slash [url]
  (if (str/ends-with? url "/") url (str url "/")))

;;;; publish

(defn publish
  "Publishes jar and pom to a Maven repository.

  Options:
    :jar         path to the jar
    :pom         path to the POM
    :repository  :clojars, a URL, or {:url ... :id ... :username ... :password ...}
    :settings    settings.xml to read credentials from, default ~/.m2/settings.xml

  Credentials: the repository map's :username and :password, then the
  environment variables a named repository uses, CLOJARS_USERNAME and
  CLOJARS_PASSWORD for :clojars, then the <server> in settings.xml whose id
  matches the repository's.

  Uploads the jar, the POM, an md5 and sha1 for each, then the artifact's
  maven-metadata.xml with the version added. Returns the URLs uploaded, in
  order. Throws on the first failing transfer."
  [{:keys [jar pom settings] :as opts}]
  (when-not jar (throw (ex-info "Missing :jar" {})))
  (when-not pom (throw (ex-info "Missing :pom" {})))
  (let [repo (repository (:repository opts))
        auth (let [{:keys [username password]} (credentials repo settings)]
               (when (and username password) [username password]))
        pom-text (slurp pom)
        {:keys [group artifact version]} (coordinates pom-text)
        _ (when (some str/blank? [group artifact version])
            (throw (ex-info (str "No coordinates in " pom) {:pom pom :coordinates {:group group :artifact artifact :version version}})))
        _ (when (str/ends-with? version "-SNAPSHOT")
            (throw (ex-info "SNAPSHOT versions are not supported" {:version version})))
        base (str (with-slash (:url repo)) (str/replace group "." "/") "/" artifact "/")
        version-base (str base version "/" artifact "-" version)
        upload! (fn [url ^bytes body]
                  [(put! url auth body)
                   (put! (str url ".md5") auth (.getBytes ^String (digest "MD5" body) "UTF-8"))
                   (put! (str url ".sha1") auth (.getBytes ^String (digest "SHA-1" body) "UTF-8"))])
        jar-bytes (with-open [in (io/input-stream jar)] (.readAllBytes in))
        pom-bytes (.getBytes ^String pom-text "UTF-8")
        metadata-url (str base "maven-metadata.xml")
        metadata (updated-metadata (get-text metadata-url auth) group artifact version)]
    (into []
          (concat (upload! (str version-base ".jar") jar-bytes)
                  (upload! (str version-base ".pom") pom-bytes)
                  (upload! metadata-url (.getBytes ^String metadata "UTF-8"))))))
