package com.uci.orchestrator.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uci.orchestrator.ApplicationConfiguration;
import com.uci.utils.CampaignService;
import com.uci.utils.kafka.SimpleProducer;
import messagerosa.core.model.XMessage;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

//@ExtendWith(MockitoExtension.class)
//@RunWith(SpringRunner.class)
@SpringBootTest(classes = ApplicationConfiguration.class)
public class CampaignConsumerTest {

	@Autowired
	SimpleProducer kafkaProducer;

	@Autowired
	public CampaignService campaignService;

	@Autowired
	public CampaignConsumer campaignConsumer;


////	@Before
//	@SneakyThrows
//    @BeforeAll
//	public static void init() throws JsonMappingException, JsonProcessingException {
////		System.out.println("setupp");
//
//		System.setProperty("log4j.configurationFile","log4j2-testconfig.xml");
//        LOGGER = LogManager.getLogger();
//
////
////        WebClient client = Mockito.mock(WebClient.class);
////
////        System.out.println(client);
////
////        System.out.println(client.get());
////
////		campaignService = new CampaignService(client, fusionAuthClient,null);
////
////		CampaignConsumer campaignConsumer = new CampaignConsumer();
//
//
////		Mockito.when(campaignService.getCampaignFromID("d655cf03-1f6f-4510-acf6-d3f51b488a5e")).thenReturn(Mono.just(json));
//
//	}

	@Test
	void consumeMessage() {
	}

	@Test
	void processMessage() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		JsonNode json = mapper.readTree("{\"id\":\"api.bot.getByParam\",\"ver\":\"1.0\",\"ts\":\"2021-09-07T09:13:15.692Z\",\"params\":{\"resmsgid\":\"d5809ec0-0fbb-11ec-8e04-21de24b1fc83\",\"msgid\":\"d57f6640-0fbb-11ec-8e04-21de24b1fc83\",\"status\":\"successful\",\"err\":null,\"errmsg\":null},\"responseCode\":\"OK\",\"result\":{\"data\":{\"id\":\"d655cf03-1f6f-4510-acf6-d3f51b488a5e\",\"name\":\"UCI Demo\",\"startingMessage\":\"Hi UCI\",\"users\":[],\"logicIDs\":[\"e96b0865-5a76-4566-8694-c09361b8ae32\"],\"owners\":null,\"created_at\":\"2021-07-08T18:48:37.740Z\",\"updated_at\":\"2021-07-14T16:59:09.088Z\",\"status\":\"Draft\",\"description\":\"For Internal Demo\",\"startDate\":\"2021-07-07T18:30:00.000Z\",\"endDate\":\"2021-07-22T18:30:00.000Z\",\"purpose\":\"For Internal Demo\",\"ownerOrgID\":null,\"ownerID\":null,\"logic\":[{\"id\":\"e96b0865-5a76-4566-8694-c09361b8ae32\",\"transformers\":[{\"id\":\"bbf56981-b8c9-40e9-8067-468c2c753659\",\"meta\":{\"form\":\"https://hosted.my.form.here.com\",\"formID\":\"UCI-demo-4\"}}],\"adapter\":\"44a9df72-3d7a-4ece-94c5-98cf26307324\",\"name\":\"UCI Demo\",\"created_at\":\"2021-07-08T18:47:44.925Z\",\"updated_at\":\"2021-07-08T18:47:44.925Z\",\"description\":null,\"ownerOrgID\":null,\"ownerID\":null}]}}}");

		Mono<XMessage> response = campaignConsumer.processMessage("d655cf03-1f6f-4510-acf6-d3f51b488a5e");
		StepVerifier.create(response).verifyComplete();
	}
}
