package org.wso2.apim.policies.mediation.ai.semantic.prompt.guard.internal;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.apimgt.api.EmbeddingProviderService;

@Component(
        name = "org.wso2.apim.policies.mediation.ai.semantic.prompt.guard.internal.EmbeddingProviderComponent",
        immediate = true
)
public class EmbeddingProviderComponent {

    @Reference(
            name = "embedding.provider.service",
            service = EmbeddingProviderService.class,
            cardinality = ReferenceCardinality.AT_LEAST_ONE,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unbindProvider"
    )
    protected void bindProvider(EmbeddingProviderService provider) {
        ServiceReferenceHolder.getInstance().addEmbeddingProvider(provider);
    }

    protected void unbindProvider(EmbeddingProviderService provider) {
        ServiceReferenceHolder.getInstance().removeEmbeddingProvider(provider);
    }
}
