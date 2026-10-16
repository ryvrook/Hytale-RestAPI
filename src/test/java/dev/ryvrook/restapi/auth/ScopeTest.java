package dev.ryvrook.restapi.auth;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScopeTest {
    @Test
    void parsesListsAndCommaSeparatedEntries() {
        assertEquals(EnumSet.of(Scope.READ, Scope.PLAYERS), Scope.parse(List.of("read,players")));
        assertEquals(EnumSet.of(Scope.READ, Scope.METRICS), Scope.parse(List.of(" READ ", "metrics", "")));
        assertEquals(EnumSet.of(Scope.COINAGE_WRITE), Scope.parse(List.of("coinage.write")));
        assertEquals(EnumSet.of(Scope.READ, Scope.METRICS, Scope.EVENTS), Scope.parse(List.of("read metrics+events")));
    }

    @Test
    void allMeansEveryScope() {
        assertEquals(EnumSet.allOf(Scope.class), Scope.parse(List.of("all")));
        assertEquals(List.of("all"), Scope.describe(EnumSet.allOf(Scope.class)));
    }

    @Test
    void unknownScopesAreNamedInTheError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Scope.parse(List.of("read,plyers,comands")));
        assertTrue(e.getMessage().contains("plyers, comands"), e.getMessage());
    }

    @Test
    void describeKeepsDeclarationOrder() {
        assertEquals(List.of("read", "commands", "metrics"),
                Scope.describe(java.util.Set.of(Scope.METRICS, Scope.READ, Scope.COMMANDS)));
    }

    @Test
    void everySuiteScopeIsNamedAfterItsSibling() {
        for (var s : dev.ryvrook.restapi.siblings.Sibling.values()) {
            assertEquals(s.id(), s.scope().id());
        }
    }
}
