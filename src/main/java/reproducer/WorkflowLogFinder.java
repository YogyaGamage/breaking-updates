package reproducer;

import com.fasterxml.jackson.databind.type.MapType;
import miner.JsonUtils;
import org.kohsuke.github.*;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class WorkflowLogFinder {
    // base-download-directory - C:\Users\Yogya\Documents\KTH\Breaking Updates\breaking-updates\workflowLogs
    static String baseDownloadDirectory = "";

    public static void main(String[] args) throws Exception {
        File[] breakingUpdates = Path.of("data/benchmark").toFile().listFiles();
        MapType jsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
        if (breakingUpdates != null) {
            // workflowLogs/workflowLogLocations.json
            Path workflowLogFilePath = Path.of("workflowLogLocations" + JsonUtils.JSON_FILE_ENDING);
            if (Files.notExists(workflowLogFilePath)) {
                Files.createFile(workflowLogFilePath);
            }
            Map<String, Map<String, Boolean>> workflowLogs = JsonUtils.readFromNullableFile(workflowLogFilePath, jsonType);
            if (workflowLogs == null) {
                workflowLogs = new HashMap<>();
            }
            int count = 0;
            for (File breakingUpdate : breakingUpdates) {
                Map<String, Object> bu = JsonUtils.readFromFile(breakingUpdate.toPath(), jsonType);
                String downloadDirectory = baseDownloadDirectory + "\\" + bu.get("breakingCommit") + "\\";
                if (!Files.exists(Path.of(downloadDirectory))) {
                    new File(downloadDirectory).mkdirs();

                    String prUrl = (String) bu.get("url");
                    String[] urlParts = prUrl.split("/");
                    String repoOwner = urlParts[3];
                    String repoName = urlParts[4];
                    int prNumber = Integer.parseInt(urlParts[6]);
                    GitHub github = new GitHubBuilder().withOAuthToken("token").build();

                    GHRepository repository = github.getRepository(repoOwner + "/" + repoName);
                    GHPullRequest pr = repository.getPullRequest(prNumber);
                    GHWorkflowRunQueryBuilder query = pr.getRepository().queryWorkflowRuns()
                            .branch(pr.getHead().getRef())
                            .event(GHEvent.PULL_REQUEST)
                            .status(GHWorkflowRun.Status.COMPLETED)
                            .conclusion(GHWorkflowRun.Conclusion.FAILURE);
                    List<GHWorkflowRun> workflowRuns = query.list().toList().stream()
                            .filter(run -> run.getHeadSha().equals(pr.getHead().getSha())).toList();
                    Map<String, Boolean> logLocation = new HashMap<>();

                    for (GHWorkflowRun workflowRun : workflowRuns) {
                        for (GHWorkflowJob ghWorkflowJob : (workflowRun.listAllJobs().toList()
                                .stream().filter(run -> run.getConclusion().equals(GHWorkflowRun.Conclusion.FAILURE))
                                .toList())) {
                            String jobUrl = String.valueOf(ghWorkflowJob.getHtmlUrl());
                            if (downloadLogFile(jobUrl, downloadDirectory))
                                logLocation.put(jobUrl, true);
                            else
                                logLocation.put(jobUrl, false);
                        }
                    }
                    workflowLogs.put((String) bu.get("breakingCommit"), logLocation);
                    System.out.println(bu.get("breakingCommit"));
                    count += 1;
                    if (count > 4) {
                        JsonUtils.writeToFile(workflowLogFilePath, workflowLogs);
                        count = 0;
                        System.out.println("Last 5 records were written to the json file");
                        Thread.sleep(2000);
                    }
                }
            }
            JsonUtils.writeToFile(workflowLogFilePath, workflowLogs);
        }
    }

    private static boolean downloadLogFile(String prUrl, String downloadDirectory) throws IOException {
        if (Files.list(Path.of(downloadDirectory)).findAny().isPresent())
            return true;
        // chrome-driver-path - C:\Users\Yogya\Downloads\chromedriver-win64\chromedriver-win64\chromedriver.exe
        System.setProperty("webdriver.chrome.driver", "chrome-driver-path");

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--disable-extensions");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        // user-data-dir-path - C:\Users\Yogya\AppData\Local\Google\Chrome\User Data
        options.addArguments("user-data-dir=");
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", downloadDirectory);
        options.setExperimentalOption("prefs", prefs);

        WebDriver driver = new ChromeDriver(options);

        try {
            driver.get(prUrl);
            Thread.sleep(7500);
            WebElement settingsButton = driver.findElement(By.cssSelector(".btn-link .octicon-gear path"));
            Actions actions = new Actions(driver);
            actions.moveToElement(settingsButton).click().build().perform();

            Thread.sleep(2500);
            WebElement downloadOption = driver.findElement(By.xpath("//*[@id='logs']/div[1]/div/details/details-menu/a[1]"));
            downloadOption.click();
            Thread.sleep(11000);
            return true;
        } catch (NoSuchElementException e) {
            e.printStackTrace();
            return false;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            driver.quit();
        }
    }
}
