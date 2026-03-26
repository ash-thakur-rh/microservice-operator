package io.example.operator.dependentresource;

import io.example.operator.customresource.AutoscalingSpec;
import io.example.operator.customresource.MicroService;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.*;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import java.util.List;

import static io.example.operator.MicroServiceUtils.appLabels;

/**
 * Manages a HorizontalPodAutoscaler (HPA v2) for the microservice Deployment.
 * This resource is only reconciled when spec.autoscaling is set
 * (controlled by {@link AutoscalingCondition}).
 */
public class HorizontalPodAutoscalerDependentResource
    extends CRUDKubernetesDependentResource<HorizontalPodAutoscaler, MicroService> {

  @Override
  protected HorizontalPodAutoscaler desired(MicroService ms, Context<MicroService> context) {
    var name = ms.getMetadata().getName();
    var namespace = ms.getMetadata().getNamespace();
    AutoscalingSpec autoscaling = ms.getSpec().getAutoscaling();

    MetricSpec cpuMetric = new MetricSpecBuilder()
        .withType("Resource")
        .withNewResource()
            .withName("cpu")
            .withNewTarget()
                .withType("Utilization")
                .withAverageUtilization(autoscaling.getTargetCPUUtilizationPercentage())
            .endTarget()
        .endResource()
        .build();

    return new HorizontalPodAutoscalerBuilder()
        .withMetadata(new ObjectMetaBuilder()
            .withName(name)
            .withNamespace(namespace)
            .withLabels(appLabels(ms))
            .build())
        .withNewSpec()
            .withMinReplicas(autoscaling.getMinReplicas())
            .withMaxReplicas(autoscaling.getMaxReplicas())
            .withNewScaleTargetRef()
                .withApiVersion("apps/v1")
                .withKind("Deployment")
                .withName(name)
            .endScaleTargetRef()
            .withMetrics(List.of(cpuMetric))
        .endSpec()
        .build();
  }
}