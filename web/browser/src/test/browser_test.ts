import { assert, assertEquals } from "@std/assert";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { type Browser, chromium, type Page } from "playwright";
import {
  assertNoPageErrors,
  type Capture,
  trackJs5,
} from "../../../../external/teavm-client/tools/e2e/harness.ts";
import {
  changed,
  click,
  coloured,
  healthy,
  logIn,
  loginScreen,
  minimap,
  pixels,
  readState,
  until,
} from "../../../../external/teavm-client/tools/e2e/game_session.ts";

const repository = fileURLToPath(new URL("../../../../", import.meta.url));
const testFile = fileURLToPath(import.meta.url);
const configuration = join(
  repository,
  "external/teavm-client/tools/e2e/deno.json",
);
const namespaceNames = ["user", "net", "pid"] as const;
const viewport = { width: 1024, height: 824 };
const adventurer = { x: 180, y: 237 };
const confirmCharacter = { x: 805, y: 608 };
const tutorialFloor = { x: 450, y: 450 };
const decoder = new TextDecoder();
const terminalEscape = "\u001b";
const terminalColors = new RegExp(`${terminalEscape}\\[[0-9;]*m`, "g");

interface Position {
  name: string;
  x: number;
  y: number;
  level: number;
}

async function command(executable: string, arguments_: string[]) {
  const result = await new Deno.Command(executable, {
    args: arguments_,
    stdout: "piped",
    stderr: "piped",
  }).output();
  if (!result.success) {
    throw new Error(
      `${executable} failed (${result.code}): ${decoder.decode(result.stderr)}`,
    );
  }
  return decoder.decode(result.stdout).trim();
}

async function namespaces() {
  return Object.fromEntries(
    await Promise.all(
      namespaceNames.map(async (
        name,
      ) => [name, await Deno.readLink(`/proc/self/ns/${name}`)]),
    ),
  );
}

function required(name: string) {
  const value = Deno.env.get(name);
  assert(value, `Set ${name}`);
  return value;
}

function property(text: string, name: string) {
  const escaped = name.replaceAll(".", "\\.");
  const value = new RegExp(`^${escaped}=(.*)$`, "m").exec(text)?.[1];
  assert(value !== undefined, `Distribution property is missing: ${name}`);
  return value.trim();
}

async function sha256(bytes: BufferSource) {
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(
    new Uint8Array(hash),
    (byte) => byte.toString(16).padStart(2, "0"),
  ).join("");
}

const digest = async (path: string) => await sha256(await Deno.readFile(path));

async function outer() {
  const distribution = await Deno.realPath(
    required("VOID_BROWSER_DISTRIBUTION"),
  );
  const cache = await Deno.realPath(required("VOID_BROWSER_CACHE"));
  for (
    const name of ["saves", "avatars", "errors", "reports", "grand_exchange"]
  ) {
    const entries = await Array.fromAsync(
      Deno.readDir(join(distribution, "data", name)),
    );
    assertEquals(
      entries.length,
      0,
      `Distribution data/${name} must be pristine; never copy existing player data`,
    );
  }
  const reports = resolve(
    Deno.env.get("VOID_BROWSER_REPORT_DIR") ??
      join(repository, "web/browser/build/reports/browser"),
  );
  await Deno.mkdir(reports, { recursive: true });
  const caseDirectory = await Deno.makeTempDir({
    dir: reports,
    prefix: "case-",
  });
  const copied = join(caseDirectory, "distribution");
  const evidence = join(caseDirectory, "evidence");
  await Deno.mkdir(evidence);
  await command("cp", ["-a", "--reflink=auto", distribution, copied]);
  await command("cp", [
    "-a",
    "--reflink=auto",
    `${cache}/.`,
    join(copied, "data/cache/"),
  ]);
  const propertiesFile = join(copied, "game.properties");
  const original = await Deno.readTextFile(propertiesFile);
  assertEquals(
    property(original, "world.start.creation"),
    "true",
    "Keep default character creation enabled",
  );
  assertEquals(
    property(original, "world.start.tutorial"),
    "true",
    "Keep the default tutorial enabled",
  );
  assertEquals(
    property(original, "web.api.token"),
    "",
    "Do not copy an API credential into a fixture",
  );
  const overrides: Record<string, string> = {
    "bots.count": "0",
    "web.api.enabled": "false",
    "web.api.registration": "false",
  };
  const changes = Object.entries(overrides).filter(([name, value]) =>
    property(original, name) !== value
  )
    .map(([name, value]) => ({
      name,
      original: property(original, name),
      fixture: value,
    }));
  const fixture = original.split("\n").map((line) => {
    const name = line.split("=", 1)[0];
    return name in overrides ? `${name}=${overrides[name]}` : line;
  }).join("\n");
  await Deno.writeTextFile(propertiesFile, fixture);
  const provenance = await Deno.readTextFile(
    join(distribution, "client.properties"),
  );
  const copiedClient = await digest(join(copied, "data/void-client.js"));
  assertEquals(copiedClient, property(provenance, "client.sha256"));
  const serverArtifacts: Record<string, string> = {};
  for await (const entry of Deno.readDir(copied)) {
    if (entry.isFile && entry.name.endsWith(".jar")) {
      serverArtifacts[entry.name] = await digest(join(copied, entry.name));
    }
  }
  await Deno.writeTextFile(
    join(evidence, "fixture.json"),
    JSON.stringify(
      {
        distribution,
        sourceClient: await digest(join(distribution, "data/void-client.js")),
        copiedClient,
        clientRevision: property(provenance, "client.revision"),
        teaVmVersion: property(provenance, "teavm.version"),
        launcher: await digest(join(copied, "run-browser")),
        serverArtifacts,
        cache: await digest(join(cache, "main_file_cache.dat2")),
        worlds: await digest(join(copied, "pages/worlds.json")),
        fixtureChanges: changes,
        creation: property(fixture, "world.start.creation"),
        tutorial: property(fixture, "world.start.tutorial"),
      },
      null,
      2,
    ),
  );
  const denoInfo = JSON.parse(
    await command(Deno.execPath(), ["info", "--json"]),
  );
  const cacheDirectory = denoInfo.denoDir as string;
  const deno = await Deno.realPath(Deno.execPath());
  const java = await Deno.realPath(
    Deno.env.get("JAVA_HOME")
      ? join(required("JAVA_HOME"), "bin/java")
      : await command("sh", ["-c", "command -v java"]),
  );
  const javaHome = dirname(dirname(java));
  const browser = await Deno.realPath(
    Deno.env.get("CHROMIUM") ?? chromium.executablePath(),
  );
  const testDirectory = dirname(testFile);
  const clientDirectory = join(repository, "external/teavm-client");
  const home = join(caseDirectory, "home");
  await Deno.mkdir(home);
  const mounts = [
    "/usr",
    "/bin",
    "/lib",
    "/lib64",
    "/etc",
    "/sys/devices/system/cpu",
    dirname(deno),
    javaHome,
    dirname(browser),
    cacheDirectory,
    testDirectory,
    clientDirectory,
  ];
  const arguments_ = [
    "--unshare-user",
    "--unshare-net",
    "--unshare-pid",
    "--unshare-ipc",
    "--unshare-uts",
    "--die-with-parent",
    "--new-session",
    "--clearenv",
    "--cap-drop",
    "ALL",
    ...Array.from(new Set(mounts)).flatMap((
      directory,
    ) => ["--ro-bind", directory, directory]),
    "--proc",
    "/proc",
    "--dev",
    "/dev",
    "--tmpfs",
    "/tmp",
    "--dir",
    "/run",
    "--bind",
    caseDirectory,
    caseDirectory,
    "--ro-bind",
    join(copied, "data/cache"),
    join(copied, "data/cache"),
    "--chdir",
    copied,
  ];
  const environment = {
    PATH: "/usr/bin:/bin",
    HOME: home,
    TMPDIR: "/tmp",
    XDG_CACHE_HOME: join(home, ".cache"),
    XDG_CONFIG_HOME: join(home, ".config"),
    DENO_DIR: cacheDirectory,
    JAVA_HOME: javaHome,
    JAVA_TOOL_OPTIONS: `-Duser.home=${home} -Djava.io.tmpdir=/tmp`,
    VOID_BROWSER_CASE: caseDirectory,
    VOID_BROWSER_CHROMIUM: browser,
    VOID_BROWSER_HOST_NAMESPACES: JSON.stringify(await namespaces()),
    LANG: "C.UTF-8",
  };
  arguments_.push(
    ...Object.entries(environment).flatMap((
      [name, value],
    ) => ["--setenv", name, value]),
  );
  arguments_.push(
    "--",
    deno,
    "run",
    "-A",
    "--cached-only",
    "--frozen",
    "--node-modules-dir=none",
    "--config",
    configuration,
    testFile,
    "--inside",
  );
  console.log(`Browser acceptance evidence: ${evidence}`);
  let result: Deno.CommandStatus;
  try {
    result = await new Deno.Command("bwrap", {
      args: arguments_,
      stdin: "null",
      stdout: "inherit",
      stderr: "inherit",
    }).spawn().status;
  } finally {
    // Only disposable copies are removed. Reports contain coordinates, never complete account saves.
    await Deno.remove(copied, { recursive: true });
    await Deno.remove(home, { recursive: true });
  }
  Deno.exitCode = result.code;
}

async function wait(
  label: string,
  predicate: () => Promise<boolean>,
  timeout = 30_000,
) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 150));
  }
  throw new Error(`${label} timed out`);
}

function installObserver() {
  type Loaded = { exports: Record<string, unknown> };
  type Runtime = { load(...arguments_: unknown[]): Promise<Loaded> };
  let installed: Runtime | undefined;
  const namespace = {};
  Object.defineProperty(namespace, "wasmGC", {
    get: () => installed,
    set(runtime: Runtime) {
      const load = runtime.load;
      runtime.load = async (...arguments_: unknown[]) => {
        const module = await load(...arguments_);
        const exports = module.exports;
        const attach = Reflect.get(exports, "attach", exports);
        if (typeof attach !== "function") return module;
        const observedAttach = (
          attachment: Record<string, unknown>,
        ): unknown => {
          (globalThis as unknown as { voidDiagnostics: unknown })
            .voidDiagnostics = attachment.diagnostics;
          (attachment.canvas as HTMLCanvasElement).id = "canvas";
          return Reflect.apply(attach, exports, [attachment]);
        };
        // TeaVM exports are live, non-enumerable getters. A fresh target also permits a read-only attach export.
        module.exports = new Proxy({}, {
          get: (_target, name) =>
            name === "attach"
              ? observedAttach
              : Reflect.get(exports, name, exports),
        });
        return module;
      };
      installed = runtime;
    },
  });
  (globalThis as unknown as { TeaVM: object }).TeaVM = namespace;
}

async function observe(page: Page) {
  await page.addInitScript(installObserver);
}

export async function observerSelfCheck() {
  const previous = ["TeaVM", "voidDiagnostics"].map((name) => ({
    name,
    descriptor: Object.getOwnPropertyDescriptor(globalThis, name),
  }));
  const main = () => "main";
  const another = () => "another";
  const calls: Array<{ receiver: unknown; attachment: unknown }> = [];
  const attach = function (this: unknown, attachment: Record<string, unknown>) {
    calls.push({ receiver: this, attachment });
    return "attached";
  };
  const values: Record<string, unknown> = { main, attach, another };
  const virtual = new Proxy({}, {
    get: (_target, name) => Reflect.get(values, name),
  });
  const loaded: { exports: Record<string, unknown> } = { exports: virtual };
  const runtime = { load: () => Promise.resolve(loaded) };
  const environment = globalThis as unknown as {
    TeaVM: { wasmGC: typeof runtime };
    voidDiagnostics: unknown;
  };
  const attachment = { diagnostics: { ready: true }, canvas: { id: "" } };
  try {
    installObserver();
    environment.TeaVM.wasmGC = runtime;
    assertEquals(
      Object.keys(virtual),
      [],
      "The regression fixture must have virtual, non-enumerable exports",
    );
    const observed = (await runtime.load()).exports;
    assert(
      observed.main === main && observed.another === another,
      "Keep original main/other export function references",
    );
    assertEquals((observed.main as () => string)(), "main");
    assertEquals((observed.attach as typeof attach)(attachment), "attached");
    assert(calls[0].receiver === virtual && calls[0].attachment === attachment);
    assert(environment.voidDiagnostics === attachment.diagnostics);
    assertEquals(attachment.canvas.id, "canvas");
    const updated = () => "updated";
    values.another = updated;
    assert(
      observed.another === updated,
      "Other exports remain live lookups, not copied snapshots",
    );
    const fixed = Object.freeze({ main, attach, another });
    loaded.exports = fixed;
    const readonly = (await runtime.load()).exports;
    assert(readonly.main === main && readonly.another === another);
    assertEquals((readonly.attach as typeof attach)(attachment), "attached");
    assert(
      calls[1].receiver === fixed,
      "A fixed read-only attach property must not violate Proxy invariants",
    );
    const deobfuscator = { decode: another };
    loaded.exports = deobfuscator;
    assert(
      (await runtime.load()).exports === deobfuscator,
      "Leave non-client exports untouched",
    );
    const attachFailure = new Error("synthetic attach failure");
    loaded.exports = {
      attach() {
        throw attachFailure;
      },
    };
    const failing = (await runtime.load()).exports.attach as typeof attach;
    let observedFailure: unknown;
    try {
      failing(attachment);
    } catch (error) {
      observedFailure = error;
    }
    assert(
      observedFailure === attachFailure,
      "Preserve the original attach failure",
    );
  } finally {
    for (const { name, descriptor } of previous) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else Reflect.deleteProperty(globalThis, name);
    }
  }
}

async function capture(
  browser: Browser,
  origin: string,
  world: number,
  evidence: string,
  label: string,
) {
  const context = await browser.newContext({ viewport, deviceScaleFactor: 1 });
  const page = await context.newPage();
  const result: Capture = {
    page,
    console: [],
    errors: [],
    network: trackJs5(page),
  };
  const requests: string[] = [];
  const sockets: string[] = [];
  page.on(
    "console",
    (message) => result.console.push(`${message.type()}: ${message.text()}`),
  );
  page.on(
    "pageerror",
    (error) => result.errors.push(error.stack ?? error.message),
  );
  page.on("request", (request) => {
    if (/^https?:/.test(request.url())) requests.push(request.url());
  });
  page.on("websocket", (socket) => sockets.push(socket.url()));
  await observe(page);
  await page.goto(`${origin}/play.html`, { waitUntil: "load" });
  assertEquals(
    new URL(page.url()).search,
    "",
    "The test must start at the visible world picker",
  );
  assertEquals(
    await page.getByLabel("Void game client", { exact: true }).count(),
    0,
  );
  await page.screenshot({
    path: join(evidence, `${label}-picker.png`),
    fullPage: true,
  });
  const select = page.getByRole("button", {
    name: new RegExp(`^(Select|Play) world ${world}$`),
  });
  if (!await select.isVisible()) {
    await page.getByText(String(world), { exact: true }).filter({
      visible: true,
    })
      .first().click();
  }
  await select.waitFor({ state: "visible" });
  await select.click();
  await page.waitForURL((url) =>
    url.searchParams.get("world") === String(world)
  );
  await page.getByLabel("Void game client", { exact: true }).waitFor({
    state: "visible",
  });
  await until(result, "actual JS5 data", () => !!result.network?.js5Bytes);
  const contract = await page.evaluate(() => ({
    backend: (globalThis as unknown as { CONFIG: { url: string } }).CONFIG.url,
    script: Array.from(document.scripts).filter((script) =>
      script.src.includes("/play/void-client.js")
    )
      .map((script) => ({ src: script.src, type: script.type })),
    wrappers: document.querySelectorAll("#client [data-void-client]").length,
  }));
  assertEquals(contract, {
    backend: origin.replace(/^http/, "ws") + "/proxy",
    script: [{ src: `${origin}/play/void-client.js`, type: "" }],
    wrappers: 1,
  });
  return { context, capture: result, requests, sockets, contract };
}

async function player(
  page: Page,
  username: string,
): Promise<Position | undefined> {
  return await page.evaluate(async (username) => {
    const response = await fetch("/api/v1/players", { cache: "no-store" });
    if (!response.ok) throw new Error("Server player query failed");
    const players = await response.json() as Position[];
    const selected = players.find((player) =>
      player.name.toLowerCase() === username.toLowerCase()
    );
    return selected
      ? {
        name: selected.name,
        x: selected.x,
        y: selected.y,
        level: selected.level,
      }
      : undefined;
  }, username);
}

async function savedPosition(distribution: string, username: string) {
  assert(
    /^Brow[a-f0-9]{7}$/.test(username),
    "Read only this run's synthetic save",
  );
  const text = await Deno.readTextFile(
    join(distribution, "data/saves", `${username.toLowerCase()}.toml`),
  ).catch((error) => {
    if (error instanceof Deno.errors.NotFound) return "";
    throw error;
  });
  const section = text.split(/^\[tile\]\s*$/m)[1]?.split(/^\[/m)[0] ?? "";
  const coordinates = Object.fromEntries(
    Array.from(
      section.matchAll(/^(x|y|level)\s*=\s*(-?\d+)\s*$/gm),
      (match) => [match[1], Number(match[2])],
    ),
  );
  return Number.isInteger(coordinates.x) && Number.isInteger(coordinates.y)
    ? { x: coordinates.x, y: coordinates.y, level: coordinates.level ?? 0 }
    : undefined;
}

async function gameSockets(port: number) {
  const expected = port.toString(16).toUpperCase().padStart(4, "0");
  const files = await Promise.all(
    ["/proc/net/tcp", "/proc/net/tcp6"].map((path) => Deno.readTextFile(path)),
  );
  return files.flatMap((text) => text.trim().split("\n").slice(1)).filter(
    (line) => {
      const columns = line.trim().split(/\s+/);
      return columns[3] === "01" &&
        [columns[1], columns[2]].some((address) =>
          address.split(":")[1] === expected
        );
    },
  ).length;
}

async function inside() {
  const caseDirectory = required("VOID_BROWSER_CASE");
  const distribution = join(caseDirectory, "distribution");
  const evidence = join(caseDirectory, "evidence");
  const hostNamespaces = JSON.parse(required("VOID_BROWSER_HOST_NAMESPACES"));
  const isolated = await namespaces();
  for (const name of namespaceNames) {
    assert(
      isolated[name] !== hostNamespaces[name],
      `Mandatory ${name} namespace isolation`,
    );
  }
  const interfaces = (await Deno.readTextFile("/proc/net/dev")).split("\n")
    .filter((line) => line.includes(":"))
    .map((line) => line.split(":", 1)[0].trim());
  assertEquals(
    interfaces,
    ["lo"],
    "The test namespace must have no external interface or route",
  );
  const properties = await Deno.readTextFile(
    join(distribution, "game.properties"),
  );
  const webPort = Number(property(properties, "web.server.port"));
  const gamePort = Number(property(properties, "network.port"));
  const world = Number(property(properties, "world.id"));
  const worlds = JSON.parse(
    await Deno.readTextFile(join(distribution, "pages/worlds.json")),
  ) as Array<{ number: number; web: string }>;
  const selected = worlds.find((entry) => entry.number === world);
  assert(
    selected,
    "The configured world must exist in the assembled site's visible picker",
  );
  const origin = selected.web.replace(/\/$/, "");
  const address = new URL(origin);
  assert(
    ["localhost", "127.0.0.1", "[::1]"].includes(address.hostname),
    "Configured test world must use isolated loopback",
  );
  assertEquals(
    Number(address.port || (address.protocol === "https:" ? 443 : 80)),
    webPort,
  );
  assertEquals(
    address.protocol,
    "http:",
    "This isolated default-distribution fixture uses HTTP loopback",
  );
  const report: Record<string, unknown> = {
    started: new Date().toISOString(),
    namespaces: isolated,
    hostNamespaces,
    interfaces,
    origin,
    world,
    launcher: "copied distribution/run-browser",
    clientSha256: await digest(join(distribution, "data/void-client.js")),
  };
  const log = await Deno.open(join(evidence, "server.log"), {
    create: true,
    write: true,
  });
  const server = new Deno.Command("sh", {
    args: ["./run-browser"],
    cwd: distribution,
    stdin: "null",
    stdout: "piped",
    stderr: "piped",
  }).spawn();
  let serverExit: Deno.CommandStatus | undefined;
  const exited = server.status.then((status) => serverExit = status);
  const drains = [server.stdout, server.stderr].map(async (stream) => {
    for await (const bytes of stream) await log.write(bytes);
  });
  let browser: Browser | undefined;
  let active: Awaited<ReturnType<typeof capture>> | undefined;
  let failure: unknown;
  try {
    await wait("assembled distribution HTTP startup", async () => {
      if (serverExit) {
        throw new Error(`Standard launcher exited with ${serverExit.code}`);
      }
      const response = await fetch(`${origin}/api/v1/ping`).catch(() =>
        undefined
      );
      await response?.body?.cancel();
      return response?.status === 204;
    }, 120_000);
    const script = await fetch(`${origin}/play/void-client.js`);
    assert(
      script.ok,
      "Assembled server must serve the installed classic client",
    );
    const served = await sha256(await script.arrayBuffer());
    assertEquals(
      served,
      report.clientSha256,
      "HTTP bytes must match the installed client",
    );
    report.servedClientSha256 = served;
    browser = await chromium.launch({
      executablePath: required("VOID_BROWSER_CHROMIUM"),
      headless: true,
      args: ["--no-proxy-server", "--use-gl=angle", "--use-angle=swiftshader"],
    });
    report.browser = browser.version();
    active = await capture(browser, origin, world, evidence, "first");
    report.picker = active.contract;
    const first = active.capture;
    await loginScreen(first);
    const username = `Brow${
      crypto.randomUUID().replaceAll("-", "").slice(0, 7)
    }`;
    const password = Array.from(
      crypto.getRandomValues(new Uint8Array(12)),
      (value) => String.fromCharCode(65 + value % 26),
    ).join("");
    // Canvas keyboard parameters are not password-redacted. Keep tracing disabled for credential input.
    await first.page.context().tracing.stop();
    const login = await logIn(first, username, password);
    report.login = {
      username,
      state: login.gameState,
      x: login.playerX,
      y: login.playerZ,
    };
    await first.page.screenshot({
      path: join(evidence, "default-character-creation.png"),
      fullPage: true,
    });
    // Character creation and tutorial remain enabled. Their rendered UI must be completed, not bypassed in state/config.
    await completeCharacterCreation(first, evidence);
    await until(
      first,
      "actual minimap terrain",
      async () => coloured(await pixels(first, minimap), 30) > 5000,
      60_000,
    );
    await until(
      first,
      "actual world scene",
      async () =>
        coloured(
          await pixels(first, { x: 50, y: 170, width: 730, height: 400 }),
          30,
        ) > 5000,
      120_000,
    );
    await first.page.screenshot({
      path: join(evidence, "world.png"),
      fullPage: true,
    });
    let before: Position | undefined;
    await wait(
      "independent server login",
      async () => !!(before = await player(first.page, username)),
      45_000,
    );
    assert(before);
    const canvas = await first.page.locator("#canvas").boundingBox();
    assert(canvas);
    await first.page.mouse.move(
      canvas.x + tutorialFloor.x * canvas.width / 1024,
      canvas.y + tutorialFloor.y * canvas.height / 768,
    );
    const hoverTick = (await readState(first)).ticks;
    await until(
      first,
      "reachable floor hover is latched",
      (state) => state.ticks >= hoverTick + 3,
    );
    report.movementTarget = { canvas: tutorialFloor, before };
    await first.page.screenshot({
      path: join(evidence, "movement-target.png"),
      fullPage: true,
    });
    await click(first, tutorialFloor.x, tutorialFloor.y);
    let after: Position | undefined;
    await wait("independent server movement", async () => {
      after = await player(first.page, username);
      return !!after && (after.x !== before!.x || after.y !== before!.y);
    }, 45_000);
    assert(after);
    report.movement = { before, after };
    await first.page.screenshot({
      path: join(evidence, "movement.png"),
      fullPage: true,
    });
    report.resize = await verifyResize(active);
    const baseline = 0;
    const connected = await gameSockets(gamePort);
    assert(
      connected > baseline,
      "Physical proxy-to-game connections are present",
    );
    const observer = await active.context.newPage();
    await observer.goto(`${origin}/api/v1/info`);
    await first.page.goto("about:blank");
    await wait(
      "server logout while browser remains alive",
      async () => !await player(observer, username),
      45_000,
    );
    await wait(
      "physical game connections released",
      async () => await gameSockets(gamePort) === baseline,
    );
    let saved: Awaited<ReturnType<typeof savedPosition>>;
    await wait(
      "coordinate-only durable save",
      async () => !!(saved = await savedPosition(distribution, username)),
    );
    assertEquals(saved, { x: after.x, y: after.y, level: after.level });
    report.logout = {
      connected,
      remaining: await gameSockets(gamePort),
      saved,
    };
    verifyCapture(active, origin);
    await saveCapture(active, join(evidence, "first-browser.json"));
    await active.context.close();
    active = await capture(browser, origin, world, evidence, "relogin");
    await loginScreen(active.capture);
    await active.context.tracing.stop();
    const retained = await logIn(active.capture, username, password);
    assertEquals(
      [retained.playerX, retained.playerZ],
      [after.x, after.y],
      "A fresh browser context must retain the server-saved position",
    );
    let online: Position | undefined;
    await wait(
      "independent server relogin",
      async () => !!(online = await player(active!.capture.page, username)),
      45_000,
    );
    assert(online && online.x === after.x && online.y === after.y);
    healthy(active.capture, retained);
    verifyCapture(active, origin);
    await saveCapture(active, join(evidence, "relogin-browser.json"));
    report.relogin = { x: retained.playerX, y: retained.playerZ };
    report.outcome = "passed";
  } catch (error) {
    failure = error;
    report.outcome = "failed";
    report.failure = error instanceof Error ? error.stack : String(error);
    if (active) {
      try {
        await active.capture.page.screenshot({
          path: join(evidence, "failure.png"),
          fullPage: true,
          timeout: 5000,
        });
      } catch (screenshotError) {
        report.screenshotFailure = String(screenshotError);
      }
    }
  } finally {
    if (active) {
      await Deno.writeTextFile(
        join(evidence, "browser.json"),
        JSON.stringify(
          {
            console: active.capture.console,
            errors: active.capture.errors,
            requests: active.requests,
            sockets: active.sockets,
            network: active.capture.network,
          },
          null,
          2,
        ),
      );
    }
    try {
      await browser?.close();
    } catch (browserError) {
      report.browserCleanupFailure = String(browserError);
      failure ??= browserError;
      report.outcome = "failed";
    }
    if (!serverExit) server.kill("SIGTERM");
    await Promise.race([
      exited,
      new Promise((resolve) => setTimeout(resolve, 15_000)),
    ]);
    if (!serverExit) server.kill("SIGKILL");
    await exited;
    await Promise.all(drains);
    log.close();
    const output = (await Deno.readTextFile(join(evidence, "server.log")))
      .replace(terminalColors, "");
    const serverErrors = output.split("\n").filter((line) =>
      /\bERROR\b|Exception in thread/.test(line)
    );
    if (serverErrors.length) {
      report.serverErrors = serverErrors;
      failure ??= new Error("Assembled server logged errors");
      report.outcome = "failed";
    }
    report.serverExit = serverExit;
    report.finished = new Date().toISOString();
    await Deno.writeTextFile(
      join(evidence, "report.json"),
      JSON.stringify(report, null, 2),
    );
  }
  console.log(
    JSON.stringify({
      outcome: report.outcome,
      report: join(evidence, "report.json"),
    }),
  );
  if (failure) throw failure;
}

async function saveCapture(
  session: Awaited<ReturnType<typeof capture>>,
  path: string,
) {
  await Deno.writeTextFile(
    path,
    JSON.stringify(
      {
        console: session.capture.console,
        errors: session.capture.errors,
        requests: session.requests,
        sockets: session.sockets,
        network: session.capture.network,
      },
      null,
      2,
    ),
  );
}

async function completeCharacterCreation(capture: Capture, evidence: string) {
  assertEquals(
    await capture.page.locator("#canvas").evaluate((element) => {
      const canvas = element as HTMLCanvasElement;
      return [canvas.width, canvas.height];
    }),
    [1024, 768],
    "Onboarding coordinates use the default 1024x768 client",
  );
  await until(
    capture,
    "default character creation screen rendered",
    (state) => state.gameState === 10,
  );
  await capture.page.screenshot({
    path: join(evidence, "character-creation-ready.png"),
    fullPage: true,
  });
  const description = { x: 240, y: 390, width: 530, height: 110 };
  const initial = await pixels(capture, description);
  await click(capture, adventurer.x, adventurer.y);
  await until(
    capture,
    "chosen Adventurer preview",
    async () => changed(initial, await pixels(capture, description)) > 1000,
  );
  await capture.page.screenshot({
    path: join(evidence, "character-style-selected.png"),
    fullPage: true,
  });
  await click(capture, confirmCharacter.x, confirmCharacter.y);
  await capture.page.screenshot({
    path: join(evidence, "character-confirmed.png"),
    fullPage: true,
  });
}

async function verifyResize(session: Awaited<ReturnType<typeof capture>>) {
  const { page } = session.capture;
  const count = session.sockets.length;
  const size = async () =>
    await page.evaluate(() => {
      const host = document.getElementById("client")!.getBoundingClientRect();
      const canvas = document.getElementById("canvas") as HTMLCanvasElement;
      return {
        width: host.width,
        height: host.height,
        drawingWidth: canvas.width,
        drawingHeight: canvas.height,
        scrollWidth: document.documentElement.scrollWidth,
        scrollHeight: document.documentElement.scrollHeight,
      };
    });
  const matches = async () => {
    const measured = await size();
    return measured.width === measured.drawingWidth &&
      measured.height === measured.drawingHeight;
  };
  const initial = await size();
  await page.getByRole("button", { name: "Hide nav bar", exact: true }).click();
  await wait(
    "hidden nav resizes game",
    async () => await matches() && (await size()).height > initial.height,
  );
  const hidden = await size();
  await page.getByRole("button", { name: "Show nav bar", exact: true }).click();
  await wait(
    "shown nav restores game",
    async () => await matches() && (await size()).height === initial.height,
  );
  const shown = await size();
  await page.setViewportSize({ width: 1100, height: 856 });
  await wait("viewport resizes game", matches);
  const resized = await size();
  assertEquals(
    [resized.scrollWidth, resized.scrollHeight],
    [1100, 856],
    "No document overflow",
  );
  await page.setViewportSize(viewport);
  await wait("original viewport restored", matches);
  assertEquals(
    session.sockets.length,
    count,
    "Resize/nav must not create duplicate backend connections",
  );
  return {
    initial,
    hidden,
    shown,
    resized,
    backendConnections: session.sockets.length,
  };
}

function verifyCapture(
  session: Awaited<ReturnType<typeof capture>>,
  origin: string,
) {
  assertNoPageErrors(session.capture);
  assertEquals(
    session.capture.console.filter((line) => /^error:/.test(line)),
    [],
    "No console errors",
  );
  assertEquals(
    session.requests.filter((url) => new URL(url).origin !== origin),
    [],
    "No external HTTP dependency",
  );
  assertEquals(
    session.requests.filter((url) =>
      /client-web\.wasm|\.teadbg(?:$|\?)|\/js5(?:$|\?)/.test(url)
    ),
    [],
    "No HTTP module/debug/JS5 sidecar",
  );
}

if (import.meta.main) {
  await observerSelfCheck();
  if (Deno.args.length === 0) await outer();
  else if (Deno.args.length === 1 && Deno.args[0] === "--inside") {
    await inside();
  } else throw new Error("Usage: browser_test.ts [--inside]");
}
