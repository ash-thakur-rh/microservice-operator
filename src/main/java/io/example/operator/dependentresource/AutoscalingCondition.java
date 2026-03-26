package io.example.operator.dependentresource;

import io.example.operator.customresource.MicroService;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.processing.dependent.workflow.Condition;

/**
 * Reconcile precondition: only create/update the HPA when spec.autoscaling is configured.
 * When the condition becomes false the HPA is deleted by the workflow.
 */
public class AutoscalingCondition
    implements Condition<HorizontalPodAutoscaler, MicroService> {

  @Override
  public boolean isMet(
      DependentResource<HorizontalPodAutoscaler, MicroService> dependentResource,
      MicroService primary,
      Context<MicroService> context) {
    return primary.getSpec().getAutoscaling() != null;
  }
}