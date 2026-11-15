package com.atlasna.catalog.inventory;

import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Every hour: write off lots past their expiry date (each lot in its own transaction, so one problem doesn't block the
 * rest) and raise "expiring soon" once per lot. Idempotent: safe to run twice or on several instances.
 */
@Slf4j
@DisallowConcurrentExecution
public class ExpiryJob extends QuartzJobBean {

    @Autowired
    private InventoryService inventory;

    @Value("${atlasna.inventory.expiring-soon-days:3}")
    private int expiringSoonDays;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        run(inventory, expiringSoonDays);
    }

    /** The job's work, callable from tests. Returns how many lots were written off. */
    public static int run(InventoryService inventory, int expiringSoonDays) {
        int expired = 0;
        for (long lotId : inventory.expiredLotIds()) {
            try {
                if (inventory.expireLot(lotId)) expired++;
            } catch (RuntimeException e) {
                log.error("Could not write off expired lot {}: {}", lotId, e.getMessage());
            }
        }
        int soon = inventory.notifyExpiringSoon(expiringSoonDays);
        if (expired > 0 || soon > 0) log.info("Expiry job: {} lot(s) written off, {} expiring soon", expired, soon);
        return expired;
    }

    @Configuration
    static class Schedule {
        @Bean
        JobDetail expiryJob() {
            return JobBuilder.newJob(ExpiryJob.class).withIdentity("lotExpiry").storeDurably().build();
        }

        @Bean
        Trigger expiryTrigger(JobDetail expiryJob) {
            return TriggerBuilder.newTrigger().forJob(expiryJob).withIdentity("lotExpiryHourly")
                    .withSchedule(SimpleScheduleBuilder.repeatHourlyForever()).build();
        }
    }
}
