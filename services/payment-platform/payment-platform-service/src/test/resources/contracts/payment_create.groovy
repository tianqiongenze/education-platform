import org.springframework.cloud.contract.spec.Contract

// D-119: Consumer-driven contract for POST /api/payments (payment creation).
//
// Consumer: sp03-order-service (OrderServiceImpl.payOrder -> PaymentService.createPayment)
// Producer: sp03-payment-service (PaymentController.create)
//
// The contract defines the HTTP shape the consumer expects. The
// spring-cloud-contract-maven-plugin generates:
//   1. A producer-side verifier test (extends PaymentServiceContractBase) that
//      asserts the real PaymentController honors this shape.
//   2. A WireMock stub jar that the consumer (sp03-order) uses in
//      PaymentServiceContractTest via @AutoConfigureStubRunner.
Contract.make {
    name "payment_create"
    description "POST /api/payments creates a payment and returns 201 with paymentId + status"

    request {
        method 'POST'
        url '/api/payments'
        body([
            orderId  : "order-contract-1",
            tenantId : "default",
            channel  : "MOCK",
            amount   : "100.00",
            currency : "CNY"
        ])
        headers {
            contentType('application/json')
        }
    }

    response {
        status 201
        body([
            paymentId  : "pay-contract-1",
            status     : "SUCCESS",
            channelTxId: "tx-contract-1",
            orderId    : "order-contract-1"
        ])
        headers {
            contentType('application/json')
        }
    }
}
