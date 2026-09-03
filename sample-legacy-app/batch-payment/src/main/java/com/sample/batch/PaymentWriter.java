package com.sample.batch;

import com.sample.model.PaymentEntity;
import org.springframework.batch.item.ItemWriter;

import javax.persistence.EntityManager;
import java.util.List;

public class PaymentWriter implements ItemWriter<PaymentEntity> {

    private EntityManager entityManager;

    @Override
    public void write(List<? extends PaymentEntity> items) throws Exception {
        // APP-DB-006: Chunk bulk update with individual loop saves
        for (PaymentEntity item : items) {
            item.setStatus("PROCESSED");
            entityManager.merge(item);
            entityManager.flush();
        }
    }
}
