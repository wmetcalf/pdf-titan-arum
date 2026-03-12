package com.oai.titanarum.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.flywaydb.core.Flyway;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "server",
    description = "Start pdf-titan-arum HTTP server",
    mixinStandardHelpOptions = true
)
public class ServerCommand implements Callable<Integer> {

    @Option(names = {"--port"}, defaultValue = "7272",
            description = "Port to bind (default: 7272)")
    int port;

    @Option(names = {"--host"}, defaultValue = "127.0.0.1",
            description = "Host to bind (default: 127.0.0.1 — localhost only)")
    String host;

    @Option(names = {"--db"}, required = true,
            description = "JDBC URL, e.g. jdbc:postgresql://localhost/titanarum")
    String dbUrl;

    @Option(names = {"--db-user"}, required = true, description = "Database username")
    String dbUser;

    @Option(names = {"--db-password"}, required = true, description = "Database password")
    String dbPassword;

    @Option(names = {"--artifact-root"}, required = true,
            description = "Directory to store job artifact files")
    Path artifactRoot;

    @Option(names = {"--workers"}, defaultValue = "-1",
            description = "Worker thread count (default: availableProcessors - 1)")
    int workers;

    @Option(names = {"--ocr-lang"}, defaultValue = "eng",
            description = "Tesseract language(s) for OCR, e.g. eng+deu+fra (default: eng)")
    String ocrLang;

    @Option(names = {"--timeout"}, defaultValue = "60",
            description = "Per-job timeout in seconds (0 = no limit, default: 60)")
    int timeoutSeconds;

    @Override
    public Integer call() throws Exception {
        int workerCount = workers > 0 ? workers : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

        Files.createDirectories(artifactRoot);

        HikariConfig hikariCfg = new HikariConfig();
        hikariCfg.setJdbcUrl(dbUrl);
        hikariCfg.setUsername(dbUser);
        hikariCfg.setPassword(dbPassword);
        hikariCfg.setMaximumPoolSize(workerCount + 4);
        HikariDataSource ds = new HikariDataSource(hikariCfg);

        Flyway.configure()
              .dataSource(ds)
              .locations("classpath:db/migration")
              .load()
              .migrate();

        JobRepository repo = new JobRepository(ds);
        LocalArtifactStore store = new LocalArtifactStore(artifactRoot);
        WorkerPool pool = new WorkerPool(repo, store, workerCount);
        pool.setTimeoutSeconds(timeoutSeconds);
        pool.setOcrLang(System.getenv().getOrDefault("OCR_LANG", ocrLang));
        String openAiKey  = System.getenv().getOrDefault("OPENAI_API_KEY", "none");
        String openAiBase = System.getenv().getOrDefault("OPENAI_BASE_URL", "");
        if (!openAiBase.isBlank()) {
            pool.setOpenAiKey(openAiKey);
            pool.setOpenAiBaseUrl(openAiBase);
            String openAiModel2 = System.getenv("OPENAI_MODEL");
            if (openAiModel2 == null || openAiModel2.isBlank()) {
                try {
                    openAiModel2 = com.oai.titanarum.OpenAiAnalyzer.detectModel(openAiKey, openAiBase);
                    System.out.println("AI analysis: auto-detected model: " + openAiModel2);
                } catch (Exception e) {
                    System.err.println("WARNING: could not detect model from " + openAiBase + ": " + e.getMessage());
                    openAiModel2 = "default";
                }
            }
            pool.setOpenAiModel(openAiModel2);
            System.out.println("AI analysis enabled — model: " + openAiModel2 + " base: " + openAiBase);
        }

        String workerHost = java.net.InetAddress.getLocalHost().getHostName();
        repo.resetOrphanedJobs(workerHost);
        pool.start(workerHost);

        Javalin app = Javalin.create(cfg -> {
            cfg.staticFiles.add("/static", Location.CLASSPATH);
            cfg.http.defaultContentType = "application/json";
            // H2: enforce upload size limit at the framework level (don't trust Content-Length)
            cfg.http.maxRequestSize = 512L * 1024 * 1024;
        });

        ApiRoutes.wire(app, repo, store, pool);
        WebRoutes.wire(app, repo);

        app.before(ctx -> {
            ctx.header("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'");
            ctx.header("X-Content-Type-Options", "nosniff");
            ctx.header("X-Frame-Options", "DENY");
        });

        app.start(host, port);
        System.out.println("pdf-titan-arum server running on http://" + host + ":" + port);
        System.out.println("Workers: " + workerCount);

        Thread.currentThread().join();
        return 0;
    }
}
