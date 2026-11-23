// Pushes the coverage reports of this build to the combined coverage job (<line>/coverage, see Jenkinsfile.coverage
// in servoy-eclipse): copies them to $JENKINS_HOME/coverage-staging/<line>/<source>/ and starts that job.
// Copy of servoy-eclipse ci/coverage/push-coverage.groovy, keep it the same.
def pushCoverage(String source, String includes, String line = null) {
    // only builds whose tests ran to the end count; a failed build keeps the previous push
    if (currentBuild.currentResult != 'SUCCESS' && currentBuild.currentResult != 'UNSTABLE') {
        echo "pushCoverage: build is ${currentBuild.currentResult}, coverage of ${source} not pushed"
        return
    }
    if (!(source ==~ /[A-Za-z0-9][A-Za-z0-9._-]{0,79}/)) {
        error "pushCoverage: invalid source name '${source}'"
    }
    def branchLine = line ?: env.JOB_NAME.contains('/') ? env.JOB_NAME.substring(0, env.JOB_NAME.lastIndexOf('/')) : null
    if (!branchLine) {
        echo "pushCoverage: job ${env.JOB_NAME} is not in a branch folder and no line was given, coverage not pushed"
        return
    }
    def files = findFiles(glob: includes.split(',').collect { it.trim() }.join(','))
    if (files.length == 0) {
        echo "pushCoverage: no files match '${includes}', coverage of ${source} not pushed"
        return
    }
    catchError(buildResult: null, stageResult: null, message: 'coverage push failed') {
        withEnv(["COVERAGE_SOURCE=${source}", "COVERAGE_LINE=${branchLine}"]) {
            writeFile file: '.coverage-push-files', text: files.collect { it.path }.join('\n') + '\n'
            sh '''
                set -eu
                staging="$JENKINS_HOME/coverage-staging/$COVERAGE_LINE"
                new="$staging/$COVERAGE_SOURCE.new.$$"
                rm -rf "$new"
                mkdir -p "$new"
                while IFS= read -r f; do
                    [ -n "$f" ] || continue
                    mkdir -p "$new/$(dirname "$f")"
                    cp "$f" "$new/$f"
                done < .coverage-push-files
                rm -f .coverage-push-files
                rm -rf "$staging/$COVERAGE_SOURCE.old"
                if [ -d "$staging/$COVERAGE_SOURCE" ]; then mv "$staging/$COVERAGE_SOURCE" "$staging/$COVERAGE_SOURCE.old"; fi
                mv "$new" "$staging/$COVERAGE_SOURCE"
                rm -rf "$staging/$COVERAGE_SOURCE.old"
                echo "pushed $(find "$staging/$COVERAGE_SOURCE" -type f | wc -l) coverage file(s) of $COVERAGE_SOURCE to $staging"
            '''
        }
        build job: "/${branchLine}/coverage", wait: false, propagate: false
    }
}


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
        stage('Coverage Report') {
            // JaCoCo coverage for ALL Java tests - plainjunit AND the integrationpde PDE tests.
            // This runs AFTER 'Integration Tests' on purpose: the integration run writes
            // target/jacoco-it.exec in each .tests module (the jacoco-prepare-agent-it execution
            // sets tycho.testArgLine, surfaced into the integration-profile tycho-surefire argLine
            // via the additionalTestArgLine indirection), and the plainjunit run in
            // 'Build with Tycho 5' already wrote target/jacoco.exec.
            // The jacoco 'report' goal cannot be redirected to another module's classes, so it is
            // run in each PRODUCTION bundle (-Pcoverage-report), which first MERGES both exec files
            // into target/jacoco-merged.exec ('merge' goal) and then reports this bundle's own
            // classes against the merged data ('report' goal). So the published number reflects
            // unit + integration coverage. See AGENTS.md "JaCoCo code coverage".
            when { expression { env.SKILLTEST_ONLY != 'true' } }
            steps {
                configFileProvider([
                    configFile(fileId: 'ba7b9372-76e5-4898-a2be-1dde60a0d6e3', variable: 'MAVEN_SETTINGS'),
                    configFile(fileId: 'maven_toolchain', variable: 'TOOLCHAIN')
                ]) {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        sh 'mvn -B -s "$MAVEN_SETTINGS" -t "$TOOLCHAIN" -Pcoverage-report org.jacoco:jacoco-maven-plugin:merge org.jacoco:jacoco-maven-plugin:report -pl bundles/com.servoy.eclipse.opencode,bundles/com.servoy.eclipse.developer.mcp $Dproperties'
                    }
                }
            }
            post {
                always {
                    // Java (JaCoCo) coverage of the plainjunit + integrationpde tests (merged).
                    recordCoverage(
                        tools: [[parser: 'JACOCO', pattern: '**/target/site/jacoco/jacoco.xml']],
                        id: 'jacoco-java',
                        name: 'JaCoCo Coverage - Java (unit + integration)',
                        // Keep the checked-out Java sources per build so the Coverage view can
                        // show the actual covered/missed LINES when you click a .java file, not
                        // only the per-file percentages (default sourceCodeRetention is NEVER,
                        // which is why Java had no line view while the webui Vitest entry - which
                        // sets EVERY_BUILD below - does).
                        sourceCodeRetention: 'EVERY_BUILD',
                        // jacoco.xml only holds package-relative file names (com/servoy/...), and the
                        // plugin otherwise only looks in src/main/java, which these Eclipse bundles do not
                        // use (build 453: "found: 0, not found: 96", so no clickable sources). Point it at
                        // the src folder of each reported bundle (same fix as servoy-eclipse).
                        sourceDirectories: [
                            [path: 'bundles/com.servoy.eclipse.developer.mcp/src'],
                            [path: 'bundles/com.servoy.eclipse.opencode/src']
                        ]
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
                    // Archive the coverage reports so they can be read per build (also via the Jenkins MCP).
                    // jacoco.xml = the merged per-bundle JaCoCo report (per class/method/line/branch
                    // counters, what the published number reflects); the cobertura file is the webui
                    // Vitest coverage. The binary jacoco*.exec files are deliberately NOT archived: they
                    // can only be turned into a report again together with the exact class files of this
                    // build (which are not archived), and everything readable is already in jacoco.xml.
                    archiveArtifacts artifacts: '**/target/site/jacoco/jacoco.xml, bundles/com.servoy.eclipse.opencode/target/coverage/cobertura-coverage.xml', allowEmptyArchive: true, fingerprint: false

                    // push the same reports to the combined coverage job; this job is not in a branch folder, so the line is given
                    script { pushCoverage('servoy-copilot', '**/target/site/jacoco/jacoco.xml, bundles/com.servoy.eclipse.opencode/target/coverage/cobertura-coverage.xml', 'master') }
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
