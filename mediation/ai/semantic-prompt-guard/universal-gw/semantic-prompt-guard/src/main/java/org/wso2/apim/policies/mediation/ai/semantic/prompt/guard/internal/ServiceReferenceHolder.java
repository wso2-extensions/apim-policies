package org.wso2.apim.policies.mediation.ai.semantic.prompt.guard.internal;

import org.wso2.carbon.apimgt.api.EmbeddingProviderService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Singleton holder for managing references to shared services like EmbeddingProviderService.
 * This class can be extended to hold other service references in the future.
 */
public class ServiceReferenceHolder {

    private static final ServiceReferenceHolder instance = new ServiceReferenceHolder();

    private final List<EmbeddingProviderService> embeddingProviders = new ArrayList<>();

    private ServiceReferenceHolder() {
    }

    public static ServiceReferenceHolder getInstance() {
        return instance;
    }

    public void addEmbeddingProvider(EmbeddingProviderService provider) {
        embeddingProviders.add(provider);
    }

    public void removeEmbeddingProvider(EmbeddingProviderService provider) {
        embeddingProviders.remove(provider);
    }

    public List<EmbeddingProviderService> getEmbeddingProviders() {
        return Collections.unmodifiableList(embeddingProviders);
    }
}
