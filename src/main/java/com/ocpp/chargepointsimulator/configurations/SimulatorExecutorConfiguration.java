package com.ocpp.chargepointsimulator.configurations;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread pools used by the simulator. All of them are Spring managed so they are shut down with the
 * application context instead of leaking threads.
 */
@Configuration
public class SimulatorExecutorConfiguration {

    /** Delayed OCPP work triggered by the central system, e.g. remote start/stop sequences. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService remoteTransactionExecutor() {
        return Executors.newFixedThreadPool(15, namedThreadFactory());
    }

    /**
     * Scheduler behind {@code @Scheduled}, i.e. the metering ticks. It is declared explicitly because
     * Spring Boot would otherwise adopt any {@code ScheduledExecutorService} bean, and one slow
     * central system could then block the scheduled work of every other charge point.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ocpp-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    private ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "ocpp-remote-transaction-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
