package com.sample.common;

import com.sample.model.PaymentEntity;
import java.util.List;

/**
 * Shared DAO used across multiple batches (Batch A, Batch B, PaymentBatch).
 * Violates APP-ARCH-001 (Coupling score HIGH).
 */
public class SharedCustomerDAO {

    public List<PaymentEntity> findCustomerPayments(String customerId) {
        // Query implementation
        return List.of();
    }
}
