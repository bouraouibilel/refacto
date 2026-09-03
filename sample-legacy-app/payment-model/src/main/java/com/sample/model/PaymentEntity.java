package com.sample.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;
import java.util.Optional;

@Entity
@Table(name = "T_PAYMENT")
public class PaymentEntity {

    @Id
    private Long id;
    private String customerId;
    private BigDecimal amount;
    private String status;

    // Violates JAVA17-007 (Optional JPA entity field)
    private Optional<String> comments;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Optional<String> getComments() { return comments; }
    public void setComments(Optional<String> comments) { this.comments = comments; }
}
