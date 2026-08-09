package com.aiproxyoauth.model;

import com.aiproxyoauth.provider.ProviderId;
import com.aiproxyoauth.provider.ProviderModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CompositeModelCatalog implements ModelCatalog {

    private final List<ProviderModelCatalog> catalogs;
    private volatile List<ModelCatalogFailure> failures = List.of();

    public CompositeModelCatalog(List<ProviderModelCatalog> catalogs) {
        this.catalogs = List.copyOf(Objects.requireNonNull(catalogs, "catalogs"));
        if (this.catalogs.isEmpty()) {
            throw new IllegalArgumentException("catalogs cannot be empty");
        }
    }

    @Override
    public List<ProviderModel> resolveModels() throws ModelCatalogException {
        Map<String, ProviderModel> merged = new LinkedHashMap<>();
        List<ModelCatalogFailure> currentFailures = new ArrayList<>();
        int successfulCatalogs = 0;

        for (ProviderModelCatalog catalog : catalogs) {
            try {
                List<ProviderModel> resolved = List.copyOf(catalog.resolveModels());
                validateOwnership(catalog.provider(), resolved);
                successfulCatalogs++;
                for (ProviderModel model : resolved) {
                    merged.putIfAbsent(key(model), model);
                }
            } catch (Exception error) {
                currentFailures.add(new ModelCatalogFailure(
                        catalog.provider(),
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage()
                ));
            }
        }

        failures = List.copyOf(currentFailures);
        if (successfulCatalogs == 0) {
            throw new ModelCatalogException(failures);
        }
        return List.copyOf(merged.values());
    }

    public List<ModelCatalogFailure> failures() {
        return failures;
    }

    private static void validateOwnership(
            ProviderId catalogProvider,
            List<ProviderModel> models
    ) {
        for (ProviderModel model : models) {
            if (model.provider() != catalogProvider) {
                throw new IllegalStateException(
                        "Catalog " + catalogProvider.wireName()
                                + " returned a model owned by "
                                + model.provider().wireName()
                );
            }
        }
    }

    private static String key(ProviderModel model) {
        return model.provider().wireName() + '\0' + model.id();
    }
}
