package com.ep.payment.ledger;

/**
 * D-238/A3: chart of accounts — the "5 套账务底座" subjects from the AI-payment
 * clearing/settlement reference model (「AI支付」文档 · 123457 模型).
 *
 * <p>Every unit of money entering the system has a subject waiting for it
 * ("每一笔钱，从进来那一刻起，就要有一个明确的科目等着它。接住了，就不会丢").
 *
 * <p>Subjects (reference doc listing, normalized to numeric codes):
 * <ul>
 *   <li>{@link #CLEARING_IN_TRANSIT 清算往来} — channel clearing in-transit
 *       suspense; money charged but not yet reconciled with the channel.</li>
 *   <li>{@link #CHANNEL_RECEIVABLE 渠道收款} / {@link #CHANNEL_PAYABLE 渠道付款}
 *       — channel-side receivable/payable.</li>
 *   <li>{@link #BANK_CLEARED_RECEIVABLE 已清算应收银行} — cleared channel funds
 *       at the bank (posted when 交易对账 matches).</li>
 *   <li>{@link #BANK_VERIFIED_PAYABLE 已核对应付银行} — verified payable bank.</li>
 *   <li>{@link #BANK_RESERVE 银行存款备付金} — client reserve funds
 *       (posted when 资金对账 matches the settlement file).</li>
 *   <li>{@link #MERCHANT_PENDING 待结算商户} — merchant settlement pending
 *       (posted when the user payment succeeds).</li>
 *   <li>{@link #MERCHANT_SETTLEMENT 商户结算户} — merchant settlement account
 *       (posted when 商户结算 runs).</li>
 *   <li>{@link #PLATFORM_INCOME 平台收入户} — platform fee income.</li>
 *   <li>{@link #SETTLEMENT_TRANSIT 结算过渡户} / {@link #CLEARING_TRANSIT 清算过渡户}
 *       — transit suspense for settlement/clearing error handling.</li>
 * </ul>
 *
 * <p>Every posting is double-entry: the sum of debits equals the sum of
 * credits within one voucher. {@code direction} follows bookkeeping
 * convention from the perspective of our platform ledger.
 */
public enum LedgerSubject {

    // ---- clearing/settlement transit & channel subjects ----
    CLEARING_IN_TRANSIT("1001", "清算往来", Direction.DEBIT_NORMAL),
    CHANNEL_RECEIVABLE("1002", "渠道收款", Direction.DEBIT_NORMAL),
    CHANNEL_PAYABLE("1003", "渠道付款", Direction.CREDIT_NORMAL),
    BANK_CLEARED_RECEIVABLE("1004", "已清算应收银行", Direction.DEBIT_NORMAL),
    BANK_VERIFIED_PAYABLE("1005", "已核对应付银行", Direction.CREDIT_NORMAL),
    BANK_RESERVE("1006", "银行存款备付金", Direction.DEBIT_NORMAL),

    // ---- merchant & platform subjects ----
    MERCHANT_PENDING("1007", "待结算商户", Direction.CREDIT_NORMAL),
    MERCHANT_SETTLEMENT("1008", "商户结算户", Direction.DEBIT_NORMAL),
    PLATFORM_INCOME("1009", "平台收入户", Direction.CREDIT_NORMAL),

    // ---- error-handling transit subjects (差错/长短款) ----
    SETTLEMENT_TRANSIT("1010", "结算过渡户", Direction.BOTH),
    CLEARING_TRANSIT("1011", "清算过渡户", Direction.BOTH),

    // ---- long/short money write-off (长短款核销) ----
    LONG_SHORT_WRITE_OFF("1012", "长短款核销", Direction.BOTH);

    public enum Direction { DEBIT_NORMAL, CREDIT_NORMAL, BOTH }

    private final String code;
    private final String name;
    private final Direction normalDirection;

    LedgerSubject(String code, String name, Direction normalDirection) {
        this.code = code;
        this.name = name;
        this.normalDirection = normalDirection;
    }

    public String code() { return code; }
    /** Subject display name — renamed to avoid clashing with {@link Enum#name()}. */
    public String displayName() { return name; }
    public Direction normalDirection() { return normalDirection; }
}
