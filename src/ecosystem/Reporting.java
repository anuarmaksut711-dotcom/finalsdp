package ecosystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/** Visitor computes statistics. Bridge lets report content and format vary independently. */
public final class Reporting {
    private Reporting() {
    }

    public interface OrganismVisitor {
        void visitPlant(Model.Plant plant);
        void visitHerbivore(Model.Herbivore herbivore);
        void visitPredator(Model.Predator predator);
    }

    public static final class PopulationVisitor implements OrganismVisitor {
        public int plants;
        public int herbivores;
        public int predators;

        @Override
        public void visitPlant(Model.Plant plant) {
            plants++;
        }

        @Override
        public void visitHerbivore(Model.Herbivore herbivore) {
            herbivores++;
        }

        @Override
        public void visitPredator(Model.Predator predator) {
            predators++;
        }
    }

    public static final class BiomassVisitor implements OrganismVisitor {
        public int plantBiomass;
        public int animalBodyUnits;

        @Override
        public void visitPlant(Model.Plant plant) {
            plantBiomass += plant.biomass;
        }

        @Override
        public void visitHerbivore(Model.Herbivore herbivore) {
            animalBodyUnits++;
        }

        @Override
        public void visitPredator(Model.Predator predator) {
            animalBodyUnits += 3;
        }
    }

    public static Model.Stats collect(Model.World world) {
        PopulationVisitor population = new PopulationVisitor();
        BiomassVisitor biomass = new BiomassVisitor();
        world.visit(population);
        world.visit(biomass);

        return new Model.Stats(
                population.plants, population.herbivores, population.predators,
                biomass.plantBiomass, biomass.animalBodyUnits);
    }

    public record ReportDocument(String title, List<String> headers, List<List<String>> rows) {
        public ReportDocument {
            headers = List.copyOf(headers);
            List<List<String>> rowCopies = new ArrayList<>();
            for (List<String> row : rows) {
                rowCopies.add(List.copyOf(row));
            }
            rows = List.copyOf(rowCopies);
        }
    }

    public interface ReportRenderer {
        String render(ReportDocument document);
        String extension();
    }

    public static final class PlainTextRenderer implements ReportRenderer {
        @Override
        public String extension() {
            return "txt";
        }

        @Override
        public String render(ReportDocument document) {
            StringBuilder text = new StringBuilder();
            text.append(document.title()).append('\n');
            text.append(String.join(" | ", document.headers())).append('\n');
            for (List<String> row : document.rows()) {
                text.append(String.join(" | ", row)).append('\n');
            }
            return text.toString();
        }
    }

    public static final class CsvRenderer implements ReportRenderer {
        @Override
        public String extension() {
            return "csv";
        }

        @Override
        public String render(ReportDocument document) {
            StringBuilder csv = new StringBuilder();
            csv.append(csvRow(document.headers())).append("\r\n");
            for (List<String> row : document.rows()) {
                csv.append(csvRow(row)).append("\r\n");
            }
            return csv.toString();
        }

        private String csvRow(List<String> row) {
            StringJoiner columns = new StringJoiner(",");
            for (String value : row) {
                columns.add(quote(value));
            }
            return columns.toString();
        }

        private String quote(String value) {
            String escaped = value.replace("\"", "\"\"");
            return "\"" + escaped + "\"";
        }
    }

    public abstract static class EcosystemReport {
        protected final ReportRenderer renderer;

        protected EcosystemReport(ReportRenderer renderer) {
            this.renderer = Objects.requireNonNull(renderer);
        }

        protected abstract ReportDocument document(Model.Snapshot snapshot);

        public final String generate(Model.Snapshot snapshot) {
            return renderer.render(document(snapshot));
        }
    }

    public static final class SummaryReport extends EcosystemReport {
        public SummaryReport(ReportRenderer renderer) {
            super(renderer);
        }

        @Override
        protected ReportDocument document(Model.Snapshot snapshot) {
            Model.Stats stats = snapshot.stats();
            String temperature = String.format(Locale.ROOT, "%.1f", snapshot.weather().temperatureC());
            String rainfall = String.format(Locale.ROOT, "%.1f", snapshot.weather().rainfallMm());
            List<List<String>> rows = List.of(
                    List.of("tick", String.valueOf(snapshot.tick())),
                    List.of("biome", snapshot.biome()),
                    List.of("strategy", snapshot.strategy()),
                    List.of("temperature_C", temperature),
                    List.of("rainfall_mm", rainfall),
                    List.of("herbivores", String.valueOf(stats.herbivores())),
                    List.of("predators", String.valueOf(stats.predators())),
                    List.of("plant_biomass_units", String.valueOf(stats.plantBiomass())),
                    List.of("animal_body_units", String.valueOf(stats.animalBodyUnits())));
            return new ReportDocument("Ecosystem summary", List.of("metric", "value"), rows);
        }
    }

    public static final class DetailedReport extends EcosystemReport {
        public DetailedReport(ReportRenderer renderer) {
            super(renderer);
        }

        @Override
        protected ReportDocument document(Model.Snapshot snapshot) {
            List<String> headers = List.of("kind", "id", "x", "y", "biomass_or_energy", "capacity", "age");
            List<List<String>> rows = new ArrayList<>();

            for (Model.PlantView plant : snapshot.plants()) {
                rows.add(List.of(
                        "plant", "",
                        String.valueOf(plant.x()), String.valueOf(plant.y()),
                        String.valueOf(plant.biomass()), String.valueOf(plant.capacity()), ""));
            }
            for (Model.AnimalView animal : snapshot.animals()) {
                rows.add(List.of(
                        animal.kind(), String.valueOf(animal.id()),
                        String.valueOf(animal.x()), String.valueOf(animal.y()),
                        String.valueOf(animal.energy()), "", String.valueOf(animal.age())));
            }

            return new ReportDocument("Ecosystem detail", headers, rows);
        }
    }
}
