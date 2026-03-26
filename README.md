# MicroService Operator

A production-ready Kubernetes / OpenShift Operator built with the
[Java Operator SDK](https://javaoperatorsdk.io/) that manages a complete microservice
application stack from a single Custom Resource.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Prerequisites](#prerequisites)
4. [Quick Start — running locally](#quick-start--running-locally)
5. [Custom Resource Reference](#custom-resource-reference)
6. [Status Reference](#status-reference)
7. [How the Operator Works](#how-the-operator-works)
8. [Deploy to a Cluster](#deploy-to-a-cluster)
9. [OpenShift Specifics](#openshift-specifics)
10. [Building the Container Image](#building-the-container-image)
11. [Publishing to OperatorHub](#publishing-to-operatorhub)
12. [Red Hat Certification (OpenShift)](#red-hat-certification-openshift)
13. [Troubleshooting](#troubleshooting)

---

## Overview

The MicroService Operator watches `MicroService` custom resources and ensures that everything
needed to run the described service exists in the cluster:

| Kubernetes resource          | When created                  | What it does                                    |
|------------------------------|-------------------------------|--------------------------------------------------|
| `ConfigMap`                  | Always                        | Holds `spec.config` key/values as env vars       |
| `Deployment`                 | Always                        | Runs the container image                         |
| `Service`                    | Always                        | ClusterIP routing to pods                        |
| `HorizontalPodAutoscaler`    | When `spec.autoscaling` is set| CPU-driven pod scaling                           |
| `Ingress` (or Route)         | When `spec.exposed: true`     | External HTTP/HTTPS access                       |

All owned resources are garbage-collected automatically when the `MicroService` CR is deleted.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  User                                                        │
│  kubectl apply -f payment-service.yaml                       │
└──────────────────────┬──────────────────────────────────────┘
                       │  MicroService CR
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  MicroServiceReconciler  (Java Operator SDK)                 │
│                                                              │
│  Workflow (dependency-ordered):                              │
│   1. ConfigMapDependentResource                              │
│   2. DeploymentDependentResource  ──depends on── 1          │
│   3. ServiceDependentResource     ──depends on── 2          │
│   4. HPADependentResource         ──depends on── 2          │
│      (only when spec.autoscaling != null)                    │
│   5. IngressDependentResource     ──depends on── 3          │
│      (only when spec.exposed: true)                          │
│                                                              │
│  Status updated after each reconcile loop                    │
└─────────────────────────────────────────────────────────────┘
```

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 17+ | Build the operator |
| Maven | 3.9+ | Build tool |
| Docker / Podman | any | Build container image |
| kubectl / oc | 1.26+ | Interact with the cluster |
| A running cluster | K8s 1.26+ or OCP 4.12+ | Target platform |

For publishing to OperatorHub you additionally need:

| Tool | Version | Purpose |
|------|---------|---------|
| `operator-sdk` | 1.34+ | Bundle generation and validation |
| `opm` | 1.38+ | Build/push catalog images |

---

## Quick Start — running locally

### 1. Clone and build

```bash
git clone <repo-url> microservice-operator
cd microservice-operator
mvn clean package -DskipTests
```

### 2. Apply the CRD

The CRD YAML is generated automatically at build time by the Fabric8 CRD generator.

```bash
kubectl apply -f target/classes/META-INF/fabric8/microservices.example.io-v1.yml
```

Verify it registered:

```bash
kubectl get crd microservices.example.io
```

### 3. Run the operator out-of-cluster

The operator picks up `~/.kube/config` automatically.

```bash
mvn exec:java -Dexec.mainClass="io.example.operator.MicroServiceOperator"
```

You should see log output similar to:

```
INFO  MicroServiceOperator - MicroService Operator started — probe server listening on :8080
INFO  io.javaoperatorsdk.operator.Operator - Operator SDK 5.3.2 (commit: ...) starting
INFO  io.javaoperatorsdk.operator.Operator - Registered reconciler: 'microservicereconciler' for resource: MicroService
```

### 4. Deploy a MicroService

```bash
kubectl apply -f k8s/microservice-sample.yaml
```

Check the status:

```bash
kubectl get microservice hello-service -o yaml
kubectl describe microservice hello-service
```

Watch the owned resources appear:

```bash
kubectl get deployment,service,configmap -l app=hello-service
```

### 5. Enable autoscaling and external access

Edit the CR to add autoscaling and expose it:

```bash
kubectl patch microservice hello-service --type=merge -p '
{
  "spec": {
    "exposed": true,
    "autoscaling": {
      "minReplicas": 1,
      "maxReplicas": 5,
      "targetCPUUtilizationPercentage": 70
    }
  }
}'
```

The operator will reconcile immediately and create an `HPA` and `Ingress`.

### 6. Turn off exposure

```bash
kubectl patch microservice hello-service --type=merge -p '{"spec":{"exposed":false}}'
```

The `Ingress` is deleted automatically.

---

## Custom Resource Reference

```yaml
apiVersion: example.io/v1
kind: MicroService
metadata:
  name: payment-service        # becomes the name of all owned resources
  namespace: production
spec:

  # ── Required ──────────────────────────────────────────────
  image: quay.io/acme/payment-service:2.1.0   # container image

  # ── Scaling ───────────────────────────────────────────────
  replicas: 2                  # desired replicas (ignored by HPA when autoscaling is set)
                               # default: 1

  # ── Networking ────────────────────────────────────────────
  containerPort: 8080          # port the container listens on; default: 8080

  exposed: true                # create Ingress/Route; default: false
  hostname: payment.apps.cluster.example.com   # optional; auto-derived when omitted

  # ── Application config ────────────────────────────────────
  # Mounted into the container as environment variables via a ConfigMap.
  config:
    LOG_LEVEL: INFO
    JAVA_OPTS: "-Xms256m -Xmx512m"
    DB_URL: "jdbc:postgresql://postgres:5432/payments"

  # ── Secrets ───────────────────────────────────────────────
  # Existing secrets injected as environment variables (envFrom).
  # Secrets must exist in the same namespace; missing secrets are tolerated (optional=true).
  envFromSecrets:
    - payment-db-credentials
    - payment-jwt-secret

  # ── Autoscaling ───────────────────────────────────────────
  # Omit this block entirely to disable autoscaling and delete any existing HPA.
  autoscaling:
    minReplicas: 2             # default: 1
    maxReplicas: 10            # default: 5
    targetCPUUtilizationPercentage: 60   # default: 70

  # ── Resource sizing ───────────────────────────────────────
  resources:
    requestsCpu: "250m"        # default: 100m
    requestsMemory: "256Mi"    # default: 128Mi
    limitsCpu: "1"             # default: 500m
    limitsMemory: "1Gi"        # default: 512Mi

  # ── Labels ────────────────────────────────────────────────
  # Merged onto all owned resources (Deployment, Service, ConfigMap, HPA, Ingress).
  extraLabels:
    team: payments
    cost-center: "cc-42"
    environment: production
```

### Field defaults summary

| Field | Default |
|-------|---------|
| `replicas` | `1` |
| `containerPort` | `8080` |
| `exposed` | `false` |
| `resources.requestsCpu` | `100m` |
| `resources.requestsMemory` | `128Mi` |
| `resources.limitsCpu` | `500m` |
| `resources.limitsMemory` | `512Mi` |
| `autoscaling.minReplicas` | `1` |
| `autoscaling.maxReplicas` | `5` |
| `autoscaling.targetCPUUtilizationPercentage` | `70` |

---

## Status Reference

```yaml
status:
  phase: RUNNING            # PENDING | RUNNING | DEGRADED | ERROR
  readyReplicas: 2          # ready pods observed in the Deployment
  url: "http://payment.apps.cluster.example.com"   # populated when exposed=true
  configMapName: payment-service-config
  message: "All replicas are ready"
  observedGeneration: 3     # CR generation when this status was last written
```

### Phase meanings

| Phase | Meaning |
|-------|---------|
| `PENDING` | Waiting for pods to pass readiness checks |
| `RUNNING` | All desired replicas are ready |
| `DEGRADED` | Some replicas are ready but not all |
| `ERROR` | Reconciliation failed; see `message` for details |

---

## How the Operator Works

### Reconciliation loop

1. A `MicroService` CR is created, updated, or a dependent resource is changed.
2. The JOSDK informer detects the event and enqueues a reconciliation.
3. `MicroServiceReconciler.reconcile()` is called.
4. The managed-dependents workflow runs each `DependentResource` in dependency order.
5. Each dependent compares its `desired()` state with the live cluster state and patches only when there is a diff.
6. The reconciler reads observed Deployment status (ready replicas) from the informer cache and patches the `MicroService` status subresource.

### Owner references

Every owned resource (ConfigMap, Deployment, Service, HPA, Ingress) carries an
`ownerReference` pointing to the parent `MicroService` CR. When the CR is deleted,
Kubernetes garbage-collects all owned resources automatically without any custom cleanup code.

### Conditional resources

`HorizontalPodAutoscaler` and `Ingress` use a `Condition` implementation as
`reconcilePrecondition` and `deletePostcondition`. When the condition is not met, the
workflow **deletes** any existing instance of that resource. This means:

- Setting `spec.exposed: false` → existing `Ingress` is deleted.
- Removing `spec.autoscaling` → existing `HPA` is deleted.

### HPA and replica safety

When `spec.autoscaling` is configured, the `Deployment` is created with `replicas: null`.
This deliberately leaves the replica count under the HPA's control and prevents the
operator from fighting the HPA on every reconcile cycle.

---

## Deploy to a Cluster

### Build and push the image

```bash
mvn clean package -DskipTests \
  jib:build \
  -Dimage=quay.io/yourorg/microservice-operator:1.0.0
```

### Apply manifests

```bash
# 1. Install the CRD
kubectl apply -f target/classes/META-INF/fabric8/microservices.example.io-v1.yml

# 2. Deploy the operator (ServiceAccount, ClusterRole, ClusterRoleBinding, Deployment)
#    Edit k8s/operator.yaml first to set the correct image
kubectl apply -f k8s/operator.yaml

# 3. Verify the operator pod is running
kubectl -n microservice-operator-system get pods

# 4. Deploy a sample MicroService in any namespace
kubectl apply -f k8s/microservice-sample.yaml
```

### Verify

```bash
# Watch operator logs
kubectl -n microservice-operator-system logs -f deploy/microservice-operator

# Check the CR status
kubectl get microservice -A

# Inspect all resources created for a CR
kubectl get all,ingress,configmap,hpa -l app=payment-service -n production
```

---

## OpenShift Specifics

### Route instead of Ingress

OpenShift uses `Route` objects for external exposure rather than `Ingress`. To support
Routes, swap `IngressDependentResource` for a Route-backed dependent resource using the
Fabric8 OpenShift client extension:

```xml
<!-- pom.xml — add alongside operator-framework -->
<dependency>
  <groupId>io.fabric8</groupId>
  <artifactId>openshift-client</artifactId>
</dependency>
```

Detect OpenShift at startup and register an alternative reconciler, or check for the
`route.openshift.io` API group in the reconcile loop.

### Security Context Constraints (SCC)

OpenShift enforces SCCs. The operator pod uses `runAsNonRoot: true` and drops all
capabilities — this is compatible with the `restricted-v2` SCC used by default on OCP 4.12+.

If your managed workloads need elevated permissions, create a custom SCC and bind it to
the workload's service account. Do **not** use `anyuid` in production.

### Running on OpenShift locally

```bash
oc login --token=<token> --server=https://api.cluster.example.com:6443
oc new-project microservice-operator-system
kubectl apply -f target/classes/META-INF/fabric8/microservices.example.io-v1.yml
kubectl apply -f k8s/operator.yaml
```

---

## Building the Container Image

The project uses [Jib](https://github.com/GoogleContainerTools/jib) — no `Dockerfile` needed.

### Build to local Docker daemon (for testing)

```bash
mvn clean package jib:dockerBuild -DskipTests
docker run --rm microservice-operator:latest
```

### Build and push to a registry

```bash
# Quay.io
mvn clean package jib:build -DskipTests \
  -Dimage=quay.io/yourorg/microservice-operator:1.0.0

# Docker Hub
mvn clean package jib:build -DskipTests \
  -Dimage=docker.io/yourorg/microservice-operator:1.0.0

# OpenShift internal registry
mvn clean package jib:build -DskipTests \
  -Dimage=image-registry.openshift-image-registry.svc:5000/microservice-operator-system/microservice-operator:1.0.0
```

### Multi-arch image (amd64 + arm64)

```bash
mvn clean package jib:build -DskipTests \
  -Dimage=quay.io/yourorg/microservice-operator:1.0.0 \
  -Djib.from.platforms=linux/amd64,linux/arm64
```

---

## Publishing to OperatorHub

OperatorHub (operatorhub.io and the OpenShift embedded hub) uses the
**Operator Lifecycle Manager (OLM)** packaging format. The steps below walk through
creating an OLM bundle, testing it, and submitting it for publication.

### Step 1 — Install the tooling

```bash
# operator-sdk (https://sdk.operatorframework.io/docs/installation/)
curl -Lo operator-sdk \
  https://github.com/operator-framework/operator-sdk/releases/latest/download/operator-sdk_linux_amd64
chmod +x operator-sdk && sudo mv operator-sdk /usr/local/bin/

# opm — OLM package manager
curl -Lo opm \
  https://github.com/operator-framework/operator-registry/releases/latest/download/linux-amd64-opm
chmod +x opm && sudo mv opm /usr/local/bin/
```

### Step 2 — Generate the OLM bundle

An OLM bundle contains:
- The CRD YAML
- A `ClusterServiceVersion` (CSV) — the main OLM manifest describing the operator
- Bundle metadata (`metadata/annotations.yaml`)

```bash
cd microservice-operator

# Set your operator image and version
OPERATOR_IMAGE=quay.io/yourorg/microservice-operator:1.0.0
VERSION=1.0.0

operator-sdk generate bundle \
  --version "$VERSION" \
  --package microservice-operator \
  --channels stable,alpha \
  --default-channel stable \
  --output-dir bundle \
  --deploy-dir k8s \
  --crds-dir target/classes/META-INF/fabric8
```

This creates:

```
bundle/
├── manifests/
│   ├── microservice-operator.clusterserviceversion.yaml   ← main OLM manifest
│   └── microservices.example.io-v1.yml                   ← CRD
└── metadata/
    └── annotations.yaml                                   ← channel/package info
```

### Step 3 — Edit the ClusterServiceVersion (CSV)

The generated CSV needs human-readable metadata before submission. Open
`bundle/manifests/microservice-operator.clusterserviceversion.yaml` and fill in:

```yaml
metadata:
  name: microservice-operator.v1.0.0
  annotations:
    # OperatorHub display metadata
    capabilities: Full Lifecycle       # Basic Install | Seamless Upgrades | Full Lifecycle | Deep Insights | Auto Pilot
    categories: Application Runtime
    description: Manages a full microservice stack (Deployment, Service, ConfigMap, HPA, Ingress) from a single MicroService CR.
    containerImage: quay.io/yourorg/microservice-operator:1.0.0
    createdAt: "2026-03-26"
    support: yourorg
    repository: https://github.com/yourorg/microservice-operator

spec:
  displayName: MicroService Operator
  description: |
    ## Overview
    The MicroService Operator turns a single `MicroService` custom resource into a
    complete running application stack on Kubernetes and OpenShift.

    ## Features
    - Manages Deployment, Service, ConfigMap, HPA, and Ingress from one CR
    - CPU-based horizontal autoscaling via HPA v2
    - External exposure toggle (Ingress / OpenShift Route)
    - Status reporting: phase, readyReplicas, URL, observedGeneration

  version: 1.0.0
  maturity: stable

  # Operator icon (base64-encoded PNG/SVG)
  icon:
    - base64data: <base64-encoded-icon>
      mediatype: image/png

  # Links shown on the OperatorHub tile
  links:
    - name: Documentation
      url: https://github.com/yourorg/microservice-operator
    - name: Source Code
      url: https://github.com/yourorg/microservice-operator

  maintainers:
    - name: Your Name
      email: you@yourorg.com

  # Permissions the operator needs
  install:
    spec:
      clusterPermissions:
        - rules:
            - apiGroups: ["example.io"]
              resources: ["microservices", "microservices/status", "microservices/finalizers"]
              verbs: ["*"]
            - apiGroups: ["apps"]
              resources: ["deployments"]
              verbs: ["*"]
            - apiGroups: [""]
              resources: ["services", "configmaps", "pods"]
              verbs: ["*"]
            - apiGroups: ["autoscaling"]
              resources: ["horizontalpodautoscalers"]
              verbs: ["*"]
            - apiGroups: ["networking.k8s.io"]
              resources: ["ingresses"]
              verbs: ["*"]
            - apiGroups: ["coordination.k8s.io"]
              resources: ["leases"]
              verbs: ["*"]
          serviceAccountName: microservice-operator

  # OwnedCRDs shown on the OperatorHub detail page
  customresourcedefinitions:
    owned:
      - name: microservices.example.io
        version: v1
        kind: MicroService
        displayName: MicroService
        description: Manages a full microservice application stack
```

### Step 4 — Validate the bundle

```bash
operator-sdk bundle validate ./bundle \
  --select-optional name=operatorhub
```

Fix any warnings or errors reported. Common issues:

| Issue | Fix |
|-------|-----|
| Missing `capabilities` annotation | Add to CSV metadata annotations |
| Missing icon | Add base64-encoded PNG to `spec.icon` |
| `description` too short | Expand `spec.description` in the CSV |
| CRD validation errors | Ensure CRD has `spec.validation.openAPIV3Schema` (generated by Fabric8) |

### Step 5 — Build and push the bundle image

```bash
BUNDLE_IMAGE=quay.io/yourorg/microservice-operator-bundle:v1.0.0

operator-sdk bundle validate ./bundle   # must pass before building

docker build -f bundle.Dockerfile -t "$BUNDLE_IMAGE" .
docker push "$BUNDLE_IMAGE"
```

### Step 6 — Test with OLM locally

Install OLM on your test cluster (if not already present):

```bash
operator-sdk olm install
```

Run the bundle end-to-end using operator-sdk:

```bash
operator-sdk run bundle "$BUNDLE_IMAGE" \
  --namespace microservice-operator-system \
  --timeout 5m
```

This installs the CRD, creates OLM subscription objects, and starts the operator pod.
Test your CR:

```bash
kubectl apply -f k8s/microservice-sample.yaml
kubectl get microservice -A
```

Clean up after testing:

```bash
operator-sdk cleanup microservice-operator \
  --namespace microservice-operator-system
```

### Step 7 — Build a catalog image (optional but recommended)

A catalog image lets you host a private OperatorHub catalog in your cluster.

```bash
CATALOG_IMAGE=quay.io/yourorg/microservice-operator-catalog:latest

opm init microservice-operator \
  --default-channel=stable \
  --description=./README.md \
  --output yaml > catalog/operator.yaml

opm render "$BUNDLE_IMAGE" --output=yaml >> catalog/operator.yaml

cat >> catalog/operator.yaml << 'EOF'
---
schema: olm.channel
package: microservice-operator
name: stable
entries:
  - name: microservice-operator.v1.0.0
EOF

opm validate catalog/
opm alpha render-template basic catalog/ -o yaml | \
  docker build -f catalog.Dockerfile -t "$CATALOG_IMAGE" .
docker push "$CATALOG_IMAGE"
```

Apply the catalog source to your cluster:

```yaml
# catalog-source.yaml
apiVersion: operators.coreos.com/v1alpha1
kind: CatalogSource
metadata:
  name: microservice-operator-catalog
  namespace: olm
spec:
  sourceType: grpc
  image: quay.io/yourorg/microservice-operator-catalog:latest
  displayName: MicroService Operator Catalog
  publisher: YourOrg
  updateStrategy:
    registryPoll:
      interval: 30m
```

```bash
kubectl apply -f catalog-source.yaml
# Operator now appears in OperatorHub UI
```

### Step 8 — Submit to community-operators (OperatorHub.io)

[OperatorHub.io](https://operatorhub.io) is fed from the
[community-operators](https://github.com/k8s-operatorhub/community-operators) GitHub repository.

```bash
# Fork and clone the community-operators repo
git clone https://github.com/<your-github-username>/community-operators
cd community-operators

# Create your operator directory
mkdir -p operators/microservice-operator/1.0.0
cp -r /path/to/microservice-operator/bundle/manifests \
       operators/microservice-operator/1.0.0/
cp -r /path/to/microservice-operator/bundle/metadata \
       operators/microservice-operator/1.0.0/

# Add a ci.yaml to specify review settings
cat > operators/microservice-operator/ci.yaml << 'EOF'
addReviewers: true
reviewers:
  - your-github-username
EOF

# Commit and open a PR
git checkout -b add-microservice-operator-v1.0.0
git add operators/microservice-operator/
git commit -m "operator microservice-operator (1.0.0)"
git push origin add-microservice-operator-v1.0.0
```

Open the PR against `k8s-operatorhub/community-operators`. The CI pipeline will:

1. Run `operator-sdk bundle validate` against your bundle
2. Run scorecard tests against a live cluster
3. Request a review from the community-operators maintainers

Typical review time: 1–2 weeks. Track progress in the PR comments.

---

## Red Hat Certification (OpenShift)

To have your operator appear in the **Red Hat OpenShift OperatorHub** (the embedded hub in
every OCP cluster), you must go through Red Hat's certification process.

### Step 1 — Create a Red Hat Partner account

Register at [connect.redhat.com](https://connect.redhat.com) → **Technology Partner** →
**Certify your Operator**.

### Step 2 — Create a project in the Red Hat Partner Portal

1. Log in to [connect.redhat.com/manage/projects](https://connect.redhat.com/manage/projects)
2. Click **Create Project** → **Operator Bundle Image**
3. Note the **project ID** (e.g. `ospid-xxxxxxxx`) — you will prefix your bundle image with
   `registry.connect.redhat.com/yourorg/microservice-operator-bundle`

### Step 3 — Certify your operator image

Your operator container image itself must also be certified before the bundle can pass.

```bash
# Submit the operator image for scanning
preflight check container quay.io/yourorg/microservice-operator:1.0.0 \
  --pyxis-api-token <YOUR_API_TOKEN>
```

[preflight](https://github.com/redhat-openshift-ecosystem/openshift-preflight) is Red Hat's
CLI tool for running certification checks locally before submission.

### Step 4 — Run preflight checks on the bundle

```bash
preflight check operator bundle/  \
  --pyxis-api-token <YOUR_API_TOKEN> \
  --certification-project-id ospid-xxxxxxxx
```

All checks must pass before submission. Common failures:

| Check | Typical fix |
|-------|-------------|
| `RequiredAnnotations` | Ensure all required CSV annotations are present |
| `CertifiedOperatorContainerImages` | The operator image itself must be certified first |
| `SemVer` | Version in CSV must follow strict semver |
| `NoProhibitedPackages` | Remove prohibited packages from the container image |

### Step 5 — Submit via the certified-operators repo

Red Hat's certified-operators pipeline is at
[github.com/redhat-openshift-ecosystem/certified-operators](https://github.com/redhat-openshift-ecosystem/certified-operators).

```bash
git clone https://github.com/<you>/certified-operators
cd certified-operators

mkdir -p operators/microservice-operator/v1.0.0
cp -r /path/to/microservice-operator/bundle/manifests \
       operators/microservice-operator/v1.0.0/
cp -r /path/to/microservice-operator/bundle/metadata \
       operators/microservice-operator/v1.0.0/

# Required: add a ci.yaml referencing your project ID
cat > operators/microservice-operator/ci.yaml << 'EOF'
cert_project_id: ospid-xxxxxxxx
EOF

git checkout -b add-microservice-operator-v1.0.0
git add operators/microservice-operator/
git commit -m "operator microservice-operator (1.0.0)"
git push origin add-microservice-operator-v1.0.0
```

Open the PR. Red Hat's automated pipeline re-runs all preflight checks. Once it passes,
a Red Hat engineer merges it and the operator appears on
[catalog.redhat.com](https://catalog.redhat.com) and the embedded OpenShift OperatorHub.

---

## Troubleshooting

### Operator pod is not starting

```bash
kubectl -n microservice-operator-system describe pod -l app=microservice-operator
kubectl -n microservice-operator-system logs deploy/microservice-operator --previous
```

Common causes: missing RBAC permissions, wrong image tag, CRD not installed.

### MicroService CR stuck in PENDING

```bash
kubectl describe microservice <name> -n <namespace>
kubectl get events -n <namespace> --sort-by='.lastTimestamp'
kubectl get deployment <name> -n <namespace> -o yaml
```

Check if pods are failing to pull the image or pass readiness probes.

### Ingress not created despite `exposed: true`

```bash
kubectl get microservice <name> -n <namespace> -o jsonpath='{.status}'
kubectl -n microservice-operator-system logs deploy/microservice-operator | grep -i ingress
```

Ensure an Ingress controller is installed in the cluster and the namespace has no network
policies blocking it.

### HPA not scaling

```bash
kubectl get hpa <name> -n <namespace>
kubectl describe hpa <name> -n <namespace>
```

The metrics-server must be installed for HPA v2 CPU metrics to work:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

### ConfigMap changes not reflected in pods

The `Deployment` does not automatically restart when the `ConfigMap` changes. To trigger
a rolling restart:

```bash
kubectl rollout restart deployment/<name> -n <namespace>
```

For automatic restarts on config changes, consider adding a config hash as a pod annotation
in `DeploymentDependentResource.desired()`.

### Running operator-sdk bundle validate fails

```
[ERRORS]  bundle.v1.0.0 ... constraints not met
```

The most common fix is to ensure the CSV `spec.installModes` lists at least one
supported mode:

```yaml
spec:
  installModes:
    - type: OwnNamespace
      supported: true
    - type: SingleNamespace
      supported: true
    - type: MultiNamespace
      supported: false
    - type: AllNamespaces
      supported: true
```