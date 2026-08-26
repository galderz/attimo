package org.mendrugo.attimo.bluehat;

import org.mendrugo.attimo.Environment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Manages the local Blue Hat cloud lifecycle.
 *
 * <p>When {@code attimo.bluehat.host-name} is "localhost", this class handles:
 * <ul>
 *   <li>{@link #cloneAndBuild()} — clone the git repository and build with Maven (during init)</li>
 *   <li>{@link #start()} — launch the Quarkus app as a background process</li>
 *   <li>{@link #healthCheck(String, int)} — verify the cloud is reachable via HTTP</li>
 *   <li>{@link #stop(Process)} — stop the background process</li>
 * </ul>
 *
 * <p>For remote hosts, only {@link #healthCheck(String, int)} is needed.
 */
public final class BlueHatCloudRunner
{
    private BlueHatCloudRunner() {}

    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
    private static final int HEALTH_MAX_RETRIES = 10;
    private static final Duration HEALTH_RETRY_DELAY = Duration.ofMillis(1000);

    /**
     * Returns the directory where the Blue Hat cloud repository is cloned.
     */
    public static Path repoDir()
    {
        final var repo = BlueHatSettings.repository();
        final var repoName = extractRepoName(repo);
        return Environment.cacheDir(BlueHat.CLOUD).resolve(repoName);
    }

    /**
     * Clone the Blue Hat cloud repository and build it with Maven.
     * Called during {@code ato bh init} when running locally.
     *
     * @throws BlueHatException if clone or build fails
     */
    public static void cloneAndBuild()
    {
        final var repo = BlueHatSettings.repository();
        if (repo.isBlank())
        {
            throw new BlueHatException(
                "No repository configured. Set attimo.bluehat.repository in application.properties."
            );
        }

        final var targetDir = repoDir();

        try
        {
            Files.createDirectories(targetDir.getParent());

            if (Files.exists(targetDir))
            {
                System.out.println("  Repository already cloned at " + targetDir);
                System.out.println("  Pulling latest changes...");
                runProcess(targetDir, "git", "pull", "--ff-only");
            }
            else
            {
                System.out.println("  Cloning " + repo + "...");
                runProcess(targetDir.getParent(), "git", "clone", repo, targetDir.getFileName().toString());
            }

            System.out.println("  Building with Maven...");
            runProcess(targetDir, "mvn", "package", "-DskipTests", "-q");
            System.out.println("  Build complete.");
        }
        catch (final BlueHatException e)
        {
            throw e;
        }
        catch (final Exception e)
        {
            throw new BlueHatException("Failed to clone and build: " + e.getMessage(), e);
        }
    }

    /**
     * Start the local Blue Hat cloud as a background process.
     * The process binds to localhost on the configured local port.
     *
     * @return the running process
     * @throws BlueHatException if the process cannot be started
     */
    public static Process start()
    {
        final var targetDir = repoDir();
        final var quarkusJar = targetDir.resolve("target")
            .resolve("quarkus-app")
            .resolve("quarkus-run.jar");

        if (!Files.exists(quarkusJar))
        {
            throw new BlueHatException(
                "Blue Hat cloud not built. Run 'ato bh init' first.\n"
                    + "Expected jar at: " + quarkusJar
            );
        }

        final var port = BlueHatSettings.localPort();

        final var logFile = logFile();

        try
        {
            Files.createDirectories(logFile.getParent());
            System.out.println("  Starting local Blue Hat cloud on port " + port + "...");
            final var process = new ProcessBuilder(
                "java"
                , "-Dquarkus.http.host=localhost"
                , "-Dquarkus.http.port=" + port
                , "-jar"
                , quarkusJar.toString()
            )
                .directory(targetDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();

            // Wait for it to be healthy
            if (!waitForHealthy("localhost", port, process))
            {
                process.destroyForcibly();
                reportStartupFailure(process, logFile);
            }

            System.out.println("  Local Blue Hat cloud is running.");
            return process;
        }
        catch (final BlueHatException e)
        {
            throw e;
        }
        catch (final IOException e)
        {
            throw new BlueHatException("Failed to start local Blue Hat cloud: " + e.getMessage(), e);
        }
    }

    /**
     * Perform a health check against a Blue Hat cloud endpoint.
     * Queries {@code GET /vm} and verifies a valid JSON response is returned.
     *
     * @param host the host name or IP
     * @param port the port
     * @return true if the health check succeeds
     */
    public static boolean healthCheck(final String host, final int port)
    {
        try
        {
            final var client = HttpClient.newBuilder()
                .connectTimeout(HEALTH_TIMEOUT)
                .build();

            final var request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/vm"))
                .GET()
                .timeout(HEALTH_TIMEOUT)
                .build();

            final var response = client.send(
                request
                , HttpResponse.BodyHandlers.ofString()
            );

            // Any 2xx response with a body is considered healthy
            return response.statusCode() >= 200
                && response.statusCode() < 300
                && response.body() != null
                && !response.body().isBlank();
        }
        catch (final Exception e)
        {
            return false;
        }
    }

    /**
     * Stop the local Blue Hat cloud process.
     *
     * @param process the process to stop (may be null)
     */
    public static void stop(final Process process)
    {
        if (process != null && process.isAlive())
        {
            System.out.println("  Stopping local Blue Hat cloud...");
            process.destroy();
            try
            {
                final boolean processExited = process.waitFor(Duration.ofSeconds(30));
                if (!processExited)
                {
                    process.destroyForcibly();
                }
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /**
     * Returns the log file path for the local Blue Hat cloud process.
     */
    static Path logFile()
    {
        return Environment.cacheDir(BlueHat.CLOUD).resolve("cloud.log");
    }

    /**
     * Wait for the Blue Hat cloud to become healthy, retrying up to HEALTH_MAX_RETRIES times.
     * Stops early if the process exits (no point retrying against a dead process).
     */
    private static boolean waitForHealthy(
        final String host
        , final int port
        , final Process process
    )
    {
        for (int i = 0; i < HEALTH_MAX_RETRIES; i++)
        {
            if (!process.isAlive())
            {
                return false;
            }

            if (healthCheck(host, port))
            {
                return true;
            }

            try
            {
                Thread.sleep(HEALTH_RETRY_DELAY.toMillis());
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    /**
     * Report why the local cloud failed to start, showing the log file location
     * and the last few lines of output.
     */
    private static void reportStartupFailure(
        final Process process
        , final Path logFile
    )
    {
        final var sb = new StringBuilder();
        sb.append("Local Blue Hat cloud failed to start.");

        if (!process.isAlive())
        {
            sb.append("\n  Process exited with code: ").append(process.exitValue());
        }
        else
        {
            sb.append("\n  Process is running but health check at http://localhost:")
                .append(BlueHatSettings.localPort())
                .append("/vm did not respond within ")
                .append(HEALTH_MAX_RETRIES)
                .append(" attempts.");
        }

        sb.append("\n  Log file: ").append(logFile);

        // Show last lines of the log
        try
        {
            if (Files.exists(logFile) && Files.size(logFile) > 0)
            {
                final var lines = Files.readAllLines(logFile);
                final var tail = lines.subList(
                    Math.max(0, lines.size() - 20)
                    , lines.size()
                );
                sb.append("\n\n  Last output:");
                for (final var line : tail)
                {
                    sb.append("\n    ").append(line);
                }
            }
        }
        catch (final IOException ignored)
        {
            // Best effort — if we can't read the log, we still report the path
        }

        throw new BlueHatException(sb.toString());
    }

    /**
     * Extract a repository name from a git URL.
     * E.g. "https://github.com/openjdk/bluehat-cloud.git" → "bluehat-cloud"
     */
    static String extractRepoName(final String repoUrl)
    {
        var name = repoUrl;

        // Remove trailing slash
        if (name.endsWith("/"))
        {
            name = name.substring(0, name.length() - 1);
        }

        // Remove .git suffix
        if (name.endsWith(".git"))
        {
            name = name.substring(0, name.length() - 4);
        }

        // Extract last path segment
        final var lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0)
        {
            name = name.substring(lastSlash + 1);
        }

        return name;
    }

    private static void runProcess(
        final Path workDir
        , final String... command
    )
    {
        try
        {
            final var process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .inheritIO()
                .start();

            final var exitCode = process.waitFor();
            if (exitCode != 0)
            {
                throw new BlueHatException(
                    "Command failed (exit " + exitCode + "): " + String.join(" ", command)
                );
            }
        }
        catch (final BlueHatException e)
        {
            throw e;
        }
        catch (final Exception e)
        {
            throw new BlueHatException(
                "Failed to run command '" + String.join(" ", command) + "': " + e.getMessage()
                , e
            );
        }
    }
}
