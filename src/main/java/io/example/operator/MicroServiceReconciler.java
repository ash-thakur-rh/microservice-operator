package io.example.operator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.example.operator.customresource.MicroService;
import io.example.operator.customresource.MicroServiceStatus;
import io.example.operator.customresource.MicroServiceStatus.Phase;
import io.example.operator.dependentresource.*;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.reconciler.*;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;

/**
 * Core reconciler for the MicroService custom resource.
 *
 * <p>Uses the managed-dependents workflow to keep the following resources in sync:
 * <ol>
 *   <li>ConfigMap          — application configuration as environment variables</li>
 *   <li>Deployment         — runs the container image</li>
 *   <li>Service            — internal ClusterIP routing</li>
 *   <li>HPA                — CPU-based horizontal autoscaling (conditional)</li>
 *   <li>Ingress            — external HTTP access (conditional)</li>
 * </ol>
 *
 * <p>The reconciler only writes the status subresource; all other resource mutations
 * are delegated to the dependent resources above.
 */
@Workflow(
    dependents = {
      @Dependent(type = ConfigMapDependentResource.class),
      @Dependent(
          type = DeploymentDependentResource.class,
          dependsOn = "ConfigMapDependentResource"),
      @Dependent(
          type = ServiceDependentResource.class,
          dependsOn = "DeploymentDependentResource"),
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

    int desired = ms.getSpec().getReplicas();
    Phase phase = (readyReplicas >= desired) ? Phase.RUNNING : Phase.PENDING;

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