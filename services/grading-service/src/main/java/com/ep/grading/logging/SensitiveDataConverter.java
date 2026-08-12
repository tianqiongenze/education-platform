package com.ep.grading.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Masks sensitive data patterns in log messages.
 * Masks: passwords, tokens, API keys, phone numbers, ID cards, emails, bank cards.
 * Security D-077: prevent sensitive data leakage in logs.
 * D-101: added email + bank card patterns for industry-agnostic PII protection.
 */
public class SensitiveDataConverter extends ClassicConverter {

    // password=xxx, password":"xxx", pwd=xxx, token=xxx, api_key=xxx, access-key=xxx
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
        "(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key)([\"']?\\s*[:=]\\s*[\"']?)([^\"'&\\s,)]+)");
    // Chinese mobile: 1XX-XXXX-XXXX
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "(1[3-9]\\d)\\d{4}(\\d{4})");
    // ID card (18 digits): must not be part of a longer digit sequence (word boundary)
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
        "(?<!\\d)(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?!\\d)");
    // Email: local-part@domain — mask the local part, keep domain for debugging
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "([a-zA-Z0-9._%+-]{1,2})[a-zA-Z0-9._%+-]*@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
    // Bank card (16-19 digits): must not be part of a longer digit sequence
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
        "(?<!\\d)(\\d{4})\\d{8,11}(\\d{4})(?!\\d)");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        if (msg == null) return "";
        msg = PASSWORD_PATTERN.matcher(msg).replaceAll("$1$2****");
        msg = EMAIL_PATTERN.matcher(msg).replaceAll("$1***@$2");
        msg = ID_CARD_PATTERN.matcher(msg).replaceAll("$1********$2");
        msg = BANK_CARD_PATTERN.matcher(msg).replaceAll("$1********$2");
        msg = PHONE_PATTERN.matcher(msg).replaceAll("$1****$2");
        return msg;
    }
}
