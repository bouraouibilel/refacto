package com.refacto.migration.core.model;

public record BatchStepDescriptor(
        String stepName,
        String stepType, // "CHUNK" or "TASKLET"
        String readerClass,
        String processorClass,
        String writerClass,
        String taskletClass,
        Integer chunkSize
) {}
