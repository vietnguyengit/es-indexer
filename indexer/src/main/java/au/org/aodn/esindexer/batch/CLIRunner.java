package au.org.aodn.esindexer.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Slf4j
@Component
public class CLIRunner implements ApplicationRunner {
    public static final String BATCH = "batch";
    public static final String JOB_NAME = "jobName";
    public static final String JOB_PARAM = "jobParam";

    protected final BatchJobRunner batchJobRunner;
    protected final ConfigurableApplicationContext context;

    @Autowired
    public CLIRunner(BatchJobRunner batchJobRunner, ConfigurableApplicationContext context) {
        this.batchJobRunner = batchJobRunner;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {

        if (args.containsOption(BATCH)) {
            if (!args.containsOption(JOB_NAME) || args.getOptionValues(JOB_NAME).size() != 1) {
                log.error("Argument must have --jobName and contains one value only");
                exitWithCode(1);
            }

            List<String> jobName = args.getOptionValues(JOB_NAME);
            String jobParam = null;
            if (args.getOptionValues(JOB_PARAM) != null && !args.getOptionValues(JOB_PARAM).isEmpty()) {
                jobParam = args.getOptionValues(JOB_PARAM).get(0);
            }

            try {
                batchJobRunner.run(jobName.get(0), jobParam);
            }
            catch (Exception e) {
                log.error("Batch job failed with exception: {}", e.getMessage());
                exitWithCode(1);
            }
            log.info("Batch job completed successfully.");
            exitWithCode(0);
        }

        // If not batch, do nothing (web server runs)
        log.info("Start application in web mode");
    }

    private void exitWithCode(int code) {
        // Use SpringApplication.exit to trigger orderly shutdown of context, beans, executors, schedulers, etc.
        int exitCode = SpringApplication.exit(context, () -> code);
        // Last-resort fallback: brief sleep to allow shutdown hooks / @PreDestroy / awaitTermination to make progress,
        // then force System.exit in case lingering non-daemon threads (e.g. from libraries) would otherwise prevent termination.
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.exit(exitCode);
    }
}
