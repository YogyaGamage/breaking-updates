package rqs;

import com.fasterxml.jackson.databind.type.MapType;
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
import miner.JsonUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class DepChangeChecker {

    private static final String PRECEDING_COMMIT_CONTAINER_TAG = "-pre";

    private static final String BREAKING_UPDATE_CONTAINER_TAG = "-breaking";
    private static final String REGISTRY = "ghcr.io/chains-project/breaking-updates";
    private static final Logger log = LoggerFactory.getLogger(DepChangeChecker.class);
    private static DockerClient dockerClient;
    private static final List<String> containers = new ArrayList<>();


    public void runDepChangeChecker(Path benchmarkDir) {

        File[] breakingUpdates = benchmarkDir.toFile().listFiles();
        createDockerClient();
        MapType buJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);

        // Read dependency count results
        MapType depCountJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class,
                Map.class);
        Path depCountResultsFilePath = Path.of("src/main/java/rqs/dep-diff-results" +
                JsonUtils.JSON_FILE_ENDING);
        if (Files.notExists(depCountResultsFilePath)) {
            try {
                Files.createFile(depCountResultsFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Map<String, Map<String, Integer>> depCountResults = JsonUtils.readFromNullableFile(depCountResultsFilePath,
                depCountJsonType);
        if (depCountResults == null) {
            depCountResults = new HashMap<>();
        }

        if (breakingUpdates != null) {
            for (File breakingUpdate : breakingUpdates) {
                Map<String, Object> bu = JsonUtils.readFromFile(breakingUpdate.toPath(), buJsonType);
                Map<String, Integer> depCount = new HashMap<>();

                String prevImage = REGISTRY + ":" + bu.get("breakingCommit") + PRECEDING_COMMIT_CONTAINER_TAG;
                String breakingImage = REGISTRY + ":" + bu.get("breakingCommit") + BREAKING_UPDATE_CONTAINER_TAG;

                // Create directories to copy the project.
                Path preProjectPath;
                Path breakingProjectPath;
                if (!depCountResults.containsKey((String) bu.get("breakingCommit"))) {
                    try {
                        Path tempProject = Path.of("tempProject");
                        preProjectPath = Files.createDirectories(tempProject
                                .resolve(bu.get("breakingCommit") + "-pre"));
                        breakingProjectPath = Files.createDirectories(tempProject
                                .resolve(bu.get("breakingCommit") + "-breaking"));
                    } catch (IOException e) {
                        log.error("Could not create directories to copy the project.");
                        throw new RuntimeException(e);
                    }

                    String prevContainer = startContainer(prevImage);
                    String breakingContainer = startContainer(breakingImage);
                    copyProject(prevContainer, (String) bu.get("project"), preProjectPath);
                    copyProject(breakingContainer, (String) bu.get("project"), breakingProjectPath);
                    try {
                        System.out.println("making tree");
                        File preTreeFile = new File("src/main/java/rqs/new-dep-trees/" + bu.get("breakingCommit") + "-pre.txt");
                        // downloadDepTree(preProjectPath.toAbsolutePath() + File.separator + bu.get("project"),
                        //         preTreeFile.getParentFile().getAbsolutePath(), preTreeFile.getName());

                        File breTreeFile = new File("src/main/java/rqs/new-dep-trees/" + bu.get("breakingCommit") + "-bre.txt");
                        // downloadDepTree(breakingProjectPath.toAbsolutePath() + File.separator + bu.get("project"),
                        //         breTreeFile.getParentFile().getAbsolutePath(), breTreeFile.getName());

                        File diffFile = new File("src/main/java/rqs/new-dep-trees/" + bu.get("breakingCommit") + "-diff.txt");

                        DependencyCounts allDepCounts = null;

                        allDepCounts = countChangedDependencies(preTreeFile, breTreeFile, diffFile);

                        depCount.put("addedDeps", allDepCounts.addedDepCount);
                        depCount.put("removedDeps", allDepCounts.removedDepCount);
                        depCountResults.put((String) bu.get("breakingCommit"), depCount);

                        JsonUtils.writeToFile(depCountResultsFilePath, depCountResults);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    // removeProject(prevImage, breakingImage, preProjectPath, breakingProjectPath);
                }
            }
        }
    }

    /*
     * Returns false as the value of the map if the build failed for the previous
     * commit or build did not fail for the
     * breaking commit. The key of the map is the started containerID.
     */
    private String startContainer(String image) {
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            System.out.println("pulling image: " + image);
            try {
                dockerClient.pullImageCmd(image)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion();
            } catch (Exception ex) {
                log.error("Image not found for {}", image, ex);
                return null;
            }
        }
        // Create a tar archive.
        try (InputStream ignored = dockerClient.saveImageCmd(image).exec()) {
        } catch (IOException e) {
            e.printStackTrace();
        }

        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        CreateContainerResponse container = containerCmd.exec();
        String containerId = container.getId();
        log.info("Created container for " + image);
        return containerId;
    }


    private Path copyProject(String containerId, String project, Path dir) {
        dockerClient.startContainerCmd(containerId).exec();
        containers.add(containerId);

        try (InputStream dependencyStream = dockerClient.copyArchiveFromContainerCmd(containerId, "/" + project)
                .exec()) {
            try (TarArchiveInputStream tarStream = new TarArchiveInputStream(dependencyStream)) {
                TarArchiveEntry entry;
                while ((entry = tarStream.getNextTarEntry()) != null) {
                    if (!entry.isDirectory()) {
                        Path filePath = dir.resolve(entry.getName());

                        if (!Files.exists(filePath)) {
                            Files.createDirectories(filePath.getParent());
                            Files.createFile(filePath);

                            byte[] fileContent = tarStream.readAllBytes();
                            Files.write(filePath, fileContent, StandardOpenOption.WRITE);
                        }
                    }
                }
            }
            log.info("Successfully copied the project {}.", project);
            return dir;
        } catch (Exception e) {
            log.error("Could not copy the project {}", project, e);
            return null;
        }
    }

    private void downloadDepTree(String projectPath, String treeFilePath, String treeFileName) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder();
        System.out.println("sudo" + " mvn " + "-f " + projectPath + " depgraph:aggregate " + "-DgraphFormat=text " + "-Ddetail=true " +
                "-DshowGroupIds=true " + "-DshowVersions=true " + "-DshowTypes=true " + "-DoutputDirectory=" + treeFilePath +
                " -DoutputFileName=" + treeFileName);
        processBuilder.command("sudo", "mvn", "-f", projectPath, "depgraph:aggregate", "-DgraphFormat=text", "-Ddetail=true",
                "-DshowGroupIds=true", "-DshowVersions=true", "-DshowTypes=true", "-DoutputDirectory=" + treeFilePath,
                "-DoutputFileName=" + treeFileName);
        Process process = processBuilder.start();

        InputStream inStream = process.getInputStream();
        InputStream errStream = process.getErrorStream();
        try {
            inStream.close();
            errStream.close();
        } catch (IOException e1) {
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.error("Process for creating the dependency tree exited with error code {} for the tree in {}",
                    exitCode,
                    treeFilePath);
        }

        System.out.println("deptree should be downloaded");
    }

    private static Set<String> readDependenciesFromFile(File treeFile) throws IOException {
        Set<String> dependencies = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(treeFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("-", 2);
                if (parts.length >= 2) {
                    dependencies.add(parts[1]);
                }
            }
        }
        return dependencies;
    }

    private static DependencyCounts countChangedDependencies(File preTreeFile, File breTreeFile, File diffFile)
            throws IOException {

        Set<String> dependencies1 = readDependenciesFromFile(preTreeFile);
        Set<String> dependencies2 = readDependenciesFromFile(breTreeFile);

        // Find the differences
        Set<String> added = new HashSet<>(dependencies2);
        added.removeAll(dependencies1);

        Set<String> removed = new HashSet<>(dependencies1);
        removed.removeAll(dependencies2);

        try (FileWriter writer = new FileWriter(diffFile)) {
            writer.write("Added Dependencies:" + "\n");
            for (String dep : added) {
                writer.write(dep + "\n");
            }
            writer.write("Removed Dependencies:" + "\n");
            for (String dep : removed) {
                writer.write(dep + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return new DependencyCounts(added.size(), removed.size());
    }

    private void removeProject(String prevImage, String breakingImage, Path preProjectPath, Path breProjectPath) {
        try {
            // dockerClient.removeImageCmd(prevImage).withForce(true).exec();
            // dockerClient.removeImageCmd(breakingImage).withForce(true).exec();
            FileUtils.forceDelete(new File(preProjectPath.toUri()));
            FileUtils.forceDelete(new File(breProjectPath.toUri()));
        } catch (Exception e) {
            log.error("Project {} could not be deleted.", breProjectPath, e);
        }
        log.info("removed the images and the project.");
    }

    public static boolean isMultiModuleMavenProject(String projectPath) {
        File pomFile = new File(projectPath, "pom.xml");

        if (pomFile.exists()) {
            try {
                MavenXpp3Reader reader = new MavenXpp3Reader();
                String pomXmlContent = FileUtils.readFileToString(pomFile, "UTF-8");
                Model model = reader.read(new StringReader(pomXmlContent));
                return model.getModules() != null && !model.getModules().isEmpty();
            } catch (IOException | XmlPullParserException e) {
                e.printStackTrace();
            }
        }
        return false;
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

    public static class DependencyCounts {
        int addedDepCount;
        int removedDepCount;

        DependencyCounts(int addedDepCount, int removedDepCount) {
            this.addedDepCount = addedDepCount;
            this.removedDepCount = removedDepCount;
        }
    }
}
