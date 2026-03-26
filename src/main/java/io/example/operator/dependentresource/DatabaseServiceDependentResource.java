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
 * Manages two Services for the PostgreSQL StatefulSet:
 *
 * <ol>
 *   <li><b>Headless service</b> ({@code <name>-db-headless}) — required by the StatefulSet
 *       to assign stable DNS names to individual pods ({@code <pod>.<headless-svc>.<ns>.svc.cluster.local}).
 *   <li><b>ClusterIP service</b> ({@code <name>-db}) — used by the application to connect to
 *       PostgreSQL. The app always talks to this stable DNS name regardless of pod restarts.
 * </ol>
 *
 * <p>Only the ClusterIP service is needed by the application; the headless service is a
 * Kubernetes requirement for StatefulSets.
 */
public class DatabaseServiceDependentResource
    extends CRUDKubernetesDependentResource<Service, MicroService> {

  @Override
  protected Service desired(MicroService ms, Context<MicroService> context) {
    // This resource manages the ClusterIP Service (the app-facing one).
    // The headless Service is created separately in DatabaseHeadlessServiceDependentResource.
    ServicePort postgresPort = new ServicePortBuilder()
        .withName("postgres")
        .withPort(5432)
        .withProtocol("TCP")
        .build();

    return new ServiceBuilder()
        .withMetadata(new ObjectMetaBuilder()
            .withName(dbServiceName(ms))
            .withNamespace(ms.getMetadata().getNamespace())
            .withLabels(dbLabels(ms))
            .build())
        .withNewSpec()
            .withSelector(Map.of("app", dbStatefulSetName(ms)))
            .withPorts(List.of(postgresPort))
            .withType("ClusterIP")
        .endSpec()
        .build();
  }
}