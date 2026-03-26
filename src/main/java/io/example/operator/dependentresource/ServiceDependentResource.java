package io.example.operator.dependentresource;

import java.util.Map;

import io.example.operator.customresource.MicroService;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import static io.example.operator.MicroServiceUtils.appLabels;

/**
 * Manages a ClusterIP Service that routes traffic to the microservice pods.
 * The service is always created regardless of the exposed flag — exposure is handled
 * by the separate Ingress/Route dependent resource.
 */
public class ServiceDependentResource
    extends CRUDKubernetesDependentResource<Service, MicroService> {

  @Override
  protected Service desired(MicroService ms, Context<MicroService> context) {
    var name = ms.getMetadata().getName();
    var namespace = ms.getMetadata().getNamespace();
    int port = ms.getSpec().getContainerPort();

    return new ServiceBuilder()
        .withMetadata(new ObjectMetaBuilder()
            .withName(name)
            .withNamespace(namespace)
            .withLabels(appLabels(ms))
            .build())
        .withNewSpec()
            .withSelector(Map.of("app", name))
            .addNewPort()
                .withName("http")
                .withPort(80)
                .withTargetPort(port)
                .withProtocol("TCP")
            .endPort()
            .withType("ClusterIP")
        .endSpec()
        .build();
  }
}