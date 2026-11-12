package org.mendrugo.attimo.bluehat;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.mendrugo.attimo.Environment;

public final class BlueHatCloudRunner {
    private BlueHatCloudRunner() {}

    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
    private static final int HEALTH_MAX_RETRIES = 10;
    private static final Duration HEALTH_RETRY_DELAY = Duration.ofMillis(1000);

    public static Path repoDir(final String repository) {
        final var repoName = extractRepoName(repository);
        return Environment.cacheDir(BlueHat.CLOUD).resolve(repoName);
    }

    public static void cloneAndBuild(final String repository) {
        if (repository.isBlank()) {
            throw new BlueHatException("No repository configured.");
        }

        final var targetDir = repoDir(repository);

        try {
            Files.createDirectories(targetDir.getParent());

            if (Files.exists(targetDir)) {
                System.out.println("  Repository already cloned at " + targetDir);
                System.out.println("  Pulling latest changes...");
                runProcess(targetDir, "git", "pull", "--ff-only");
            } else {
                System.out.println("  Cloning " + repository + "...");
                runProcess(targetDir.getParent(), "git", "clone", repository,
                        targetDir.getFileName().toString());
            }

            System.out.println("  Building with Maven...");
            runProcess(targetDir, "mvn", "package", "-DskipTests", "-q");
            System.out.println("  Build complete.");
        } catch (final BlueHatException e) {
            throw e;
        } catch (final Exception e) {
            throw new BlueHatException("Failed to clone and build: " + e.getMessage(), e);
        }
    }

    public static Process start(final String repository) {
        final var targetDir = repoDir(repository);
        final var quarkusJar = targetDir.resolve("target")
                .resolve("quarkus-app")
                .resolve("quarkus-run.jar");

        if (!Files.exists(quarkusJar)) {
            throw new BlueHatException("Blue Hat cloud not built. Run 'ato bh init' first.\n" + "Expected jar at: " + quarkusJar);
        }

        final var logFile = logFile();

        try {
            Files.createDirectories(logFile.getParent());
            Files.deleteIfExists(logFile);
            System.out.println("  Starting local Blue Hat cloud...");
            final var process = new ProcessBuilder("java", "-Dquarkus.http.host=localhost", "-jar", quarkusJar.toString())
                    .directory(targetDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(Redirect.appendTo(logFile.toFile()))
                    .start();

            if (!waitForHealthy("localhost", BlueHat.API_PORT, process)) {
                process.destroyForcibly();
                reportStartupFailure(process, logFile);
            }

            System.out.println("  Local Blue Hat cloud is running.");
            return process;
        } catch (final BlueHatException e) {
            throw e;
        } catch (final IOException e) {
            throw new BlueHatException("Failed to start local Blue Hat cloud: " + e.getMessage(), e);
        }
    }

    public static boolean healthCheck(final String host, final int port) {
        try {
            final var client = HttpClient.newBuilder()
                    .connectTimeout(HEALTH_TIMEOUT)
                    .build();

            final var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + port + "/vm"))
                    .GET()
                    .timeout(HEALTH_TIMEOUT)
                    .build();

            final var response = client.send(request, BodyHandlers.ofString());

            return response.statusCode() >= 200
                    && response.statusCode() < 300
                    && response.body() != null
                    && !response.body().isBlank();
        } catch (final Exception e) {
            return false;
        }
    }

    public static void stop(final Process process) {
        if (process != null && process.isAlive()) {
            System.out.println("  Stopping local Blue Hat cloud...");
            process.destroy();
            try {
                final boolean processExited = process.waitFor(Duration.ofSeconds(30));
                if (!processExited) {
                    process.destroyForcibly();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    static Path logFile() {
        return Environment.cacheDir(BlueHat.CLOUD).resolve("cloud.log");
    }

    private static boolean waitForHealthy(final String host, final int port, final Process process) {
        for (int i = 0; i < HEALTH_MAX_RETRIES; i++) {
            if (!process.isAlive()) {
                return false;
            }

            if (healthCheck(host, port)) {
                return true;
            }

            try {
                Thread.sleep(HEALTH_RETRY_DELAY.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    private static void reportStartupFailure(final Process process, final Path logFile) {
        final var sb = new StringBuilder();
        sb.append("Local Blue Hat cloud failed to start.");

        if (!process.isAlive()) {
            sb.append("\n  Process exited with code: ").append(process.exitValue());
        } else {
            sb.append("\n  Process is running but health check at http://localhost:")
              .append(BlueHat.API_PORT)
              .append("/vm did not respond within ")
              .append(HEALTH_MAX_RETRIES)
              .append(" attempts.");
        }

        sb.append("\n  Log file: ").append(logFile);

        try {
            if (Files.exists(logFile) && Files.size(logFile) > 0) {
                final var lines = Files.readAllLines(logFile);
                final var tail = lines.subList(Math.max(0, lines.size() - 20), lines.size());
                sb.append("\n\n  Last output:");
                for (final var line : tail) {
                    sb.append("\n    ").append(line);
                }
            }
        } catch (final IOException ignored) {

        }

        throw new BlueHatException(sb.toString());
    }

    static String extractRepoName(final String repoUrl) {
        var name = repoUrl;

        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }

        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }

        final var lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }

        return name;
    }

    private static void runProcess(final Path workDir, final String... command) {
        try {
            final var process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .inheritIO()
                    .start();

            final var exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new BlueHatException("Command failed (exit " + exitCode + "): " + String.join(" ", command));
            }
        } catch (final BlueHatException e) {
            throw e;
        } catch (final Exception e) {
            throw new BlueHatException("Failed to run command '" + String.join(" ", command) + "': " + e.getMessage(), e);
        }
    }

    public static Process ensureCloudRunning(final BlueHatConfig config) {
        final var hostName = config.effectiveHostName();
        final var port = BlueHat.API_PORT;

        if (config.isLocal()) {
            return BlueHatCloudRunner.start(config.getRepository());
        } else {
            if (!BlueHatCloudRunner.healthCheck(hostName, port)) {
                throw new BlueHatException("Blue Hat cloud at " + hostName + ":" + port + " is not reachable. Verify the host is running.");
            }
            System.out.println("  Blue Hat cloud at " + hostName + ":" + port + " is healthy.");
            return null;
        }
    }
}
