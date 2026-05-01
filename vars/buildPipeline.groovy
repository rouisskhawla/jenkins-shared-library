import org.devops.Versioning

def call(Map config = [:]) {

    def serviceDir = config.serviceDir ?: error("serviceDir required")
    def imageName  = config.imageName  ?: error("imageName required")
    def serviceName = config.serviceName ?: error("serviceName required")

    def serviceType  = config.type ?: 'backend'

    def dockerRegistry = config.dockerRegistry ?: 'https://index.docker.io/v1/'
    def dockerCreds    = config.dockerCredentialsId ?: 'dockerlogin'

    def kubeDev  = config.kubeconfigDev  ?: 'kubeconfig-dev'
    def kubeProd = config.kubeconfigProd ?: 'kubeconfig-prod'

    def branch = env.BRANCH_NAME

    pipeline {
        agent any

        environment {
            SERVICE_DIR = "${serviceDir}"
            IMAGE_NAME  = "${imageName}"
            SERVICE_TYPE = "${serviceType}"
        }

        stages {

            stage('Compute Version') {
                steps {
                    script {

                        env.VERSION = Versioning.generate(
                            imageName,
                            env.BRANCH_NAME,
                            env.BUILD_NUMBER,
                            env.GIT_COMMIT
                        )

                        echo "Computed version: ${env.VERSION}"
                    }
                }
            }

            stage('Build') {
                steps {
                    script {

                        if (serviceType == 'backend') {

                            tool name: 'Maven 3.9.11', type: 'maven'
                            tool name: 'jdk17', type: 'jdk'

                            dir(serviceDir) {
                                sh 'mvn clean package -DskipTests'
                            }

                        } else if (serviceType == 'frontend') {

                            tool name: 'Node 24', type: 'nodejs'

                            dir(serviceDir) {
                                sh 'npm ci'

                                if (env.BRANCH_NAME == 'dev') {
                                    sh 'npm run build -- --configuration development'
                                } else {
                                    sh 'npm run build -- --configuration production'
                                }
                            }

                        } else {
                            error("Unknown service type: ${serviceType}")
                        }
                    }
                }
            }

            stage('Docker Build') {
                steps {
                    dir(serviceDir) {
                        script {
                            docker.withRegistry(dockerRegistry, dockerCreds) {
                                docker.build("${imageName}:${env.VERSION}")
                            }
                        }
                    }
                }
            }

            stage('Docker Push') {
                steps {
                    script {
                        docker.withRegistry(dockerRegistry, dockerCreds) {
                            def img = docker.image("${imageName}:${env.VERSION}")
                            img.push()

                            if (branch == 'main') {
                                img.push('latest')
                            }
                        }
                    }
                }
            }

            stage('Deploy to Kubernetes') {
                steps {
                    script {

                        def namespace
                        def kubeCredId
                        def valuesFile
                        def environmentName

                        def helmValuesDir = "helm-values/${serviceName}"
                        def chartDir = "charts/microservice"

                        if (branch == 'dev') {
                            namespace = 'dev'
                            kubeCredId = kubeDev
                            valuesFile = "values-dev.yaml"
                            environmentName = "DEVELOPMENT"
                        } 
                        else if (branch == 'main') {
                            namespace = 'prod'
                            kubeCredId = kubeProd
                            valuesFile = "values-prod.yaml"
                            environmentName = "PRODUCTION"
                        }

                        input(
                            message: """
CONFIRM DEPLOYMENT

Service Dir    : ${serviceDir}
Service Name   : ${serviceName}
Helm Values Dir: ${helmValuesDir}
Chart Dir      : ${chartDir}
Image Repo     : ${imageName}
Computed Tag   : ${env.VERSION}
Branch         : ${branch}
Environment    : ${environmentName}
Namespace      : ${namespace}

Proceed?
""",
                            ok: "Deploy"
                        )

                        withCredentials([file(credentialsId: kubeCredId, variable: 'KUBECONFIG')]) {

                            sh "kubectl cluster-info"

                            sh """
                                kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -
                            """

                            sh """
                                helm upgrade --install ${serviceName} ${chartDir} \
                                -f ${helmValuesDir}/${valuesFile} \
                                --set global.imageTag=${env.VERSION} \
                                --namespace ${namespace} \
                                --create-namespace
                            """

                            sh """
                                kubectl rollout status deployment/${serviceName} -n ${namespace}
                            """

                            sh """
                                kubectl get pods -n ${namespace}
                                kubectl get svc -n ${namespace}
                                kubectl get ingress -n ${namespace}
                            """
                        }
                    }
                }
            }
        }

        post {
            always {
                echo "Pipeline finished with status: ${currentBuild.currentResult}"
                cleanWs()
            }
        }
    }
}
