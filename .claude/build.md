## Build Commands

```bash
./gradlew build -x test        # initial setup (cache files needed for tests)
./gradlew test                 # all tests
./gradlew test --tests "content.skill.firemaking.FiremakingTest"
./gradlew :game:test           # single module
./gradlew test -Pcoverage      # all tests with jacoco coverage reports (off by default)
./gradlew spotlessApply        # format (required before committing)
./gradlew shadowJar            # distributable JAR
./gradlew run                  # start server (working dir = root)
./gradlew bundleDistribution   # JAR + data + startup scripts
```

Tests require cache files in `data/cache/`. CI runs all engine and content tests on all PRs.

## Browser distribution

The optional `web:browser` target builds the pinned `external/teavm-client`
submodule with its independent wrapper. Do not add those Java modules to Void's
Kotlin project graph or require native C/SDL tooling.

```bash
./gradlew :web:browser:installBrowserDist -PbrowserCacheDir=/path/to/cache
./gradlew :web:browser:runBrowser -PbrowserCacheDir=/path/to/cache
./gradlew :web:browser:checkBrowser -PbrowserCacheDir=/path/to/cache
./gradlew -p buildSrc spotlessCheck test
```

Run state belongs in `instances/browser`, not `build/install`. Preserve its
configuration and player data. The browser check uses a separate synthetic
fixture with mandatory namespace isolation. Never use an existing instance or
the deployed k0s server as its test target. See `docs/Browser-Client.md` for the
source pin, authentication, tools, world configuration, and attribution limits.
