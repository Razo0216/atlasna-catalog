package com.atlasna.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Module boundaries are enforced, not just a naming convention: each top-level package is a module, may use other
 * modules only through their public API (root package or a named interface), and modules may not depend on each
 * other in a cycle. Also writes the module diagrams to target/spring-modulith-docs (copied to docs/modules).
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(AtlasnaCatalogApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        MODULES.verify();
    }

    @Test
    void writeModuleDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml().writeModuleCanvases();
    }
}
