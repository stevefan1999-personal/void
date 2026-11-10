# Build your own client.jar

1. Checkout https://github.com/GregHib/void-client in IntelliJ
2. `Project Structure | Artifacts | Add | Jar` -> From Modules with Dependencies
3. Set Main Class: Loader
4. Change MANIFEST.MF directory to `./void-client/` (default will be `./void-client/client/src`)
5. OK
6. File | Build | Artifacts | void-client:jar | build

`void-client.jar` will be located in `/out/artifacts/void_client_jar/`

# Use the TeaVM browser client (optional)

The client stays in the separate [634-deob repository](https://github.com/stevefan1999-personal/634-deob), which currently requires private repository access.
Use revision [`d949b478`](https://github.com/stevefan1999-personal/634-deob/tree/d949b478a95f63d6717614af84f79f40beb7c020) as the source pin.
Build the client separately and supply its `client-web/build/upstream/void-client.js` output.
This classic script contains the TeaVM 0.16.0 WasmGC client and runtime. It needs a browser with WasmGC support.
Void does not fetch or build the client, and its default client stays unchanged.

For development, copy `game/src/main/resources/game.properties` to the repository root if no local copy exists.
Change these values in that local `game.properties`:

```properties
web.server.enabled=true
web.client.path=/absolute/path/to/634-deob/client-web/build/upstream/void-client.js
```

With the game cache installed, generate the existing website with `./gradlew :web:site:run`, then start the server with `./gradlew :game:run`.
Open `http://localhost:8080/play.html?world=1` to select the local world.
The existing page loads `/play/void-client.js` and connects to `/proxy`. No additional routes or Wasm files are needed.
For another host, update the world's `web` address in `web/site/src/main/resources/static/worlds.json` before generating the website.
Use HTTPS for remote browser access, with WebSocket forwarding to `/proxy`.

To include the same script in the existing server bundle:

```bash
./gradlew :game:bundleDistZip -PwebClient=/absolute/path/to/634-deob/client-web/build/upstream/void-client.js
```

The bundle contains the supplied script as `data/void-client.js` instead of the default client and its source map.
After extraction, enable `web.server.enabled` and keep `web.client.path=./data/void-client.js`.
Copy the generated `build/pages/` into the bundle's `build/pages/`, or set `web.server.pages` to your generated website directory.
Install the game cache and start the server as normal. The bundle does not contain the cache or website.
Without `-PwebClient`, packaging uses the existing client. Neither mode changes files in the source checkout.

# Modify an existing 634 deob

1. Update the RSA keys to match - [modified-files](https://github.com/GregHib/void-client/commit/a112069f0faa589fe880180fb8fff9fc10c49816)
2. Disable the lobby by setting the initial login stage to 2 - [modified-files](https://github.com/GregHib/void-client/pull/2)
3. Fix the area sound packet from (id = 5, size = 6) to size = 8 - [modified-files](https://github.com/GregHib/void-client/commit/51da151e3563d01c27284f57d88cc15dab4fbcd4)
4. Support java 6+ by skipping `Runtime.load0` call - [modified-files](https://github.com/GregHib/void-client/pull/3)

> [Code changes can be found here](https://gist.github.com/GregHib/900e90082314f949b04ff5c0d3e4d8ab) however naming will likely differ in your deob client.

