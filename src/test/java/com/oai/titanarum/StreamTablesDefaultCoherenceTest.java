package com.oai.titanarum;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE TRIPWIRE for the borderless-table default. One product decision, FIVE independent
 * declarations, in three languages, none derived from any other:
 *
 * <ol>
 *   <li><b>CLI</b> — picocli's {@code @Option} on {@code PdfTitanArumApp.streamTables}. Governs the
 *       CLI <em>only</em>: {@code new PdfTitanArumApp()} never runs picocli, so no server and no
 *       blastbox path ever applies it.</li>
 *   <li><b>programmatic</b> — the FIELD INITIALIZER on the same field. This is what an embedder and
 *       {@code WorkerPool}'s freshly constructed app start from.</li>
 *   <li><b>blastbox job.json</b> — {@code JobDescriptor.streamTables()}'s absent-key resolution in
 *       {@code runWorker}, which is what a {@code job.json} without the key means.</li>
 *   <li><b>REST / database</b> — the {@code jobs.stream_tables} column DEFAULT in
 *       {@code db/migration}, which is what an INSERT omitting the column means.</li>
 *   <li><b>blastbox dispatcher</b> — {@code titanarum/engine.py}'s {@code _DEFAULT_JOB}, the value
 *       actually written into {@code job.json}.</li>
 * </ol>
 *
 * <p>Flipping any ONE of those leaves the others on the old value, and every one of them fails
 * SILENTLY — the flag simply does something different depending on which door the job came through.
 * That is not hypothetical: the REST surface shipped unable to express this flag at all, because
 * nothing tied {@code WorkerPool}'s setter block to the other surfaces.
 *
 * <p>Declarations 1–3 are all wired to {@link PdfTitanArumApp#STREAM_TABLES_DEFAULT} and so cannot
 * drift; this class proves that wiring is real (it reads the values back through the actual parse /
 * construct / deserialize paths, not by reading the constant twice) and then parses the SQL and the
 * Python — which cannot share a Java constant — and fails if either disagrees.
 *
 * <p>ALSO PINNED HERE: the picocli inversion trap. picocli sets a plain boolean flag to the OPPOSITE
 * of its {@code defaultValue} when the flag is present, so changing {@code defaultValue="false"} to
 * {@code "true"} on a bare boolean option turns {@code --stream-tables} into an OFF switch. The
 * one-word "flip the default" edit is therefore actively wrong, and
 * {@link #cliFlagStillMeansOnAndHasAWorkingOffSwitch()} is what catches it.
 *
 * @see StreamTablesSurfaceTest for the behavioural end-to-end coverage of each surface
 */
class StreamTablesDefaultCoherenceTest {

    @TempDir
    Path tmp;

    /** The one value every surface must agree on. */
    private static final boolean EXPECTED = PdfTitanArumApp.STREAM_TABLES_DEFAULT;

    private static boolean streamTablesOf(PdfTitanArumApp app) throws Exception {
        Field f = PdfTitanArumApp.class.getDeclaredField("streamTables");
        f.setAccessible(true);
        return (boolean) f.get(app);
    }

    // ------------------------------------------------------------------ locating the repo's files

    /**
     * Resolves a repo-relative path. Surefire runs with the module basedir as its working directory
     * (see {@code ServerApiRoutesFormBindingTest}, which reads the HTML template the same way), but
     * walk up a few levels so an IDE runner with a different working directory still finds it.
     *
     * <p>Deliberately FAILS rather than skipping when the file is missing: a silent skip would defeat
     * the entire purpose of this class.
     */
    private static Path repoFile(String relative) {
        Path here = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && here != null; i++, here = here.getParent()) {
            Path candidate = here.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return fail("could not find " + relative + " from working directory "
                + Path.of("").toAbsolutePath() + " — this test must be able to read it, because it "
                + "is the only thing pinning that declaration of the stream-tables default");
    }

    /** Strips {@code --} line comments so documentation prose is never parsed as SQL. */
    private static String stripSqlComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            int at = line.indexOf("--");
            out.append(at >= 0 ? line.substring(0, at) : line).append('\n');
        }
        return out.toString();
    }

    // -------------------------------------------------------------------- 1-3. the Java surfaces

    @Test
    void theStringPicocliNeedsMatchesTheBooleanConstant() throws Exception {
        // An annotation value must be a compile-time String, so the boolean is spelled twice. This is
        // the only duplication the compiler cannot catch.
        Field f = PdfTitanArumApp.class.getDeclaredField("STREAM_TABLES_DEFAULT_VALUE");
        f.setAccessible(true);
        String asText = (String) f.get(null);
        assertEquals(EXPECTED, Boolean.parseBoolean(asText),
                "STREAM_TABLES_DEFAULT_VALUE (\"" + asText + "\") is what picocli actually applies; "
                        + "STREAM_TABLES_DEFAULT is what every other Java surface reads. They must "
                        + "say the same thing.");
    }

    @Test
    void cliDefaultMatches() {
        PdfTitanArumApp parsed = new PdfTitanArumApp();
        new CommandLine(parsed).parseArgs("--input", tmp.resolve("x.pdf").toString(),
                "--output", tmp.resolve("y").toString());
        assertDoesNotThrow(() -> assertEquals(EXPECTED, streamTablesOf(parsed),
                "the CLI's picocli default disagrees with STREAM_TABLES_DEFAULT"));
    }

    @Test
    void programmaticDefaultMatches() throws Exception {
        // No picocli parse at all — the field initializer. This is the value WorkerPool's fresh app
        // and any embedder start from, and the @Option annotation is invisible to them.
        assertEquals(EXPECTED, streamTablesOf(new PdfTitanArumApp()),
                "new PdfTitanArumApp() disagrees with STREAM_TABLES_DEFAULT: the field initializer "
                        + "is a SEPARATE declaration from the @Option defaultValue, and every "
                        + "programmatic caller (WorkerPool, callWith, embedders) gets this one");
    }

    @Test
    void blastboxJobJsonAbsentKeyDefaultMatches() throws Exception {
        // The blastbox file-IPC path. A job.json without the key must mean "the shipping default",
        // which is only expressible because the record component is a boxed Boolean; as a primitive
        // it was pinned to false forever.
        PdfTitanArumApp.JobDescriptor job = new ObjectMapper()
                .readValue("{\"input_path\":\"/x\",\"output_dir\":\"/y\"}",
                        PdfTitanArumApp.JobDescriptor.class);
        assertNull(job.streamTables(),
                "JobDescriptor.streamTables must stay a BOXED Boolean: a primitive cannot tell an "
                        + "absent key from an explicit false, which silently pins old job.json files "
                        + "to OFF regardless of the shipping default");
        assertEquals(EXPECTED, PdfTitanArumApp.resolveStreamTables(job.streamTables()),
                "an absent stream_tables key must resolve to STREAM_TABLES_DEFAULT");
    }

    @Test
    void explicitValuesInJobJsonAlwaysWinOverTheDefault() throws Exception {
        ObjectMapper m = new ObjectMapper();
        for (boolean explicit : new boolean[]{false, true}) {
            PdfTitanArumApp.JobDescriptor job = m.readValue(
                    "{\"input_path\":\"/x\",\"output_dir\":\"/y\",\"stream_tables\":" + explicit + "}",
                    PdfTitanArumApp.JobDescriptor.class);
            assertEquals(explicit, job.streamTables(),
                    "explicit stream_tables=" + explicit + " must bind");
            assertEquals(explicit, PdfTitanArumApp.resolveStreamTables(job.streamTables()),
                    "an explicit stream_tables=" + explicit + " must survive default resolution — a "
                            + "default flip must never take away per-job control");
        }
    }

    // ------------------------------------------------------------- the CLI's on/off switches work

    /**
     * THE INVERSION TRAP. picocli assigns a bare boolean option the opposite of its
     * {@code defaultValue} when the option appears, so with {@code defaultValue="true"} a plain
     * {@code @Option} would make {@code --stream-tables} mean OFF — silently inverting every existing
     * script, and every harness arm that opts in by passing the flag.
     */
    @Test
    void cliFlagStillMeansOnAndHasAWorkingOffSwitch() throws Exception {
        assertTrue(cliParse("--stream-tables"),
                "--stream-tables must still mean ON. If this fails, the option was reduced to a "
                        + "plain boolean flag with defaultValue=\"true\", and picocli has inverted "
                        + "it: the flag now DISABLES the feature it names. Keep arity=\"0..1\" with "
                        + "an explicit fallbackValue.");
        assertFalse(cliParse("--stream-tables=false"),
                "--stream-tables=false is the documented off switch and must turn the flag OFF; a "
                        + "default that cannot be reversed per invocation is not acceptable");
        assertTrue(cliParse("--stream-tables=true"), "--stream-tables=true must mean ON");
        assertEquals(EXPECTED, cliParse(), "omitting the flag must yield the shipping default");
    }

    /** An unparseable value must fail LOUDLY, never quietly resolve to off (or to on). */
    @Test
    void anUnparseableCliValueIsRejectedRatherThanGuessed() {
        assertThrows(CommandLine.ParameterException.class, () -> cliParse("--stream-tables=maybe"),
                "a typo'd value must be a hard parse error: silently choosing a side would either "
                        + "disable a detection stage or override an operator's explicit intent");
    }

    private boolean cliParse(String... extra) throws Exception {
        PdfTitanArumApp app = new PdfTitanArumApp();
        List<String> args = new ArrayList<>(List.of("--input", tmp.resolve("x.pdf").toString(),
                "--output", tmp.resolve("y").toString()));
        args.addAll(List.of(extra));
        new CommandLine(app).parseArgs(args.toArray(String[]::new));
        return streamTablesOf(app);
    }

    // ------------------------------------------------------------------ 4. the database migrations

    /**
     * Replays every migration in version order and asserts the FINAL column default agrees. Written
     * as a replay rather than "grep the newest file" so that appending another migration that moves
     * the default again is picked up automatically, and so that editing V8 in place (migrations are
     * append-only — don't) does not quietly pass.
     */
    @Test
    void databaseColumnDefaultMatches() throws Exception {
        Path dir = repoFile("src/main/resources/db/migration/V8__add_stream_tables.sql").getParent();
        List<Path> migrations;
        try (var s = Files.list(dir)) {
            migrations = s.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(StreamTablesDefaultCoherenceTest::versionOf))
                    .toList();
        }
        assertFalse(migrations.isEmpty(), "no migrations found in " + dir);

        Pattern addColumn = Pattern.compile(
                "ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?stream_tables\\b[^;]*?"
                        + "DEFAULT\\s+(TRUE|FALSE)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern setDefault = Pattern.compile(
                "ALTER\\s+COLUMN\\s+stream_tables\\s+SET\\s+DEFAULT\\s+(TRUE|FALSE)",
                Pattern.CASE_INSENSITIVE);
        Pattern dropDefault = Pattern.compile(
                "ALTER\\s+COLUMN\\s+stream_tables\\s+DROP\\s+DEFAULT", Pattern.CASE_INSENSITIVE);

        Optional<Boolean> effective = Optional.empty();
        String setBy = null;
        for (Path p : migrations) {
            String sql = stripSqlComments(Files.readString(p, StandardCharsets.UTF_8));
            for (String stmt : sql.split(";")) {
                Matcher add = addColumn.matcher(stmt);
                if (add.find()) {
                    effective = Optional.of(Boolean.parseBoolean(add.group(1)));
                    setBy = p.getFileName().toString();
                }
                Matcher set = setDefault.matcher(stmt);
                if (set.find()) {
                    effective = Optional.of(Boolean.parseBoolean(set.group(1)));
                    setBy = p.getFileName().toString();
                }
                if (dropDefault.matcher(stmt).find()) {
                    effective = Optional.empty();
                    setBy = p.getFileName().toString();
                }
            }
        }

        assertTrue(effective.isPresent(),
                "no migration leaves jobs.stream_tables with a column DEFAULT (last touched by "
                        + setBy + "). An INSERT that omits the column — an old application binary "
                        + "mid-rolling-deploy — would then fail against NOT NULL.");
        assertEquals(EXPECTED, effective.get(),
                "the jobs.stream_tables column DEFAULT (last set by " + setBy + ") disagrees with "
                        + "STREAM_TABLES_DEFAULT. Migrations are append-only: add the next V<n> with "
                        + "ALTER COLUMN stream_tables SET DEFAULT " + (EXPECTED ? "TRUE" : "FALSE")
                        + " rather than editing an existing one.");
    }

    private static int versionOf(Path p) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(p.getFileName().toString());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** V8 shipped; editing it instead of appending would rewrite history for deployed databases. */
    @Test
    void theOriginalMigrationIsNotRewrittenInPlace() throws Exception {
        String v8 = Files.readString(repoFile("src/main/resources/db/migration/V8__add_stream_tables.sql"),
                StandardCharsets.UTF_8);
        assertTrue(stripSqlComments(v8).matches("(?is).*ADD\\s+COLUMN\\s+stream_tables\\s+BOOLEAN\\s+"
                        + "NOT\\s+NULL\\s+DEFAULT\\s+FALSE.*"),
                "V8 must keep shipping exactly what it shipped (DEFAULT FALSE). Flyway checksums it "
                        + "on every deployed database; changing it breaks their next migration. Move "
                        + "the default in a NEW migration instead.");
    }

    // ---------------------------------------------------------------- 5. the blastbox dispatcher

    /**
     * {@code titanarum/engine.py} builds the job.json the worker reads, so its {@code _DEFAULT_JOB}
     * is the value blastbox jobs really get — the Java-side absent-key default is only the fallback
     * for a dispatcher too old to write the key.
     */
    @Test
    void enginePyDefaultJobMatches() throws Exception {
        String block = defaultJobBlockOf(repoFile("titanarum/engine.py"));
        Matcher m = Pattern.compile("[\"']stream_tables[\"']\\s*:\\s*(True|False)\\b").matcher(block);
        assertTrue(m.find(),
                "titanarum/engine.py's _DEFAULT_JOB has no literal stream_tables entry. Every job "
                        + "key is declared there; if it moved, this tripwire has to move with it.");
        boolean pythonDefault = "True".equals(m.group(1));
        assertEquals(EXPECTED, pythonDefault,
                "titanarum/engine.py _DEFAULT_JOB[\"stream_tables\"] is " + m.group(1)
                        + " but STREAM_TABLES_DEFAULT is " + EXPECTED + ". The blastbox dispatcher "
                        + "writes this value into job.json, so it — not the Java default — is what "
                        + "sandboxed workers actually run with.");
    }

    /**
     * Reads the {@code _DEFAULT_JOB} literal with {@code #} comments stripped. Stripping matters:
     * the comment above the entry documents how to switch the flag off and contains the text
     * {@code "stream_tables": false}, which a naive regex would match instead.
     */
    private static String defaultJobBlockOf(Path enginePy) throws IOException {
        String src = Files.readString(enginePy, StandardCharsets.UTF_8);
        int start = src.indexOf("_DEFAULT_JOB");
        assertTrue(start >= 0, "no _DEFAULT_JOB in " + enginePy);
        int open = src.indexOf('{', start);
        int close = src.indexOf("\n}", open);
        assertTrue(open >= 0 && close > open, "could not delimit _DEFAULT_JOB in " + enginePy);
        StringBuilder out = new StringBuilder();
        for (String line : src.substring(open, close).split("\n")) {
            int hash = line.indexOf('#');
            out.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
        }
        return out.toString();
    }

    /**
     * The env override is the documented per-job off switch for sandboxed workers, so its parser must
     * keep treating the falsey spellings as false. {@code tests/test_fileipc.py} executes the real
     * Python; this pins the SOURCE so a Java-only test run still notices the parser being special
     * cased for this one key (which is how it would drift from its siblings).
     */
    @Test
    void enginePyParsesTheEnvFlagWithTheSharedHelper() throws Exception {
        String src = Files.readString(repoFile("titanarum/engine.py"), StandardCharsets.UTF_8);
        assertTrue(src.contains("(\"TITANARUM_STREAM_TABLES\", \"stream_tables\"),"),
                "TITANARUM_STREAM_TABLES must stay in engine.py's shared env->job mapping loop, "
                        + "which is what makes it parse identically to its sibling toggles (\"0\", "
                        + "\"false\", \"no\", \"off\" => off). A bespoke parser for this one key is "
                        + "how an env flag ends up truthy for the string \"false\".");
        assertTrue(src.contains("in (\"1\", \"true\", \"yes\", \"on\")"),
                "engine.py's _flag helper must keep its allowlist of truthy spellings, so anything "
                        + "else — including \"false\" and \"0\" — is false");
    }
}
