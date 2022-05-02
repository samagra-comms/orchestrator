package com.uci.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.util.concurrent.ListenableFuture;
import com.uci.dao.repository.XMessageRepository;
import com.uci.orchestrator.Consumer.ReactiveConsumer;
import com.uci.utils.BotService;
import com.uci.utils.CampaignService;
import com.uci.utils.cache.service.RedisCacheService;
import com.uci.utils.kafka.ReactiveProducer;
import com.uci.utils.kafka.SimpleProducer;
import com.uci.utils.service.UserService;
import io.fusionauth.client.FusionAuthClient;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.json.JSONArray;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

//@Configuration
//@ConfigurationProperties
//@TestPropertySource("classpath:test-application.properties")
public class ApplicationConfiguration{
//	@Value("${campaign.url}")
//  public String CAMPAIGN_URL;
//
//	@Value("${campaign.admin.token}")
//	private String CAMPAIGN_ADMIN_TOKEN;
//
//	@Value("${fusionauth.url}")
//	private String fusionAuthUrl;
//
//	@Value("${fusionauth.key}")
//	private String fusionAuthKey;
//
//	@Value("${spring.kafka.bootstrap-servers}")
//  private String BOOTSTRAP_SERVERS;

	@Autowired
	Flux<ReceiverRecord<String, String>> reactiveKafkaReceiver;

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

	@Bean
	public UserService getUserService(){
		return new UserService();
	}

	@Bean
	public RedisCacheService getRedisCacheService(){
		return new RedisCacheService(new RedisTemplate<>());
	}

	@Bean
	public Cache getCache(){
		return Mockito.mock(Cache.class);
	}

	@Bean
	public SimpleProducer getSimpleProducer(){
		return new SimpleProducer(kafkaTemplate());
	}

	@Bean
	public BotService getBotService(){
		WebClient webClient = WebClient.builder()
				.baseUrl("CAMPAIGN_URL")
				.defaultHeader("admin-token", "CAMPAIGN_ADMIN_TOKEN")
				.build();
		return new BotService(webClient, getFAClient(), getCache());
	}

	@Bean
	public FusionAuthClient getFAClient() {
		return new FusionAuthClient("FUSIONAUTH_KEY", "FUSIONAUTH_URL");
	}

	@Bean
	public CampaignService getCampaignService() {
		WebClient webClient = WebClient.builder()
				.baseUrl("CAMPAIGN_URL")
				.defaultHeader("admin-token", "CAMPAIGN_ADMIN_TOKEN")
				.build();
		return new CampaignService(webClient, getFAClient(), getCache());
	}

	@Bean
	public ReactiveConsumer getReactiveConsumer() throws JsonProcessingException {
		ObjectMapper objectMapper = new ObjectMapper();
		Mockito.when(botService.getCurrentAdapter("UCI Demo")).thenReturn(Mono.just("44a9df72-3d7a-4ece-94c5-98cf26307324"));
		JsonNode campaignNode = objectMapper.readTree("{\"id\":\"d655cf03-1f6f-4510-acf6-d3f51b488a5e\",\"name\":\"UCI Demo\",\"startingMessage\":\"Hi UCI\",\"users\":[],\"logicIDs\":[\"e96b0865-5a76-4566-8694-c09361b8ae32\"],\"owners\":null,\"created_at\":\"2021-07-08T18:48:37.740Z\",\"updated_at\":\"2022-02-11T14:09:53.570Z\",\"status\":\"enabled\",\"description\":\"For Internal Demo\",\"startDate\":\"2022-02-01T00:00:00.000Z\",\"endDate\":null,\"purpose\":\"For Internal Demo\",\"ownerOrgID\":\"ORG_001\",\"ownerID\":\"95e4942d-cbe8-477d-aebd-ad8e6de4bfc8\",\"userSegments\":[],\"logic\":[{\"id\":\"e96b0865-5a76-4566-8694-c09361b8ae32\",\"transformers\":[{\"id\":\"bbf56981-b8c9-40e9-8067-468c2c753659\",\"meta\":{\"form\":\"https://hosted.my.form.here.com\",\"formID\":\"UCI-demo-1\"}}],\"adapter\":{\"id\":\"44a9df72-3d7a-4ece-94c5-98cf26307324\",\"channel\":\"WhatsApp\",\"provider\":\"gupshup\",\"config\":{\"2WAY\":\"2000193033\",\"phone\":\"9876543210\",\"HSM_ID\":\"2000193031\",\"credentials\":{\"vault\":\"samagra\",\"variable\":\"gupshupSamagraProd\"}},\"name\":\"SamagraProd\",\"updated_at\":\"2021-06-16T06:02:39.125Z\",\"created_at\":\"2021-06-16T06:02:41.823Z\"},\"name\":\"UCI Demo\",\"created_at\":\"2021-07-08T18:47:44.925Z\",\"updated_at\":\"2022-02-03T12:29:32.959Z\",\"description\":null}]}");
		Mockito.when(campaignService.getCampaignFromNameTransformer("UCI Demo")).thenReturn(Mono.just(campaignNode));
		userService.CAMPAIGN_URL = "http://localhost";
		Mockito.when(userService.getUsersFromFederatedServers(Mockito.anyString())).thenReturn(new JSONArray());
		Mockito.when(redisCacheService.getFAUserIDForAppCache("yOJcM+Gm7yVkKeQqPhdDKNb0wsmh8St/ty+pM5Q+4W4=" + "-" + "d655cf03-1f6f-4510-acf6-d3f51b488a5e")).thenReturn("91311fd1-5c1c-4f81-b9eb-2d259159554a");
		Mockito.when(xMessageRepository.findAllByUserIdAndTimestampAfter(Mockito.any(), Mockito.any())).thenReturn(Flux.just());
		return new ReactiveConsumer(reactiveKafkaReceiver);
	}

	@Bean
	Map<String, Object> kafkaConsumerConfiguration() {
		Map<String, Object> configuration = new HashMap<>();
		configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "165.232.182.146:9094");
		configuration.put(ConsumerConfig.GROUP_ID_CONFIG, "sample-producer");
		configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);
		configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);
		configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
		configuration.put(ProducerConfig.ACKS_CONFIG, "all");
		return configuration;
	}

	@Bean
	Map<String, Object> kafkaProducerConfiguration() {
		Map<String, Object> configuration = new HashMap<>();
		configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "165.232.182.146:9094");
		configuration.put(ProducerConfig.CLIENT_ID_CONFIG, "sample-producer");
		configuration.put(ProducerConfig.ACKS_CONFIG, "all");
		configuration.put(org.springframework.kafka.support.serializer.JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
		configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);
		configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.springframework.kafka.support.serializer.JsonSerializer.class);
		return configuration;
	}

	@Bean
	ReceiverOptions<String, String> kafkaReceiverOptions(@Value("inbound-processed") String[] inTopicName) {
		ReceiverOptions<String, String> options = ReceiverOptions.create(kafkaConsumerConfiguration());
		return options.subscription(Arrays.asList(inTopicName))
				.withKeyDeserializer(new JsonDeserializer<>())
				.withValueDeserializer(new JsonDeserializer(String.class));
	}

	@Bean
	SenderOptions<Integer, String> kafkaSenderOptions() {
		return SenderOptions.create(kafkaProducerConfiguration());
	}

	@Bean
	Flux<ReceiverRecord<String, String>> reactiveKafkaReceiver(@Autowired ReceiverOptions<String, String> kafkaReceiverOptions) {
		KafkaReceiver<String, String> kafkaReceiver = KafkaReceiver.create(kafkaReceiverOptions);
		Consumer consumer = new MockConsumer<String, String>(OffsetResetStrategy.EARLIEST);
		ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<String, String >("inbound-processed", 0, 33, null,
				"<?xml version=\"1.0\"?>\n" +
						"<xMessage>\n" +
						"    <app>UCI Demo</app>\n" +
						"    <channel>WhatsApp</channel>\n" +
						"    <channelURI>WhatsApp</channelURI>\n" +
						"    <from>\n" +
						"        <bot>false</bot>\n" +
						"        <broadcast>false</broadcast>\n" +
						"        <deviceType>PHONE</deviceType>\n" +
						"        <userID>7823807161</userID>\n" +
						"    </from>\n" +
						"    <messageId>\n" +
						"        <channelMessageId>ABEGkZlgQyWAAgo-sDVSUOa9jH0z</channelMessageId>\n" +
						"    </messageId>\n" +
						"    <messageState>REPLIED</messageState>\n" +
						"    <messageType>TEXT</messageType>\n" +
						"    <payload>\n" +
						"        <text>Hi UCI</text>\n" +
						"    </payload>\n" +
						"    <provider>Netcore</provider>\n" +
						"    <providerURI>Netcore</providerURI>\n" +
						"    <timestamp>1636621428000</timestamp>\n" +
						"    <to>\n" +
						"        <bot>false</bot>\n" +
						"        <broadcast>false</broadcast>\n" +
						"        <userID>admin</userID>\n" +
						"    </to>\n" +
						"</xMessage>"
				);

		ReceiverOffset receiverOffset = new ReceiverOffset() {
			@Override
			public TopicPartition topicPartition() {
				return new TopicPartition("inbound-processed", 0);
			}

			@Override
			public long offset() {
				return 0;
			}

			@Override
			public void acknowledge() {

			}

			@Override
			public Mono<Void> commit() {
				return null;
			}
		};
		ReceiverRecord<String, String> receiverRecord = new ReceiverRecord<String, String >(consumerRecord, receiverOffset);
//		return KafkaReceiver.create(kafkaReceiverOptions).receive();
		return Flux.just(receiverRecord);
	}




	@Bean
	KafkaSender<Integer, String> reactiveKafkaSender(SenderOptions<Integer, String> kafkaSenderOptions) {
		return KafkaSender.create(kafkaSenderOptions);
	}

	@Bean
	ReactiveProducer kafkaReactiveProducer() {
		return new ReactiveProducer();
	}

	@Bean
	ProducerFactory<String, String> producerFactory(){
		ProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(kafkaProducerConfiguration());
		return producerFactory;
	}

	@Bean
	KafkaTemplate<String, String> kafkaTemplate() {
		KafkaTemplate<String, String> kafkaTemplate = new KafkaTemplate<>(producerFactory());
		return (KafkaTemplate<String, String>) kafkaTemplate;
	}
}
