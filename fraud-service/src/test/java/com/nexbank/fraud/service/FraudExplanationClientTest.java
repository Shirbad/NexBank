package com.nexbank.fraud.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class FraudExplanationClientTest {
    @Test void returnsPlainEnglishExplanationUsingOnlyMaskedRuleContext(){
        RestClient.Builder builder=RestClient.builder();
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://llm.example/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization","Bearer test-key"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Amount INR 2500.00")))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"Large amount to a new recipient.\"}}]}",MediaType.APPLICATION_JSON));

        FraudExplanationClient client=new FraudExplanationClient("test-key","https://llm.example","test-model",builder);

        assertEquals("Large amount to a new recipient.",client.explain(new BigDecimal("2500.00"),40,List.of("first recipient")));
        server.verify();
    }

    @Test void skipsProviderWhenNotConfigured(){
        FraudExplanationClient client=new FraudExplanationClient("","","",RestClient.builder());
        assertNull(client.explain(new BigDecimal("10.00"),20,List.of("rule")));
    }

    @Test void providerFailureFallsBackToNoExplanation(){
        RestClient.Builder builder=RestClient.builder();
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://llm.example/chat/completions"))
                .andRespond(withServerError());
        FraudExplanationClient client=new FraudExplanationClient("test-key","https://llm.example","test-model",builder);

        assertNull(client.explain(new BigDecimal("2500.00"),40,List.of("first recipient")));
        server.verify();
    }
}
