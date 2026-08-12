import org.springframework.cloud.contract.spec.Contract

// D-119: Consumer-driven contract for POST /api/payments/{orderId}/refund.
//
// Consumer: sp03-order-service (OrderServiceImpl.refundOrder -> PaymentService.refund)
// Producer: sp03-payment-service (PaymentController.refund)
//
// The order service refunds an order's payment after marking the order REFUNDED.
// This contract pins the HTTP response shape the consumer depends on.
Contract.make {
    name "payment_refund"
    description "POST /api/payments/{orderId}/refund refunds the order's payment and returns 200 with refundId + status"

    request {
        method 'POST'
        url '/api/payments/order-contract-1/refund'
        body([
            amount : "100.00",
            reason : "contract test refund"
        ])
        headers {
            contentType('application/json')
        }
    }

    response {
        status 200
        body([
            refundId : "refund-contract-1",
            orderId  : "order-contract-1",
            status   : "SUCCESS",
            amount   : "100.00"
        ])
        headers {
            contentType('application/json')
        }
    }
}
