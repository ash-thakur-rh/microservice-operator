package io.example.operator.dependentresource;

import java.util.HashMap;
import java.util.Map;

import io.example.operator.customresource.MicroService;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import static io.example.operator.MicroServiceUtils.appLabels;
import static io.example.operator.MicroServiceUtils.configMapName;

/**
 * Manages a ConfigMap that holds the application's runtime configuration.
 * The ConfigMap is mounted into the Deployment as environment variables via envFrom.
 */
public class ConfigMapDependentResource
    extends CRUDKubernetesDependentResource<ConfigMap, MicroService> {

  @Override
  protected ConfigMap desired(MicroService ms, Context<MicroService> context) {
    Map<String, String> data = new HashMap<>();
    if (ms.getSpec().getConfig() != null) {
      data.putAll(ms.getSpec().getConfig());
    }

    return new ConfigMapBuilder()
        .withMetadata(new ObjectMetaBuilder()
            .withName(configMapName(ms))
            .withNamespace(ms.getMetadata().getNamespace())
            .withLabels(appLabels(ms))
            .build())
        .withData(data)
        .build();
  }
}