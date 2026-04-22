import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splits contest results into institution and team CSV files.
 */
public final class ContestResultsSplitter {
    private static final List<String> REQUIRED_COLUMNS = List.of(
        "Institution",
        "Team Number",
        "City",
        "State/Province",
        "Country",
        "Advisor",
        "Problem",
        "Ranking"
    );
    private static final List<String> INSTITUTION_HEADER =
        List.of("Institution ID", "Institution Name", "City", "State/Province", "Country");
    private static final List<String> TEAM_HEADER =
        List.of("Team Number", "Advisor", "Problem", "Ranking", "Institution ID");

    public static void main(String[] args) {
        try {
            new ContestResultsSplitter().run(args);
        } catch (CsvException exception) {
            System.err.println(exception.getMessage());
        }
    }

    private void run(String[] args) throws CsvException {
        if (args.length != 3) {
            throw new CsvException(
                "Usage: java ContestResultsSplitter <input.csv> <institutions_output.csv> <teams_output.csv>"
            );
        }

        Path inputPath = Paths.get(args[0]);
        Path institutionsOutput = Paths.get(args[1]);
        Path teamsOutput = Paths.get(args[2]);

        List<ContestRow> rows = readRows(inputPath);
        OutputData output = buildOutputs(rows);
        writeCsv(institutionsOutput, INSTITUTION_HEADER, output.institutionRows());
        writeCsv(teamsOutput, TEAM_HEADER, output.teamRows());

        System.out.println("Read " + rows.size() + " team rows from " + inputPath + ".");
        System.out.println("Wrote " + output.institutionRows().size() + " institutions to " + institutionsOutput + ".");
        System.out.println("Wrote " + output.teamRows().size() + " teams to " + teamsOutput + ".");
    }

    private List<ContestRow> readRows(Path inputPath) throws CsvException {
        if (!Files.exists(inputPath)) {
            throw new CsvException("Input file not found: " + inputPath);
        }

        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new CsvException("Input file is empty: " + inputPath);
            }

            List<String> header = parseCsvLine(headerLine);
            Map<String, Integer> headerIndex = new HashMap<>();
            for (int index = 0; index < header.size(); index++) {
                String column = header.get(index).replace("\uFEFF", "").replace("ï»¿", "").trim();
                header.set(index, column);
                headerIndex.put(column, index);
            }
            List<String> missing = new ArrayList<>();
            for (String required : REQUIRED_COLUMNS) {
                if (!headerIndex.containsKey(required)) {
                    missing.add(required);
                }
            }
            if (!missing.isEmpty()) {
                throw new CsvException("Input file is missing required columns: " + String.join(", ", missing));
            }

            List<ContestRow> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                List<String> values = parseCsvLine(line);
                rows.add(new ContestRow(
                    valueAt(values, headerIndex.get("Institution")),
                    valueAt(values, headerIndex.get("Team Number")),
                    valueAt(values, headerIndex.get("City")),
                    valueAt(values, headerIndex.get("State/Province")),
                    valueAt(values, headerIndex.get("Country")),
                    valueAt(values, headerIndex.get("Advisor")),
                    valueAt(values, headerIndex.get("Problem")),
                    valueAt(values, headerIndex.get("Ranking"))
                ));
            }
            return rows;
        } catch (IOException exception) {
            throw new CsvException("Unable to read input file " + inputPath + ": " + exception.getMessage());
        }
    }

    private String valueAt(List<String> values, int index) {
        return index < values.size() ? values.get(index).trim() : "";
    }

    private OutputData buildOutputs(List<ContestRow> rows) {
        List<InstitutionRecord> institutions = new ArrayList<>();
        List<Map<String, String>> teamRows = new ArrayList<>();

        for (ContestRow row : rows) {
            InstitutionRecord institution = findInstitution(institutions, row);
            if (institution == null) {
                institution = new InstitutionRecord("INST%04d".formatted(institutions.size() + 1), row);
                institutions.add(institution);
            } else {
                institution.merge(row);
            }

            Map<String, String> team = new LinkedHashMap<>();
            team.put("Team Number", row.teamNumber());
            team.put("Advisor", row.advisor());
            team.put("Problem", row.problem());
            team.put("Ranking", row.ranking());
            team.put("Institution ID", institution.id());
            teamRows.add(team);
        }

        List<Map<String, String>> institutionRows = new ArrayList<>();
        for (InstitutionRecord institution : institutions) {
            institutionRows.add(institution.toRow());
        }
        return new OutputData(institutionRows, teamRows);
    }

    private InstitutionRecord findInstitution(List<InstitutionRecord> institutions, ContestRow row) {
        InstitutionRecord bestMatch = null;
        int bestScore = Integer.MIN_VALUE;

        for (InstitutionRecord institution : institutions) {
            int score = institution.matchScore(row);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = institution;
            }
        }

        return bestScore >= 100 ? bestMatch : null;
    }

    private void writeCsv(Path outputPath, List<String> header, List<Map<String, String>> rows) throws CsvException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", header));
            writer.newLine();

            for (Map<String, String> row : rows) {
                List<String> values = new ArrayList<>(header.size());
                for (String column : header) {
                    values.add(escapeCsv(row.getOrDefault(column, "")));
                }
                writer.write(String.join(",", values));
                writer.newLine();
            }
        } catch (IOException exception) {
            throw new CsvException("Unable to write output file " + outputPath + ": " + exception.getMessage());
        }
    }

    private String escapeCsv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")
            ? "\"" + escaped + "\""
            : escaped;
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int index = 0; index < line.length(); index++) {
            char currentChar = line.charAt(index);
            if (currentChar == '"') {
                if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (currentChar == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(currentChar);
            }
        }

        values.add(current.toString());
        return values;
    }

    private record ContestRow(
        String institution,
        String teamNumber,
        String city,
        String stateProvince,
        String country,
        String advisor,
        String problem,
        String ranking
    ) {
    }

    private record OutputData(List<Map<String, String>> institutionRows, List<Map<String, String>> teamRows) {
    }

    private static final class InstitutionRecord {
        private final String id;
        private String name;
        private String city;
        private String stateProvince;
        private String country;

        private InstitutionRecord(String id, ContestRow row) {
            this.id = id;
            this.name = clean(row.institution());
            this.city = clean(row.city());
            this.stateProvince = clean(row.stateProvince());
            this.country = clean(row.country());
        }

        private String id() {
            return id;
        }

        private int matchScore(ContestRow row) {
            String rowCity = row.city();
            String rowState = row.stateProvince();
            String rowCountry = row.country();
            if (!sameName(name, row.institution())
                || !compatible(city, rowCity)
                || !compatible(stateProvince, rowState)
                || !compatible(country, rowCountry)) {
                return Integer.MIN_VALUE;
            }
            return 100 + (exact(city, rowCity) ? 15 : 0)
                + (exact(stateProvince, rowState) ? 15 : 0)
                + (exact(country, rowCountry) ? 20 : 0);
        }

        private void merge(ContestRow row) {
            name = prefer(name, row.institution());
            city = prefer(city, row.city());
            stateProvince = prefer(stateProvince, row.stateProvince());
            country = prefer(country, row.country());
        }

        private Map<String, String> toRow() {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Institution ID", id);
            row.put("Institution Name", name);
            row.put("City", city);
            row.put("State/Province", stateProvince);
            row.put("Country", country);
            return row;
        }

        private static boolean sameName(String left, String right) {
            return exact(normalizeField(left).replace(" ", ""), normalizeField(right).replace(" ", ""));
        }

        private static boolean compatible(String left, String right) {
            String normalizedLeft = normalizeField(left);
            String normalizedRight = normalizeField(right);
            return normalizedLeft.isEmpty() || normalizedRight.isEmpty() || normalizedLeft.equals(normalizedRight);
        }

        private static boolean exact(String left, String right) {
            String normalizedLeft = normalizeField(left);
            String normalizedRight = normalizeField(right);
            return !normalizedLeft.isEmpty() && normalizedLeft.equals(normalizedRight);
        }

        private static String prefer(String current, String incoming) {
            String left = clean(current);
            String right = clean(incoming);
            return left.isEmpty() ? right : right.isEmpty() || left.length() >= right.length() ? left : right;
        }

        private static String normalizeField(String value) {
            String cleaned = clean(value).toLowerCase(Locale.ROOT);
            StringBuilder normalized = new StringBuilder(cleaned.length());
            boolean lastWasSpace = true;
            for (int index = 0; index < cleaned.length(); index++) {
                char current = cleaned.charAt(index);
                if (Character.isLetterOrDigit(current)) {
                    normalized.append(current);
                    lastWasSpace = false;
                } else if (!lastWasSpace) {
                    normalized.append(' ');
                    lastWasSpace = true;
                }
            }
            return normalized.toString().trim();
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private static final class CsvException extends Exception {
        private CsvException(String message) {
            super(message);
        }
    }
}
