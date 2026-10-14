pipeline {
    agent any
    
    options {
        quietPeriod(120)
    }
    
    triggers {
        githubPush()
    }
    
    parameters {
        // Standaard goals voor dit specifieke project overgenomen uit de oude config
        string(name: 'goals', defaultValue: 'clean install -Dtycho.localArtifacts=ignore -Dservoy.url=file:///data/www/latest/servoy_lts_2026/update_site/', trim: false)
    }
    
    environment {
        // Beide secrets worden hier veilig uit de Jenkins-kluis gehaald zonder te lekken naar GitHub
        TEAMS_WEBHOOK = credentials('servoy-teams-webhook')
        MAVEN_GPG_PASSPHRASE = credentials('servoy-gpg-passphrase')
    }
    
    tools {
        jdk 'Java 21'
        maven 'Maven 3.9.16'
    }
    
    stages {
        stage('Checkout') {
            steps {
                checkout scm
                // Decide whether this push is "skilltest-only" - i.e. every changed file is
                // under the skilltest TEST project or the skilltest Jenkinsfile. Those do not
                // affect the product build/deploy, so the heavy stages below skip for them.
                // The skilltest BUNDLE (bundles/.../opencode.skilltest) is a product bundle and
                // is deliberately NOT in this set, so a bundle change still builds the product.
                // First build (no prior changeset) is treated as NOT skilltest-only, so a fresh
                // branch always does a full build.
                script {
                    def changed = []
                    for (cs in currentBuild.changeSets) {
                        for (c in cs.items) {
                            for (f in c.affectedFiles) {
                                changed << f.path
                            }
                        }
                    }
                    boolean hasChanges = !changed.isEmpty()
                    boolean onlySkilltest = hasChanges && changed.every { p ->
                        p.startsWith('tests/com.servoy.eclipse.opencode.skilltest.tests/') ||
                        p == 'Jenkinsfile.skilltest'
                    }
                    env.SKILLTEST_ONLY = onlySkilltest.toString()
                    echo "Changed files: ${changed.size()}; skilltest-only (skip product build): ${onlySkilltest}"
                }
            }
        }
        
        stage('Build with Tycho 5') {
            when { expression { env.SKILLTEST_ONLY != 'true' } }
            steps {
                wrap([$class: 'Xvfb', installationName: 'xvfb', autoDisplayName: true]) {
                    configFileProvider([
                        // Let op: gebruikt de specifieke ba7b9372... settings-file van dit project
                        configFile(fileId: 'ba7b9372-76e5-4898-a2be-1dde60a0d6e3', variable: 'MAVEN_SETTINGS'),
                        configFile(fileId: 'maven_toolchain', variable: 'TOOLCHAIN')
                    ]) {
                        sh 'export MAVEN_OPTS="-Dmaven.test.failure.ignore=true" && mvn -B -s "$MAVEN_SETTINGS" -t "$TOOLCHAIN" $goals'
                    }
                }
            }
        }
        stage('Deploy Plugin Site') {
            when { expression { env.SKILLTEST_ONLY != 'true' } }
            steps {
                sh '''
                rm -rf /data/www/latest/servoy_ai/
                mkdir -p /data/www/latest/servoy_ai/
                for d in repository.site_aiplugin/target/repository/; do 
                    [ -d "$d" ] && cp -r "$d/." "/data/www/latest/servoy_ai/"
                    break
                done
                '''
            }
        }
        stage('Integration Tests') {
            when { expression { env.SKILLTEST_ONLY != 'true' } }
            steps {
                wrap([$class: 'Xvfb', installationName: 'xvfb', autoDisplayName: true]) {
                    configFileProvider([
                        configFile(fileId: 'ba7b9372-76e5-4898-a2be-1dde60a0d6e3', variable: 'MAVEN_SETTINGS'),
                        configFile(fileId: 'maven_toolchain', variable: 'TOOLCHAIN')
                    ]) {
                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                            sh 'mvn -B -s "$MAVEN_SETTINGS" -t "$TOOLCHAIN" verify -Pintegration -pl tests/com.servoy.eclipse.developer.mcp.tests,tests/com.servoy.eclipse.opencode.tests -am -Dservoy.url=file:///data/www/latest/servoy_lts_2026/update_site/'
                        }
                    }
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                }
            }
        }
    }
    
    post {
       failure {
            office365ConnectorSend webhookUrl: TEAMS_WEBHOOK, status: 'Failed', adaptiveCards: true
        }
        
        unstable {
            office365ConnectorSend webhookUrl: TEAMS_WEBHOOK, status: 'Unstable', adaptiveCards: true
        }
        
        fixed {
            office365ConnectorSend webhookUrl: TEAMS_WEBHOOK, status: 'Back to Normal', adaptiveCards: true
        }
    }
}
