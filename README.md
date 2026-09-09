# publish-jar

Publishes a jar and its POM to a Maven repository. Clojars first. Plain
Clojure over `babashka.http-client`, no Maven, so it runs on the JVM and in
[babashka](https://github.com/babashka/babashka), where it is the publish
step for a `build.clj` that runs with `bb`.

## Usage

```clojure
;; deps.edn or bb.edn
io.github.babashka/publish-jar {:mvn/version "0.0.1"}
```

```clojure
(require '[babashka.publish-jar :as publish])

(publish/publish {:jar "target/lib.jar"
                  :pom "target/classes/META-INF/maven/my.group/lib/pom.xml"
                  :repository :clojars})
```

With tools.build, `write-pom` writes the POM at that path and `jar` the jar;
`publish` takes it from there.

## What it does

Uploads the jar and the POM, an `.md5` and `.sha1` next to each, then the
artifact's `maven-metadata.xml` with the version added. Returns the URLs
uploaded, in order, and throws on the first transfer that fails, with the
URL and the HTTP status.

## Repositories

`:repository` is `:clojars`, a URL, or a map:

```clojure
{:url "https://repo.example.com/releases/"
 :id "example"            ; the <server> id in settings.xml
 :username "..."          ; or leave both out and use settings.xml
 :password "..."}
```

Credentials, in order: `:username` and `:password` in the map; for
`:clojars` the `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` environment
variables, a deploy token as the password; the `<server>` in
`~/.m2/settings.xml` whose id is the repository's. Encrypted passwords
from `settings-security.xml` are read the way Maven reads them. `:settings`
names another settings.xml.

## Not yet

Snapshots, GPG signatures and Maven Central's publisher portal.

## License

Copyright © 2026 Michiel Borkent

Distributed under the EPL License. See LICENSE. The cipher for encrypted
passwords follows plexus-cipher and plexus-sec-dispatcher, Apache License
2.0, see NOTICE.md.
