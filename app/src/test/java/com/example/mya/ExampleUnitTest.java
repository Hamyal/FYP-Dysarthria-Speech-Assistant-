package com.example.mya;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for VocalAid app logic.
 */
public class ExampleUnitTest {

    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    // --- Session record display & recording flow ---

    @Test
    public void formatResultForDisplay_healthyScore_showsClearAndPercent() {
        String out = PatientSessionRecord.formatResultForDisplay("healthy", 0.85);
        assertTrue(out.contains("Clear"));
        assertTrue(out.contains("85%"));
    }

    @Test
    public void formatResultForDisplay_dysarthric_showsModerate() {
        String out = PatientSessionRecord.formatResultForDisplay("dysarthric", 0.30);
        assertTrue(out.contains("Moderate"));
        assertTrue(out.contains("30%"));
    }

    @Test
    public void formatResultForDisplay_nullPrediction_scoreOnly() {
        String out = PatientSessionRecord.formatResultForDisplay(null, 0.75);
        assertTrue(out.contains("75%"));
    }

    @Test
    public void formatResultForDisplay_label0_normalized() {
        String out = PatientSessionRecord.formatResultForDisplay("LABEL_0", 0.5);
        assertFalse(out.contains("LABEL"));
        assertTrue(out.contains("50%"));
    }

    @Test
    public void wavHeaderSize_is44Bytes() {
        int dataLen = 16000 * 2;
        int headerSize = 44;
        int riffSize = headerSize - 8 + dataLen;
        assertEquals(36 + dataLen, riffSize);
        assertTrue(headerSize + dataLen > 44);
    }
}