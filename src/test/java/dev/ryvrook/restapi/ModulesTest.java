package dev.ryvrook.restapi;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModulesTest {
    @Test
    void missingModulesAreSkippedQuietly() {
        List<String> problems = new ArrayList<>();
        List<RestModule> found = Modules.load(getClass().getClassLoader(), problems::add);
        assertTrue(problems.isEmpty(), problems.toString());
        assertTrue(found.stream().anyMatch(m -> m.group().equals("core")));
    }

    @Test
    void everyModuleHasItsOwnPackage() {
        java.util.Set<String> packages = new java.util.HashSet<>();
        for (String c : Modules.CLASSES) {
            assertTrue(packages.add(c.substring(0, c.lastIndexOf('.'))), c);
        }
    }
}
