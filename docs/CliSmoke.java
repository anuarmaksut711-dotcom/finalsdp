import ecosystem.Creation;
import ecosystem.EcosystemFacade;
import ecosystem.Reporting;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Exercises the packaged application in a separate process and working directory. */
public final class CliSmoke {
    private static Path jar;
    private static Path output;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String run(boolean success, String... arguments) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Djava.awt.headless=true", "-jar", jar.toString()));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(output, "command-", ".log");
        Process process = new ProcessBuilder(command).directory(output.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
            throw new AssertionError("CLI command exceeded 30 seconds: " + Arrays.toString(arguments));
        }
        String text = Files.readString(log, StandardCharsets.UTF_8);
        check((process.exitValue() == 0) == success,
                "Unexpected CLI result for " + Arrays.toString(arguments) + ": " + text);
        return text;
    }

    private static void reports() throws Exception {
        run(true, "--examples", "reports-one");
        run(true, "--examples", "reports-two");
        EcosystemFacade facade = new EcosystemFacade();
        facade.reset(new Creation.WorldBuilder().build());
        for (int tick = 0; tick < 16; tick++) facade.step();
        for (boolean detailed : new boolean[]{false, true}) {
            for (Reporting.ReportRenderer renderer : List.of(new Reporting.PlainTextRenderer(), new Reporting.CsvRenderer())) {
                String name = (detailed ? "detailed" : "summary") + "-tick16." + renderer.extension();
                Path one = output.resolve("reports-one").resolve(name);
                Path two = output.resolve("reports-two").resolve(name);
                check(Files.mismatch(one, two) == -1, "Reports are not reproducible: " + name);
                check(Files.readString(one, StandardCharsets.UTF_8).equals(facade.report(detailed, renderer)),
                        "Packaged report differs from the model: " + name);
            }
        }
        System.out.println("PASS CLI exports four reproducible report variants matching the model");
    }

    private static void comparison() throws Exception {
        run(true, "--demo", "comparison-one.csv");
        run(true, "--demo", "comparison-two.csv");
        Path one = output.resolve("comparison-one.csv"), two = output.resolve("comparison-two.csv");
        check(Files.mismatch(one, two) == -1, "Comparison is not reproducible");
        List<String> lines = Files.readAllLines(one, StandardCharsets.UTF_8);
        check(lines.size() == 304, "Comparison needs a header and 303 observations");
        check(lines.get(0).equals("strategy,tick,herbivores,predators,plant_biomass,animal_body_units"), "Invalid comparison header");
        String[] names = {"Nearest food", "Safest food", "Sustainable grazing"};
        Set<String> results = new HashSet<>();
        for (int strategy = 0; strategy < names.length; strategy++) {
            StringBuilder history = new StringBuilder();
            for (int tick = 0; tick <= 100; tick++) {
                String row = lines.get(1 + strategy * 101 + tick);
                String[] fields = row.split(",");
                check(fields.length == 6 && fields[0].equals(names[strategy]) && Integer.parseInt(fields[1]) == tick,
                        "Missing or unordered observation");
                for (int column = 2; column < fields.length; column++) check(Integer.parseInt(fields[column]) >= 0, "Negative observation");
                history.append(row.substring(names[strategy].length())).append('\n');
            }
            results.add(history.toString());
        }
        check(results.size() > 1, "The three strategies produced identical histories");
        System.out.println("PASS CLI comparison has 303 ordered observations and reproducible strategy differences");
    }

    private static void screenshot(String name, String biome, int width, int height) throws Exception {
        run(true, "--screenshot", name, "16", biome, "Nearest food", Integer.toString(width), Integer.toString(height));
        BufferedImage image = ImageIO.read(output.resolve(name).toFile());
        check(image != null && image.getWidth() == width && image.getHeight() == height, "Invalid screenshot dimensions");
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < height; y += 9) for (int x = 0; x < width; x += 9) colors.add(image.getRGB(x, y));
        check(colors.size() > 32, "Screenshot is blank or missing rendered content");
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "Pass the packaged JAR path");
        jar = Path.of(args[0]).toAbsolutePath();
        check(Files.isRegularFile(jar), "JAR does not exist");
        output = Files.createTempDirectory("ecosystem-cli-smoke-");
        try {
            String help = run(true, "--help");
            check(help.contains("--self-test") && help.contains("--screenshot"), "Help omits supported commands");
            run(false, "--no-such-command");
            run(false, "--screenshot", "invalid.png", "-1");
            run(false, "--screenshot", "invalid.png", "word");
            run(false, "--screenshot", "invalid.png", "0", "Meadow", "Nearest food", "1", "1");
            run(false, "--screenshot", "invalid.png", "0", "UnknownBiome");
            run(false, "--demo", "extra.csv", "unexpected");
            System.out.println("PASS CLI help and rejection of unknown commands, bad numbers, dimensions, biomes and extra arguments");
            reports();
            comparison();
            screenshot("meadow.png", "Meadow", 1280, 820);
            screenshot("desert.png", "Desert", 1044, 661);
            System.out.println("PASS CLI renders Meadow and Desert without a display at two supported sizes");
            System.out.println("SUCCESS: CLI smoke passed from an unrelated working directory.");
        } finally {
            try (var files = Files.walk(output)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
