import { defineConfig } from 'vitest/config';

// Vitest runner config for the Angular unit-test builder (referenced from
// angular.json -> test.options.runnerConfig). It ONLY configures coverage output,
// so the builder keeps providing the Angular TestBed environment/globals itself.
//
// The coverage include/exclude are NOT set here on purpose: the Angular
// @angular/build:unit-test builder does not read them from this file, it reads
// --coverage-include / --coverage-exclude CLI flags (see the "test:coverage"
// script in package.json). Only reportsDirectory + reporter are honored from here.
//
// reportsDirectory is written under the Maven bundle's target/ (webui/.. = the
// com.servoy.eclipse.opencode bundle root) so the build's frontend-maven-plugin run
// and Jenkins' recordCoverage can pick up target/coverage/cobertura-coverage.xml.
export default defineConfig({
  test: {
    coverage: {
      reportsDirectory: '../target/coverage',
      reporter: ['text-summary', 'json-summary', 'lcov', 'cobertura'],
    },
  },
});
