package com.refacto.migration.core.model;

import java.util.List;

public record BatchDescriptor(
        String jobName,
        String declaringClass,
        String configFilePath,
        String moduleName,
        List<BatchStepDescriptor> steps,
        List<String> listeners,
        String schedulingCron
) {}
