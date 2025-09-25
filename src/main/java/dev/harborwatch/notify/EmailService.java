package dev.harborwatch.notify;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

/**
 * EmailService
 *
 * - Uses AWS SDK v2 (SESv2) and DefaultCredentialsProvider, which on EC2 picks
 * up the attached IAM role automatically (IMDSv2). No AWS keys in env/files. -
 * Region is taken from env (AWS_REGION / AMAZON_REGION / AWS_DEFAULT_REGION)
 * with a default. - Minimal, safe logging. No PII in logs.
 *
 * Notes: - In SES Sandbox, the FROM and all TO addresses must be verified. - In
 * production SES, only the sender domain must be verified.
 */
@Service
public class EmailService {

	private static final Logger log = LoggerFactory.getLogger(EmailService.class);

	// Read region from common env vars; fall back to ap-south-1 (Mumbai)
	private static Region resolveRegion() {
		String r = getenvNonEmpty("AWS_REGION",
				getenvNonEmpty("AMAZON_REGION", getenvNonEmpty("AWS_DEFAULT_REGION", "us-east-1")));

		try {
			return Region.of(r);
		} catch (Exception e) {
			// As a last resort, default safely
			log.warn("Invalid AWS region '{}'; defaulting to ap-south-1", r);
			return Region.AP_SOUTH_1;
		}
	}

	private static String getenvNonEmpty(String k, String fallback) {
		String v = System.getenv(k);
		return (v != null && !v.isBlank()) ? v : fallback;
	}

	private static String getenvNonEmpty(String k) {
		return getenvNonEmpty(k, null);
	}

	private final SesV2Client ses;

	public EmailService() {
		// Build SESv2 client using:
		// - Region: from env (or default)
		// - Credentials: DefaultCredentialsProvider (prefers EC2 instance profile/IAM
		// role)
		this.ses = SesV2Client.builder().region(resolveRegion())
				.credentialsProvider(DefaultCredentialsProvider.create()).build();

		log.info("EmailService initialized with region={}", ses.serviceClientConfiguration().region());
	}

	/**
	 * Send a simple plaintext email.
	 *
	 * @param from    a verified email (or a domain identity in SES)
	 * @param to      list of recipients (verified if SES sandbox)
	 * @param subject subject line
	 * @param body    plain text body
	 * @return SES messageId on success
	 */
	public String sendPlainText(String from, List<String> to, String subject, String body) {
		Objects.requireNonNull(from, "from must not be null");
		if (to == null || to.isEmpty()) {
			throw new IllegalArgumentException("to must contain at least one recipient");
		}
		String scrubbedSubject = subject == null ? "" : subject;

		Destination destination = Destination.builder().toAddresses(to).build();

		Message message = Message.builder().subject(Content.builder().data(scrubbedSubject).build())
				.body(Body.builder().text(Content.builder().data(body == null ? "" : body).build()).build()).build();

		SendEmailRequest req = SendEmailRequest.builder().fromEmailAddress(from).destination(destination)
				.content(EmailContent.builder().simple(message).build()).build();

		try {
			SendEmailResponse resp = ses.sendEmail(req);
			String messageId = resp.messageId();
			log.info("SES sendEmail OK (messageId={})", messageId);
			return messageId;
		} catch (SdkServiceException se) {
			// Service exceptions: bad identity, sandbox violation, throttling, etc.
//			log.error("SES service error (statusCode={}, awsErrorCode={}): {}", se.statusCode(),
//					se.awsErrorDetails() != null ? se.awsErrorDetails().errorCode() : "n/a", se.getMessage());
			throw se;
		} catch (SdkClientException ce) {
			// Client exceptions: IMDS/credential issues, network/DNS, timeouts, etc.
			log.error("SES client error: {}", ce.getMessage());
			throw ce;
		} catch (Exception e) {
			log.error("Unexpected error sending SES email: {}", e.getMessage());
			throw e;
		}
	}

	/**
	 * Convenience: send a small test email to verify IAM role + SES setup. The
	 * 'from' should come from env or config (see example below).
	 */
	public String sendTest(String from, String to) {
		String now = ZonedDateTime.now().format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
		String subject = "HarborWatch SES IAM-role test";
		String content = "Hello from HarborWatch.\n\n" + "Time: " + now + "\n"
				+ "This email was sent using the EC2 instance role (DefaultCredentialsProvider).\n";
		return sendPlainText(from, List.of(to), subject, content);
	}

	@PreDestroy
	public void close() {
		try {
			ses.close();
		} catch (Exception e) {
			log.debug("SES client close ignored: {}", e.getMessage());
		}
	}
}
