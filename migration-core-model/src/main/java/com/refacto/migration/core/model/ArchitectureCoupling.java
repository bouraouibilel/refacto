package com.refacto.migration.core.model;

import com.refacto.migration.core.enums.CouplingLevel;
import java.util.List;
import java.util.Map;

public record ArchitectureCoupling(
        String componentName,
        String componentType, // DAO, Entity, Service, Helper
        String definedInModule,
        List<String> consumerBatchJobs,
        List<String> consumerModules,
        CouplingLevel couplingLevel,
        boolean eligibleForCommons,
        String recommendation
) {}
