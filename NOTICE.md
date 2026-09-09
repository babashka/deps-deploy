# Notices

`babashka.deps-deploy.cipher` reads the encrypted passwords of
settings.xml and settings-security.xml in the format of plexus-cipher and
plexus-sec-dispatcher 2.0 (Codehaus Plexus, Apache License 2.0): an
8-byte salt, a pad-length byte and AES/CBC/PKCS5 ciphertext, keyed by the
SHA-256 of password and salt. The namespace is a Clojure port of that
format, copied from babashka's Maven procurer.
