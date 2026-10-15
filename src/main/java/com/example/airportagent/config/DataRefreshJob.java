package com.example.airportagent.config;

import com.example.airportagent.Application;
import com.example.airportagent.data.AirportDataLoader;
import com.example.airportagent.data.AirportDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Refreshes the data on a schedule. It runs scripts/prepare_data.py, which downloads the public files of BTS, FAA
 * and OurAirports. If the script ends well, the job replaces the data folder and restarts the app, so that the app
 * reads the new files. Before it replaces the files, it makes a basic check of the new data. The job is off by
 * default (airport.refresh.enabled).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "airport.refresh", name = "enabled", havingValue = "true")
public class DataRefreshJob {

    /** The data have at least this number of airports. The real data have more than 500. */
    private static final int MIN_AIRPORTS = 100;
    private static final Logger LOG = LoggerFactory.getLogger(DataRefreshJob.class);

    private final RefreshProperties properties;
    private final ConfigurableApplicationContext context;
    private final ApplicationArguments arguments;

    /** Creates the job with its settings and the application context that it restarts. */
    public DataRefreshJob(RefreshProperties properties, ConfigurableApplicationContext context,
                          ApplicationArguments arguments) {
        this.properties = properties;
        this.context = context;
        this.arguments = arguments;
    }

    /** Runs the script. If it ends well, it moves the new files to the data folder and restarts the app. */
    @Scheduled(cron = "${airport.refresh.cron}")
    public void refresh() {
        Path target = Path.of(properties.dataDir());
        Path temporary = Path.of(properties.dataDir() + ".tmp");
        boolean done = runWithRetry(properties.retries(), properties.retryDelaySeconds() * 1000,
                () -> runScript(temporary));
        if (!done) {
            LOG.error("The data refresh failed after {} tries. The app keeps the old data.", properties.retries());
            return;
        }
        if (!isValid(temporary)) {
            LOG.error("The new data failed the basic check. The app keeps the old data.");
            return;
        }
        try {
            FileSystemUtils.deleteRecursively(target);
            Files.move(temporary, target);
        } catch (IOException e) {
            LOG.error("The data refresh could not move the new files. The app keeps the old data.", e);
            return;
        }
        LOG.info("The data refresh is done. The app restarts to read the new data.");
        restart();
    }

    /** True if the folder has files that the loader can read and the data look complete. */
    private static boolean isValid(Path folder) {
        try {
            return looksComplete(new AirportDataLoader(folder).load());
        } catch (RuntimeException e) {
            LOG.warn("Cannot read the new data: {}", e.getMessage());
            return false;
        }
    }

    /** The basic check: the data have airports, traffic, enplanements and a data year. */
    static boolean looksComplete(AirportDataset data) {
        return data.airports().size() >= MIN_AIRPORTS && !data.traffic().isEmpty()
                && !data.enplanements().isEmpty() && data.info().dataYear() > 0;
    }

    /**
     * Calls the action until it returns 0 or the tries are used. It waits between two tries.
     *
     * @param tries         the maximum number of calls
     * @param delayMillis   the wait time between two calls
     * @param exitCode      the action. A result of 0 means that it ended well.
     * @return true if one call ended well
     */
    static boolean runWithRetry(int tries, long delayMillis, IntSupplier exitCode) {
        for (int attempt = 1; attempt <= tries; attempt++) {
            if (exitCode.getAsInt() == 0) {
                return true;
            }
            LOG.warn("The data script failed (try {} of {}).", attempt, tries);
            if (attempt < tries && delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** Starts the Python script with the output folder. Gives the exit code, or -1 if the script cannot start. */
    private int runScript(Path output) {
        List<String> command = new ArrayList<>(List.of(properties.pythonCommand(), "-I", properties.script(),
                "--out", output.toString()));
        if (properties.year() > 0) {
            command.addAll(List.of("--year", String.valueOf(properties.year())));
        }
        try {
            return new ProcessBuilder(command).inheritIO().start().waitFor();
        } catch (IOException e) {
            LOG.warn("Cannot start the data script: {}", e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Closes the application context and starts the application again, in a new thread. */
    private void restart() {
        String[] args = arguments.getSourceArgs();
        Thread thread = new Thread(() -> {
            context.close();
            Application.main(args);
        }, "restart");
        thread.setDaemon(false);
        thread.start();
    }
}
