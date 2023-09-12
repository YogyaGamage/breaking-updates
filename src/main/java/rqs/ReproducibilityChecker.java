package rqs;

import com.fasterxml.jackson.databind.type.MapType;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import miner.JsonUtils;
import miner.ReproducibleBreakingUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReproducibilityChecker {

    private static final String PRECEDING_COMMIT_CONTAINER_TAG = "-pre";

    private static final String BREAKING_UPDATE_CONTAINER_TAG = "-breaking";
    private static final String REGISTRY = "ghcr.io/chains-project/breaking-updates";
    private static final Logger log = LoggerFactory.getLogger(ReproducibilityChecker.class);
    private static DockerClient dockerClient;
    private static final Short EXIT_CODE_OK = 0;

    public static final Map<Pattern, ReproducibleBreakingUpdate.FailureCategory> FAILURE_PATTERNS = new HashMap<>();

    static {
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(COMPILATION ERROR | Failed to execute goal io\\.takari\\.maven\\.plugins:takari-lifecycle-plugin.*?:compile)"),
                ReproducibleBreakingUpdate.FailureCategory.COMPILATION_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(\\[ERROR] Tests run: | There are test failures | There were test failures |" +
                        "Failed to execute goal org\\.apache\\.maven\\.plugins:maven-surefire-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.TEST_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.jenkins-ci\\.tools:maven-hpi-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.JENKINS_PLUGIN_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.jvnet\\.jaxb2\\.maven2:maven-jaxb2-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.JAXB_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.apache\\.maven\\.plugins:maven-scm-plugin:.*?:checkout)"),
                ReproducibleBreakingUpdate.FailureCategory.SCM_CHECKOUT_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.apache\\.maven\\.plugins:maven-checkstyle-plugin:.*?:check)"),
                ReproducibleBreakingUpdate.FailureCategory.CHECKSTYLE_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.apache\\.maven\\.plugins:maven-enforcer-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.MAVEN_ENFORCER_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Could not resolve dependencies | \\[ERROR] Some problems were encountered while processing the POMs | " +
                        "\\[ERROR] .*?The following artifacts could not be resolved)"),
                ReproducibleBreakingUpdate.FailureCategory.DEPENDENCY_RESOLUTION_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal se\\.vandmo:dependency-lock-maven-plugin:.*?:check)"),
                ReproducibleBreakingUpdate.FailureCategory.DEPENDENCY_LOCK_FAILURE);
    }

    public void runReproducibilityChecker(Path benchmarkDir) {

        File[] breakingUpdates = benchmarkDir.toFile().listFiles();
        createDockerClient();
        MapType buJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);

        // Read reproducibility results
        MapType reproducibilityJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Boolean.class);
        Path reproducibilityResultsFilePath = Path.of("src/main/java/rqs/reproducibility-results" +
                JsonUtils.JSON_FILE_ENDING);
        if (Files.notExists(reproducibilityResultsFilePath)) {
            try {
                Files.createFile(reproducibilityResultsFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Map<String, Boolean> reproducibilityResults = JsonUtils.readFromNullableFile(reproducibilityResultsFilePath,
                reproducibilityJsonType);
        if (reproducibilityResults == null) {
            reproducibilityResults = new HashMap<>();
        }

        if (breakingUpdates != null) {
            for (File breakingUpdate : breakingUpdates) {
                Map<String, Object> bu = JsonUtils.readFromFile(breakingUpdate.toPath(), buJsonType);

                String prevImage = REGISTRY + ":" + bu.get("breakingCommit") + PRECEDING_COMMIT_CONTAINER_TAG;
                String breakingImage = REGISTRY + ":" + bu.get("breakingCommit") + BREAKING_UPDATE_CONTAINER_TAG;

                Path projectPath;
                try {
                    projectPath = Files.createDirectories(Path.of("tempProject")
                            .resolve((String) bu.get("breakingCommit")));
                } catch (IOException e) {
                    log.error("Could not create directories to copy the log file.");
                    throw new RuntimeException(e);
                }

                // Run reproduction.
                if (!reproducibilityResults.containsKey((String) bu.get("breakingCommit"))) {

                    ReproducibleBreakingUpdate.FailureCategory failureCategory =
                            ReproducibleBreakingUpdate.FailureCategory.valueOf((String) bu.get("failureCategory"));
                    Boolean isReproducible = isReproducible(failureCategory, (String) bu.get("project"), projectPath, prevImage, breakingImage);
                    reproducibilityResults.put((String) bu.get("breakingCommit"), isReproducible);

                    JsonUtils.writeToFile(reproducibilityResultsFilePath, reproducibilityResults);

                    removeProject(prevImage, breakingImage);
                }
            }
        }
    }

    private Boolean isReproducible(ReproducibleBreakingUpdate.FailureCategory failureCategory, String project,
                                   Path copyDir, String prevImage, String breakingImage) {
        Map.Entry<String, Boolean> prevContainer = startContainer(prevImage, true, project);
        Map.Entry<String, Boolean> breakingContainer = startContainer(breakingImage, false, project);
        if (prevContainer == null || breakingContainer == null)
            return null;
        if (!prevContainer.getValue() || !breakingContainer.getValue())
            return false;
        Path logFilePath = storeLogFile(project, breakingContainer.getKey(), copyDir);
        if (logFilePath != null) {
            return getFailureCategory(logFilePath).equals(failureCategory);
        }
        return null;
    }

    /* Returns false as the value of the map if the build failed for the previous commit or build did not fail for the
    breaking commit. The key of the map is the started containerID. */
    private Map.Entry<String, Boolean> startContainer(String image, boolean isPrevImage, String project) {
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            try {
                dockerClient.pullImageCmd(image)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion();
            } catch (Exception ex) {
                log.error("Image not found for {}", image, ex);
                return null;
            }
        }
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image)
                .withWorkingDir("/" + project)
                .withCmd("sh", "-c", "--network none", "set -o pipefail && (mvn clean test -B 2>&1 | tee -ai output.log)");
        CreateContainerResponse container = containerCmd.exec();
        String containerId = container.getId();
        dockerClient.startContainerCmd(containerId).exec();
        log.info("Created container for " + image);
        WaitContainerResultCallback result = dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback());
        if (result.awaitStatusCode().intValue() != EXIT_CODE_OK) {
            if (isPrevImage) {
                log.error("Previous commit failed for {}", image);
                return Map.entry(containerId, false);
            }
        } else {
            if (!isPrevImage) {
                log.error("Breaking commit did not fail for {}", image);
                return Map.entry(containerId, false);
            }
        }
        return Map.entry(containerId, true);
    }

    private Path storeLogFile(String project, String containerId, Path outputDir) {
        Path logOutputLocation = outputDir.resolve("breakingCommitOutput.log");
        String logLocation = "/%s/output.log".formatted(project);
        try (InputStream logStream = dockerClient.copyArchiveFromContainerCmd(containerId, logLocation).exec()) {
            byte[] fileContent = logStream.readAllBytes();
            Files.write(logOutputLocation, fileContent);
            return logOutputLocation;
        } catch (IOException e) {
            log.error("Could not store the log file for the project {}", project);
            return null;
        }
    }

    private ReproducibleBreakingUpdate.FailureCategory getFailureCategory(Path path) {
        try {
            String logContent = Files.readString(path, StandardCharsets.ISO_8859_1);
            for (Map.Entry<Pattern, ReproducibleBreakingUpdate.FailureCategory> entry : FAILURE_PATTERNS.entrySet()) {
                Pattern pattern = entry.getKey();
                Matcher matcher = pattern.matcher(logContent);
                if (matcher.find()) {
                    log.info("Failure category found: {}", entry.getValue());
                    return entry.getValue();
                }
            }
            log.error("Did not find the failure category");
            return ReproducibleBreakingUpdate.FailureCategory.UNKNOWN_FAILURE;
        } catch (IOException e) {
            log.error("Failure category could not be parsed for {}", path);
            throw new RuntimeException(e);
        }
    }

    private void removeProject(String prevImage, String breakingImage) {

        dockerClient.removeImageCmd(prevImage).withForce(true).exec();
        dockerClient.removeImageCmd(breakingImage).withForce(true).exec();

        log.info("removed the images.");
    }

    private void createDockerClient() {
        DockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withRegistryUrl("https://hub.docker.com")
                .build();
        DockerHttpClient httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .sslConfig(clientConfig.getSSLConfig())
                .connectTimeout(30)
                .build();
        dockerClient = DockerClientImpl.getInstance(clientConfig, httpClient);
    }
}

