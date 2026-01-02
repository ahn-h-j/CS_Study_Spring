package com.cos.cs_study_spring.concurrency.cucumber;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.*;

/**
 * Cucumber Test Runner for Concurrency Control Tests
 *
 * 5가지 동시성 제어 전략을 BDD 방식으로 검증합니다.
 *
 * 테스트 실행:
 * ./gradlew test --tests "ConcurrencyCucumberTest"
 *
 * HTML 리포트 생성 위치:
 * build/reports/cucumber/cucumber-report.html
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.cos.cs_study_spring.concurrency.cucumber")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, html:build/reports/cucumber/cucumber-report.html, json:build/reports/cucumber/cucumber-report.json")
@ConfigurationParameter(key = PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, value = "true")
public class ConcurrencyCucumberTest {
}
