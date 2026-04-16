package com.plm.auth.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

class RegisterEmailTemplateRendererTest {

    private final RegisterEmailTemplateRenderer renderer = new RegisterEmailTemplateRenderer();

    @Test
    void verificationTemplate_shouldRenderWhenHtmlContainsLiteralPercent() {
        String html = renderer.render("alice@example.com", "123456", OffsetDateTime.now().plusMinutes(10));

        Assertions.assertTrue(html.contains("alice@example.com"));
        Assertions.assertTrue(html.contains("123456"));
        Assertions.assertTrue(html.contains("width=\"100%\""));
    }

    @Test
    void invitationTemplate_shouldRenderWhenHtmlContainsLiteralPercent() {
        String html = renderer.renderWorkspaceInvitationEmail(
                "Alpha Team",
                "Alice",
                "https://example.com/invite?token=abc",
                OffsetDateTime.now().plusHours(24));

        Assertions.assertTrue(html.contains("Alice"));
        Assertions.assertTrue(html.contains("Alpha Team"));
        Assertions.assertTrue(html.contains("https://example.com/invite?token=abc"));
        Assertions.assertTrue(html.contains("width=\"100%\""));
    }

    @Test
    void testTemplate_shouldRenderWhenHtmlContainsLiteralPercent() {
        OffsetDateTime sentAt = OffsetDateTime.parse("2026-04-16T18:00:00+08:00");
        String html = renderer.renderTestEmail("alice@example.com", sentAt);

        Assertions.assertTrue(html.contains("alice@example.com"));
        Assertions.assertTrue(html.contains(sentAt.toString()));
        Assertions.assertTrue(html.contains("width=\"100%\""));
    }
}