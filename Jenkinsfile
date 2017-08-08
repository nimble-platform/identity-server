node ('nimble-jenkins-slave') {
    def app
    stage('Clone & Build') {
            // slackSend 'Started build no. ${env.BUILD_ID} of ${env.JOB_NAME}'
            git(url: 'https://github.com/nimble-platform/identity-service.git', branch: 'master')
            sh 'git submodule init'
            sh 'git submodule update'
            withMaven(maven: 'M339') {
              sh 'mvn clean install -DskipTests'
            }
            withMaven(maven: 'M339') {
              sh 'mvn -f identity-service/pom.xml docker:build'
            }
    }
    stage ('Docker Push')  {
      docker.withRegistry('https://registry.hub.docker.com', 'NimbelPlatformDocker') {
            app.push("${env.BUILD_NUMBER}")
            app.push("latest")
        }
    }
}

