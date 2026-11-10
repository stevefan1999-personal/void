# Build and run the TeaVM browser distribution

The browser distribution combines Void's server, its generated website, and a
separately pinned TeaVM client. It uses the existing `/play/void-client.js` and
`/proxy` routes. Normal server builds do not require this client or Deno.

## Prepare the inputs

1. Initialize the client as described in [Client Building](Client-Building.md).
   The source host currently requires approved Git authentication.
2. Install Deno `2.9.7`. The client uses its own Gradle `9.5.0` wrapper and
   provisions Java 8 and Java 25 toolchains.
3. Obtain the game cache as described in the
   [Installation Guide](Installation-Guide.md). Site generation reads this cache.
   The cache is not included in the application archive.

The Git submodule and `gradle/teavm-client.properties` must name the same full
client revision. Its browser compiler is official TeaVM `0.16.0`. C, Rust, SDL,
and Java desktop builds are not prerequisites.

The producer rejects missing initialization, source changes, incorrect pins,
unhydrated wrapper files, incorrect tool versions, and failed builds. It does
not substitute the legacy client or an old local artifact.

## Build the complete package

From the Void checkout:

```sh
./gradlew :web:browser:installBrowserDist -PbrowserCacheDir=/path/to/cache
./gradlew :web:browser:browserDistZip -PbrowserCacheDir=/path/to/cache
```

The installed application is under `web/browser/build/install/void-browser`.
The archive is under `web/browser/build/distributions/`.

The package contains the server JAR, generated pages, the single generated
`data/void-client.js`, browser-enabled properties, startup scripts, and notices.
`client.properties` records the source revision, tool versions, and script hash.
The legacy client, game cache, existing saves, credentials, and local cluster
configuration are not copied into the package.

The browser profile disables the account administration API and automatic
administrator assignment. It retains ordinary character creation and tutorial
settings. This is not a production deployment or a production security review.

## Run without putting saves in build output

```sh
./gradlew :web:browser:runBrowser -PbrowserCacheDir=/path/to/cache
```

This prepares a durable `instances/browser` directory, then runs its launcher.
Open `http://localhost:8080/play` and select the configured world. The server
uses its existing listener behavior, so use an isolated development network.
This task does not install TLS, ingress, firewall rules, or VPN settings.

The runtime cache is separate from the site-generation input. The server can
download it on first start, or you can populate the instance's `data/cache/`.

Stop the server before preparing an update. The managed instance retains its
`game.properties`, ownership marker, saves, cache, reports, avatars, and temporary
runtime data. Gradle `clean` does not remove it. An unowned nonempty directory
is rejected, never reset. Incompatible world, port, or storage settings fail
before an update instead of being overwritten.

Do not redirect runtime storage through environment variables outside the
managed paths. Void's existing environment-over-file precedence still applies.
The preservation checks inspect file configuration, not arbitrary runtime
environment overrides. Back up durable player data before operating on it.

`installBrowserDist` is generated output, not a safe place for player saves. To run an
extracted archive, put it in a durable directory first. Run `run-browser` on
Unix or `run-browser.bat` on Windows. Never replace its runtime data during an
application update.

## Configure a different world or host

Gradle properties take precedence over their environment alternatives:

|    Gradle property    |     Environment variable     |            Default            |
|-----------------------|------------------------------|-------------------------------|
| `browserPublicUrl`    | `VOID_BROWSER_PUBLIC_URL`    | `http://localhost:<web port>` |
| `browserWebPort`      | `VOID_BROWSER_WEB_PORT`      | Upstream web port, `8080`     |
| `browserGamePort`     | `VOID_BROWSER_GAME_PORT`     | Upstream game port, `43594`   |
| `browserWorld`        | `VOID_BROWSER_WORLD`         | Upstream world number, `16`   |
| `browserWorldName`    | `VOID_BROWSER_WORLD_NAME`    | Upstream world name           |
| `browserWorlds`       | `VOID_BROWSER_WORLDS`        | Generated local world list    |
| `browserCacheDir`     | `VOID_BROWSER_CACHE_DIR`     | Checkout `data/cache/`        |
| `browserAssetsSource` | `VOID_BROWSER_ASSETS_SOURCE` | Verified public downloads     |
| `browserRunDir`       | `VOID_BROWSER_RUN_DIR`       | Checkout `instances/browser`  |

For example:

```sh
./gradlew :web:browser:installBrowserDist \
  -PbrowserCacheDir=/path/to/cache \
  -PbrowserPublicUrl=https://game.example.test \
  -PbrowserWorldName="Browser world"
```

Provide a JSON world list with `browserWorlds` when several worlds are needed.
The same loaded input renders the visible picker and supplies served
`worlds.json`. Changing that input regenerates both. The file is public site
content. Never put credentials or private configuration in it.

Public URL and world-list choices are build inputs. Rebuild the site when they
change. Do not overwrite only the generated JSON and leave stale picker HTML.

## Self-hosted play assets

The browser profile stages the existing Alpine and font dependencies locally.
Public URLs, hashes, and license files are pinned in
`web/site/src/main/resources/site-assets.json`. Changed or missing assets fail
the build. There is no silent network or old-artifact fallback.

For an offline build, `browserAssetsSource` can name a complete verified vendor
directory from a previous browser package. Its bytes are checked against the
pins. This does not embed the game cache or promise offline world-map tiles.

## Check the integration

```sh
./gradlew -p buildSrc spotlessCheck test
./gradlew :web:site:test :web:server:test
./gradlew :web:browser:checkBrowser -PbrowserCacheDir=/path/to/cache
```

The browser check uses the pinned client's existing Playwright harness. Install
its matching Chromium, or select a compatible system browser with `CHROMIUM`.
On Linux, Bubblewrap must provide private user, network, and PID namespaces.
The check never falls back to exposed host listeners.

When checking `buildSrc` separately after a parent build, use
`-Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false`.
This avoids reusing the Kotlin daemon's incremental registration across the two
builds. It does not disable any tests.

Acceptance copies the actual assembled application and a static cache into a
fresh fixture. It selects the visible world, logs in with a synthetic account,
completes ordinary character creation, renders, moves, logs out, checks saved
coordinates, and logs in again. Only fixture bots are disabled. Existing player
data is rejected. Browser and server processes stop after the check.

Run the incremental world-input regression outside Gradle:

```sh
deno run -A --no-config web/browser/src/test/worlds_incremental.ts \
  /path/to/cache /path/to/verified/vendor
```

It modifies only generated browser-site output and disposable test inputs.
Regenerate your application profile afterward. It does not update a managed
running instance.

## Attribution and limits

The client source remains a separate repository. Void's BSD license does not
grant rights to the reconstructed client. Review redistribution rights before
publishing an application archive. TeaVM release notices and staged public
asset licenses are included, but they do not resolve client-source rights.

TeaVM `0.16.0` has a known debug-metadata limitation. Do not interpret this
integration as a guarantee of complete named Java stack frames. Local checks
are not GitHub Actions results or production deployment evidence.
