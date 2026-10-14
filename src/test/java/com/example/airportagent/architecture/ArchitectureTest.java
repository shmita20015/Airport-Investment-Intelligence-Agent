package com.example.airportagent.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** These rules make the design claims testable. See DESIGN.md, section 4.2. */
@AnalyzeClasses(packages = "com.example.airportagent", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** The scoring code must not use the LLM, Spring or any other layer. */
    @ArchTest
    static final ArchRule scoringAndDomainAreFree = noClasses()
            .that().resideInAnyPackage("..scoring..", "..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "..agent..", "..api..", "..service..", "..data..", "..config..");

    /** The agent and the API call the service. They do not call the scoring or the data code. */
    @ArchTest
    static final ArchRule agentAndApiUseTheServiceOnly = noClasses()
            .that().resideInAnyPackage("..agent..", "..api..")
            .should().dependOnClassesThat().resideInAnyPackage("..scoring..", "..data..")
            .allowEmptyShould(true);

    /** Only the agent package may use Spring AI. */
    @ArchTest
    static final ArchRule onlyTheAgentUsesSpringAi = noClasses()
            .that().resideOutsideOfPackage("..agent..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..");
}
