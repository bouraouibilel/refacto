package com.sample.batch;

import com.sample.model.PaymentEntity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertNotNull;

public class PaymentProcessorTest {

    private PaymentProcessor processor;

    @Before
    public void setUp() {
        processor = new PaymentProcessor();
    }

    @After
    public void tearDown() {
        processor = null;
    }

    @Test
    public void testProcessorInstantiation() {
        assertNotNull(processor);
    }
}
