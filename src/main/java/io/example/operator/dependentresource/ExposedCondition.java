package io.example.operator.dependentresource;

import io.example.operator.customresource.MicroService;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.processing.dependent.workflow.Condition;

/**
 * Reconcile precondition: only create/update the Ingress when spec.exposed=true.
 * When exposed is set back to false the operator deletes the Ingress automatically.
 */
public class ExposedCondition implements Condition<Ingress, MicroService> {

  @Override
  public boolean isMet(
      DependentResource<Ingress, MicroService> dependentResource,
      MicroService primary,
      Context<MicroService> context) {
    return primary.getSpec().isExposed();
  }
}