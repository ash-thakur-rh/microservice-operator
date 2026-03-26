package io.example.operator.dependentresource;

import io.example.operator.customresource.MicroService;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.processing.dependent.workflow.Condition;

/**
 * Reconcile precondition for all database-related dependent resources.
 * Database resources (Secret, Service, StatefulSet) are only created when
 * spec.database is configured. When removed, the operator deletes them automatically.
 */
public class DatabaseCondition implements Condition<Object, MicroService> {

  @Override
  public boolean isMet(
      DependentResource<Object, MicroService> dependentResource,
      MicroService primary,
      Context<MicroService> context) {
    return primary.getSpec().getDatabase() != null;
  }
}