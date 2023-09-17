package rqs;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import miner.BreakingUpdate;
import miner.GitHubAPITokenQueue;
import okhttp3.OkHttpClient;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class TempPusher {
    private static final String PRECEDING_COMMIT_CONTAINER_TAG = "-pre";

    private static final String BREAKING_UPDATE_CONTAINER_TAG = "-breaking";
    private static final String REGISTRY = "ghcr.io/chains-project/breaking-updates";
    private static final String CACHE_REPO = "chains-project/breaking-updates-cache";
    private static final String BRANCH_NAME = "main";
    private static final Logger log = LoggerFactory.getLogger(ReproducibilityChecker.class);
    private static DockerClient dockerClient;


    public static void main(String[] args) throws IOException {
        createDockerClient();
        Set<String> withoutPrevJar = readTextFile("subfolders_without_prev_jar.txt");
        Set<String> withoutNewJar = readTextFile("subfolders_without_new_jar.txt");

        for (String element : withoutPrevJar) {
            System.out.println("Subfolder: " + element);
            String jsonFilePath = "data/benchmark/" + element + ".json";
            List<String> buValues = extractValuesFromJson(jsonFilePath);

            String image = REGISTRY + ":" + element + PRECEDING_COMMIT_CONTAINER_TAG;
            String containerId = startContainer(image);
            extractPrevDependencies(element, buValues.get(0), buValues.get(1), buValues.get(2), containerId);
            removeProject(image);

        }

        for (String element : withoutNewJar) {
            System.out.println("Subfolder: " + element);
            String jsonFilePath = "data/benchmark/" + element + ".json";
            List<String> buValues = extractValuesFromJson(jsonFilePath);


            String image = REGISTRY + ":" + element + BREAKING_UPDATE_CONTAINER_TAG;
            String containerId = startContainer(image);
            extractPostDependencies(element, buValues.get(0), buValues.get(1), buValues.get(3), containerId);
            removeProject(image);
        }
    }

    private static Set<String> readTextFile(String filename) {
        Set<String> set = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                set.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return set;
    }

    private static List<String> extractValuesFromJson(String jsonFilePath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(jsonFilePath))) {
            StringBuilder jsonContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonContent.append(line);
            }

            List<String> buValues = new ArrayList<>();

            // Parse the JSON content using Gson
            JsonParser jsonParser = new JsonParser();
            JsonObject jsonObject = jsonParser.parse(jsonContent.toString()).getAsJsonObject();

            // Extract values
            JsonObject updatedDependency = jsonObject.getAsJsonObject("updatedDependency");
            String dependencyGroupID = updatedDependency.get("dependencyGroupID").getAsString();
            String dependencyArtifactID = updatedDependency.get("dependencyArtifactID").getAsString();
            String previousVersion = updatedDependency.get("previousVersion").getAsString();
            String newVersion = updatedDependency.get("newVersion").getAsString();

            buValues.add(dependencyGroupID);
            buValues.add(dependencyArtifactID);
            buValues.add(previousVersion);
            buValues.add(newVersion);

            return buValues;
        }
    }

    private static String startContainer(String image) {
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
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        CreateContainerResponse container = containerCmd.exec();
        String containerId = container.getId();
        dockerClient.startContainerCmd(containerId).exec();
        log.info("Created container for " + image);

        return containerId;
    }

    private static void createDockerClient() {
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

    private static void extractPrevDependencies(String buCommit, String dependencyGroupID, String dependencyArtifactID,
                                                String previousVersion, String prevContainerId) {
        String dependencyLocationBase = "/root/.m2/repository/%s/%s/"
                .formatted(dependencyGroupID.replaceAll("\\.", "/"), dependencyArtifactID);
        for (String type : List.of("jar", "pom")) {
            String oldDependencyLocation = dependencyLocationBase + "%s/%s-%s.%s"
                    .formatted(previousVersion, dependencyArtifactID, previousVersion, type);
            try (InputStream dependencyStream = dockerClient.copyArchiveFromContainerCmd
                    (prevContainerId, oldDependencyLocation).exec()) {
                byte[] fileContent = dependencyStream.readAllBytes();
                // Push the saved old jar/pom file to the cache repo.
                String jarName = "%s__%s__%s___prev.%s".formatted(dependencyGroupID, dependencyArtifactID,
                        previousVersion, type);
                pushFiles(buCommit, jarName, fileContent);
                return;
            } catch (NotFoundException e) {
                if (type.equals("jar")) {
                    log.info("Could not find the old jar for breaking update {}. Searching for a pom instead...",
                            buCommit);
                } else {
                    log.error("Could not find the old jar or pom for breaking update {}", buCommit);
                }
            } catch (IOException e) {
                log.error("Could not store the old {} for breaking update {}.", type, buCommit, e);
            }
        }
    }

    private static void extractPostDependencies(String buCommit, String dependencyGroupID, String dependencyArtifactID,
                                                String newVersion, String postContainerId) {
        String dependencyLocationBase = "/root/.m2/repository/%s/%s/"
                .formatted(dependencyGroupID.replaceAll("\\.", "/"), dependencyArtifactID);
        for (String type : List.of("jar", "pom")) {
            String newDependencyLocation = dependencyLocationBase + "%s/%s-%s.%s"
                    .formatted(newVersion, dependencyArtifactID, newVersion, type);
            try (InputStream dependencyStream = dockerClient.copyArchiveFromContainerCmd(postContainerId,
                    newDependencyLocation).exec()) {
                byte[] fileContent = dependencyStream.readAllBytes();
                // Push the saved new jar/pom file to the cache repo.
                String jarName = "%s__%s__%s___new.%s".formatted(dependencyGroupID, dependencyArtifactID, newVersion,
                        type);
                pushFiles(buCommit, jarName, fileContent);
                return;
            } catch (NotFoundException e) {
                if (type.equals("jar")) {
                    log.error("Could not find the new jar for breaking update {}.", buCommit);
                } else {
                    log.error("Could not find the new pom for breaking update {}.", buCommit);
                }
            } catch (IOException e) {
                log.error("Could not store the new {} for breaking update {}.", type, buCommit, e);
            }
        }
    }

    private void storeLogFile(BreakingUpdate bu, String containerId) {
        // Save log result in reproduction dir.
        String logLocation = "/%s/%s.log".formatted(bu.project, bu.breakingCommit);
        try (InputStream logStream = dockerClient.copyArchiveFromContainerCmd(containerId, logLocation).exec()) {
            byte[] fileContent = logStream.readAllBytes();
            pushFiles(bu.breakingCommit, bu.breakingCommit + ".log", fileContent);
        } catch (IOException e) {
            log.error("Could not store the log file for breaking update {}", bu.breakingCommit);
            throw new RuntimeException(e);
        }
    }

    public static void pushFiles(String breakingCommit, String fileName, byte[] fileContent) {

        OkHttpClient httpConnector = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS).build();
        try {
            List<String> apiTokens = Files.readAllLines(Path.of("token.txt"));
            GitHubAPITokenQueue tokenQueue = new GitHubAPITokenQueue(apiTokens);
            GitHub github = tokenQueue.getGitHub(httpConnector);
            GHRepository repo = github.getRepository(CACHE_REPO);
            GHRef branchRef = repo.getRef("heads/" + BRANCH_NAME);
            String latestCommitHash = branchRef.getObject().getSha();
            // Create the tree.
            GHTreeBuilder treeBuilder = repo.createTree();
            treeBuilder.baseTree(latestCommitHash);
            treeBuilder.add("data/" + breakingCommit + "/" + fileName, fileContent, false);
            GHTree tree = treeBuilder.create();
            // Create the commit.
            GHCommit commit = repo.createCommit()
                    .message("Push the %s for the breaking update %s.".formatted(fileName, breakingCommit))
                    .parent(latestCommitHash)
                    .tree(tree.getSha())
                    .create();
            // Update the branch reference.
            branchRef.updateTo(commit.getSHA1());
            log.info("Successfully pushed the {} to the {}.", fileName, CACHE_REPO);
        } catch (IOException e) {
            log.error("Failed to push the {} to the {}.", fileName, CACHE_REPO, e);
        } catch (GHException e) {
            log.error("The provided GitHub token does not have the permission to push the {} to the {}",
                    fileName, CACHE_REPO, e);
        }
    }

    private static void removeProject(String image) {
        dockerClient.removeImageCmd(image).withForce(true).exec();
        log.info("removed the image.");
    }
}
