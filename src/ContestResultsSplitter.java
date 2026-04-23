import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
 * Reads the contest results CSV and writes a descriptive statistics report.
 * <p>
 * The report includes the average number of teams per institution, institutions ordered by
 * team count, institutions with Outstanding teams, and United States teams that earned a
 * Meritorious ranking or better. Institution records are deduplicated before analysis so
 * repeated teams from the same school are counted together.
 */
public final class ContestResultsSplitter {
    /**
     * Source columns required by the analysis.
     */
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

    /**
     * Starts the statistics report generator.
     *
     * @param args command-line arguments in the form {@code <input.csv> <report_output.txt>}
     */
    public static void main(String[] args) {
        try {
            new ContestResultsSplitter().run(args);
        } catch (RuntimeException exception) {
            System.err.println("The report could not be completed because of an unexpected problem.");
            System.err.println("Details: " + exception.getMessage());
            System.err.println("Please check the input data and file paths, then try again.");
        }
    }

    /**
     * Validates command-line arguments, loads the input data, and writes the report.
     *
     * @param args command-line arguments
     */
    private void run(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java ContestResultsSplitter <input.csv> <report_output.txt>");
            System.err.println("Please provide the source CSV path and the report output path.");
            return;
        }

        Path inputPath;
        Path outputPath;
        try {
            inputPath = Paths.get(args[0]);
            outputPath = Paths.get(args[1]);
        } catch (InvalidPathException exception) {
            System.err.println("One of the provided file paths is invalid: " + exception.getInput());
            System.err.println("Please check the input and output paths and try again.");
            return;
        }

        ContestData data = load(inputPath);
        if (data == null) {
            return;
        }
        if (!writeReport(outputPath, data)) {
            return;
        }

        System.out.println("Read " + data.rows().size() + " team rows from " + inputPath + ".");
        System.out.println("Wrote statistics report to " + outputPath + ".");
    }

    /**
     * Loads raw CSV rows and converts them into deduplicated institutions and linked teams.
     *
     * @param inputPath source CSV path
     * @return structured contest data for reporting, or {@code null} when the input cannot be used
     */
    private ContestData load(Path inputPath) {
        if (!Files.exists(inputPath)) {
            System.err.println("Input file not found: " + inputPath);
            System.err.println("Please confirm the CSV file exists and run the program again.");
            return null;
        }

        List<ContestRow> rows = readRows(inputPath);
        if (rows == null) {
            return null;
        }
        List<InstitutionRecord> institutions = new ArrayList<>();
        List<TeamRecord> teams = new ArrayList<>();

        // Walk every source row once, linking each team to a deduplicated institution record.
        for (ContestRow row : rows) {
            InstitutionRecord institution = findInstitution(institutions, row);
            if (institution == null) {
                institution = new InstitutionRecord(row);
                institutions.add(institution);
            } else {
                // Combine partial duplicate rows so missing location fields can be filled from later records.
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

    /**
     * Reads and validates the CSV file into row objects.
     *
     * @param inputPath source CSV path
     * @return contest rows in source order, or {@code null} when the CSV cannot be read or validated
     */
    private List<ContestRow> readRows(Path inputPath) {
        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                System.err.println("Input file is empty: " + inputPath);
                System.err.println("Please provide a CSV file with a header row and contest results.");
                return null;
            }

            List<String> header = parseCsvLine(headerLine);
            Map<String, Integer> headerIndex = new HashMap<>();
            // Build a column-name lookup so later row parsing can read fields by required header name.
            for (int index = 0; index < header.size(); index++) {
                // Some source files include either a real BOM or mojibake from a misread BOM.
                String column = header.get(index).replace("\uFEFF", "").replace("ï»¿", "").trim();
                headerIndex.put(column, index);
            }

            List<String> missing = new ArrayList<>();
            // Verify every column the report needs is available before any data rows are processed.
            for (String required : REQUIRED_COLUMNS) {
                if (!headerIndex.containsKey(required)) {
                    missing.add(required);
                }
            }
            if (!missing.isEmpty()) {
                System.err.println("Input file is missing required columns: " + String.join(", ", missing));
                System.err.println("Please update the CSV header and run the program again.");
                return null;
            }

            List<ContestRow> rows = new ArrayList<>();
            String line;
            // Read each remaining CSV record, skip blanks, and convert the values into a typed row.
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
            System.err.println("Unable to read input file " + inputPath + ": " + exception.getMessage());
            System.err.println("Please check that the file is accessible and not open exclusively by another program.");
            return null;
        }
    }

    /**
     * Finds the existing institution that best matches a source row.
     *
     * @param institutions institutions already discovered
     * @param row source row to match
     * @return the best compatible institution, or {@code null} if no match is strong enough
     */
    private InstitutionRecord findInstitution(List<InstitutionRecord> institutions, ContestRow row) {
        InstitutionRecord bestMatch = null;
        int bestScore = Integer.MIN_VALUE;

        // Compare the incoming row against each known institution and keep the highest-scoring match.
        for (InstitutionRecord institution : institutions) {
            int score = institution.matchScore(row);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = institution;
            }
        }
        return bestScore >= 100 ? bestMatch : null;
    }

    /**
     * Builds and writes the required part 2 report sections.
     *
     * @param outputPath report destination
     * @param data deduplicated contest data
     * @return {@code true} when the report is written successfully
     */
    private boolean writeReport(Path outputPath, ContestData data) {
        List<InstitutionRecord> institutions = new ArrayList<>(data.institutions());
        institutions.sort(
            Comparator.comparingInt(InstitutionRecord::teamCount).reversed()
                .thenComparing(InstitutionRecord::name, String.CASE_INSENSITIVE_ORDER)
        );

        TreeSet<String> outstandingInstitutions = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // Collect only institutions with at least one Outstanding team for the report section.
        for (InstitutionRecord institution : data.institutions()) {
            if (institution.outstanding()) {
                // TreeSet keeps the final Outstanding institution list alphabetized without duplicates.
                outstandingInstitutions.add(displayInstitution(institution));
            }
        }

        List<TeamRecord> usTopTeams = new ArrayList<>();
        // Filter the full team list down to United States teams ranked Meritorious or higher.
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
            // Write the already-sorted institution list with each institution's aggregated team count.
            for (InstitutionRecord institution : institutions) {
                writer.write(displayInstitution(institution) + " | " + institution.teamCount());
                writer.newLine();
            }
            writer.newLine();

            writer.write("Institutions with Outstanding teams");
            writer.newLine();
            // Write the alphabetized set of institutions that had at least one Outstanding result.
            for (String institution : outstandingInstitutions) {
                writer.write(institution);
                writer.newLine();
            }
            writer.newLine();

            writer.write("US teams with Meritorious ranking or better");
            writer.newLine();
            // Write each qualifying US team after sorting by institution name and team number.
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
            System.err.println("Unable to write output file " + outputPath + ": " + exception.getMessage());
            System.err.println("Please check that the output folder exists and is writable.");
            return false;
        }
        return true;
    }

    /**
     * Calculates the average number of teams per deduplicated institution.
     *
     * @param data contest data
     * @return average formatted to two decimal places
     */
    private String averageTeamsPerInstitution(ContestData data) {
        if (data.institutions().isEmpty()) {
            return "0.00";
        }
        return BigDecimal.valueOf(data.teams().size())
            .divide(BigDecimal.valueOf(data.institutions().size()), 2, RoundingMode.HALF_UP)
            .toPlainString();
    }

    /**
     * Formats institution name and available location fields for report output.
     *
     * @param institution institution to display
     * @return comma-separated institution description
     */
    private String displayInstitution(InstitutionRecord institution) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, institution.name());
        addIfPresent(parts, institution.city());
        addIfPresent(parts, institution.stateProvince());
        addIfPresent(parts, institution.country());
        return String.join(", ", parts);
    }

    /**
     * Adds a non-blank value to a display list.
     *
     * @param parts list being built
     * @param value value to add when present
     */
    private void addIfPresent(List<String> parts, String value) {
        if (!value.isBlank()) {
            parts.add(value);
        }
    }

    /**
     * Determines whether an institution should be treated as United States based on country text.
     *
     * @param institution institution to inspect
     * @return {@code true} for common United States spellings and abbreviations
     */
    private boolean isUsInstitution(InstitutionRecord institution) {
        String country = institution.country().toLowerCase(Locale.ROOT).replace(".", "").trim().replaceAll("\\s+", " ");
        return country.equals("united states")
            || country.equals("united states of america")
            || country.equals("usa")
            || country.equals("us")
            || country.equals("u s a")
            || country.equals("u s");
    }

    /**
     * Converts ranking text into an ordered numeric value.
     *
     * @param ranking contest ranking label
     * @return ordered value where higher means a stronger result
     */
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

    /**
     * Safely reads a CSV value by index.
     *
     * @param values parsed row values
     * @param index requested column index
     * @return trimmed value, or an empty string for missing trailing values
     */
    private String valueAt(List<String> values, int index) {
        return index < values.size() ? values.get(index).trim() : "";
    }

    /**
     * Parses one CSV record, including quoted fields and escaped quotes.
     *
     * @param line raw CSV record
     * @return fields in column order
     */
    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        // Scan the record character by character so commas inside quotes are not treated as separators.
        for (int index = 0; index < line.length(); index++) {
            char currentChar = line.charAt(index);
            if (currentChar == '"') {
                if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    // Two consecutive quotes inside a quoted field represent one literal quote.
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

    /**
     * Complete data model used by the report after CSV parsing and institution deduplication.
     *
     * @param rows original source rows
     * @param institutions deduplicated institutions
     * @param teams team rows linked to their institution records
     */
    private record ContestData(List<ContestRow> rows, List<InstitutionRecord> institutions, List<TeamRecord> teams) {
    }

    /**
     * One raw team row from the source CSV.
     *
     * @param institution institution name
     * @param teamNumber contest team number
     * @param city institution city
     * @param stateProvince institution state or province
     * @param country institution country
     * @param advisor faculty advisor
     * @param problem selected contest problem
     * @param ranking final ranking
     */
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

    /**
     * Team-level data linked to the deduplicated institution record.
     *
     * @param teamNumber contest team number
     * @param advisor faculty advisor
     * @param problem selected contest problem
     * @param ranking final ranking
     * @param institution deduplicated institution for the team
     */
    private record TeamRecord(
        String teamNumber,
        String advisor,
        String problem,
        String ranking,
        InstitutionRecord institution
    ) {
    }

    /**
     * Institution data used for identity matching and team-count aggregation.
     */
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

        /**
         * Returns the preferred institution name.
         *
         * @return institution name
         */
        private String name() {
            return name;
        }

        /**
         * Returns the merged city value.
         *
         * @return city, or an empty string when unknown
         */
        private String city() {
            return city;
        }

        /**
         * Returns the merged state/province value.
         *
         * @return state or province, or an empty string when unknown
         */
        private String stateProvince() {
            return stateProvince;
        }

        /**
         * Returns the merged country value.
         *
         * @return country, or an empty string when unknown
         */
        private String country() {
            return country;
        }

        /**
         * Returns the number of teams associated with this institution.
         *
         * @return team count
         */
        private int teamCount() {
            return teamCount;
        }

        /**
         * Indicates whether any team from this institution earned Outstanding.
         *
         * @return {@code true} when at least one team earned Outstanding
         */
        private boolean outstanding() {
            return outstanding;
        }

        /**
         * Adds a team to this institution's aggregate statistics.
         *
         * @param team team to count
         */
        private void addTeam(TeamRecord team) {
            teamCount++;
            if ("Outstanding".equalsIgnoreCase(team.ranking())) {
                outstanding = true;
            }
        }

        /**
         * Merges non-empty institution fields from another row.
         *
         * @param row source row for the same institution
         */
        private void merge(ContestRow row) {
            name = prefer(name, row.institution());
            city = prefer(city, row.city());
            stateProvince = prefer(stateProvince, row.stateProvince());
            country = prefer(country, row.country());
        }

        /**
         * Scores whether a row represents this institution.
         *
         * @param row source row to compare
         * @return match score, or {@link Integer#MIN_VALUE} when incompatible
         */
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

        /**
         * Compares institution names after removing case, punctuation, and spacing differences.
         *
         * @param left first name
         * @param right second name
         * @return {@code true} when normalized names are equal
         */
        private static boolean sameName(String left, String right) {
            return exact(normalizeField(left).replace(" ", ""), normalizeField(right).replace(" ", ""));
        }

        /**
         * Checks whether two location values can describe the same institution.
         *
         * @param left existing value
         * @param right incoming value
         * @return {@code true} when either side is blank or both normalized values match
         */
        private static boolean compatible(String left, String right) {
            String normalizedLeft = normalizeField(left);
            String normalizedRight = normalizeField(right);
            return normalizedLeft.isEmpty() || normalizedRight.isEmpty() || normalizedLeft.equals(normalizedRight);
        }

        /**
         * Checks whether two non-blank values match after normalization.
         *
         * @param left first value
         * @param right second value
         * @return {@code true} when both values are non-blank and normalized equal
         */
        private static boolean exact(String left, String right) {
            String normalizedLeft = normalizeField(left);
            String normalizedRight = normalizeField(right);
            return !normalizedLeft.isEmpty() && normalizedLeft.equals(normalizedRight);
        }

        /**
         * Chooses the better value when merging duplicate institution records.
         *
         * @param current current stored value
         * @param incoming value from another row
         * @return non-blank preferred value, favoring the longer value when both are present
         */
        private static String prefer(String current, String incoming) {
            String left = clean(current);
            String right = clean(incoming);
            return left.isEmpty() ? right : right.isEmpty() || left.length() >= right.length() ? left : right;
        }

        /**
         * Normalizes text for matching by lowercasing and replacing punctuation runs with spaces.
         *
         * @param value raw value
         * @return normalized value
         */
        private static String normalizeField(String value) {
            String cleaned = clean(value).toLowerCase(Locale.ROOT);
            StringBuilder normalized = new StringBuilder(cleaned.length());
            boolean lastWasSpace = true;
            // Collapse runs of punctuation or whitespace into single spaces while preserving letters and digits.
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

        /**
         * Trims a nullable value.
         *
         * @param value raw value
         * @return trimmed value, or an empty string for {@code null}
         */
        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

}
