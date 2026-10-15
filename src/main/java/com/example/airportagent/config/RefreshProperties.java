package com.example.airportagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the data refresh job. The job runs scripts/prepare_data.py on a schedule.
 *
 * @param enabled           true to run the job. The default is false.
 * @param cron              the schedule (Spring cron, six fields)
 * @param pythonCommand     the command that starts Python
 * @param script            the path of the data script
 * @param dataDir           the folder with the refreshed CSV files. The app reads it at start-up, if it exists.
 * @param year              the data year for the script, or 0 for the default of the script
 * @param retries           the number of tries when the script fails
 * @param retryDelaySeconds the wait time between two tries
 */
@ConfigurationProperties(prefix = "airport.refresh")
public record RefreshProperties(boolean enabled, String cron, String pythonCommand, String script, String dataDir,
                                int year, int retries, long retryDelaySeconds) {}
