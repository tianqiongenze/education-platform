package com.ep.web.common;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Map;

/**
 * v8 P2 (D-243): user-facing error message localization.
 *
 * <p>The v8 scorecard's usability gap — "错误码文案未国际化" — closed with a
 * deliberately small surface: only the <b>infrastructure-owned</b> error codes
 * (the ones this library emits) carry bilingual copy. Business-exception
 * messages ({@code ex.getMessage()} from domain code) are passed through
 * untouched — their localization belongs to the service that owns the wording,
 * not the shared web layer.</p>
 *
 * <p>Language resolution: {@code Accept-Language} header, zh family → zh-CN
 * copy, anything else → en copy (the English copy is also the fallback for a
 * missing header). Machine-readable {@code code} stays ASCII in both languages
 * so API clients keep parsing the same field.</p>
 */
public final class ErrorMessages {

    private ErrorMessages() {
    }

    /** zh-CN copy for the infrastructure-owned error codes. */
    private static final Map<String, String> ZH = Map.of(
            "BAD_REQUEST", "请求参数不合法",
            "CONFLICT", "请求与当前状态冲突",
            "NOT_FOUND", "请求的资源不存在",
            "FORBIDDEN", "没有权限执行该操作",
            "VALIDATION_ERROR", "参数校验未通过",
            "RATE_LIMITED", "请求过于频繁，请稍后重试",
            "IP_BANNED", "访问已被临时限制",
            "INTERNAL_ERROR", "系统繁忙，请稍后重试或联系支持人员"
    );

    /** en copy (also the default when the header is absent). */
    private static final Map<String, String> EN = Map.of(
            "BAD_REQUEST", "Invalid request",
            "CONFLICT", "The request conflicts with the current state",
            "NOT_FOUND", "The requested resource was not found",
            "FORBIDDEN", "You are not allowed to perform this action",
            "VALIDATION_ERROR", "Validation failed",
            "RATE_LIMITED", "Too many requests, please retry later",
            "IP_BANNED", "Access is temporarily restricted",
            "INTERNAL_ERROR", "An unexpected error occurred. Please retry or contact support."
    );

    /**
     * Codes whose copy is fixed infrastructure text — the only ones safe to
     * localize wholesale. Business-detail codes (CONFLICT with a refund
     * overdraw message, VALIDATION_ERROR with field paths, …) must pass their
     * detail through verbatim, so they are deliberately NOT in this set.
     */
    private static final java.util.Set<String> LOCALIZABLE =
            java.util.Set.of("INTERNAL_ERROR");

    /**
     * Localized message. Only fixed-copy codes are localized; everything else
     * returns the caller's raw message (business detail preserved verbatim).
     */
    public static String forCode(String code, String fallbackMessage, HttpServletRequest request) {
        if (!LOCALIZABLE.contains(code)) {
            return fallbackMessage;
        }
        Locale locale = resolveLocale(request);
        Map<String, String> bundle = isChinese(locale) ? ZH : EN;
        return bundle.getOrDefault(code, fallbackMessage);
    }

    /** Whether the request prefers Chinese (Accept-Language zh family). */
    public static boolean isChinese(HttpServletRequest request) {
        return isChinese(resolveLocale(request));
    }

    /** Accept-Language parsing — first entry wins, tolerant of malformed headers. */
    static Locale resolveLocale(HttpServletRequest request) {
        if (request == null) {
            return Locale.ENGLISH;
        }
        try {
            String header = request.getHeader("Accept-Language");
            if (header == null || header.isBlank()) {
                return Locale.ENGLISH;
            }
            // "zh-CN,zh;q=0.9,en;q=0.8" → first tag "zh-CN"
            String first = header.split(",")[0].trim();
            int q = first.indexOf(';');
            if (q > 0) {
                first = first.substring(0, q).trim();
            }
            return Locale.forLanguageTag(first.isEmpty() ? "en" : first);
        } catch (RuntimeException e) {
            return Locale.ENGLISH;
        }
    }

    private static boolean isChinese(Locale locale) {
        String lang = locale.getLanguage();
        return "zh".equalsIgnoreCase(lang) || "zh-cn".equalsIgnoreCase(locale.toLanguageTag());
    }
}
