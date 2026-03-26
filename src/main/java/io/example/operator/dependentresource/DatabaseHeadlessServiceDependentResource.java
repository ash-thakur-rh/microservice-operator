package io.example.operator.dependentresource;

import java.util.List;
import java.util.Map;

import io.example.operator.customresource.MicroService;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import static io.example.operator.MicroServiceUtils.*;

/**
 * Manages the headless Service required by the PostgreSQL StatefulSet.
 *
 * <p>Kubernetes uses this service to assign stable DNS entries to StatefulSet pods in the form:
 * {@code <pod-name>.<headless-svc>.<namespace>.svc.cluster.local}
 *
 * <p>This service is internal to the StatefulSet — the application always connects through
 * the ClusterIP service managed by {@link DatabaseServiceDependentResource}.
 */
public class DatabaseHeadlessServiceDependentResource
    extends CRUDKubernetesDependentResource<Service, MicroService> {

  @Override
  protected Service desired(MicroService ms, Context<MicroService> context) {
    ServicePort postgresPort = new ServicePortBuilder()
        .withName("postgres")
        .withPort(5432)
        .withProtocol("TCP")
        .build();

    return new ServiceBuilder()
        .withMetadata(new ObjectMetaBuilder()
            .withName(dbHeadlessServiceName(ms))
            .withNamespace(ms.getMetadata().getNamespace())
            .withLabels(dbLabels(ms))
            .build())
        .withNewSpec()
            .withClusterIP("None")     // "None" = headless (no kube-proxy, no VIP)
            .withSelector(Map.of("app", dbStatefulSetName(ms)))
            .withPorts(List.of(postgresPort))
        .endSpec()
        .build();
  }
}