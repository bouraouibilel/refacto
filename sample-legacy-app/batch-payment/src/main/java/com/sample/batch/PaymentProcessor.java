package com.sample.batch;

import com.sample.common.SharedCustomerDAO;
import com.sample.model.PaymentEntity;
import org.springframework.batch.item.ItemProcessor;

import javax.persistence.EntityManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * PaymentProcessor legacy: cumule de multiples responsabilités, accès DB directs,
 * requêtes SELECT FOR UPDATE et appels de persistance en boucle.
 */
public class PaymentProcessor implements ItemProcessor<PaymentEntity, PaymentEntity> {

    // APP-DB-001: Direct DB access outside DAO
    private EntityManager entityManager;

    // APP-ARCH-001: Usage of shared business DAO
    private SharedCustomerDAO customerDAO;

    // Mixed responsibilities fields (APP-CODE-001)
    private PaymentValidator validator;
    private PaymentCsvWriter csvWriter;
    private NotificationService mailService;

    @Override
    public PaymentEntity process(PaymentEntity item) throws Exception {
        if (item == null) return null;

        // 1. Business validation mixed into main method (APP-CODE-002)
        if (item.getAmount() == null || item.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // 2. Embedded SQL with SELECT * and FOR UPDATE without deterministic ORDER BY (APP-DB-003, APP-DB-005)
        String lockSql = "SELECT * FROM t_payment p WHERE p.customer_id = '" + item.getCustomerId() + "' FOR UPDATE";

        // 3. Database operations in loop (APP-DB-004)
        List<PaymentEntity> previousPayments = customerDAO.findCustomerPayments(item.getCustomerId());
        for (PaymentEntity prev : previousPayments) {
            // N+1 calls
            entityManager.find(PaymentEntity.class, prev.getId());
        }

        // 4. Calculations mixed with orchestration (APP-CODE-002)
        BigDecimal fee = item.getAmount().multiply(new BigDecimal("0.02"));
        item.setAmount(item.getAmount().subtract(fee));

        // 5. File operation using legacy java.io.File (JAVA17-002)
        File auditFile = new File("/tmp/payment_audit.log");
        if (auditFile.exists()) {
            readLegacyAuditFile(auditFile);
        }

        return item;
    }

    // JAVA17-001: Manual close in finally block
    private void readLegacyAuditFile(File file) throws IOException {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            fis.read();
        } finally {
            if (fis != null) {
                fis.close();
            }
        }
    }

    // Dummy inner helpers to demonstrate responsibilities
    private static class PaymentValidator { void validate() {} }
    private static class PaymentCsvWriter { void export() {} }
    private static class NotificationService { void notifyCustomer() {} }
}
