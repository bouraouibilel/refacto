package com.sample.batch;

import com.sample.model.PaymentEntity;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableBatchProcessing
public class PaymentJobConfig {

    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;

    public PaymentJobConfig(JobBuilderFactory jobBuilderFactory, StepBuilderFactory stepBuilderFactory) {
        this.jobBuilderFactory = jobBuilderFactory;
        this.stepBuilderFactory = stepBuilderFactory;
    }

    @Bean
    public Job paymentJob() {
        return jobBuilderFactory.get("paymentJob")
                .start(paymentStep())
                .build();
    }

    @Bean
    public Step paymentStep() {
        return stepBuilderFactory.get("paymentStep")
                .<PaymentEntity, PaymentEntity>chunk(100)
                .reader(new PaymentReader())
                .processor(new PaymentProcessor())
                .writer(new PaymentWriter())
                .build();
    }
}
