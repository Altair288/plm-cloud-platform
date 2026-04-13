package com.plm.auth.service;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

@Component
public class RegisterEmailTemplateRenderer {
    public String render(String email, String verificationCode, OffsetDateTime expiresAt) {
        long minutes = Math.max(1, ChronoUnit.MINUTES.between(OffsetDateTime.now(), expiresAt));
        String safeEmail = escapeHtml(email);
        String safeCode = escapeHtml(verificationCode);
        return """
                <!DOCTYPE html>
                <html lang=\"en\">
                <head>
                  <meta charset=\"UTF-8\" />
                  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />
                  <title>PLM Cloud Verification</title>
                </head>
                <body style=\"margin:0;padding:0;background:#f3f5f8;font-family:Segoe UI,Arial,sans-serif;color:#1f2937;\">
                  <div style=\"padding:32px 16px;\">
                    <div style=\"max-width:560px;margin:0 auto;\">
                      <div style=\"text-align:center;margin-bottom:18px;color:#64748b;font-size:12px;letter-spacing:0.18em;text-transform:uppercase;\">PLM Cloud Security Verification</div>
                      <div style=\"background:#ffffff;border:1px solid #dbe3ea;border-radius:24px;box-shadow:0 18px 48px rgba(15,23,42,0.08);overflow:hidden;\">
                        <div style=\"padding:28px 28px 18px;text-align:center;border-bottom:1px solid #eef2f7;\">
                          <div style=\"width:56px;height:56px;margin:0 auto 18px;border-radius:18px;background:linear-gradient(135deg,#0f172a,#2563eb);color:#ffffff;font-size:28px;line-height:56px;font-weight:700;\">P</div>
                          <div style=\"font-size:34px;line-height:1.15;font-weight:700;color:#111827;\">Email Verification</div>
                          <div style=\"margin-top:12px;font-size:15px;line-height:1.7;color:#475569;\">Use this code to complete your PLM Cloud registration for <strong>%s</strong>.</div>
                        </div>
                        <div style=\"padding:28px;\">
                          <div style=\"text-align:center;font-size:44px;font-weight:700;letter-spacing:0.2em;color:#0f172a;padding:18px 12px;border-radius:20px;background:linear-gradient(180deg,#f8fafc,#eef4ff);border:1px solid #d8e3ff;\">%s</div>
                          <div style=\"margin-top:18px;text-align:center;font-size:14px;color:#475569;line-height:1.7;\">The code will remain valid for the next <strong>%d minutes</strong>.</div>
                          <div style=\"margin-top:24px;padding-top:20px;border-top:1px solid #eef2f7;font-size:13px;line-height:1.8;color:#64748b;text-align:center;\">If you did not request this email, you can safely ignore it. For security reasons, do not share this code with anyone.</div>
                        </div>
                      </div>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(safeEmail, safeCode, minutes);
    }

    public String renderText(String verificationCode, OffsetDateTime expiresAt) {
        long minutes = Math.max(1, ChronoUnit.MINUTES.between(OffsetDateTime.now(), expiresAt));
        return "Your PLM Cloud verification code is " + verificationCode + ". It will expire in " + minutes + " minutes.";
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
          .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}