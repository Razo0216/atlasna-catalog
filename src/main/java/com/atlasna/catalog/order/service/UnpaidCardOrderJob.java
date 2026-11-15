package com.atlasna.catalog.order.service;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Every minute: cancel card orders whose payment never arrived (stock back, slot released). Idempotent and safe to run
 * on several instances at once — each cancellation re-checks the order's state inside its own transaction.
 */
@DisallowConcurrentExecution
public class UnpaidCardOrderJob extends QuartzJobBean {

    @Autowired
    private OrderPaymentService payments;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        payments.cancelExpiredUnpaid();
    }

    @Configuration
    static class Schedule {
        @Bean
        JobDetail unpaidCardOrderJob() {
            return JobBuilder.newJob(UnpaidCardOrderJob.class).withIdentity("unpaidCardOrders").storeDurably().build();
        }

        @Bean
        Trigger unpaidCardOrderTrigger(JobDetail unpaidCardOrderJob) {
            return TriggerBuilder.newTrigger().forJob(unpaidCardOrderJob).withIdentity("unpaidCardOrdersEveryMinute")
                    .withSchedule(SimpleScheduleBuilder.repeatMinutelyForever()).build();
        }
    }
}
