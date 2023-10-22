package rqs;

import picocli.CommandLine;

import java.nio.file.Path;

/**
 * This class represents the main entry points to the reproducibility checker and the dependency diff checker.
 */
public class Main {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CLIEntryPoint()).execute(args);
        System.exit(exitCode);
    }

    @CommandLine.Command(subcommands = {Reproduce.class, SaveNGetDiff.class},mixinStandardHelpOptions = true,version = "0.1")
    public static class CLIEntryPoint implements Runnable {
        @Override
        public void run() { CommandLine.usage(this, System.out); }
    }

    @CommandLine.Command(name = "check-reproducibility", mixinStandardHelpOptions = true, version = "0.1")
    private static class Reproduce implements Runnable {

        @CommandLine.Option(
                names = {"-b", "--benchmark-dir"},
                paramLabel = "BENCHMARK-DIR",
                description = "The directory where successful breaking update reproduction information are written.",
                required = true
        )
        Path benchmarkDir;

        @Override
        public void run() {
            ReproducibilityChecker reproducibilityChecker = new ReproducibilityChecker();
            reproducibilityChecker.runReproducibilityCheckerAndCounter(benchmarkDir);
        }
    }

    @CommandLine.Command(name = "save-image-n-get-dep-diff", mixinStandardHelpOptions = true, version = "0.1")
    private static class SaveNGetDiff implements Runnable {

        @CommandLine.Option(
                names = {"-b", "--benchmark-dir"},
                paramLabel = "BENCHMARK-DIR",
                description = "The directory where successful breaking update reproduction information are written.",
                required = true
        )
        Path benchmarkDir;

        @Override
        public void run() {
            DepChangeChecker depChangeChecker = new DepChangeChecker();
            depChangeChecker.runDepChangeChecker(benchmarkDir);
        }
    }
}
