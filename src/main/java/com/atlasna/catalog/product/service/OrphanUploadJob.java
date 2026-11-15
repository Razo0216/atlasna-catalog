package com.atlasna.catalog.product.service;

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

import java.time.Duration;

/** Every hour: delete uploaded photos that were never attached to a product within a day (abandoned uploads). */
@DisallowConcurrentExecution
public class OrphanUploadJob extends QuartzJobBean {

    @Autowired
    private ProductImageService images;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        images.deleteOrphans(Duration.ofHours(24));
    }

    @Configuration
    static class Schedule {
        @Bean
        JobDetail orphanUploadJob() {
            return JobBuilder.newJob(OrphanUploadJob.class).withIdentity("orphanUploads").storeDurably().build();
        }

        @Bean
        Trigger orphanUploadTrigger(JobDetail orphanUploadJob) {
            return TriggerBuilder.newTrigger().forJob(orphanUploadJob).withIdentity("orphanUploadsHourly")
                    .withSchedule(SimpleScheduleBuilder.repeatHourlyForever()).build();
        }
    }
}
