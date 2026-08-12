package com.ep.data.cdc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpTypeTest {

    @Test
    void fromCode_returnsAllFourTypes() {
        assertEquals(OpType.CREATE, OpType.fromCode("c"));
        assertEquals(OpType.UPDATE, OpType.fromCode("u"));
        assertEquals(OpType.DELETE, OpType.fromCode("d"));
        assertEquals(OpType.READ, OpType.fromCode("r"));
    }

    @Test
    void fromCode_isCaseInsensitiveAndToleratesWhitespace() {
        assertEquals(OpType.CREATE, OpType.fromCode("C"));
        assertEquals(OpType.UPDATE, OpType.fromCode(" U "));
        assertEquals(OpType.DELETE, OpType.fromCode("D"));
    }

    @Test
    void fromCode_throwsOnInvalidCode() {
        assertThrows(IllegalArgumentException.class, () -> OpType.fromCode("x"));
    }

    @Test
    void fromCode_throwsOnNull() {
        assertThrows(IllegalArgumentException.class, () -> OpType.fromCode(null));
    }

    @Test
    void codeReturnsExpectedSingleChar() {
        assertEquals("c", OpType.CREATE.code());
        assertEquals("u", OpType.UPDATE.code());
        assertEquals("d", OpType.DELETE.code());
        assertEquals("r", OpType.READ.code());
    }

    @Test
    void valuesContainsAllFour() {
        assertEquals(4, OpType.values().length);
        assertNotNull(OpType.valueOf("CREATE"));
    }
}
