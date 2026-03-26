package io.example.operator.dependentresource;

import java.util.List;
import java.util.Map;

import io.example.operator.customresource.MicroService;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.*;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import static io.example.operator.MicroServiceUtils.*;

/**
 * Manages a Kubernetes Ingress resource to expose the microservice externally.
 * Only created when spec.exposed=true (controlled by {@link ExposedCondition}).
 *
 * On OpenShift, consider replacing this with a Route resource or using the
 * openshift-route DependentResource from the JOSDK extensions.
 */
public class IngressDependentResource
    extends CRUDKubernetesDependentResource<Ingress, MicroService> {

  @Override
  protected Ingress desired(MicroService ms, Context<MicroService> context) {
    var name = ms.getMetadata().getName();
    var namespace = ms.getMetadata().getNamespace();
    String host = resolveHostname(ms);

    IngressRule rule = new IngressRuleBuilder()
        .withHost(host)
        .withNewHttp()
            .withPaths(new HTTPIngressPathBuilder()
                .withPath("/")
                .withPathType("Prefix")
                .withNewBackend()
                    .withNewService()
                        .withName(name)
                        .withNewPort().withNumber(80).endPort()
                    .endService()
                .endBackend()
                .build())
        .endHttp()
        .build();

    return new IngressBuilder()
        .withMetadata(new ObjectMetaBuilder()
            .withName(name)
            .withNamespace(namespace)
            .withLabels(appLabels(ms))
            .withAnnotations(Map.of(
                "nginx.ingress.kubernetes.io/rewrite-target", "/",
                "nginx.ingress.kubernetes.io/proxy-body-size", "10m"))
            .build())
        .withNewSpec()
            .withRules(List.of(rule))
        .endSpec()
        .build();
  }
}