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
import java.util.Map;

/**
 * Splits a contest results CSV into institution and team output files.
 * <p>
 * The program expects a source file containing a fixed set of columns used by the
 * contest exports. It generates one output that deduplicates institutions and another
 * that preserves the team-level rows while linking each team back to its institution
 * through a generated identifier.
 */
public class ContestResultsSplitter {
    /**
     * Source columns required to build the two output files.
     */
    private static final String[] REQUIRED_COLUMNS = {
            "Institution",
            "Team Number",
            "City",
            "State/Province",
            "Country",
            "Advisor",
            "Problem",
            "Ranking"
    };

    /**
     * Runs the CSV split operation.
     *
     * @param args optional command-line arguments in the form
     *             {@code [input.csv] [institutions_output.csv] [teams_output.csv]}
     */
    public static void main(String[] args) {
        if (args.length > 3) {
            printUsage();
            return;
        }

        // Default to the repository's sample input and output filenames.
        Path inputPath = Paths.get("2015.csv");
        Path institutionsOutput = Paths.get("Institutions.csv");
        Path teamsOutput = Paths.get("Teams.csv");

        // Later arguments override the defaults in order.
        if (args.length >= 1) {
            inputPath = Paths.get(args[0]);
        }
        if (args.length >= 2) {
            institutionsOutput = Paths.get(args[1]);
        }
        if (args.length == 3) {
            teamsOutput = Paths.get(args[2]);
        }

        if (!Files.exists(inputPath)) {
            System.err.println("Input file not found: " + inputPath);
            return;
        }

        // Stop immediately on read or validation errors so no partial output is written.
        ReadResult readResult = readRows(inputPath);
        if (!readResult.successful()) {
            System.err.println(readResult.message());
            return;
        }

        List<Map<String, String>> rows = readResult.rows();
        // Transform the flat source rows into the two output tables expected by the project.
        OutputData outputData = buildOutputs(rows);

        WriteResult institutionsWriteResult = writeCsv(
                institutionsOutput,
                List.of("Institution ID", "Institution Name", "City", "State/Province", "Country"),
                outputData.institutions
        );
        if (!institutionsWriteResult.successful()) {
            System.err.println(institutionsWriteResult.message());
            return;
        }

        WriteResult teamsWriteResult = writeCsv(
                teamsOutput,
                List.of("Team Number", "Advisor", "Problem", "Ranking", "Institution ID"),
                outputData.teams
        );
        if (!teamsWriteResult.successful()) {
            System.err.println(teamsWriteResult.message());
            return;
        }

        System.out.println("Read " + rows.size() + " team rows from " + inputPath + ".");
        System.out.println("Wrote " + outputData.institutions.size() + " institutions to " + institutionsOutput + ".");
        System.out.println("Wrote " + outputData.teams.size() + " teams to " + teamsOutput + ".");
    }

    /**
     * Prints the supported command-line syntax.
     */
    private static void printUsage() {
        System.err.println(
                "Usage: java -cp src ContestResultsSplitter [input.csv] [institutions_output.csv] [teams_output.csv]"
        );
    }

    /**
     * Reads the source CSV and converts each data row into a map keyed by normalized header name.
     *
     * @param inputPath path to the source CSV
     * @return a success result containing parsed rows, or a failure result with an error message
     */
    private static ReadResult readRows(Path inputPath) {
        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return ReadResult.failure("Input file is empty: " + inputPath);
            }

            List<String> header = parseCsvLine(headerLine);
            for (int i = 0; i < header.size(); i++) {
                // Some source files include either a real BOM or mojibake from a misread BOM.
                header.set(i, normalizeHeader(header.get(i)));
            }

            List<String> missingColumns = findMissingColumns(header);
            if (!missingColumns.isEmpty()) {
                return ReadResult.failure(
                        "Input file is missing required columns: " + String.join(", ", missingColumns)
                );
            }

            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                // Ignore blank records so accidental spacing in the source file does not create empty teams.
                if (line.isBlank()) {
                    continue;
                }

                List<String> values = parseCsvLine(line);
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    // Missing trailing values are treated as empty strings to keep every required key present.
                    String value = i < values.size() ? values.get(i).trim() : "";
                    row.put(header.get(i), value);
                }
                rows.add(row);
            }
            return ReadResult.success(rows);
        } catch (IOException exception) {
            return ReadResult.failure("Unable to read input file " + inputPath + ": " + exception.getMessage());
        }
    }

    /**
     * Compares the input header against the required source columns.
     *
     * @param header normalized input header values
     * @return the subset of required columns that are missing
     */
    private static List<String> findMissingColumns(List<String> header) {
        List<String> missing = new ArrayList<>();
        for (String requiredColumn : REQUIRED_COLUMNS) {
            if (!header.contains(requiredColumn)) {
                missing.add(requiredColumn);
            }
        }
        return missing;
    }

    /**
     * Normalizes a header value by removing BOM artifacts and surrounding whitespace.
     *
     * @param value raw header value from the CSV
     * @return cleaned header value
     */
    private static String normalizeHeader(String value) {
        return value.replace("\uFEFF", "").replace("ï»¿", "").trim();
    }

    /**
     * Builds the institution and team output datasets from the parsed source rows.
     *
     * @param rows parsed source rows
     * @return output collections ready to be written as CSV files
     */
    private static OutputData buildOutputs(List<Map<String, String>> rows) {
        LinkedHashMap<InstitutionKey, String> institutionIds = new LinkedHashMap<>();
        List<Map<String, String>> institutions = new ArrayList<>();
        List<Map<String, String>> teams = new ArrayList<>();

        for (Map<String, String> row : rows) {
            // Institutions are considered unique by name and location, not by team number or advisor.
            InstitutionKey institutionKey = new InstitutionKey(
                    row.get("Institution"),
                    row.get("City"),
                    row.get("State/Province"),
                    row.get("Country")
            );

            // LinkedHashMap preserves first-seen order so generated IDs remain stable for a given input order.
            String institutionId = institutionIds.get(institutionKey);
            if (institutionId == null) {
                institutionId = String.format("INST%04d", institutionIds.size() + 1);
                institutionIds.put(institutionKey, institutionId);

                // Write one institution row the first time that institution appears in the source data.
                Map<String, String> institution = new LinkedHashMap<>();
                institution.put("Institution ID", institutionId);
                institution.put("Institution Name", institutionKey.institutionName);
                institution.put("City", institutionKey.city);
                institution.put("State/Province", institutionKey.stateProvince);
                institution.put("Country", institutionKey.country);
                institutions.add(institution);
            }

            // Every source row becomes a team row, linked back to its generated institution ID.
            Map<String, String> team = new LinkedHashMap<>();
            team.put("Team Number", row.get("Team Number"));
            team.put("Advisor", row.get("Advisor"));
            team.put("Problem", row.get("Problem"));
            team.put("Ranking", row.get("Ranking"));
            team.put("Institution ID", institutionId);
            teams.add(team);
        }

        return new OutputData(institutions, teams);
    }

    /**
     * Writes a CSV file using the provided column order.
     *
     * @param outputPath destination file
     * @param header ordered output header
     * @param rows row values keyed by header name
     * @return a success result when the file is written, otherwise a failure result with an error message
     */
    private static WriteResult writeCsv(Path outputPath, List<String> header, List<Map<String, String>> rows) {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", header));
            writer.newLine();

            for (Map<String, String> row : rows) {
                List<String> values = new ArrayList<>();
                for (String column : header) {
                    // Serialize columns in the exact header order so the output schema stays predictable.
                    values.add(escapeCsv(row.getOrDefault(column, "")));
                }
                writer.write(String.join(",", values));
                writer.newLine();
            }
            return WriteResult.success();
        } catch (IOException exception) {
            return WriteResult.failure("Unable to write output file " + outputPath + ": " + exception.getMessage());
        }
    }

    /**
     * Escapes a single value for CSV output according to standard double-quote rules.
     *
     * @param value raw cell value
     * @return escaped cell value
     */
    private static String escapeCsv(String value) {
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    /**
     * Parses a single CSV record line, supporting quoted values and escaped quotes.
     *
     * @param line raw CSV line
     * @return values in column order
     */
    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char currentChar = line.charAt(i);

            if (currentChar == '"') {
                // Two consecutive quotes inside a quoted field represent one literal quote.
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    // A non-escaped quote toggles whether commas should be treated as data or separators.
                    inQuotes = !inQuotes;
                }
            } else if (currentChar == ',' && !inQuotes) {
                // A comma only ends the current value when it appears outside a quoted field.
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(currentChar);
            }
        }

        // Add the last field after the loop because CSV records do not end with a separator.
        values.add(current.toString());
        return values;
    }

    /**
     * Holds the two output datasets produced from the source file.
     */
    private static final class OutputData {
        private final List<Map<String, String>> institutions;
        private final List<Map<String, String>> teams;

        /**
         * Creates an immutable container for the output rows.
         *
         * @param institutions institution rows for {@code Institutions.csv}
         * @param teams team rows for {@code Teams.csv}
         */
        private OutputData(List<Map<String, String>> institutions, List<Map<String, String>> teams) {
            this.institutions = institutions;
            this.teams = teams;
        }
    }

    /**
     * Represents the outcome of reading and validating the input CSV.
     */
    private static final class ReadResult {
        private final List<Map<String, String>> rows;
        private final String message;

        /**
         * Creates a read result.
         *
         * @param rows parsed rows when successful
         * @param message failure message when unsuccessful
         */
        private ReadResult(List<Map<String, String>> rows, String message) {
            this.rows = rows;
            this.message = message;
        }

        /**
         * Creates a successful read result.
         *
         * @param rows parsed rows from the source CSV
         * @return success result containing rows
         */
        private static ReadResult success(List<Map<String, String>> rows) {
            return new ReadResult(rows, null);
        }

        /**
         * Creates a failed read result.
         *
         * @param message explanation of the read failure
         * @return failure result
         */
        private static ReadResult failure(String message) {
            return new ReadResult(null, message);
        }

        /**
         * Indicates whether the read completed successfully.
         *
         * @return {@code true} when no error message is present
         */
        private boolean successful() {
            return message == null;
        }

        /**
         * Returns the parsed rows.
         *
         * @return parsed rows, or {@code null} on failure
         */
        private List<Map<String, String>> rows() {
            return rows;
        }

        /**
         * Returns the failure message.
         *
         * @return failure message, or {@code null} on success
         */
        private String message() {
            return message;
        }
    }

    /**
     * Represents the outcome of writing an output CSV file.
     */
    private static final class WriteResult {
        private final String message;

        /**
         * Creates a write result.
         *
         * @param message failure message when unsuccessful
         */
        private WriteResult(String message) {
            this.message = message;
        }

        /**
         * Creates a successful write result.
         *
         * @return success result
         */
        private static WriteResult success() {
            return new WriteResult(null);
        }

        /**
         * Creates a failed write result.
         *
         * @param message explanation of the write failure
         * @return failure result
         */
        private static WriteResult failure(String message) {
            return new WriteResult(message);
        }

        /**
         * Indicates whether the write completed successfully.
         *
         * @return {@code true} when no error message is present
         */
        private boolean successful() {
            return message == null;
        }

        /**
         * Returns the failure message.
         *
         * @return failure message, or {@code null} on success
         */
        private String message() {
            return message;
        }
    }

    /**
     * Composite key used to identify unique institutions across team rows.
     */
    private static final class InstitutionKey {
        private final String institutionName;
        private final String city;
        private final String stateProvince;
        private final String country;

        /**
         * Creates the institution identity key.
         *
         * @param institutionName institution name
         * @param city institution city
         * @param stateProvince institution state or province
         * @param country institution country
         */
        private InstitutionKey(String institutionName, String city, String stateProvince, String country) {
            this.institutionName = institutionName;
            this.city = city;
            this.stateProvince = stateProvince;
            this.country = country;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof InstitutionKey)) {
                return false;
            }
            InstitutionKey that = (InstitutionKey) other;
            return institutionName.equals(that.institutionName)
                    && city.equals(that.city)
                    && stateProvince.equals(that.stateProvince)
                    && country.equals(that.country);
        }

        @Override
        public int hashCode() {
            int result = institutionName.hashCode();
            result = 31 * result + city.hashCode();
            result = 31 * result + stateProvince.hashCode();
            result = 31 * result + country.hashCode();
            return result;
        }
    }
}