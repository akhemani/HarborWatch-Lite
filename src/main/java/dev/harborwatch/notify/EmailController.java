package dev.harborwatch.notify;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EmailController {

	private final EmailService email;

	EmailController(EmailService email) {
		this.email = email;
	}

	@GetMapping("/api/email/test")
	public String sendTest(@RequestParam String from, @RequestParam String to) {
		String id = email.sendTest(from, to);
		return "OK messageId=" + id;
	}
}
