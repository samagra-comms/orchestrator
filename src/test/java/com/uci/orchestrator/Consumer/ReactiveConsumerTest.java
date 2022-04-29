package com.uci.orchestrator.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uci.dao.models.XMessageDAO;
import com.uci.dao.repository.XMessageRepository;
import com.uci.orchestrator.ApplicationConfiguration;
import com.uci.utils.BotService;
import com.uci.utils.CampaignService;
import com.uci.utils.cache.service.RedisCacheService;
import com.uci.utils.kafka.ReactiveProducer;
import com.uci.utils.kafka.SimpleProducer;
import com.uci.utils.service.UserService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.stream.Consumer;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import java.time.LocalDateTime;


//@Slf4j
@SpringBootTest(classes = ApplicationConfiguration.class)
@ExtendWith(MockitoExtension.class)
class ReactiveConsumerTest {

    @Autowired
    ReactiveConsumer reactiveConsumer;

    @MockBean
    XMessageRepository xMessageRepository;

    @Autowired
    public SimpleProducer kafkaProducer;

    @Autowired
    public ReactiveProducer reactiveProducer;

    @MockBean
    public BotService botService;

    @MockBean
    public CampaignService campaignService;

    @MockBean
    public UserService userService;

    @MockBean
    public RedisCacheService redisCacheService;


    @BeforeAll
    public static void setup(){
        MockConsumer mockConsumer = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST);
    }

    @Test
    void onMessage() throws JsonProcessingException {

        ObjectMapper objectMapper = new ObjectMapper();
        Mockito.when(botService.getCurrentAdapter("UCI Demo")).thenReturn(Mono.just("44a9df72-3d7a-4ece-94c5-98cf26307324"));
        JsonNode campaignNode = objectMapper.readTree("{\"id\":\"d655cf03-1f6f-4510-acf6-d3f51b488a5e\",\"name\":\"UCI Demo\",\"startingMessage\":\"Hi UCI\",\"users\":[],\"logicIDs\":[\"e96b0865-5a76-4566-8694-c09361b8ae32\"],\"owners\":null,\"created_at\":\"2021-07-08T18:48:37.740Z\",\"updated_at\":\"2022-02-11T14:09:53.570Z\",\"status\":\"enabled\",\"description\":\"For Internal Demo\",\"startDate\":\"2022-02-01T00:00:00.000Z\",\"endDate\":null,\"purpose\":\"For Internal Demo\",\"ownerOrgID\":\"ORG_001\",\"ownerID\":\"95e4942d-cbe8-477d-aebd-ad8e6de4bfc8\",\"userSegments\":[],\"logic\":[{\"id\":\"e96b0865-5a76-4566-8694-c09361b8ae32\",\"transformers\":[{\"id\":\"bbf56981-b8c9-40e9-8067-468c2c753659\",\"meta\":{\"form\":\"https://hosted.my.form.here.com\",\"formID\":\"UCI-demo-1\"}}],\"adapter\":{\"id\":\"44a9df72-3d7a-4ece-94c5-98cf26307324\",\"channel\":\"WhatsApp\",\"provider\":\"gupshup\",\"config\":{\"2WAY\":\"2000193033\",\"phone\":\"9876543210\",\"HSM_ID\":\"2000193031\",\"credentials\":{\"vault\":\"samagra\",\"variable\":\"gupshupSamagraProd\"}},\"name\":\"SamagraProd\",\"updated_at\":\"2021-06-16T06:02:39.125Z\",\"created_at\":\"2021-06-16T06:02:41.823Z\"},\"name\":\"UCI Demo\",\"created_at\":\"2021-07-08T18:47:44.925Z\",\"updated_at\":\"2022-02-03T12:29:32.959Z\",\"description\":null}]}");
        Mockito.when(campaignService.getCampaignFromNameTransformer("UCI Demo")).thenReturn(Mono.just(campaignNode));
        userService.CAMPAIGN_URL = "http://localhost";
        Mockito.when(userService.getUsersFromFederatedServers(Mockito.anyString())).thenReturn(new JSONArray());
        Mockito.when(redisCacheService.getFAUserIDForAppCache("yOJcM+Gm7yVkKeQqPhdDKNb0wsmh8St/ty+pM5Q+4W4=" + "-" + "d655cf03-1f6f-4510-acf6-d3f51b488a5e")).thenReturn("91311fd1-5c1c-4f81-b9eb-2d259159554a");
        Mockito.when(xMessageRepository.findAllByUserIdAndTimestampAfter("7823807161", Mockito.any())).thenReturn(Flux.just(new XMessageDAO()));
        reactiveConsumer.onMessage();

    }
}