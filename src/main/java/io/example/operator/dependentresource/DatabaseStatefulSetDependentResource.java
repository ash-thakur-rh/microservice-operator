package io.example.operator.dependentresource;

import java.util.List;
import java.util.Map;

import io.example.operator.customresource.DatabaseSpec;
import io.example.operator.customresource.MicroService;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import static io.example.operator.MicroServiceUtils.*;

/**
 * Manages a PostgreSQL StatefulSet with an attached PersistentVolumeClaim.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>Data is stored in a named {@code volumeClaimTemplate} — Kubernetes provisions and
 *       owns the PVC; it survives pod restarts and StatefulSet re-creation.</li>
 *   <li>A {@code subPath: postgres} is used inside the volume mount to avoid the
 *       "data directory has wrong ownership" error that occurs when mounting an empty volume
 *       directly to {@code /var/lib/postgresql/data}.</li>
 *   <li>Credentials are injected from the operator-generated Secret via {@code envFrom}
 *       — no hardcoded passwords anywhere in the manifest.</li>
 *   <li>Liveness uses {@code pg_isready} exec probe; readiness also uses {@code pg_isready}
 *       which only returns OK when PostgreSQL is fully accepting connections.</li>
 *   <li>{@code volumeClaimTemplates} are immutable after StatefulSet creation. Changing
 *       {@code storageSize} requires deleting the StatefulSet (data PVC is preserved) and
 *       letting the operator recreate it.</li>
 * </ul>
 */
public class DatabaseStatefulSetDependentResource
    extends CRUDKubernetesDependentResource<StatefulSet, MicroService> {

  @Override
  protected StatefulSet desired(MicroService ms, Context<MicroService> context) {
    DatabaseSpec db = ms.getSpec().getDatabase();
    String name = dbStatefulSetName(ms);
    String namespace = ms.getMetadata().getNamespace();
    Map<String, String> labels = dbLabels(ms);

    // pg_isready exit codes: 0 = accepting, 1 = rejecting, 2 = no response
    ExecAction pgIsReady = new ExecActionBuilder()
        .withCommand("pg_isready",
            "-U", db.getUsername(),
            "-d", db.getDatabaseName(),
            "-h", "127.0.0.1")
        .build();

    Probe livenessProbe = new ProbeBuilder()
        .withExec(pgIsReady)
        .withInitialDelaySeconds(30)
        .withPeriodSeconds(10)
        .withFailureThreshold(6)
        .build();

    Probe readinessProbe = new ProbeBuilder()
        .withExec(pgIsReady)
        .withInitialDelaySeconds(5)
        .withPeriodSeconds(5)
        .withFailureThreshold(3)
        .build();

    // PVC template — created by Kubernetes per pod; survives pod and StatefulSet deletion
    PersistentVolumeClaim pvcTemplate = new PersistentVolumeClaimBuilder()
        .withNewMetadata()
            .withName("data")
        .endMetadata()
        .withNewSpec()
            .withAccessModes(List.of("ReadWriteOnce"))
            .withStorageClassName(db.getStorageClass()) // null → cluster default
            .withNewResources()
                .withRequests(Map.of("storage", new Quantity(db.getStorageSize())))
            .endResources()
        .endSpec()
        .build();

    return new StatefulSetBuilder()
        .withMetadata(new ObjectMetaBuilder()
            .withName(name)
            .withNamespace(namespace)
            .withLabels(labels)
            .build())
        .withNewSpec()
            // ServiceName must match the headless service for stable pod DNS
            .withServiceName(dbHeadlessServiceName(ms))
            .withReplicas(1)
            .withNewSelector()
                .withMatchLabels(Map.of("app", name))
            .endSelector()
            .withNewTemplate()
                .withNewMetadata()
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("postgres")
                        .withImage(db.getImage())
                        .addNewPort()
                            .withContainerPort(5432)
                            .withName("postgres")
                        .endPort()
                        // Credentials from the operator-generated Secret
                        .addNewEnvFrom()
                            .withNewSecretRef()
                                .withName(dbSecretName(ms))
                            .endSecretRef()
                        .endEnvFrom()
                        .addNewVolumeMount()
                            .withName("data")
                            .withMountPath("/var/lib/postgresql/data")
                            // subPath avoids the "wrong ownership" error on fresh volumes
                            .withSubPath("postgres")
                        .endVolumeMount()
                        .withLivenessProbe(livenessProbe)
                        .withReadinessProbe(readinessProbe)
                        .withNewResources()
                            .withRequests(Map.of(
                                "cpu",    new Quantity("100m"),
                                "memory", new Quantity("256Mi")))
                            .withLimits(Map.of(
                                "cpu",    new Quantity("500m"),
                                "memory", new Quantity("512Mi")))
                        .endResources()
                    .endContainer()
                .endSpec()
            .endTemplate()
            .withVolumeClaimTemplates(pvcTemplate)
        .endSpec()
        .build();
  }
}