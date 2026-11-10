import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

const repository = fileURLToPath(new URL("../../../../", import.meta.url));
const cache = Deno.args[0] ?? Deno.env.get("VOID_BROWSER_CACHE_DIR");
const assets = Deno.args[1] ?? Deno.env.get("VOID_BROWSER_ASSETS_SOURCE");
assert(
  cache,
  "Supply the static game cache path as argument 1 or VOID_BROWSER_CACHE_DIR",
);
assert((await Deno.stat(join(cache, "main_file_cache.dat2"))).isFile);
const report = join(repository, "web/browser/build/reports/worlds-incremental");
await Deno.mkdir(report, { recursive: true, mode: 0o700 });
const fixture = await Deno.makeTempDir({ prefix: "void-worlds-" });
const worlds = join(fixture, "worlds.json");
const output = join(repository, "web/browser/build/site");

async function writeWorld(name: string) {
  await Deno.writeTextFile(
    worlds,
    JSON.stringify([{
      number: 16,
      name,
      region: "Local",
      mode: "Normal",
      address: "localhost:43594",
      web: "http://localhost:8080",
    }]) + "\n",
  );
}

async function generate(label: string) {
  const javaHome = Deno.env.get("JAVA_HOME");
  const java = javaHome
    ? join(javaHome, "bin", Deno.build.os === "windows" ? "java.exe" : "java")
    : "java";
  const result = await new Deno.Command(java, {
    cwd: repository,
    args: [
      "-cp",
      join(repository, "gradle/wrapper/gradle-wrapper.jar"),
      "org.gradle.wrapper.GradleWrapperMain",
      "--max-workers=2",
      ":web:browser:generateSite",
      `-PbrowserWorlds=${worlds}`,
      `-PbrowserCacheDir=${cache}`,
      ...(assets ? [`-PbrowserAssetsSource=${assets}`] : []),
      "--console=plain",
    ],
    stdout: "piped",
    stderr: "piped",
  }).output();
  await Deno.writeFile(
    join(report, `${label}.log`),
    new Uint8Array([
      ...result.stdout,
      ...result.stderr,
    ]),
  );
  assert(result.success, `Site generation ${label} failed; see ${report}`);
}

try {
  await writeWorld("IncrementalBefore");
  // Invalidate only a generated page, so the baseline cannot reuse a different profile.
  await Deno.remove(join(output, "play.html")).catch((error) => {
    if (!(error instanceof Deno.errors.NotFound)) throw error;
  });
  await generate("before");
  assert(
    (await Deno.readTextFile(join(output, "play.html"))).includes(
      "IncrementalBefore",
    ),
  );
  await writeWorld("IncrementalAfter");
  await generate("after");
  assert(
    (await Deno.readTextFile(join(output, "play.html"))).includes(
      "IncrementalAfter",
    ),
    "Changing the same world file must regenerate the visible picker",
  );
  assert(
    (await Deno.readTextFile(join(output, "worlds.json"))).includes(
      "IncrementalAfter",
    ),
    "Served JSON must follow the same world input",
  );
  console.log(
    "World input changes regenerated both visible HTML and served JSON.",
  );
} finally {
  await Deno.remove(fixture, { recursive: true });
}
