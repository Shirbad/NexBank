package com.nexbank.fraud.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
public class FraudExplanationClient {
    private static final Logger log=LoggerFactory.getLogger(FraudExplanationClient.class);
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final RestClient.Builder restClientBuilder;

    @Autowired
    public FraudExplanationClient(@Value("${AI_API_KEY:}") String apiKey,
                                  @Value("${AI_API_URL:}") String apiUrl,
                                  @Value("${AI_MODEL:}") String model,RestClient.Builder restClientBuilder) {
        this.apiKey=apiKey; this.apiUrl=apiUrl; this.model=model;this.restClientBuilder=restClientBuilder;
    }

    public String explain(BigDecimal amount, int score, List<String> reasons) {
        if (apiKey.isBlank() || apiUrl.isBlank() || model.isBlank()) return null;
        try {
            RestClient client=restClientBuilder.clone().baseUrl(apiUrl).defaultHeader("Authorization","Bearer "+apiKey).build();
            Map<String,Object> request=Map.of(
                    "model",model,
                    "temperature",0.2,
                    "messages",List.of(
                            Map.of("role","system","content","Explain a rule-based banking fraud alert in plain English for a compliance officer. Do not make or recommend the final block-or-allow decision. Use only the supplied masked transaction facts."),
                            Map.of("role","user","content","Amount INR "+amount.toPlainString()+"; risk score "+score+"/100; rule findings: "+String.join("; ",reasons))));
            JsonNode response=client.post().uri("/chat/completions").body(request).retrieve().body(JsonNode.class);
            JsonNode content=response==null?null:response.path("choices").path(0).path("message").path("content");
            return content==null||content.isMissingNode()||content.isNull()?null:content.asText();
        } catch (Exception e) {
            log.warn("Fraud explanation provider failed; alert will be saved without AI text",e);
            return null;
        }
    }
}
