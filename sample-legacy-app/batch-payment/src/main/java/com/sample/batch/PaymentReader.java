package com.sample.batch;

import com.sample.model.PaymentEntity;
import org.springframework.batch.item.ItemReader;

import java.util.Iterator;
import java.util.List;

public class PaymentReader implements ItemReader<PaymentEntity> {

    private Iterator<PaymentEntity> iterator;

    @Override
    public PaymentEntity read() {
        if (iterator == null) {
            iterator = List.of(new PaymentEntity()).iterator();
        }
        return iterator.hasNext() ? iterator.next() : null;
    }
}
