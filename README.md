# jenkins-shared-library

A reusable Jenkins Shared Library that centralizes CI/CD pipeline logic for **build**, **versioning**, **Docker packaging**, and **Kubernetes deployments** across multiple services.

Instead of duplicating pipeline code in every project's `Jenkinsfile`, this library provides a single `buildPipeline()` function. Each service calls it with a few parameters and the library handles everything else.

---

## Repository Structure

```
jenkins-shared-library/
├── vars/
│   └── buildPipeline.groovy      # Global callable pipeline function
└── src/
    └── org/devops/
        └── Versioning.groovy     # Version computation logic
```

- `vars/` — exposed to all Jenkinsfiles as global functions
- `src/` — regular Groovy classes imported internally by `vars/` files

---

## Usage

### Register the Library

In Jenkins: **Manage Jenkins → System → Global Pipeline Libraries**

| Field | Value |
|---|---|
| Name | `jenkins-shared-library` |
| Default version | `main` |
| Source | Git URL of this repository |

### Call from a Jenkinsfile

Backend service (Maven):

```groovy
@Library('jenkins-shared-library@v1.0.16') _

buildPipeline(
    serviceDir:  'services/api-gateway',
    serviceName: 'api-gateway',
    imageName:   'username/ci-cd-gateway'
)
```

Frontend service (Node.js):

```groovy
@Library('jenkins-shared-library@v1.0.16') _

buildPipeline(
    serviceDir:  'services/bookstore-frontend',
    serviceName: 'bookstore-frontend',
    imageName:   'username/ci-cd-frontend',
    type:        'frontend'
)
```

Pin to a version tag (`@v1.0.16`) rather than `@main`. This prevents a library update from affecting all service pipelines simultaneously.

---

## `buildPipeline()` Parameters

| Parameter | Required | Default | Description |
|---|---|---|---|
| `serviceDir` | ✅ | — | Path to the service directory within the repo |
| `serviceName` | ✅ | — | Service name used in Helm release and deployment labels |
| `imageName` | ✅ | — | Docker image name |
| `type` | ❌ | `backend` | Service type: `backend` (Maven) or `frontend` (Node.js) |
| `dockerRegistry` | ❌ | `https://index.docker.io/v1/` | Docker registry URL |
| `dockerCredentialsId` | ❌ | `dockerlogin` | Jenkins credentials ID for Docker Hub |
| `kubeconfigDev` | ❌ | `kubeconfig-dev` | Jenkins credentials ID for dev cluster kubeconfig |
| `kubeconfigProd` | ❌ | `kubeconfig-prod` | Jenkins credentials ID for prod cluster kubeconfig |

---

## Pipeline Stages

### 1. Compute Version

Delegates to `Versioning.groovy`. Generates a unique image tag per branch:

| Branch | Example |
|---|---|
| `dev` | `1.0.47-dev-a3f9c12` |
| `main` | `1.0.47-a3f9c12` |
| Other | `1.0.47-feature-login-a3f9c12` |

### 2. Build

- **Backend:** runs `mvn clean package -DskipTests` using the configured JDK 17 and Maven 3.9 tools
- **Frontend:** runs `npm ci` then `npm run build` with `--configuration development` on `dev` and `--configuration production` on `main`

### 3. Docker Build

Builds the image tagged with the computed version inside the service directory.

### 4. Docker Push

Pushes the versioned tag. On `main`, also pushes the `latest` tag.

### 5. Deploy to Kubernetes

Runs `helm upgrade --install` against the target cluster:

- `dev` branch → `dev` namespace, `kubeconfig-dev` credentials
- `main` branch → `prod` namespace, `kubeconfig-prod` credentials

Uses the shared Helm chart in `charts/microservice/` with per-service values from `helm-values/<serviceName>/values-<env>.yaml`. After Helm runs, confirms the rollout with `kubectl rollout status`.

### Manual Approval Gate

The pipeline pauses before deploying and displays a full summary:

```
CONFIRM DEPLOYMENT

Service     : api-gateway
Image       : username/ci-cd-gateway:1.0.47-a3f9c12
Branch      : main
Environment : PRODUCTION
Namespace   : prod

Proceed?
```

A human must click **Deploy** before Helm touches the production cluster.

---

## Versioning Logic

`src/org/devops/Versioning.groovy` computes the image tag from the branch name, build number, and short commit SHA:

```
<major>.<minor>.<patch+buildNumber>[-<branch>]-<shortSHA>
```

This makes every image in the registry traceable, no need to look up which build number corresponds to which commit.

---

## Required Jenkins Configuration

### Tool Installations

Configured these under **Manage Jenkins → Tools**:

| Tool | Name used in library |
|---|---|
| JDK 17 | `jdk17` |
| Maven 3.9 | `Maven 3.9.11` |
| Node.js 24 | `Node 24` |

### Credentials

| ID | Type | Description |
|---|---|---|
| `dockerlogin` | Username/Password | Docker Hub credentials |
| `kubeconfig-dev` | Secret File | kubeconfig for dev cluster |
| `kubeconfig-prod` | Secret File | kubeconfig for prod cluster |

---

## Related Repositories

- [reliable-ci-cd-pipeline](https://github.com/rouisskhawla/reliable-ci-cd-pipeline) — application monorepo that consumes this library
- [github-shared-workflow](https://github.com/rouisskhawla/github-shared-workflow) — equivalent pattern for GitHub Actions
- [gitlab-shared-template](https://github.com/rouisskhawla/gitlab-shared-template) — equivalent pattern for GitLab CI