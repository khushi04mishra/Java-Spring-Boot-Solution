package com.example.bfhs;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

@Service
public class FlowService {
  private final Logger log = LoggerFactory.getLogger(FlowService.class);

  private final WebClient webClient;
  private final String name;
  private final String regNo;
  private final String email;
  private final String generateUrl;
  private final String submitUrl;
  private final String q1Url;
  private final String q2Url;
  private final String localQuestionFile;
  private final String inlineQuestion;

  public FlowService(WebClient.Builder webClientBuilder,
                     @Value("${bfh.name}") String name,
                     @Value("${bfh.regNo}") String regNo,
                     @Value("${bfh.email}") String email,
                     @Value("${bfh.generate.url}") String generateUrl,
                     @Value("${bfh.submit.url}") String submitUrl,
                     @Value("${bfh.question1.url}") String q1Url,
                     @Value("${bfh.question2.url}") String q2Url,
                     @Value("${bfh.local.question-file:}") String localQuestionFile,
                     @Value("${bfh.inline.question:}") String inlineQuestion) {
    this.webClient = webClientBuilder
        .baseUrl("") // we'll use full urls per call
        .build();
    this.name = name;
    this.regNo = regNo;
    this.email = email;
    this.generateUrl = generateUrl;
    this.submitUrl = submitUrl;
    this.q1Url = q1Url;
    this.q2Url = q2Url;
    this.localQuestionFile = localQuestionFile;
    this.inlineQuestion = inlineQuestion;
  }

  public void executeFlow() {
    log.info("Starting BFH solve flow for {}, regNo {}", name, regNo);

    // 1. Call generateWebhook
    Map<String, String> requestBody = Map.of(
        "name", name,
        "regNo", regNo,
        "email", email
    );

    GenerateResponse generateResponse = webClient.post()
        .uri(URI.create(generateUrl))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(requestBody)
        .retrieve()
        .bodyToMono(GenerateResponse.class)
        .retryWhen(retrySpec())
        .block();

    if (generateResponse == null || !StringUtils.hasText(generateResponse.getWebhook())) {
      log.error("Failed to get webhook from generateWebhook response: {}", generateResponse);
      return;
    }

    String webhookUrl = generateResponse.getWebhook();
    String accessToken = generateResponse.getAccessToken(); // treat as JWT

    log.info("Received webhook: {}, accessToken present: {}", webhookUrl, accessToken != null);

    // 2. Determine which question based on regNo last two digits
    boolean lastTwoDigitsOdd = isRegNoLastTwoDigitsOdd(regNo);
    String chosenQuestionUrl = lastTwoDigitsOdd ? q1Url : q2Url;
    log.info("RegNo last two digits odd? {} -> choosing question URL: {}", lastTwoDigitsOdd, chosenQuestionUrl);

    // 3. Try to fetch question text
    String questionText = fetchQuestionText(chosenQuestionUrl)
        .orElseGet(() -> {
          log.warn("Could not fetch remote question text; trying inline / local fallback");
          return fallbackQuestionText().orElse(null);
        });

    if (questionText == null) {
      log.error("No question text available. Provide inline question via application.properties or upload a local file.");
      return;
    }

    log.info("Question text length: {}", questionText.length());

    // 4. Solve the question -> produce finalQuery
    String finalQuery;
    try {
      finalQuery = solveSqlQuestion(questionText);
    } catch (Exception e) {
      log.error("Failed to solve SQL question automatically: {}", e.getMessage(), e);
      // fallback: you can place the final query manually in application.properties
      finalQuery = null;
    }

    if (!StringUtils.hasText(finalQuery)) {
      log.warn("No finalQuery produced automatically. Please provide 'finalQuery' in application.properties or paste it here.");
      return;
    }

    // 5. Submit finalQuery to webhook using accessToken as Authorization header
    Map<String, String> submitBody = Map.of("finalQuery", finalQuery);

    try {
      webClient.post()
          .uri(URI.create(submitUrl))
          .header(HttpHeaders.AUTHORIZATION, accessToken)
          .contentType(MediaType.APPLICATION_JSON)
          .body(BodyInserters.fromValue(submitBody))
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(20))
          .block();
      log.info("Successfully submitted finalQuery to testWebhook");
    } catch (Exception ex) {
      log.error("Failed to submit finalQuery: {}", ex.getMessage(), ex);
    }
  }

  private reactor.util.retry.Retry retrySpec() {
    return reactor.util.retry.Retry.backoff(3, Duration.ofSeconds(1))
        .maxBackoff(Duration.ofSeconds(5))
        .jitter(0.25);
  }

  private Optional<String> fetchQuestionText(String questionUrl) {
    if (!StringUtils.hasText(questionUrl)) return Optional.empty();
    try {
      // Try to download raw text (many drive links won't allow direct access; user-provided link might)
      log.info("Attempting to fetch question from URL: {}", questionUrl);
      String page = webClient.get()
          .uri(URI.create(questionUrl))
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(10))
          .block();
      if (page != null && page.length() > 20) {
        return Optional.of(page);
      } else {
        log.warn("Fetched page empty or too short");
      }
    } catch (Exception e) {
      log.warn("Error fetching remote question URL: {}", e.getMessage());
    }
    return Optional.empty();
  }

  private Optional<String> fallbackQuestionText() {
    if (StringUtils.hasText(inlineQuestion)) {
      return Optional.of(inlineQuestion);
    }
    if (StringUtils.hasText(localQuestionFile)) {
      try {
        var p = Path.of(localQuestionFile);
        if (Files.exists(p)) {
          return Optional.of(String.join("\n", Files.readAllLines(p)));
        }
      } catch (Exception e) {
        log.warn("Error reading local question file: {}", e.getMessage());
      }
    }
    return Optional.empty();
  }

  private boolean isRegNoLastTwoDigitsOdd(String regNo) {
    if (regNo == null) return true; // default
    // extract digits from end
    String digits = regNo.replaceAll("\\D+", "");
    if (digits.length() >= 2) {
      String lastTwo = digits.substring(digits.length() - 2);
      try {
        int val = Integer.parseInt(lastTwo);
        return (val % 2) == 1;
      } catch (NumberFormatException e) {
        // fallback to last digit
      }
    }
    // fallback: last digit
    if (digits.length() >= 1) {
      int last = Character.getNumericValue(digits.charAt(digits.length() - 1));
      return (last % 2) == 1;
    }
    return true;
  }

  /**
   * Solve SQL question from plain text.
   * IMPORTANT: This method is a placeholder for automatic solving logic.
   * For full guarantee, you should paste the SQL final query here or upload the question
   * and I will return the optimized final query.
   */
  private String solveSqlQuestion(String questionText) {
    // This method can be extended to parse the question and produce a SQL statement.
    // For now - attempt to detect "expected final query" if the question contains it;
    // Otherwise return null and let the user supply the final query.
    // I'll replace this with a precise solver once you upload the question image/text.
    return null;
  }
}
