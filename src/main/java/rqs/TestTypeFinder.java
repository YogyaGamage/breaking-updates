package rqs;

import com.fasterxml.jackson.databind.type.MapType;
import miner.JsonUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestTypeFinder {

    public static void main(String[] args)  {

        Path benchmarkDir = Path.of("data/benchmark");
        File[] breakingUpdates = benchmarkDir.toFile().listFiles();
        MapType buJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);

        // Read TestTypes
        MapType testTypeJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class,
                Object.class);
        Path testTypeFilePath = Path.of("src/main/java/rqs/test-types" + JsonUtils.JSON_FILE_ENDING);
        Path logFileFolder = Path.of("reproductionLogs/successfulReproductionLogs");
        if (Files.notExists(testTypeFilePath)) {
            try {
                Files.createFile(testTypeFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Map<String, Map<String, Integer>> testFailureTypes = JsonUtils.readFromNullableFile(testTypeFilePath,
                testTypeJsonType);
        if (testFailureTypes == null) {
            testFailureTypes = new HashMap<>();
        }

        if (breakingUpdates != null) {
            for (File breakingUpdate : breakingUpdates) {
                Map<String, Object> bu = JsonUtils.readFromFile(breakingUpdate.toPath(), buJsonType);
                Map<String, Integer> testFailureTypeRecord = new HashMap<>();
                if (bu.get("failureCategory").equals("TEST_FAILURE") && !testFailureTypes.containsKey((String) bu.get("breakingCommit"))) {
                    String logFilePath = logFileFolder + "/" + bu.get("breakingCommit") + ".log";
                    List <Integer> failureTypes = extractTestFailureType(logFilePath);
                    if (failureTypes.get(0) == -1) {
                        testFailureTypeRecord.put("VMCrashed", 1);
                    }
                    else if (failureTypes.get(0) == -2) {
                        testFailureTypeRecord.put("SureFirePluginError", 1);
                    }
                    else if (failureTypes.get(0) == -3) {
                        testFailureTypeRecord.put("JUnitError", 1);
                    }
                    else {
                        testFailureTypeRecord.put("totalModuleTests", failureTypes.get(0));
                        testFailureTypeRecord.put("testFailures", failureTypes.get(1));
                        testFailureTypeRecord.put("testErrors", failureTypes.get(2));
                        testFailureTypeRecord.put("randomisedTestFailures", failureTypes.get(3));
                        testFailureTypeRecord.put("skippedTests", failureTypes.get(4));
                    }
                    testFailureTypes.put((String) bu.get("breakingCommit"), testFailureTypeRecord);
                    JsonUtils.writeToFile(testTypeFilePath, testFailureTypes);
                }
            }
        }
    }

    private static List<Integer> extractTestFailureType(String logFilePath) {
        List<Integer> counts = new ArrayList<>();
        try {
            FileInputStream fileInputStream = new FileInputStream(logFilePath);
            InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream, StandardCharsets.ISO_8859_1);
            BufferedReader reader = new BufferedReader(inputStreamReader);
            String line;

            Pattern errorPattern = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)");
            Pattern errorPattern2 = Pattern.compile("Tests summary: \\d+ suites, (\\d+) tests, (\\d+) failur.*?, (\\d+) ignored");
            Pattern errorPattern3 = Pattern.compile("The forked VM terminated without properly saying goodbye. VM crash or System.exit called?");
            Pattern errorPattern4 = Pattern.compile("\\[ERROR] Number of foreign imports: 1");
            Pattern errorPattern5 = Pattern.compile("\\[ERROR] .java\\.util\\.Set org\\.junit\\.platform\\.engine\\.TestDescriptor\\.getAncestors");

            int testCount = 0;
            int failureCount = 0;
            int errorCount = 0;
            int randomisedTestFailureCount = 0;
            int skippedCount = 0;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = errorPattern.matcher(line);
                if (matcher.find()) {
                    // This will get the last line with test errors in the log folder.
                    testCount = Integer.parseInt(matcher.group(1));
                    failureCount = Integer.parseInt(matcher.group(2));
                    errorCount = Integer.parseInt(matcher.group(3));
                    skippedCount = Integer.parseInt(matcher.group(4));
                }
                Matcher matcher2 = errorPattern2.matcher(line);
                if (matcher2.find()) {
                    // This will get the last line with test errors in the log folder.'
                    testCount = Integer.parseInt(matcher2.group(1));
                    randomisedTestFailureCount = Integer.parseInt(matcher2.group(2));
                    skippedCount = Integer.parseInt(matcher2.group(3));
                }
                Matcher matcher3 = errorPattern3.matcher(line);
                if (matcher3.find()) {
                    return List.of(-1);
                }
                Matcher matcher4 = errorPattern4.matcher(line);
                if (matcher4.find()) {
                    return List.of(-2);
                }
                Matcher matcher5 = errorPattern5.matcher(line);
                if (matcher5.find()) {
                    return List.of(-3);
                }
            }
            reader.close();
            counts.add(testCount);
            counts.add(failureCount);
            counts.add(errorCount);
            counts.add(randomisedTestFailureCount);
            counts.add(skippedCount);
            if (failureCount == 0 && errorCount == 0 && randomisedTestFailureCount == 0) {
                System.out.println(logFilePath + " does not contain any errors or failures!!!!!!!!!!!!!!!!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return counts;
    }
}
