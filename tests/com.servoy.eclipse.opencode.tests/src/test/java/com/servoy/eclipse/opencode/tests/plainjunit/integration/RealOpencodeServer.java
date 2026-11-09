/*
 This file belongs to the Servoy development and deployment environment, Copyright (C) 1997-2026 Servoy BV

 This program is free software; you can redistribute it and/or modify it under
 the terms of the GNU Affero General Public License as published by the Free
 Software Foundation; either version 3 of the License, or (at your option) any
 later version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License along
 with this program; if not, see http://www.gnu.org/licenses or write to the Free
 Software Foundation,Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
*/

package com.servoy.eclipse.opencode.tests.plainjunit.integration;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.servoy.eclipse.opencode.tomcat.OpencodeChatServlet;

/**
 * A real {@code opencode serve} process for the integration tests in this
 * package, plus an {@link OpencodeChatServlet} wired to it.
 * <p>
 * opencode is installed the way the bundle does it in Servoy Developer
 * ({@code OpencodeFolderCreatorJob}): {@code npm install} of the host bundle's
 * {@code opencode/package.json} (the exact pinned {@code @opencode/cli}
 * version) with its {@code .npmrc}, so the postinstall that materializes the
 * native binary runs. The install is cached per version under
 * {@code <java.io.tmpdir>/servoy-opencode-tests/<version>} and reused by later
 * runs; nothing is uninstalled. npm is the one the host bundle's build already
 * downloads ({@code target/node}), otherwise {@code npm} from {@code PATH}.
 * </p>
 * <p>
 * The process is started like {@code RunOpencodeCommand} does it
 * ({@code serve --port PORT --hostname 127.0.0.1}, password through
 * {@code OPENCODE_SERVER_PASSWORD}, all {@code XDG_*} dirs in a temp folder)
 * but with a <em>cleared</em> environment, so an inherited {@code OPENCODE_*}
 * variable can never change the outcome. Its working directory is deliberately
 * a different folder than the project directory, as in the situation that
 * triggered SVY-21461.
 * </p>
 * <p>
 * System properties: {@code opencode.executable} uses that binary and skips the
 * install; {@code opencode.test.bundleDir} (default
 * {@code ../../bundles/com.servoy.eclipse.opencode}),
 * {@code opencode.test.cacheDir} and {@code opencode.test.npm} override the
 * locations above. Anything missing makes the test fail loudly, never skip.
 * </p>
 *
 * @author mvid
 * @since 2026.12
 */
final class RealOpencodeServer implements AutoCloseable {
	private static final String EXECUTABLE_PROPERTY = "opencode.executable";
	private static final String BUNDLE_DIR_PROPERTY = "opencode.test.bundleDir";
	private static final String CACHE_DIR_PROPERTY = "opencode.test.cacheDir";
	private static final String NPM_PROPERTY = "opencode.test.npm";
	private static final Pattern PINNED_VERSION = Pattern.compile("\"@opencode/cli\"\\s*:\\s*\"([^\"]+)\"");
	private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
	private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
	private static final String AUTH_USER = "opencode";
	/**
	 * Parent environment variables that are still needed to start a process on
	 * Windows.
	 */
	private static final List<String> PASS_THROUGH_ENV = List.of("PATH", "SystemRoot", "SYSTEMROOT", "TEMP", "TMP",
			"TMPDIR");

	final int port;
	final String password;
	/** The directory the servlet reports as the active Servoy project. */
	final Path projectDir;
	/** opencode's own working directory, deliberately not the project directory. */
	final Path serverCwd;
	private final Path serveLog;
	private final Process process;
	private final HttpClient http;

	private RealOpencodeServer(Process process, int port, String password, Path projectDir, Path serverCwd,
			Path serveLog) {
		this.process = process;
		this.port = port;
		this.password = password;
		this.projectDir = projectDir;
		this.serverCwd = serverCwd;
		this.serveLog = serveLog;
		this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
				.build();
	}

	/**
	 * Installs (or reuses) the pinned opencode and starts it inside
	 * {@code sandbox}.
	 *
	 * @param sandbox        an empty directory owned by the test
	 * @param opencodeConfig the {@code opencode.json} to give the server, or
	 *                       {@code null} for none (no model configured)
	 */
	static RealOpencodeServer start(Path sandbox, String opencodeConfig) throws Exception {
		Path home = Files.createDirectory(sandbox.resolve("home"));
		File executable = resolveOpencode(home);
		if (opencodeConfig != null) {
			// XDG_CONFIG_HOME is home, so opencode reads home/opencode/opencode.json
			// (the --version check above may already have created that directory)
			Files.writeString(Files.createDirectories(home.resolve("opencode")).resolve("opencode.json"),
					opencodeConfig);
		}

		// toRealPath: on macOS /var and /tmp are symlinks and opencode reports real
		// paths.
		Path projectDir = Files.createDirectory(sandbox.resolve("project")).toRealPath();
		Path serverCwd = Files.createDirectory(sandbox.resolve("opencode-cwd")).toRealPath();
		Path serveLog = sandbox.resolve("serve.log");
		int port = freePort();
		String password = "test-" + Long.toHexString(System.nanoTime());

		ProcessBuilder pb = new ProcessBuilder(executable.getAbsolutePath(), "serve", "--port", String.valueOf(port),
				"--hostname", "127.0.0.1");
		pb.directory(serverCwd.toFile());
		Map<String, String> env = pb.environment();
		isolate(env, home);
		env.put("PWD", serverCwd.toString());
		env.put("OPENCODE_SERVER_PASSWORD", password);
		pb.redirectErrorStream(true);
		pb.redirectOutput(serveLog.toFile());

		RealOpencodeServer server = new RealOpencodeServer(pb.start(), port, password, projectDir, serverCwd, serveLog);
		try {
			server.waitUntilServing();
		} catch (Throwable t) {
			server.close();
			throw t;
		}
		return server;
	}

	/**
	 * The servlet as Servoy Developer wires it, pointed at this server and
	 * {@link #projectDir}.
	 */
	OpencodeChatServlet servlet() {
		Function<String, URL> noResources = p -> null;
		return new OpencodeChatServlet(() -> port, () -> password, () -> true, timeout -> true,
				() -> projectDir.toString(), noResources);
	}

	/** Calls opencode directly, bypassing the servlet. */
	HttpResponse<String> direct(String method, String pathAndQuery, String body) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery))
				.timeout(Duration.ofSeconds(30)).header("authorization", "Basic " + Base64.getEncoder()
						.encodeToString((AUTH_USER + ":" + password).getBytes(StandardCharsets.UTF_8)));
		if (body == null) {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		} else {
			b.header("content-type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
		}
		return http.send(b.build(), BodyHandlers.ofString());
	}

	/** The server's console output so far, for failure messages. */
	String log() {
		try {
			return Files.readString(serveLog);
		} catch (IOException e) {
			return "<no log: " + e.getMessage() + ">";
		}
	}

	@Override
	public void close() throws InterruptedException {
		process.descendants().forEach(ProcessHandle::destroy);
		process.destroy();
		if (!process.waitFor(10, TimeUnit.SECONDS)) {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			process.waitFor(10, TimeUnit.SECONDS);
		}
	}

	static String encode(Path dir) {
		return URLEncoder.encode(dir.toString(), StandardCharsets.UTF_8);
	}

	private void waitUntilServing() throws Exception {
		long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
		Exception last = null;
		while (System.nanoTime() < deadline) {
			if (!process.isAlive())
				fail("opencode exited with code " + process.exitValue() + " before serving. Log:\n" + log());
			try {
				HttpResponse<String> r = direct("GET", "/api/session?directory=" + encode(projectDir), null);
				if (r.statusCode() == 200)
					return;
				last = new IOException("HTTP " + r.statusCode() + ": " + r.body());
			} catch (IOException e) {
				last = e;
			}
			Thread.sleep(250);
		}
		fail("opencode did not serve within " + STARTUP_TIMEOUT.toSeconds() + "s on port " + port + " (last: " + last
				+ "). Log:\n" + log());
	}

	// -----------------------------------------------------------------------
	// Getting opencode: the pinned version, installed like the bundle does
	// -----------------------------------------------------------------------

	private static File resolveOpencode(Path home) throws Exception {
		String configured = System.getProperty(EXECUTABLE_PROPERTY);
		if (configured != null && !configured.isBlank()) {
			File f = new File(configured);
			if (!f.canExecute())
				fail("-D" + EXECUTABLE_PROPERTY + "=" + configured + " is not an executable file");
			return f;
		}

		Path hostBundle = hostBundleDir();
		Path hostOpencode = hostBundle.resolve("opencode");
		Matcher m = PINNED_VERSION.matcher(Files.readString(hostOpencode.resolve("package.json")));
		if (!m.find())
			fail("No pinned @opencode/cli version in " + hostOpencode.resolve("package.json"));
		String version = m.group(1);

		Path cacheRoot = Path.of(System.getProperty(CACHE_DIR_PROPERTY,
				Path.of(System.getProperty("java.io.tmpdir"), "servoy-opencode-tests").toString()));
		Path installDir = cacheRoot.resolve(version);
		File cached = executableIn(installDir);
		if (cached != null && reportsVersion(cached, version, home))
			return cached;

		// Install into a private staging dir and move it in place at the end, so a
		// concurrent or interrupted run never leaves a half-installed version behind.
		Files.createDirectories(cacheRoot);
		Path staging = Files.createTempDirectory(cacheRoot, version + ".installing-");
		Files.copy(hostOpencode.resolve("package.json"), staging.resolve("package.json"));
		Files.copy(hostOpencode.resolve(".npmrc"), staging.resolve(".npmrc"));
		npmInstall(hostBundle, staging);
		File staged = executableIn(staging);
		if (staged == null)
			fail("npm install of @opencode/cli " + version + " produced no opencode executable in " + staging);
		if (!reportsVersion(staged, version, home))
			fail("the installed opencode is not a working " + version
					+ " binary (was its postinstall skipped? see SVY-21679): " + staged);

		if (Files.exists(installDir))
			deleteTree(installDir); // a broken earlier install
		try {
			Files.move(staging, installDir, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			// another run moved its install in first: use that one, drop ours
			File other = executableIn(installDir);
			if (other != null && reportsVersion(other, version, home)) {
				deleteTree(staging);
				return other;
			}
			return staged;
		}
		return executableIn(installDir);
	}

	/** Same lookup as {@code RunOpencodeCommand#resolveOpencodeExecutable}. */
	private static File executableIn(Path installDir) {
		String exeName = WINDOWS ? "opencode.exe" : "opencode";
		File cliBin = installDir.resolve("node_modules/@opencode/cli/bin/" + exeName).toFile();
		if (cliBin.isFile())
			return cliBin;
		File shimBin = installDir.resolve("node_modules/.bin/" + exeName).toFile();
		return shimBin.isFile() ? shimBin : null;
	}

	/**
	 * A real binary prints its version; the stub left by a skipped postinstall
	 * prints an error.
	 */
	private static boolean reportsVersion(File executable, String version, Path home) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(executable.getAbsolutePath(), "--version").redirectErrorStream(true);
		isolate(pb.environment(), home);
		Process p = pb.start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!p.waitFor(60, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			return false;
		}
		return p.exitValue() == 0 && out.contains(version);
	}

	private static void npmInstall(Path hostBundle, Path dir) throws Exception {
		List<String> command = new ArrayList<>();
		String pathPrefix = null;
		String configured = System.getProperty(NPM_PROPERTY);
		Path bundledNode = hostBundle.resolve("target/node");
		Path bundledNpm = bundledNode.resolve("node_modules/npm/bin/npm-cli.js");
		if (configured != null && !configured.isBlank()) {
			command.add(configured);
		} else if (Files.isRegularFile(bundledNpm)) {
			// node + npm downloaded by the host bundle's frontend-maven-plugin
			command.add(bundledNode.resolve(WINDOWS ? "node.exe" : "node").toString());
			command.add(bundledNpm.toString());
			pathPrefix = bundledNode.toString(); // postinstall scripts call `node`
		} else {
			command.add(WINDOWS ? "npm.cmd" : "npm");
		}
		command.addAll(List.of("install", "--no-audit", "--no-fund"));

		Path log = dir.resolve("npm-install.log");
		ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
				.redirectOutput(log.toFile());
		if (pathPrefix != null) {
			String path = pb.environment().getOrDefault("PATH", "");
			pb.environment().put("PATH", pathPrefix + File.pathSeparator + path);
		}
		Process p;
		try {
			p = pb.start();
		} catch (IOException e) {
			fail("cannot run npm (" + command.get(0) + "): build the host bundle first (it downloads node into "
					+ bundledNode + "), put npm on PATH, or set -D" + NPM_PROPERTY + "=<npm>", e);
			return;
		}
		if (!p.waitFor(INSTALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
			fail("npm install did not finish within " + INSTALL_TIMEOUT.toMinutes() + " min. Log:\n"
					+ Files.readString(log));
		}
		if (p.exitValue() != 0)
			fail("npm install exited with " + p.exitValue() + ". Log:\n" + Files.readString(log));
	}

	private static Path hostBundleDir() {
		Path dir = Path.of(System.getProperty(BUNDLE_DIR_PROPERTY, "../../bundles/com.servoy.eclipse.opencode"))
				.toAbsolutePath().normalize();
		if (!Files.isRegularFile(dir.resolve("opencode/package.json")))
			fail("host bundle not found at " + dir + " (working dir " + System.getProperty("user.dir") + "); set -D"
					+ BUNDLE_DIR_PROPERTY + "=<path to com.servoy.eclipse.opencode>");
		return dir;
	}

	/**
	 * Replaces the environment with a minimal one whose home and XDG dirs all point
	 * at {@code home}.
	 */
	private static void isolate(Map<String, String> env, Path home) {
		Map<String, String> parentEnv = System.getenv();
		env.clear();
		for (String name : PASS_THROUGH_ENV) {
			if (parentEnv.containsKey(name))
				env.put(name, parentEnv.get(name));
		}
		env.put("HOME", home.toString());
		env.put("USERPROFILE", home.toString());
		env.put("XDG_CONFIG_HOME", home.toString());
		env.put("XDG_DATA_HOME", home.toString());
		env.put("XDG_STATE_HOME", home.toString());
		env.put("XDG_CACHE_HOME", home.toString());
	}

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return s.getLocalPort();
		}
	}

	private static void deleteTree(Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(p);
		}
	}
}
