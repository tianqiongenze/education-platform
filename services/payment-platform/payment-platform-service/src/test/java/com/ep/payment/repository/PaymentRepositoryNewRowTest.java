package com.ep.payment.repository;

import com.ep.payment.api.PaymentChannel;
import com.ep.payment.api.PaymentStatus;
import com.ep.payment.entity.PaymentEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * D-240/B (r28) contract test at the Hibernate level: a NEW payment row must
 * INSERT (persist), never merge.
 *
 * <p><b>Historical defect</b> (r25/r26/r27 failures): the domain {@code Payment}
 * constructor initialises {@code lockVersion=0}; copying that 0 into the
 * entity's {@code @Version} field makes Spring Data {@code isNew()} false, so
 * {@code save()} runs <b>merge()</b>. Hibernate 7 treats "detached entity with
 * non-null version + missing row" as a concurrent delete →
 * {@code StaleObjectStateException} → HTTP 500 on every agent payment (proven
 * on CRDB: 0 rows inserted). Both shapes are pinned here on a real H2/PG-mode
 * database — no Spring context, so the suite stays offline-safe.</p>
 */
class PaymentRepositoryNewRowTest {

    private static SessionFactory sf;

    @BeforeAll
    static void boot() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.url",
                        "jdbc:h2:mem:ep_payment_newrow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .build();
        sf = new MetadataSources(registry)
                .addAnnotatedClass(PaymentEntity.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void close() {
        if (sf != null) {
            sf.close();
        }
    }

    @Test
    @DisplayName("FIXED shape: preset id + null version → persist INSERTs, row readable, version assigned")
    void newPaymentInsertsNotMerges() {
        String paymentId = UUID.randomUUID().toString();
        PaymentEntity entity = newEntity(paymentId, "ord-fixed-1");
        entity.setLockVersion(null); // what r28's toEntity now guarantees

        try (Session session = sf.openSession()) {
            session.beginTransaction();
            session.persist(entity);
            session.getTransaction().commit();
        }

        try (Session session = sf.openSession()) {
            PaymentEntity reloaded = session.find(PaymentEntity.class, paymentId);
            assertNotNull(reloaded, "row must exist after save");
            assertEquals(PaymentStatus.SUCCESS, reloaded.getStatus());
            assertEquals("AG-IT-1", reloaded.getAgentId());
            assertNotNull(reloaded.getLockVersion(), "Hibernate assigns version on INSERT");
        }
    }

    @Test
    @DisplayName("BROKEN shape (the historical defect): version=0 on missing row → merge throws StaleObjectState")
    void mergeWithNonNullVersionOnMissingRowThrows() {
        PaymentEntity broken = newEntity(UUID.randomUUID().toString(), "ord-broken-1");
        broken.setLockVersion(0); // exactly what toEntity used to produce

        try (Session session = sf.openSession()) {
            session.beginTransaction();
            try {
                session.merge(broken);
                session.flush();
                throw new AssertionError("expected an optimistic-lock failure — "
                        + "this shape must never reach the repository");
            } catch (jakarta.persistence.OptimisticLockException
                    | org.hibernate.StaleStateException expected) {
                // Hibernate 7 raises StaleObjectStateException ("Row was already
                // updated or deleted by another transaction") at flush; the JPA
                // facade may wrap it in OptimisticLockException.
                session.getTransaction().rollback();
            }
        }
    }

    private static PaymentEntity newEntity(String paymentId, String orderId) {
        PaymentEntity e = new PaymentEntity();
        e.setPaymentId(paymentId);
        e.setOrderId(orderId);
        e.setTenantId("default");
        e.setAmount(new BigDecimal("60.00"));
        e.setCurrency("CNY");
        e.setChannel(PaymentChannel.MOCK);
        e.setStatus(PaymentStatus.SUCCESS);
        e.setChannelTxId("ch-tx-it-1");
        e.setCreatedAt(Instant.now());
        e.setPaidAt(Instant.now());
        e.setAgentId("AG-IT-1");
        e.setAuthorizationId("A-IT-1");
        return e;
    }
}
