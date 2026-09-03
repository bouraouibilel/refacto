package com.refacto.migration.discovery;

import com.refacto.migration.core.model.BatchDescriptor;
import com.refacto.migration.core.model.ModuleDescriptor;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectDiscoveryTest {

    @Test
    void shouldDiscoverMultiModuleMavenProject(@TempDir Path tempDir) throws Exception {
        // Create root pom.xml
        String rootPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.sample.app</groupId>
                    <artifactId>sample-parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <properties>
                        <java.version>11</java.version>
                    </properties>
                    <modules>
                        <module>batch-payment</module>
                        <module>payment-model</module>
                    </modules>
                </project>
                """;
        FileUtils.writeStringToFile(tempDir.resolve("pom.xml").toFile(), rootPom, StandardCharsets.UTF_8);

        // Submodule batch-payment
        Path batchDir = tempDir.resolve("batch-payment");
        batchDir.toFile().mkdirs();
        String batchPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.sample.app</groupId>
                        <artifactId>sample-parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>batch-payment</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.batch</groupId>
                            <artifactId>spring-batch-core</artifactId>
                            <version>4.3.0</version>
                        </dependency>
                        <dependency>
                            <groupId>com.sample.app</groupId>
                            <artifactId>payment-model</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """;
        FileUtils.writeStringToFile(batchDir.resolve("pom.xml").toFile(), batchPom, StandardCharsets.UTF_8);

        // Submodule payment-model
        Path modelDir = tempDir.resolve("payment-model");
        modelDir.toFile().mkdirs();
        String modelPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.sample.app</groupId>
                        <artifactId>sample-parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>payment-model</artifactId>
                </project>
                """;
        FileUtils.writeStringToFile(modelDir.resolve("pom.xml").toFile(), modelPom, StandardCharsets.UTF_8);

        MavenProjectDiscoveryService service = new MavenProjectDiscoveryService();
        List<ModuleDescriptor> modules = service.discoverModules(tempDir);

        assertThat(modules).hasSize(3); // root + 2 submodules
        ModuleDescriptor batchModule = modules.stream()
                .filter(m -> m.artifactId().equals("batch-payment"))
                .findFirst().orElseThrow();

        assertThat(batchModule.hasSpringBatch()).isTrue();
        assertThat(batchModule.javaVersion()).isEqualTo("11");

        // Dependent module check
        ModuleDescriptor modelModule = modules.stream()
                .filter(m -> m.artifactId().equals("payment-model"))
                .findFirst().orElseThrow();
        assertThat(modelModule.dependentModules()).contains("batch-payment");
    }

    @Test
    void shouldDiscoverSpringBatchJob(@TempDir Path tempDir) throws Exception {
        Path javaDir = tempDir.resolve("src/main/java/com/sample");
        javaDir.toFile().mkdirs();

        String javaCode = """
                package com.sample;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.batch.core.Job;
                import org.springframework.batch.core.Step;

                @Configuration
                public class PaymentJobConfig {
                    @Bean
                    public Job paymentJob() {
                        return jobBuilderFactory.get("paymentJob")
                            .start(step1())
                            .build();
                    }

                    @Bean
                    public Step step1() {
                        return stepBuilderFactory.get("step1")
                            .<Payment, Payment>chunk(100)
                            .reader(reader())
                            .processor(processor())
                            .writer(writer())
                            .build();
                    }
                }
                """;
        FileUtils.writeStringToFile(javaDir.resolve("PaymentJobConfig.java").toFile(), javaCode, StandardCharsets.UTF_8);

        SpringBatchDiscoveryService batchService = new SpringBatchDiscoveryService();
        List<BatchDescriptor> batches = batchService.discoverBatches(tempDir, "batch-payment", tempDir);

        assertThat(batches).hasSize(1);
        BatchDescriptor job = batches.get(0);
        assertThat(job.jobName()).isEqualTo("paymentJob");
        assertThat(job.steps()).isNotEmpty();
        assertThat(job.steps().get(0).chunkSize()).isEqualTo(100);
    }
}
