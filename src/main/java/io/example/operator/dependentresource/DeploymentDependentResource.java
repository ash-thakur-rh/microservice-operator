package io.example.operator.dependentresource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.example.operator.customresource.MicroService;
import io.example.operator.customresource.ResourceRequirementsSpec;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import static io.example.operator.MicroServiceUtils.*;

/**
 * Manages the Deployment for the microservice container.
 *
 * Features:
 * - Injects config from the owned ConfigMap via envFrom
 * - Mounts secrets listed in spec.envFromSecrets
 * - Sets resource requests/limits
 * - Configures readiness and liveness probes on the container port
 * - Respects the replica count (overridden by HPA when autoscaling is enabled)
 */
public class DeploymentDependentResource
    extends CRUDKubernetesDependentResource<Deployment, MicroService> {

  @Override
  protected Deployment desired(MicroService ms, Context<MicroService> context) {
    var spec = ms.getSpec();
    var name = ms.getMetadata().getName();
    var namespace = ms.getMetadata().getNamespace();
    Map<String, String> labels = appLabels(ms);

    // Build envFrom sources: always include the app's own ConfigMap
    List<EnvFromSource> envFromSources = new ArrayList<>();
    envFromSources.add(new EnvFromSourceBuilder()
        .withConfigMapRef(new ConfigMapEnvSourceBuilder()
            .withName(configMapName(ms))
            .build())
        .build());

    // When the operator manages a database, inject the auto-generated credentials secret.
    // This supplies SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD
    // automatically — no manual kubectl create secret required.
    if (spec.getDatabase() != null) {
      envFromSources.add(new EnvFromSourceBuilder()
          .withSecretRef(new SecretEnvSourceBuilder()
              .withName(dbSecretName(ms))
              .withOptional(false) // fail fast if secret is missing
              .build())
          .build());
    }

    // Mount any additional user-managed secrets as env sources
    if (spec.getEnvFromSecrets() != null) {
      for (String secretName : spec.getEnvFromSecrets()) {
        envFromSources.add(new EnvFromSourceBuilder()
            .withSecretRef(new SecretEnvSourceBuilder()
                .withName(secretName)
                .withOptional(true)
                .build())
            .build());
      }
    }

    // Resource requests and limits
    ResourceRequirementsSpec res = Optional.ofNullable(spec.getResources())
        .orElse(new ResourceRequirementsSpec());
    ResourceRequirements k8sResources = new ResourceRequirementsBuilder()
        .withRequests(Map.of(
            "cpu", new Quantity(res.getRequestsCpu()),
            "memory", new Quantity(res.getRequestsMemory())))
        .withLimits(Map.of(
            "cpu", new Quantity(res.getLimitsCpu()),
            "memory", new Quantity(res.getLimitsMemory())))
        .build();

    // HTTP liveness / readiness probes — paths come from spec so each microservice
    // can declare its own health endpoints without changing the operator.
    // Defaults target Spring Boot Actuator; override in the CR for other frameworks.
    Probe livenessProbe = new ProbeBuilder()
        .withHttpGet(new HTTPGetActionBuilder()
            .withPath(spec.getLivenessPath())
            .withPort(new IntOrString(spec.getContainerPort()))
            .build())
        .withInitialDelaySeconds(15)
        .withPeriodSeconds(10)
        .withFailureThreshold(3)
        .build();

    Probe readinessProbe = new ProbeBuilder()
        .withHttpGet(new HTTPGetActionBuilder()
            .withPath(spec.getReadinessPath())
            .withPort(new IntOrString(spec.getContainerPort()))
            .build())
        .withInitialDelaySeconds(5)
        .withPeriodSeconds(5)
        .withFailureThreshold(3)
        .build();

    // Replica count: when HPA is active the Deployment replicas field is owned by the HPA.
    // We set it only when there is no autoscaling spec so we don't fight the HPA.
    Integer replicas = spec.getAutoscaling() == null ? spec.getReplicas() : null;

    return new DeploymentBuilder()
        .withMetadata(new ObjectMetaBuilder()
            .withName(name)
            .withNamespace(namespace)
            .withLabels(labels)
            .build())
        .withNewSpec()
            .withReplicas(replicas)
            .withNewSelector()
                .withMatchLabels(Map.of("app", name))
            .endSelector()
            .withNewTemplate()
                .withNewMetadata()
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName(name)
                        .withImage(spec.getImage())
                        .addNewPort()
                            .withContainerPort(spec.getContainerPort())
                            .withName("http")
                        .endPort()
                        .withEnvFrom(envFromSources)
                        .withResources(k8sResources)
                        .withLivenessProbe(livenessProbe)
                        .withReadinessProbe(readinessProbe)
                    .endContainer()
                .endSpec()
            .endTemplate()
        .endSpec()
        .build();
  }
}