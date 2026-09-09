(ns babashka.deps-deploy.gpg
  "Detached armoured signatures through the gpg program, as deps-deploy
  makes them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn program
  "The gpg to run: the deps-deploy.gpg property, DEPS_DEPLOY_GPG, else
  gpg on the path."
  []
  (or (System/getProperty "deps-deploy.gpg") (System/getenv "DEPS_DEPLOY_GPG") "gpg"))

(defn read-passphrase
  "Asks for the passphrase on the console. Throws when there is none, as
  in CI, instead of the NullPointerException deps-deploy gives."
  []
  (if-let [console (System/console)]
    (String. (.readPassword console "%s" (into-array ["gpg passphrase: "])))
    (throw (ex-info "No console to read the gpg passphrase from; leave out :read-passphrase? and let gpg-agent supply it"
                    {}))))

(defn- run
  "Runs gpg with args, writing passphrase to its stdin when given. Returns
  {:exit :out :err}."
  [args passphrase]
  (let [process (.start (doto (ProcessBuilder. ^java.util.List (into [(program)] args))
                          (.redirectErrorStream false)))]
    (with-open [in (.getOutputStream process)]
      (when passphrase (.write in (.getBytes ^String passphrase "UTF-8"))))
    (let [out (slurp (.getInputStream process))
          err (slurp (.getErrorStream process))
          exit (.waitFor process)]
      {:exit exit :out out :err err})))

(defn sign!
  "Signs file, writing file.asc next to it, and returns that path. :key-id
  picks the key; :passphrase is fed to gpg in batch mode, otherwise
  gpg-agent handles it. Throws with gpg's stderr when signing fails."
  [file {:keys [key-id passphrase]}]
  (let [file (str file)
        args (concat (when passphrase ["--batch" "--pinentry-mode" "loopback" "--passphrase-fd" "0"])
                     (when key-id ["--local-user" (str key-id)])
                     ["--yes" "--armour" "--detach-sign" file])
        {:keys [exit err]} (try (run args passphrase)
                                (catch java.io.IOException e
                                  (throw (ex-info (str "Could not run " (program) ": " (.getMessage e))
                                                  {:file file :program (program)} e))))
        asc (str file ".asc")]
    (when-not (zero? exit)
      (throw (ex-info (str "gpg failed to sign " file " (exit " exit ")"
                           (let [e (str/trim err)] (when-not (str/blank? e) (str ": " e))))
                      {:file file :exit exit :err err})))
    (when-not (.exists (io/file asc))
      (throw (ex-info (str "gpg reported success but wrote no " asc) {:file file})))
    asc))
