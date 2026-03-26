package io.example.operator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.example.operator.customresource.MicroService;
import io.example.operator.customresource.MicroServiceStatus;
import io.example.operator.customresource.MicroServiceStatus.Phase;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.example.operator.dependentresource.*;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;

/**
 * Core reconciler for the MicroService custom resource.
 *
 * <p>Uses the managed-dependents workflow to keep the following resources in sync:
 *
 * <pre>
 * Always:
 *   1. ConfigMap                  — application config as env vars
 *
 * When spec.database is set (operator-provisioned PostgreSQL):
 *   2. DatabaseSecretDependentResource       — auto-generated credentials Secret
 *   3. DatabaseHeadlessServiceDependentResource — headless Service for StatefulSet DNS
 *   4. DatabaseServiceDependentResource      — ClusterIP Service for app → DB traffic
 *   5. DatabaseStatefulSetDependentResource  — PostgreSQL StatefulSet + PVC
 *
 * Always (after ConfigMap, after DB secret if database is enabled):
 *   6. Deployment                 — runs the microservice container
 *   7. Service                    — ClusterIP for in-cluster traffic to the app
 *
 * Conditional:
 *   8. HPA                        — CPU autoscaling (when spec.autoscaling is set)
 *   9. Ingress                    — external access (when spec.exposed: true)
 * </pre>
 *
 * <p>The reconciler only writes the status subresource; all resource mutations are
 * delegated to the dependent resources listed above.
 */
@Workflow(
    dependents = {
      // ── Application config ──────────────────────────────────────────────────────
      @Dependent(type = ConfigMapDependentResource.class),

      // ── Operator-provisioned database (all conditional on spec.database != null) ─
      @Dependent(
          type = DatabaseSecretDependentResource.class,
          reconcilePrecondition = DatabaseCondition.class),
      @Dependent(
          type = DatabaseHeadlessServiceDependentResource.class,
          dependsOn = "DatabaseSecretDependentResource",
          reconcilePrecondition = DatabaseCondition.class),
      @Dependent(
          type = DatabaseServiceDependentResource.class,
          dependsOn = "DatabaseSecretDependentResource",
          reconcilePrecondition = DatabaseCondition.class),
      @Dependent(
          type = DatabaseStatefulSetDependentResource.class,
          dependsOn = {"DatabaseHeadlessServiceDependentResource",
                       "DatabaseServiceDependentResource"},
          reconcilePrecondition = DatabaseCondition.class),

      // ── Application workload ─────────────────────────────────────────────────────
      @Dependent(
          type = DeploymentDependentResource.class,
          dependsOn = "ConfigMapDependentResource"),
      @Dependent(
          type = ServiceDependentResource.class,
          dependsOn = "DeploymentDependentResource"),

      // ── Optional features ────────────────────────────────────────────────────────
      @Dependent(
          type = HorizontalPodAutoscalerDependentResource.class,
          dependsOn = "DeploymentDependentResource",
          reconcilePrecondition = AutoscalingCondition.class,
          deletePostcondition = AutoscalingCondition.class),
      @Dependent(
          type = IngressDependentResource.class,
          dependsOn = "ServiceDependentResource",
          reconcilePrecondition = ExposedCondition.class,
          deletePostcondition = ExposedCondition.class)
    })
@ControllerConfiguration
public class MicroServiceReconciler implements Reconciler<MicroService>, Cleaner<MicroService> {

  private static final Logger log = LoggerFactory.getLogger(MicroServiceReconciler.class);

  @Override
  public UpdateControl<MicroService> reconcile(MicroService ms, Context<MicroService> context)
      throws Exception {
    log.info("Reconciling MicroService {}/{}", ms.getMetadata().getNamespace(),
        ms.getMetadata().getName());

    // Read observed state from the secondary (cached) Deployment
    int readyReplicas = context.getSecondaryResource(Deployment.class)
        .map(d -> d.getStatus() != null ? d.getStatus().getReadyReplicas() : 0)
        .orElse(0);

    // If a database is managed, it must have at least one ready replica before we report RUNNING
    boolean dbReady = true;
    if (ms.getSpec().getDatabase() != null) {
      int dbReadyReplicas = context.getSecondaryResource(StatefulSet.class)
          .map(s -> s.getStatus() != null ? s.getStatus().getReadyReplicas() : 0)
          .orElse(0);
      dbReady = dbReadyReplicas >= 1;
    }

    int desired = ms.getSpec().getReplicas();
    Phase phase = (dbReady && readyReplicas >= desired) ? Phase.RUNNING : Phase.PENDING;

    MicroService statusPatch = buildStatusPatch(ms, phase, readyReplicas);
    return UpdateControl.patchStatus(statusPatch);
  }

  @Override
  public ErrorStatusUpdateControl<MicroService> updateErrorStatus(
      MicroService ms, Context<MicroService> context, Exception e) {
    log.error("Error reconciling MicroService {}/{}: {}",
        ms.getMetadata().getNamespace(), ms.getMetadata().getName(), e.getMessage(), e);
    MicroService statusPatch = buildStatusPatch(ms, Phase.ERROR, 0);
    statusPatch.getStatus().setMessage("Reconciliation error: " + e.getMessage());
    return ErrorStatusUpdateControl.patchStatus(statusPatch);
  }

  @Override
  public DeleteControl cleanup(MicroService ms, Context<MicroService> context) {
    log.info("Cleaning up MicroService {}/{}", ms.getMetadata().getNamespace(),
        ms.getMetadata().getName());
    // Dependent resources are garbage-collected automatically via owner references.
    return DeleteControl.defaultDelete();
  }

  // --- helpers ---

  private MicroService buildStatusPatch(MicroService ms, Phase phase, int readyReplicas) {
    MicroServiceStatus status = new MicroServiceStatus();
    status.setPhase(phase);
    status.setReadyReplicas(readyReplicas);
    status.setObservedGeneration(ms.getMetadata().getGeneration());
    status.setConfigMapName(MicroServiceUtils.configMapName(ms));

    if (ms.getSpec().isExposed()) {
      String host = MicroServiceUtils.resolveHostname(ms);
      status.setUrl("http://" + host);
    }

    String msg = switch (phase) {
      case RUNNING -> "All replicas are ready";
      case PENDING -> "Waiting for pods to become ready";
      case ERROR -> status.getMessage() != null ? status.getMessage() : "Unknown error";
      default -> "";
    };
    status.setMessage(msg);

    MicroService patch = new MicroService();
    patch.setMetadata(new ObjectMetaBuilder()
        .withName(ms.getMetadata().getName())
        .withNamespace(ms.getMetadata().getNamespace())
        .build());
    patch.setStatus(status);
    return patch;
  }
}