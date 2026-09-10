# Changelog

## Unreleased

- Add `deploy` to publish jars and POM files to Maven repositories or install them locally with `:installer :local`.
- Support checksums, Maven metadata, gpg signatures, snapshots, and multiple jars per deployment.
- Support Maven Central's Publisher Portal with `:repository :central`.
- Accept slipset/deps-deploy options and repository ids from `:mvn/repos`.
