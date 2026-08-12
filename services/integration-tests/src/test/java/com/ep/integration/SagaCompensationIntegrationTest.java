package com.ep.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-138: Real Saga compensation verification against the ACTUAL Saga state
 * machine JSON definitions.
 *
 * <p>{@link SagaTransactionTest} (D-113) verifies the LIFO replay contract using
 * an in-memory {@code FakeSagaEngine} that is hand-wired in the test. That test
 * never reads the production {@code create-order-saga.json} /
 * {@code cancel-order-saga.json} definitions, so a drift between the JSON and
 * the test's hand-coded steps would slip through silently (Iron Law 0: no
 * silent failure).
 *
 * <p>This test loads the REAL Seata Saga state-machine JSON shipped under
 * {@code services/order-platform/order-platform-service/src/main/resources/saga/} and
 * asserts the structural correctness of the compensation wiring:
 * <ul>
 *   <li>every forward {@code ServiceTask} step that needs rollback declares a
 *       {@code CompensateState} pointing to a real compensation state;</li>
 *   <li>the {@code CompensationTrigger} state exists so Seata can replay
 *       compensations on an exception;</li>
 *   <li>each {@code Catch} on a forward step routes to the trigger;</li>
 *   <li>the LIFO compensation order is structurally meaningful (>=2
 *       compensable tasks chained);</li>
 *   <li>the {@code CompensateState} targets resolve to states whose
 *       {@code ServiceMethod} actually does the inverse work.</li>
 * </ul>
 *
 * <p>Tagged {@code integration} so it runs during {@code mvn verify} but is
 * excluded from the fast unit-test phase.
 */
@Tag("integration")
class SagaCompensationIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // create-order-saga.json
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create-order-saga.json: every forward ServiceTask has a CompensateState")
    void createOrderSaga_everyForwardTaskHasCompensateState() throws Exception {
        JsonNode saga = loadSaga("saga/create-order-saga.json");
        JsonNode states = saga.get("States");
        assertNotNull(states, "Saga must define States");
        assertTrue(states.isArray(), "States must be an array (Seata Saga JSON format)");

        Set<String> stateNames = collectStateNames(states);
        List<String> forwardTasksWithoutCompensation = new ArrayList<>();

        for (JsonNode state : states) {
            if (!"ServiceTask".equals(text(state, "Type"))) {
                continue;
            }
            // Skip states that are themselves compensations (they do inverse work
            // and need no further rollback). Heuristic: their ServiceMethod starts
            // with "compensate" or they have no CompensateState AND no Next into
            // a non-terminal state.
            String serviceMethod = text(state, "ServiceMethod");
            boolean isCompensationState = serviceMethod != null
                    && (serviceMethod.startsWith("compensate")
                        || serviceMethod.startsWith("cancel")
                        || serviceMethod.startsWith("refund")
                        || serviceMethod.startsWith("release"));
            if (isCompensationState) {
                continue;
            }

            String name = text(state, "Name");
            String compensateState = text(state, "CompensateState");
            if (compensateState == null || compensateState.isBlank()) {
                forwardTasksWithoutCompensation.add(name);
            } else {
                assertTrue(stateNames.contains(compensateState),
                        "CompensateState '" + compensateState + "' declared by '" + name
                                + "' must resolve to a real state in States");
            }
        }

        assertTrue(forwardTasksWithoutCompensation.isEmpty(),
                "Every forward ServiceTask must declare a CompensateState. "
                        + "Tasks missing it: " + forwardTasksWithoutCompensation);
    }

    @Test
    @DisplayName("create-order-saga.json: has CompensationTrigger for exception-driven rollback")
    void createOrderSaga_hasCompensationTrigger() throws Exception {
        JsonNode saga = loadSaga("saga/create-order-saga.json");
        JsonNode states = saga.get("States");

        JsonNode trigger = findStateByType(states, "CompensationTrigger");
        assertNotNull(trigger, "Saga must have a CompensationTrigger state for rollback");
        // The trigger must route to a terminal Fail state per Seata Saga semantics.
        String next = text(trigger, "Next");
        assertNotNull(next, "CompensationTrigger must declare a Next transition");
        assertEquals("Fail", next,
                "CompensationTrigger should route to Fail after replaying compensations");
    }

    @Test
    @DisplayName("create-order-saga.json: Catch clauses route forward failures to CompensationTrigger")
    void createOrderSaga_catchRoutesToTrigger() throws Exception {
        JsonNode saga = loadSaga("saga/create-order-saga.json");
        JsonNode states = saga.get("States");

        int catchCount = 0;
        int routedToTrigger = 0;
        for (JsonNode state : states) {
            if (!"ServiceTask".equals(text(state, "Type"))) {
                continue;
            }
            JsonNode catches = state.get("Catch");
            if (catches == null || !catches.isArray()) {
                continue;
            }
            for (JsonNode c : catches) {
                catchCount++;
                if ("CompensationTrigger".equals(text(c, "Next"))) {
                    routedToTrigger++;
                }
            }
        }

        assertTrue(catchCount > 0,
                "create-order saga must declare at least one Catch for exception handling");
        assertEquals(catchCount, routedToTrigger,
                "Every Catch on a forward step must route to CompensationTrigger, "
                        + "otherwise the saga would not roll back on failure");
    }

    @Test
    @DisplayName("create-order-saga.json: StartState is a forward ServiceTask")
    void createOrderSaga_startStateIsValid() throws Exception {
        JsonNode saga = loadSaga("saga/create-order-saga.json");
        String start = text(saga, "StartState");
        assertNotNull(start, "Saga must declare a StartState");
        JsonNode states = saga.get("States");
        JsonNode startState = findStateByName(states, start);
        assertNotNull(startState, "StartState '" + start + "' must exist in States");
        assertEquals("ServiceTask", text(startState, "Type"),
                "StartState should be a ServiceTask (forward step)");
    }

    @Test
    @DisplayName("create-order-saga.json: terminal Succeed and Fail states exist")
    void createOrderSaga_hasTerminalStates() throws Exception {
        JsonNode saga = loadSaga("saga/create-order-saga.json");
        JsonNode states = saga.get("States");

        assertNotNull(findStateByType(states, "Succeed"),
                "Saga must declare a Succeed terminal state");
        assertNotNull(findStateByType(states, "Fail"),
                "Saga must declare a Fail terminal state");
    }

    @Test
    @DisplayName("create-order-saga.json: compensation states call inverse ServiceMethods")
    void createOrderSaga_compensationStatesDoInverseWork() throws Exception {
        JsonNode saga = loadSaga("saga/create-order-saga.json");
        JsonNode states = saga.get("States");

        // Build the forward -> compensation map from CompensateState pointers.
        // Then verify each compensation state's ServiceMethod is structurally
        // an inverse (compensate/cancel/refund/release verb).
        for (JsonNode state : states) {
            if (!"ServiceTask".equals(text(state, "Type"))) {
                continue;
            }
            String compensateStateName = text(state, "CompensateState");
            if (compensateStateName == null) {
                continue;
            }
            JsonNode compState = findStateByName(states, compensateStateName);
            assertNotNull(compState,
                    "CompensateState '" + compensateStateName + "' must exist");
            String compMethod = text(compState, "ServiceMethod");
            assertNotNull(compMethod,
                    "Compensation state '" + compensateStateName + "' must declare a ServiceMethod");
            boolean isInverse = compMethod.startsWith("compensate")
                    || compMethod.startsWith("cancel")
                    || compMethod.startsWith("refund")
                    || compMethod.startsWith("release");
            assertTrue(isInverse,
                    "Compensation ServiceMethod '" + compMethod + "' of state '"
                            + compensateStateName + "' should name an inverse action "
                            + "(compensate*/cancel*/refund*/release*)");
        }
    }

    @Test
    @DisplayName("create-order-saga.json: >=2 compensable forward tasks (LIFO ordering is meaningful)")
    void createOrderSaga_atLeastTwoCompensableTasks() throws Exception {
        JsonNode saga = loadSaga("saga/create-order-saga.json");
        JsonNode states = saga.get("States");

        int compensable = 0;
        for (JsonNode state : states) {
            if ("ServiceTask".equals(text(state, "Type"))
                    && state.has("CompensateState")) {
                compensable++;
            }
        }
        // Order saga should have >=2 compensable steps (e.g. ReserveStock,
        // CreatePayment, ConfirmOrder) so LIFO compensation ordering is
        // actually exercised.
        assertTrue(compensable >= 2,
                "Need >=2 compensable tasks to validate LIFO order. Found: " + compensable);
    }

    @Test
    @DisplayName("create-order-saga.json: forward chain is acyclic and reaches Succeed")
    void createOrderSaga_forwardChainReachesSucceed() throws Exception {
        JsonNode saga = loadSaga("saga/create-order-saga.json");
        JsonNode states = saga.get("States");
        String start = text(saga, "StartState");

        Set<String> visited = new HashSet<>();
        String cursor = start;
        int guard = 0;
        while (cursor != null && !"Succeed".equals(cursor) && !"Fail".equals(cursor)) {
            if (!visited.add(cursor)) {
                fail("Forward chain has a cycle revisiting state: " + cursor);
            }
            JsonNode state = findStateByName(states, cursor);
            assertNotNull(state, "Forward chain references unknown state: " + cursor);
            cursor = text(state, "Next");
            if (++guard > 100) {
                fail("Forward chain too long (guard) — possible runaway Next wiring");
            }
        }
        assertEquals("Succeed", cursor,
                "Happy-path forward chain starting at '" + start + "' must reach Succeed");
    }

    // ------------------------------------------------------------------
    // cancel-order-saga.json
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancel-order-saga.json: valid cancellation flow")
    void cancelOrderSaga_validFlow() throws Exception {
        JsonNode saga = loadSaga("saga/cancel-order-saga.json");

        assertNotNull(saga.get("Name"), "Saga must have a Name");
        assertNotNull(saga.get("States"), "Saga must define States");
        assertEquals("cancelOrderSaga", text(saga, "Name"),
                "Saga name should indicate cancel operation");
        // The cancel saga's forward path IS the compensation of the original
        // create-order saga, so it need not declare CompensateState pointers
        // on its own forward steps.
        JsonNode states = saga.get("States");
        assertNotNull(findStateByType(states, "Succeed"),
                "cancel-order saga must declare a Succeed terminal state");
        assertNotNull(findStateByType(states, "Fail"),
                "cancel-order saga must declare a Fail terminal state");
    }

    @Test
    @DisplayName("cancel-order-saga.json: forward chain reaches Succeed")
    void cancelOrderSaga_forwardChainReachesSucceed() throws Exception {
        JsonNode saga = loadSaga("saga/cancel-order-saga.json");
        JsonNode states = saga.get("States");
        String start = text(saga, "StartState");

        Set<String> visited = new HashSet<>();
        String cursor = start;
        int guard = 0;
        while (cursor != null && !"Succeed".equals(cursor) && !"Fail".equals(cursor)) {
            if (!visited.add(cursor)) {
                fail("Cancel saga forward chain has a cycle revisiting: " + cursor);
            }
            JsonNode state = findStateByName(states, cursor);
            assertNotNull(state, "Cancel saga references unknown state: " + cursor);
            cursor = text(state, "Next");
            if (++guard > 100) {
                fail("Cancel saga forward chain too long (guard)");
            }
        }
        assertEquals("Succeed", cursor,
                "Cancel saga happy-path must reach Succeed");
    }

    // ------------------------------------------------------------------
    // Cross-saga consistency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("both saga definitions share the same compensation ServiceMethods")
    void bothSagas_shareCompensationServiceMethods() throws Exception {
        JsonNode create = loadSaga("saga/create-order-saga.json");
        JsonNode cancel = loadSaga("saga/cancel-order-saga.json");

        Set<String> createMethods = serviceMethods(create);
        Set<String> cancelMethods = serviceMethods(cancel);

        // The cancel saga's forward ServiceMethods should be a subset of the
        // create saga's compensation ServiceMethods (they are the SAME inverse
        // actions: compensateReserveStock, compensateCreatePayment, cancelOrder).
        assertFalse(cancelMethods.isEmpty(), "cancel saga must define ServiceMethods");
        for (String m : cancelMethods) {
            assertTrue(createMethods.contains(m),
                    "cancel-order saga method '" + m
                            + "' should also appear in create-order saga (shared compensation)");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private JsonNode loadSaga(String resourcePath) throws Exception {
        // 1. Try the integration-tests classpath (test resources).
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        // 2. Fallback: read directly from the order-platform-service resources so the
        //    test does not require copying saga JSONs into integration-tests.
        if (is == null) {
            Path direct = Path.of("../order-platform/order-platform-service/src/main/resources/" + resourcePath);
            if (Files.exists(direct)) {
                is = Files.newInputStream(direct);
            }
        }
        assertNotNull(is, "Cannot find saga definition on classpath or disk: " + resourcePath);
        return objectMapper.readTree(is);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static JsonNode findStateByType(JsonNode states, String type) {
        for (JsonNode state : states) {
            if (type.equals(text(state, "Type"))) {
                return state;
            }
        }
        return null;
    }

    private static JsonNode findStateByName(JsonNode states, String name) {
        for (JsonNode state : states) {
            if (name.equals(text(state, "Name"))) {
                return state;
            }
        }
        return null;
    }

    private static Set<String> collectStateNames(JsonNode states) {
        Set<String> names = new HashSet<>();
        for (JsonNode state : states) {
            String n = text(state, "Name");
            if (n != null) {
                names.add(n);
            }
        }
        return names;
    }

    private static Set<String> serviceMethods(JsonNode saga) {
        Set<String> methods = new HashSet<>();
        JsonNode states = saga.get("States");
        for (JsonNode state : states) {
            String m = text(state, "ServiceMethod");
            if (m != null) {
                methods.add(m);
            }
        }
        return methods;
    }
}
