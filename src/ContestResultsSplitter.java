import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Reads contest results and writes the part 2 statistics report.
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

    public static void main(String[] args) {
        try {
            new ContestResultsSplitter().run(args);
        } catch (ContestDataException exception) {
            System.err.println(exception.getMessage());
        }
    }

    private void run(String[] args) throws ContestDataException {
        if (args.length != 2) {
            throw new ContestDataException(
                "Usage: java ContestResultsSplitter <input.csv> <report_output.txt>"
            );
        }

        Path inputPath = Paths.get(args[0]);
        Path outputPath = Paths.get(args[1]);
        ContestData data = load(inputPath);
        writeReport(outputPath, data);

        System.out.println("Read " + data.rows().size() + " team rows from " + inputPath + ".");
        System.out.println("Wrote statistics report to " + outputPath + ".");
    }

    private ContestData load(Path inputPath) throws ContestDataException {
        if (!Files.exists(inputPath)) {
            throw new ContestDataException("Input file not found: " + inputPath);
        }

        List<ContestRow> rows = readRows(inputPath);
        List<InstitutionRecord> institutions = new ArrayList<>();
        List<TeamRecord> teams = new ArrayList<>();

        for (ContestRow row : rows) {
            InstitutionRecord institution = findInstitution(institutions, row);
            if (institution == null) {
                institution = new InstitutionRecord(row);
                institutions.add(institution);
            } else {
                institution.merge(row);
            }

            TeamRecord team = new TeamRecord(
                row.teamNumber(),
                row.advisor(),
                row.problem(),
                row.ranking(),
                institution
            );
            teams.add(team);
            institution.addTeam(team);
        }

        return new ContestData(rows, institutions, teams);
    }

    private List<ContestRow> readRows(Path inputPath) throws ContestDataException {
        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new ContestDataException("Input file is empty: " + inputPath);
            }

            List<String> header = parseCsvLine(headerLine);
            Map<String, Integer> headerIndex = new HashMap<>();
            for (int index = 0; index < header.size(); index++) {
                String column = header.get(index).replace("\uFEFF", "").replace("ï»¿", "").trim();
                headerIndex.put(column, index);
            }

            List<String> missing = new ArrayList<>();
            for (String required : REQUIRED_COLUMNS) {
                if (!headerIndex.containsKey(required)) {
                    missing.add(required);
                }
            }
            if (!missing.isEmpty()) {
                throw new ContestDataException(
                    "Input file is missing required columns: " + String.join(", ", missing)
                );
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
            throw new ContestDataException("Unable to read input file " + inputPath + ": " + exception.getMessage());
        }
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

    private void writeReport(Path outputPath, ContestData data) throws ContestDataException {
        List<InstitutionRecord> institutions = new ArrayList<>(data.institutions());
        institutions.sort(
            Comparator.comparingInt(InstitutionRecord::teamCount).reversed()
                .thenComparing(InstitutionRecord::name, String.CASE_INSENSITIVE_ORDER)
        );

        TreeSet<String> outstandingInstitutions = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (InstitutionRecord institution : data.institutions()) {
            if (institution.outstanding()) {
                outstandingInstitutions.add(displayInstitution(institution));
            }
        }

        List<TeamRecord> usTopTeams = new ArrayList<>();
        for (TeamRecord team : data.teams()) {
            if (isUsInstitution(team.institution()) && rankingValue(team.ranking()) >= rankingValue("Meritorious")) {
                usTopTeams.add(team);
            }
        }
        usTopTeams.sort(
            Comparator.comparing((TeamRecord team) -> team.institution().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TeamRecord::teamNumber)
        );

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("Average teams entered per institution: " + averageTeamsPerInstitution(data));
            writer.newLine();
            writer.newLine();

            writer.write("Institutions ordered by number of teams");
            writer.newLine();
            for (InstitutionRecord institution : institutions) {
                writer.write(displayInstitution(institution) + " | " + institution.teamCount());
                writer.newLine();
            }
            writer.newLine();

            writer.write("Institutions with Outstanding teams");
            writer.newLine();
            for (String institution : outstandingInstitutions) {
                writer.write(institution);
                writer.newLine();
            }
            writer.newLine();

            writer.write("US teams with Meritorious ranking or better");
            writer.newLine();
            for (TeamRecord team : usTopTeams) {
                writer.write(
                    "Team " + team.teamNumber()
                        + " | " + displayInstitution(team.institution())
                        + " | " + team.ranking()
                        + " | Advisor: " + team.advisor()
                        + " | Problem: " + team.problem()
                );
                writer.newLine();
            }
        } catch (IOException exception) {
            throw new ContestDataException("Unable to write output file " + outputPath + ": " + exception.getMessage());
        }
    }

    private String averageTeamsPerInstitution(ContestData data) {
        if (data.institutions().isEmpty()) {
            return "0.00";
        }
        return BigDecimal.valueOf(data.teams().size())
            .divide(BigDecimal.valueOf(data.institutions().size()), 2, RoundingMode.HALF_UP)
            .toPlainString();
    }

    private String displayInstitution(InstitutionRecord institution) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, institution.name());
        addIfPresent(parts, institution.city());
        addIfPresent(parts, institution.stateProvince());
        addIfPresent(parts, institution.country());
        return String.join(", ", parts);
    }

    private void addIfPresent(List<String> parts, String value) {
        if (!value.isBlank()) {
            parts.add(value);
        }
    }

    private boolean isUsInstitution(InstitutionRecord institution) {
        String country = institution.country().toLowerCase(Locale.ROOT).replace(".", "").trim().replaceAll("\\s+", " ");
        return country.equals("united states")
            || country.equals("united states of america")
            || country.equals("usa")
            || country.equals("us")
            || country.equals("u s a")
            || country.equals("u s");
    }

    private int rankingValue(String ranking) {
        return switch (ranking.trim().toLowerCase(Locale.ROOT)) {
            case "unsuccessful" -> 0;
            case "successful participant" -> 1;
            case "honorable mention" -> 2;
            case "meritorious" -> 3;
            case "finalist" -> 4;
            case "outstanding" -> 5;
            default -> -1;
        };
    }

    private String valueAt(List<String> values, int index) {
        return index < values.size() ? values.get(index).trim() : "";
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

    private record ContestData(List<ContestRow> rows, List<InstitutionRecord> institutions, List<TeamRecord> teams) {
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

    private record TeamRecord(
        String teamNumber,
        String advisor,
        String problem,
        String ranking,
        InstitutionRecord institution
    ) {
    }

    private static final class InstitutionRecord {
        private String name;
        private String city;
        private String stateProvince;
        private String country;
        private int teamCount;
        private boolean outstanding;

        private InstitutionRecord(ContestRow row) {
            this.name = clean(row.institution());
            this.city = clean(row.city());
            this.stateProvince = clean(row.stateProvince());
            this.country = clean(row.country());
        }

        private String name() {
            return name;
        }

        private String city() {
            return city;
        }

        private String stateProvince() {
            return stateProvince;
        }

        private String country() {
            return country;
        }

        private int teamCount() {
            return teamCount;
        }

        private boolean outstanding() {
            return outstanding;
        }

        private void addTeam(TeamRecord team) {
            teamCount++;
            if ("Outstanding".equalsIgnoreCase(team.ranking())) {
                outstanding = true;
            }
        }

        private void merge(ContestRow row) {
            name = prefer(name, row.institution());
            city = prefer(city, row.city());
            stateProvince = prefer(stateProvince, row.stateProvince());
            country = prefer(country, row.country());
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

    private static final class ContestDataException extends Exception {
        private ContestDataException(String message) {
            super(message);
        }
    }
}
