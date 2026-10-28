@integration
Feature: 订单支付
  学生对已发布课程下单并支付;支付状态由订单承载 (FakeOrderService:
  PENDING -> payOrder -> PAID, payMethod 记录在订单上;refundOrder 仅允许
  PAID -> REFUNDED,重复退款被状态守卫拒绝)。

  Scenario: 学生下单并支付成功
    Given 已上架课程 "Java进阶"
    When 学生 "alice" 下单并使用 "ALIPAY" 支付
    Then 订单状态变为 PAID
    And 支付方式 "ALIPAY" 已记录在订单上

  Scenario: 已退款订单再次退款被拒绝
    Given 已上架课程 "Java进阶"
    And 学生 "bob" 已下单并支付成功
    And 该订单已退款
    When 再次对该订单发起退款
    Then 退款被拒绝
    And 订单状态保持 REFUNDED
