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
        string(name: 'goals', defaultValue: 'clean install', trim: false)
        string(name: 'Dproperties', defaultValue: '-Dtycho.localArtifacts=ignore -Dservoy.url=file:///data/www/latest/servoy_lts_2026/update_site/ ', trim: false)
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
                        sh 'export MAVEN_OPTS="-Dmaven.test.failure.ignore=true" && mvn -B -s "$MAVEN_SETTINGS" -t "$TOOLCHAIN" $goals $Dproperties'
                    }
                }
            }
        }
        stage('Coverage Report') {
            // JaCoCo coverage for the plain-JUnit tests. The 'Build with Tycho 5' stage above
            // already ran those tests with the agent attached (prepare-agent + @{jacocoArgLine}
            // in each .tests pom), so target/jacoco.exec exists per test module. The jacoco
            // 'report' goal cannot be redirected to another module's classes, so it is run in
            // each PRODUCTION bundle (-Pcoverage-report points it at the sibling .tests exec).
            // This reflects plainjunit coverage only - the integrationpde PDE tests are not yet
            // wired for coverage. See AGENTS.md "JaCoCo code coverage".
            when { expression { env.SKILLTEST_ONLY != 'true' } }
            steps {
                configFileProvider([
                    configFile(fileId: 'ba7b9372-76e5-4898-a2be-1dde60a0d6e3', variable: 'MAVEN_SETTINGS'),
                    configFile(fileId: 'maven_toolchain', variable: 'TOOLCHAIN')
                ]) {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        sh 'mvn -B -s "$MAVEN_SETTINGS" -t "$TOOLCHAIN" -Pcoverage-report org.jacoco:jacoco-maven-plugin:report -pl bundles/com.servoy.eclipse.opencode,bundles/com.servoy.eclipse.developer.mcp $Dproperties'
                    }
                }
            }
            post {
                always {
                    // Java (JaCoCo) coverage of the plainjunit tests.
                    recordCoverage(
                        tools: [[parser: 'JACOCO', pattern: '**/target/site/jacoco/jacoco.xml']],
                        id: 'jacoco-java',
                        name: 'JaCoCo Coverage - Java (plainjunit)'
                    )
                    // Angular webui (Vitest / V8) coverage, Cobertura report produced by the
                    // 'webui vitest coverage' frontend-maven-plugin run in the build stage.
                    // Own recordCoverage with its own id/name so it gets an independent entry
                    // and trend (a single recordCoverage over mixed parsers would collide).
                    // ignoreParsingErrors: istanbul's cobertura reporter can emit duplicate
                    // <method> elements that the coverage parser otherwise rejects.
                    recordCoverage(
                        tools: [[parser: 'COBERTURA', pattern: 'bundles/com.servoy.eclipse.opencode/target/coverage/cobertura-coverage.xml']],
                        id: 'vitest-webui',
                        name: 'Vitest Coverage - webui (opencode chat UI)',
                        ignoreParsingErrors: true,
                        sourceCodeRetention: 'EVERY_BUILD'
                    )
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
                            sh 'mvn -B -s "$MAVEN_SETTINGS" -t "$TOOLCHAIN" verify -Pintegration -pl bundles/com.servoy.eclipse.developer.mcp,bundles/com.servoy.eclipse.opencode,tests/com.servoy.eclipse.developer.mcp.tests,tests/com.servoy.eclipse.opencode.tests -am $Dproperties'
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
