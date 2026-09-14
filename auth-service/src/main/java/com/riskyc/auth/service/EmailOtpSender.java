package com.riskyc.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends the OTP code over real SMTP via Spring Mail. Deliberately generic:
 * configured entirely through spring.mail.* properties (see application.yml),
 * so swapping Gmail's SMTP for SendGrid/Mailgun/SES's SMTP relay later is a
 * config change, not a code change.
 */
@Service
public class EmailOtpSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailOtpSender(JavaMailSender mailSender, @Value("${riskyc.mail.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void sendOtp(String toEmail, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Your RiskyC Chat verification code");
        message.setText("Your verification code is " + code + ". It expires in 5 minutes.\n\n"
                + "If you didn't request this, you can ignore this email.");
        mailSender.send(message);
    }
}
