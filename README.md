# International Math Contest Statistics Report

---

This project reads an International Math Contest results CSV file and creates a plain-text statistics report. It is written in Java and uses Apache ANT to compile and run the program.

---

## What The Program Does

The program loads contest team results from a CSV file, groups teams by institution, and writes a report with these sections:

- Average number of teams entered per institution.
- Institutions ordered by number of teams.
- Institutions that had at least one team ranked `Outstanding`.
- United States teams with a `Meritorious` ranking or better.

The program also tries to merge duplicate institution rows. For example, if the same institution appears in multiple rows with slightly different or missing location information, the program keeps one institution record and fills in the best available name, city, state/province, and country values.

---

## Project Files

- `src/ContestResultsSplitter.java` contains the program logic.
- `build.xml` contains the Ant build and run targets.
- `2015.csv` is the default input file used by the Ant run target.
- `StatisticsReport.txt` is the default output report file.

---

## Requirements

Install these before running the project:

- [Java JDK 21.0.7](https://www.oracle.com/java/technologies/downloads/#java21)
- [Apache Ant 1.10.15](https://ant.apache.org/)
- A terminal, command prompt, or IDE capable of compiling and running Java code or Ant

To confirm both Java and ANT are downloaded correctly, run the following commands:

```powershell
java -version
ant -version
```

If those commands are not recognized, make sure Java and Ant are installed and that their `bin` folders are added to your system `PATH`.

---

## Input CSV Requirements

The input CSV must include these column headers:

- `Institution`
- `Team Number`
- `City`
- `State/Province`
- `Country`
- `Advisor`
- `Problem`
- `Ranking`

Extra columns are allowed. Blank lines are ignored. Missing trailing values are treated as blank fields.

---

## How To Run

From the project root directory, run:

```powershell
ant run
```

By default, this compiles the program and runs it with:

```text
2015.csv StatisticsReport.txt
```

That means the program reads `2015.csv` and writes the report to `StatisticsReport.txt`.

---

## Program Feedback

The program prints clear messages if something is wrong, such as:

- The input file does not exist.
- The input file is empty.
- Required CSV columns are missing.
- The output file cannot be written.
- The command-line arguments are missing or invalid.

When the run succeeds, it prints how many team rows were read and where the report was written.

---

## Final Comments

This project provides a simple Java and Ant workflow for turning contest CSV data into a readable statistics report. It validates the required input columns, gives clear feedback when something is wrong, and summarizes institution and team performance in `StatisticsReport.txt`.
