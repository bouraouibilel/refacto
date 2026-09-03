package com.refacto.migration.core.model;

import java.util.List;

public record RecipePack(
        String id,
        String name,
        String description,
        String version,
        List<String> recipeIds,
        List<String> packDependencies
) {}
