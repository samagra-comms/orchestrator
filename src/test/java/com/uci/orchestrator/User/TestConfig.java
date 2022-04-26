package com.uci.orchestrator.User;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.uci.orchestrator.Consumer.CampaignConsumer;
import com.uci.utils.CampaignService;
import com.uci.utils.kafka.SimpleProducer;
import io.fusionauth.client.FusionAuthClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;


public class TestConfig {

    @Autowired
    SimpleProducer simpleProducer;

    @Bean
    public Logger getLogger(){
        return LogManager.getLogger();
    }

    @Bean
    public CampaignService getCampaignService() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode botNode = objectMapper.readTree("{\"id\":\"api.bot.getByParam\",\"ver\":\"1.0\",\"ts\":\"2022-04-21T19:16:52.914Z\",\"params\":{\"resmsgid\":\"9a01a120-c1a7-11ec-afae-4f4769c7c758\",\"msgid\":\"9a00b6c0-c1a7-11ec-afae-4f4769c7c758\",\"status\":\"successful\",\"err\":null,\"errmsg\":null},\"responseCode\":\"OK\",\"result\":{\"data\":{\"id\":\"d655cf03-1f6f-4510-acf6-d3f51b488a5e\",\"name\":\"UCI Demo\",\"startingMessage\":\"Hi UCI\",\"users\":[],\"logicIDs\":[\"e96b0865-5a76-4566-8694-c09361b8ae32\"],\"owners\":null,\"created_at\":\"2021-07-08T18:48:37.740Z\",\"updated_at\":\"2022-02-11T14:09:53.570Z\",\"status\":\"enabled\",\"description\":\"For Internal Demo\",\"startDate\":\"2022-02-01T00:00:00.000Z\",\"endDate\":null,\"purpose\":\"For Internal Demo\",\"ownerOrgID\":\"ORG_001\",\"ownerID\":\"95e4942d-cbe8-477d-aebd-ad8e6de4bfc8\",\"logic\":[{\"id\":\"e96b0865-5a76-4566-8694-c09361b8ae32\",\"transformers\":[{\"id\":\"bbf56981-b8c9-40e9-8067-468c2c753659\",\"meta\":{\"form\":\"https://hosted.my.form.here.com\",\"formID\":\"UCI-demo-1\"}}],\"adapter\":\"44a9df72-3d7a-4ece-94c5-98cf26307324\",\"name\":\"UCI Demo\",\"created_at\":\"2021-07-08T18:47:44.925Z\",\"updated_at\":\"2022-02-03T12:29:32.959Z\",\"description\":null}]}}}\n");
        Cache cache = Mockito.mock(Cache.class);
        Mockito.when(cache.getIfPresent("campaign-node-by-id:d655cf03-1f6f-4510-acf6-d3f51b488a5e")).thenReturn(botNode);
        return new CampaignService(getWebClient(), getFusionAuthClient(), cache);
    }

    @Bean
    public CampaignConsumer getCampaignConsumer(){
        return new CampaignConsumer();
    }

    @Bean
    public FusionAuthClient getFusionAuthClient() {
        return new FusionAuthClient("c0VY85LRCYnsk64xrjdXNVFFJ3ziTJ91r08Cm0Pcjbc", "http://134.209.150.161:9011");
    }

    @Bean
    public WebClient getWebClient(){
        return WebClient.builder().build();
    }

    @Bean
    public Cache getCache(){
        return null;
    }

    @Bean
    public SimpleProducer getSimpleProducer(){
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "165.232.182.146:9094");
        configuration.put(ProducerConfig.CLIENT_ID_CONFIG, "sample-producer");
        configuration.put(ProducerConfig.ACKS_CONFIG, "all");
        configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);
        configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);

        ProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<String, String>(configuration);
        return new SimpleProducer(new KafkaTemplate<String, String>(producerFactory));
    }
}