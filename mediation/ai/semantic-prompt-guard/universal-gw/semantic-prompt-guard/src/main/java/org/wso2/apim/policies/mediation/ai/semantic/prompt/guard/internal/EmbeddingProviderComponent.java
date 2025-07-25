/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

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
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unbindProvider"
    )
    protected void bindProvider(EmbeddingProviderService provider) {
        ServiceReferenceHolder.getInstance().setEmbeddingProvider(provider);
    }

    protected void unbindProvider(EmbeddingProviderService provider) {
        ServiceReferenceHolder.getInstance().setEmbeddingProvider(null);
    }
}
