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
needed to run the described service exists in the cluster — including its database.

| Kubernetes resource             | When created                       | What it does                                         |
|---------------------------------|------------------------------------|------------------------------------------------------|
| `ConfigMap`                     | Always                             | Holds `spec.config` key/values as env vars           |
| `Secret`                        | When `spec.database` is set        | Auto-generated DB credentials (operator-owned)       |
| `Service` (headless)            | When `spec.database` is set        | Stable pod DNS for the StatefulSet                   |
| `Service` (ClusterIP, DB)       | When `spec.database` is set        | App → database connectivity                          |
| `StatefulSet` + `PVC`           | When `spec.database` is set        | PostgreSQL database with persistent storage          |
| `Deployment`                    | Always                             | Runs the microservice container                      |
| `Service` (ClusterIP, app)      | Always                             | In-cluster routing to the app pods                   |
| `HorizontalPodAutoscaler`       | When `spec.autoscaling` is set     | CPU-driven pod scaling                               |
| `Ingress` (or Route)            | When `spec.exposed: true`          | External HTTP/HTTPS access                           |

All owned resources are garbage-collected automatically when the `MicroService` CR is deleted.
Database PVCs survive StatefulSet deletion by design — your data is not lost when you update the CR.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  User                                                        │
│  kubectl apply -f petclinic.yaml                             │
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

## Database Support

The operator can provision and manage a PostgreSQL database as part of the same CR.
You declare what you want — the operator does the rest.

### What the operator creates automatically

```
spec.database present
       │
       ├── Secret  <name>-db-secret          ← auto-generated password, never rotated without intent
       ├── Service <name>-db-headless         ← ClusterIP: None  (required by StatefulSet)
       ├── Service <name>-db                  ← ClusterIP  (app connects here)
       └── StatefulSet <name>-db
               └── PVC  data-<name>-db-0     ← persists across pod restarts and StatefulSet updates
```

The generated `Secret` contains both the PostgreSQL server variables and the Spring Boot
Datasource variables so the app pod needs only a single `envFrom` reference:

| Key | Value |
|-----|-------|
| `POSTGRES_DB` | value of `spec.database.databaseName` |
| `POSTGRES_USER` | value of `spec.database.username` |
| `POSTGRES_PASSWORD` | **auto-generated** 32-char random string |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<name>-db:5432/<databaseName>` |
| `SPRING_DATASOURCE_USERNAME` | same as `POSTGRES_USER` |
| `SPRING_DATASOURCE_PASSWORD` | same as `POSTGRES_PASSWORD` |

### No manual secret creation

```yaml
# Before (manual)
kubectl create secret generic my-db-credentials \
  --from-literal=SPRING_DATASOURCE_URL=jdbc:postgresql://... \
  --from-literal=SPRING_DATASOURCE_USERNAME=user \
  --from-literal=SPRING_DATASOURCE_PASSWORD=s3cr3t

# After (operator-managed) — just add this to your CR:
spec:
  database:
    databaseName: myappdb
    username: myuser
    storageSize: 5Gi
```

### Disabling the database

Remove the `spec.database` block and re-apply. The operator deletes the StatefulSet,
Services, and Secret. The **PVC is not deleted** — your data is preserved.
To reclaim storage, delete the PVC manually after verifying you no longer need the data.

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

Open the PetClinic UI in your browser (port-forward for local access):

```bash
kubectl port-forward svc/petclinic 8080:80 -n default
# Open http://localhost:8080
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
  name: petclinic              # becomes the name of all owned resources
  namespace: production
spec:

  # ── Required ──────────────────────────────────────────────
  # Spring PetClinic — monolithic Spring Boot app with full Thymeleaf web UI.
  # Docker Hub : https://hub.docker.com/r/springcommunity/spring-petclinic
  # Port       : 8080  |  UI: http://<host>/  |  REST: http://<host>/api
  # Note: the Angular frontend (spring-petclinic-angular) has no published Docker image;
  #       use the monolith if you want a working UI out of the box.
  image: docker.io/springcommunity/spring-petclinic:latest

  # ── Scaling ───────────────────────────────────────────────
  replicas: 2                  # desired replicas (ignored by HPA when autoscaling is set)
                               # default: 1

  # ── Networking ────────────────────────────────────────────
  containerPort: 8080          # Spring PetClinic default port

  exposed: true                # create Ingress/Route; default: false
  hostname: petclinic-api.apps.cluster.example.com   # optional; auto-derived when omitted

  # ── Health probes ─────────────────────────────────────────
  # Override these to match your microservice framework:
  #   Spring Boot Actuator : /actuator/health/liveness  /actuator/health/readiness (default)
  #   Quarkus              : /q/health/live              /q/health/ready
  #   Micronaut            : /health/live                /health/ready
  #   Vert.x / generic     : /healthz                   /readyz
  livenessPath: /actuator/health/liveness    # default
  readinessPath: /actuator/health/readiness  # default

  # ── Application config ────────────────────────────────────
  # Mounted into the container as environment variables via a ConfigMap.
  # Spring Boot reads SPRING_* and SERVER_* env vars automatically.
  config:
    SPRING_PROFILES_ACTIVE: postgresql,production
    SPRING_JPA_OPEN_IN_VIEW: "false"
    LOGGING_LEVEL_ORG_SPRINGFRAMEWORK: WARN

  # ── Operator-managed database ──────────────────────────────
  # When present the operator provisions PostgreSQL automatically.
  # Omit this block to use an external database or an in-memory DB.
  database:
    image: docker.io/postgres:15-alpine   # any compatible PostgreSQL image
    databaseName: petclinicdb             # name of the database to create
    username: petclinic                   # PostgreSQL username
    storageSize: 5Gi                      # PVC size (immutable after first apply)
    # storageClass: gp3                   # omit to use the cluster default StorageClass

  # ── User-managed secrets ──────────────────────────────────
  # Additional pre-existing secrets injected as env vars (envFrom).
  # Missing secrets are tolerated (optional=true).
  # Note: the database credentials secret is injected automatically — do NOT list it here.
  envFromSecrets:
    - app-api-keys

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
    team: backend
    cost-center: "cc-11"
    environment: production
```

### Field defaults summary

| Field | Default | Notes |
|-------|---------|-------|
| `replicas` | `1` | Ignored by HPA when `autoscaling` is set |
| `containerPort` | `8080` | |
| `exposed` | `false` | |
| `livenessPath` | `/actuator/health/liveness` | Spring Boot Actuator default |
| `readinessPath` | `/actuator/health/readiness` | Spring Boot Actuator default |
| `resources.requestsCpu` | `100m` | |
| `resources.requestsMemory` | `128Mi` | |
| `resources.limitsCpu` | `500m` | |
| `resources.limitsMemory` | `512Mi` | |
| `autoscaling.minReplicas` | `1` | |
| `autoscaling.maxReplicas` | `5` | |
| `autoscaling.targetCPUUtilizationPercentage` | `70` | |

### Probe path reference for common Java frameworks

| Framework | `livenessPath` | `readinessPath` |
|-----------|----------------|-----------------|
| **Spring Boot Actuator** (default) | `/actuator/health/liveness` | `/actuator/health/readiness` |
| **Quarkus** | `/q/health/live` | `/q/health/ready` |
| **Micronaut** | `/health/live` | `/health/ready` |
| **Vert.x / generic** | `/healthz` | `/readyz` |
| **Helidon** | `/health/live` | `/health/ready` |

---

## Status Reference

```yaml
status:
  phase: RUNNING            # PENDING | RUNNING | DEGRADED | ERROR
  readyReplicas: 2          # ready pods observed in the Deployment
  url: "http://petclinic-api.apps.cluster.example.com"   # populated when exposed=true
  configMapName: petclinic-config
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
kubectl get all,ingress,configmap,hpa -l app=petclinic-prod -n production
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

The project uses [Eclipse JKube](https://www.eclipse.org/jkube/) — **no Dockerfile needed**.
JKube's built-in Java generator detects the executable fat JAR produced by `maven-shade-plugin`,
selects an appropriate base image (`eclipse-temurin:17-jre-alpine` for Kubernetes,
`ubi8/openjdk-17` for OpenShift S2I), and wires up the entrypoint automatically.

There are two plugin goals depending on your target platform:

| Plugin goal | Where image is built | Docker daemon needed? |
|---|---|---|
| `k8s:build` | Local Docker daemon | Yes |
| `k8s:push` | Pushes to remote registry | Yes |
| `oc:build` | Inside OpenShift (S2I binary build) | **No** |
| `oc:push` | OpenShift internal registry | No |

---

### Option A — Kubernetes (Docker build)

Requires Docker (or Podman in Docker-compat mode) running locally.

#### 1. Build the fat JAR and image

```bash
mvn clean package k8s:build -DskipTests
```

JKube reads the `<image>` configuration in pom.xml, copies the fat JAR into
`eclipse-temurin:17-jre-alpine`, and tags it as `microservice-operator:1.0.0-SNAPSHOT`.

#### 2. Push to a registry

```bash
# Quay.io
mvn package k8s:build k8s:push -DskipTests \
  -Djkube.image.name=quay.io/yourorg/microservice-operator:1.0.0

# Docker Hub
mvn package k8s:build k8s:push -DskipTests \
  -Djkube.image.name=docker.io/yourorg/microservice-operator:1.0.0
```

Log in to the registry before pushing:

```bash
docker login quay.io          # Quay.io
docker login                  # Docker Hub
```

#### 3. (Optional) Build + generate manifests + deploy in one shot

```bash
mvn package k8s:build k8s:resource k8s:apply -DskipTests \
  -Djkube.image.name=quay.io/yourorg/microservice-operator:1.0.0
```

---

### Option B — OpenShift (S2I binary build — no Docker daemon)

OpenShift has a built-in build system. JKube's `oc:build` uploads the fat JAR to an
OpenShift `BuildConfig` and the build runs entirely server-side — no Docker needed on
the developer's machine. This is the recommended approach for OpenShift deployments.

#### Prerequisites

```bash
# Log in to your OpenShift cluster
oc login --token=<token> --server=https://api.cluster.example.com:6443

# Create (or switch to) the operator namespace
oc new-project microservice-operator-system
```

#### 1. Build the fat JAR and trigger an S2I build on OpenShift

```bash
mvn clean package oc:build -DskipTests \
  -Djkube.image.name=microservice-operator:1.0.0
```

JKube:
1. Creates a `BuildConfig` in the current namespace
2. Uploads `target/microservice-operator-*.jar` as a binary input
3. OpenShift builds the image using `ubi8/openjdk-17` and pushes it to the internal registry

#### 2. Generate OpenShift manifests and deploy

```bash
mvn oc:resource oc:apply
```

This generates a `DeploymentConfig` (or `Deployment`), `Service`, and `ServiceAccount`
in `target/classes/META-INF/jkube/openshift/` and applies them to the cluster.

#### 3. All-in-one OpenShift workflow

```bash
mvn clean package oc:build oc:resource oc:apply -DskipTests \
  -Djkube.image.name=microservice-operator:1.0.0
```

---

### What `mvn package` produces

```
target/
├── microservice-operator-1.0.0-SNAPSHOT.jar        ← executable fat JAR (all deps bundled)
└── classes/
    └── META-INF/
        └── fabric8/
            └── microservices.example.io-v1.yml     ← CRD (apply this to the cluster first)
```

The fat JAR is created by `maven-shade-plugin` — it includes all dependencies, sets
`Main-Class: io.example.operator.MicroServiceOperator` in the manifest, and replaces
the original thin JAR. You can verify it runs standalone:

```bash
mvn clean package -DskipTests
java -jar target/microservice-operator-1.0.0-SNAPSHOT.jar
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
    - Full application stack from one CR: ConfigMap, Deployment, Service, HPA, Ingress
    - Operator-provisioned PostgreSQL: StatefulSet + PVC + auto-generated credentials Secret
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