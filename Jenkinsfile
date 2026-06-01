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
        string(name: 'goals', defaultValue: 'package -U', trim: false)
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
            }
        }
        
        stage('Build with Tycho 5') {
            steps {
                wrap([$class: 'Xvfb', installationName: 'xvfb', autoDisplayName: true]) {
                    configFileProvider([
                        // Let op: gebruikt de specifieke ba7b9372... settings-file van dit project
                        configFile(fileId: 'ba7b9372-76e5-4898-a2be-1dde60a0d6e3', variable: 'MAVEN_SETTINGS'),
                        configFile(fileId: 'maven_toolchain', variable: 'TOOLCHAIN')
                    ]) {
                        sh 'mvn -B -s "$MAVEN_SETTINGS" -t "$TOOLCHAIN" $goals'
                    }
                }
            }
        }
        
        stage('Deploy Plugin Site') {
            // Deze stap wordt automatisch alleen uitgevoerd als de vorige stages succesvol waren
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
    }
    
    post {
        success {
            // Trigger beide downstream installer-jobs met de specifieke verplichte parameter-goals
            build job: 'make_installer_eclipse', 
                  parameters: [string(name: 'goals', value: 'clean install -Ponly_product -U')], 
                  wait: false
                  
            build job: 'release/make_installer_eclipse', 
                  parameters: [string(name: 'goals', value: 'clean install -Ponly_product -U')], 
                  wait: false
        }
        
        failure {
            office365ConnectorSend webhookUrl: "${TEAMS_WEBHOOK}", status: 'Failed'
        }
        
        unstable {
            office365ConnectorSend webhookUrl: "${TEAMS_WEBHOOK}", status: 'Unstable'
        }
        
        fixed {
            office365ConnectorSend webhookUrl: "${TEAMS_WEBHOOK}", status: 'Back to Normal'
        }
    }
}