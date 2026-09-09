(ns babashka.publish-jar.settings
  "The parts of ~/.m2/settings.xml that publishing needs: the servers and
  their credentials, with ${env.NAME} and system properties interpolated
  the way Maven does."
  (:require [babashka.publish-jar.cipher :as cipher]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parse [s]
  (xml/parse-str s))

(defn- tag= [tag el]
  (and (map? el) (= tag (name (:tag el)))))

(defn children [el tag]
  (filter #(tag= tag %) (:content el)))

(defn child [el tag]
  (first (children el tag)))

(defn child-text
  "The trimmed text of el's child tag, nil when absent or blank."
  [el tag]
  (when-let [c (child el tag)]
    (let [s (str/trim (apply str (filter string? (:content c))))]
      (when-not (str/blank? s) s))))

(defn interpolate
  "Replaces ${env.NAME}, ${user.home} and other system properties in s."
  [s]
  (when s
    (str/replace s #"\$\{([^}]+)\}"
                 (fn [[whole key]]
                   (or (when (str/starts-with? key "env.")
                         (System/getenv (subs key 4)))
                       (System/getProperty key)
                       whole)))))

(defn user-settings-file []
  (io/file (System/getProperty "user.home") ".m2" "settings.xml"))

(defn servers
  "The <server> entries of a settings.xml as a map of id to
  {:username :password}, passwords decrypted."
  [file]
  (let [file (io/file file)]
    (if (.exists file)
      (let [root (parse (slurp file))]
        (into {}
              (for [s (some-> (child root "servers") (children "server"))
                    :let [id (child-text s "id")]
                    :when id]
                [id {:username (interpolate (child-text s "username"))
                     :password (cipher/decrypt-password (interpolate (child-text s "password"))
                                                        {:server id})}])))
      {})))
