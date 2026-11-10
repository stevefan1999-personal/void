# Build your own client.jar

1. Checkout https://github.com/GregHib/void-client in IntelliJ
2. `Project Structure | Artifacts | Add | Jar` -> From Modules with Dependencies
3. Set Main Class: Loader
4. Change MANIFEST.MF directory to `./void-client/` (default will be `./void-client/client/src`)
5. OK
6. File | Build | Artifacts | void-client:jar | build

`void-client.jar` will be located in `/out/artifacts/void_client_jar/`

# Modify an existing 634 deob

1. Update the RSA keys to match - [modified-files](https://github.com/GregHib/void-client/commit/a112069f0faa589fe880180fb8fff9fc10c49816)
2. Disable the lobby by setting the initial login stage to 2 - [modified-files](https://github.com/GregHib/void-client/pull/2)
3. Fix the area sound packet from (id = 5, size = 6) to size = 8 - [modified-files](https://github.com/GregHib/void-client/commit/51da151e3563d01c27284f57d88cc15dab4fbcd4)
4. Support java 6+ by skipping `Runtime.load0` call - [modified-files](https://github.com/GregHib/void-client/pull/3)

> [Code changes can be found here](https://gist.github.com/GregHib/900e90082314f949b04ff5c0d3e4d8ab) however naming will likely differ in your deob client.

## TeaVM browser client source

The browser integration pins a separate client repository at
`external/teavm-client`. It does not import that source under Void's license or
apply Void's Gradle conventions to it.

Initialize the pinned checkout before building the browser client:

```sh
git submodule update --init external/teavm-client
git -C external/teavm-client lfs install --local
git -C external/teavm-client lfs pull --include="gradle/wrapper/gradle-wrapper.jar,originals/31071.jar"
```

The client host currently requires authentication. Use approved Git credentials
for that host. Never put a token in `.gitmodules`, commands, or committed files.
There is no fallback to another repository or an old compiled client.

The pinned browser build uses official TeaVM `0.16.0` and the client's own
Gradle wrapper. Native C/SDL and Java desktop builds are not browser
prerequisites. Review the client source and dependency redistribution terms
before publishing a client-containing distribution.
