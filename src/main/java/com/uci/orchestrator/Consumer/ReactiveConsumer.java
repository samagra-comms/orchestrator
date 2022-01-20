package com.uci.orchestrator.Consumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Objects;
import com.inversoft.error.Errors;
import com.inversoft.rest.ClientResponse;
import org.apache.tomcat.util.json.ParseException;
import com.uci.dao.models.XMessageDAO;
import com.uci.dao.repository.XMessageRepository;
import com.uci.utils.BotService;
import com.uci.utils.CampaignService;
import com.uci.utils.encryption.AESWrapper;
import com.uci.utils.kafka.ReactiveProducer;
import com.uci.utils.kafka.SimpleProducer;
import com.uci.utils.kafka.adapter.TextMapGetterAdapter;
import com.uci.utils.kafka.RecordProducer;

import io.fusionauth.domain.api.UserConsentResponse;
import io.fusionauth.domain.api.UserRequest;
import io.fusionauth.domain.api.UserResponse;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.fusionauth.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.DeviceType;
import messagerosa.core.model.SenderReceiverInfo;
import messagerosa.core.model.Transformer;
import messagerosa.core.model.XMessage;
import messagerosa.xml.XMessageParser;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.protocol.types.Field;
import org.apache.tomcat.util.json.JSONParser;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kie.api.runtime.KieSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import javax.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.uci.utils.encryption.AESWrapper.encodeKey;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReactiveConsumer {

	private final Flux<ConsumerRecord<String, String>> reactiveKafkaReceiver;

//    @Autowired
//    public KieSession kSession;

	@Autowired
	public XMessageRepository xMessageRepository;

	@Autowired
	public RecordProducer kafkaProducer;

	@Autowired
	public ReactiveProducer reactiveProducer;

	@Value("${odk-transformer}")
	public String odkTransformerTopic;

	@Autowired
	public BotService botService;

	@Autowired
	public CampaignService campaignService;

	@Value("${encryptionKeyString}")
	private String secret;

	@Autowired
	private Tracer tracer;

	public AESWrapper encryptor;

	private final String DEFAULT_APP_NAME = "Global Bot";
	LocalDateTime yesterday = LocalDateTime.now().minusDays(1L);

	@EventListener(ApplicationStartedEvent.class)
	public void onMessage() {
		reactiveKafkaReceiver.doOnNext(new Consumer<ConsumerRecord<String, String>>() {
			@Override
			public void accept(ConsumerRecord<String, String> stringMessage) {
				Context extractedContext = GlobalOpenTelemetry.getPropagators().getTextMapPropagator().extract(Context.current(), stringMessage.headers(), TextMapGetterAdapter.getter);
                log.info("Opentelemetry extracted context : "+extractedContext);
                
        		try (Scope scope = extractedContext.makeCurrent()) {
        			final long startTime = System.nanoTime();
					logTimeTaken(startTime, 0);
					XMessage msg = XMessageParser.parse(new ByteArrayInputStream(stringMessage.value().getBytes()));

	                try {
						SenderReceiverInfo from = msg.getFrom();
						logTimeTaken(startTime, 1);
						Span childSpan1 = createChildSpan("fetchAdapterID");
						fetchAdapterID(msg.getApp()).doOnNext(new Consumer<String>() {
							@Override
							public void accept(String adapterID) {
								childSpan1.end();
								logTimeTaken(startTime, 2);
								from.setCampaignID(msg.getApp());
								from.setDeviceType(DeviceType.PHONE);
								resolveUserNew(msg).doOnNext(new Consumer<XMessage>() {
									@Override
									public void accept(XMessage msg) {
										SenderReceiverInfo from = msg.getFrom();
										// msg.setFrom(from);
										Span childSpan2 = createChildSpan("getLastMessageID");
										getLastMessageID(msg).doOnNext(lastMessageID -> {
											childSpan2.end();
											logTimeTaken(startTime, 3);
											msg.setLastMessageID(lastMessageID);
											msg.setAdapterId(adapterID);
											if (msg.getMessageState().equals(XMessage.MessageState.REPLIED)
													|| msg.getMessageState().equals(XMessage.MessageState.OPTED_IN)) {
												try {
													Span childSpan3 = createChildSpan("sendEventToKafka");
													kafkaProducer.send(odkTransformerTopic, msg.toXML(), Context.current());
													// reactiveProducer.sendMessages(odkTransformerTopic,
													// msg.toXML());
													childSpan3.end();
												} catch (JAXBException e) {
													e.printStackTrace();
												}
												logTimeTaken(startTime, 15);
											}
										}).doOnError(genericError("getLastMessageID")).subscribe();
									}
								}).doOnError(genericError("resolveUserNew")).subscribe();

							}
						}).doOnError(genericError("fetchAdapterID")).subscribe();

					} catch (Throwable e) {
						genericException(e.getMessage());
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}).doOnError(genericError("KafkaFlux", null)).subscribe();

	}

	/**
	 * Create Child Span with current context & parent span
	 * 
	 * @param spanName
	 * @param context
	 * @param parentSpan
	 * @return childSpan
	 */
	private Span createChildSpan(String spanName, Context context, Span parentSpan) {
		String prefix = "orchestrator-";
		return tracer.spanBuilder(prefix + spanName).setParent(context.with(parentSpan)).startSpan();
	}
	
	/**
	 * Create Child Span
	 * 
	 * @param spanName
	 * @return childSpan
	 */
	private Span createChildSpan(String spanName) {
		String prefix = "orchestratorSpan-";
		return tracer.spanBuilder(prefix + spanName).startSpan();
	}

	/**
	 * Log Exceptions & if span exists, add error to span
	 * 
	 * @param eMsg
	 * @param span
	 */
	private void genericException(String eMsg, Span span) {
		eMsg = "Exception: " + eMsg;
		log.error(eMsg);
		if (span != null) {
			span.setStatus(StatusCode.ERROR, "Exception: " + eMsg);
			span.end();
		}
	}
	
	/**
	 * Log Exceptions
	 * 
	 * @param eMsg
	 */
	private void genericException(String eMsg) {
		eMsg = "Exception: " + eMsg;
		log.error(eMsg);
	}

	/**
	 * Log Exception & if span exists, add error to span
	 * 
	 * @param s
	 * @param span
	 * @return
	 */
	private Consumer<Throwable> genericError(String s, Span span) {
		return c -> {
			String msg = "Error in " + s + "::" + c.getMessage();
			log.error(msg);
			if (span != null) {
				log.info("generic message - span");
				span.setStatus(StatusCode.ERROR, msg);
				span.end();
			}
		};
	}
	
	/**
	 * Log Exception
	 * 
	 * @param s
	 * @return
	 */
	private Consumer<Throwable> genericError(String s) {
		return c -> {
			String msg = "Error in " + s + "::" + c.getMessage();
			log.error(msg);
		};
	}

	private Mono<XMessage> resolveUserNew(XMessage xmsg) {
		try {
			SenderReceiverInfo from = xmsg.getFrom();
			String appName = xmsg.getApp();

			String deviceString = from.getDeviceType().toString() + ":" + from.getUserID();
			String encodedBase64Key = encodeKey(secret);
			String deviceID = AESWrapper.encrypt(deviceString, encodedBase64Key);
			Span childSpan1 = createChildSpan("FA-retrieveUserByUsername");
			ClientResponse<UserResponse, Errors> response = campaignService.fusionAuthClient
					.retrieveUserByUsername(deviceID);
			childSpan1.end();
			if (response.wasSuccessful()) {
				from.setDeviceID(response.successResponse.user.id.toString());
				xmsg.setFrom(from);
				return xmsgCampaignForm(xmsg, response.successResponse.user);
			} else {
				Span childSpan2 = createChildSpan("updateUser");
				return botService.updateUser(deviceString, appName)
						.flatMap(new Function<Pair<Boolean, String>, Mono<XMessage>>() {
							@Override
							public Mono<XMessage> apply(Pair<Boolean, String> result) {
								childSpan2.end();
								if (result.getLeft()) {
									from.setDeviceID(result.getRight());
									xmsg.setFrom(from);
									Span childSpan3 = createChildSpan("FA-retrieveUserByUsername");
									ClientResponse<UserResponse, Errors> response = campaignService.fusionAuthClient
											.retrieveUserByUsername(deviceID);
									childSpan3.end();
									if (response.wasSuccessful()) {
										return xmsgCampaignForm(xmsg, response.successResponse.user);
									} else {
										return Mono.just(xmsg);
									}
								} else {
									xmsg.setFrom(null);
									return Mono.just(xmsg);
								}
							}
						}).doOnError(genericError("updateUser", null));
			}
		} catch (Exception e) {
			e.printStackTrace();
			log.error("Exception in resolveUser" + e.getMessage());
			xmsg.setFrom(null);
			return Mono.just(xmsg);
		}
	}

	private Mono<XMessage> xmsgCampaignForm(XMessage xmsg, User user) {
		Span childSpan1 = createChildSpan("getCampaignFromNameTransformer");
		return campaignService.getCampaignFromNameTransformer(xmsg.getCampaign())
				.map(new Function<JsonNode, XMessage>() {
					@Override
					public XMessage apply(JsonNode campaign) {
						childSpan1.end();
						String campaignID = campaign.findValue("id").asText();
						Map<String, String> formIDs = getCampaignFormIds(campaign);
						String currentFormID = getCurrentFormId(xmsg, campaignID, formIDs, user);

						HashMap<String, String> metaData = new HashMap<String, String>();
						metaData.put("campaignId", campaignID);
						metaData.put("currentFormID", currentFormID);

						saveCurrentFormID(xmsg.getFrom().getUserID(), campaignID, currentFormID);

						Transformer transf = new Transformer();
						transf.setId("test");
						transf.setMetaData(metaData);

						ArrayList<Transformer> transformers = new ArrayList<Transformer>();
						transformers.add(transf);

						xmsg.setTransformers(transformers);

						try {
							System.out.println("XML:" + xmsg.toXML());
						} catch (JAXBException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}

						return xmsg;
					}
				}).doOnError(genericError("getCampaignFromNameTransformer", null));
	}

	/**
	 * Get all form ids from the campaign node
	 * 
	 * @param campaign
	 * @return
	 */
	private Map<String, String> getCampaignFormIds(JsonNode campaign) {
		ArrayList<String> formIDs = new ArrayList<String>();
		Map<String, String> formIDs2 = new HashMap();
		try {
			campaign.findValue("logicIDs").forEach(t -> {
				formIDs2.put(t.asText(), "");
			});

			campaign.findValue("logic").forEach(t -> {
				formIDs2.put(t.findValue("id").asText(), t.findValue("formID").asText());
			});

			System.out.println("formIDs:" + formIDs2);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return formIDs2;
	}

	/**
	 * Get the current from id
	 * 
	 * @param xmsg
	 * @param campaignID
	 * @param formIDs
	 * @return
	 */
	private String getCurrentFormId(XMessage xmsg, String campaignID, Map<String, String> formIDs, User user) {
		String currentFormID = "";

		Boolean consent = checkUserCampaignConsent(campaignID, user);

		/* Fetch current form id from file for user & campaign */
		currentFormID = getCurrentFormIDFromFile(xmsg.getFrom().getUserID(), campaignID);

		/*
		 * if current form id is empty, then set the first form id as current form id
		 * else if current form id is equal to consent form id
		 * 
		 */
		System.out.println("currentFormID:" + currentFormID);
		if (currentFormID == null || currentFormID.isEmpty()) {
			if (!formIDs.isEmpty() && formIDs.size() > 0) {
				currentFormID = formIDs.values().toArray()[0].toString();
			}
		}

		/* if current form is consent form */
		if (currentFormID.equals(getConsentFormID())) {
			/*
			 * if consent already exists, set next form as current one, else check the
			 * response for further details
			 */
			if (consent) {
				currentFormID = formIDs.values().toArray()[1].toString();
			} else {
				String response = xmsg.getPayload().getText();
				if (response.equals("1")) {
					// update fusion auth client for consent & set the next form id as current form
					// id
					addUserCampaignConsent(campaignID, user);
					currentFormID = formIDs.values().toArray()[1].toString();
				} else if (response.equals("2")) {
					// drop conversation
					currentFormID = "";
					System.out.println("drop conversation.");
				} else {
					// invalid response, leave the consent form id as current
				}
			}
		}

		System.out.println(currentFormID);

		return currentFormID;
	}

	/**
	 * Get current form id set in file for user & campaign
	 * 
	 * @param userID
	 * @param campaignID
	 * @return
	 */
	private String getCurrentFormIDFromFile(String userID, String campaignID) {
		String currentFormID = "";
		Resource resource = new ClassPathResource(getJsonFilePath());
		try {
			ObjectMapper mapper = new ObjectMapper();

			InputStream inputStream = resource.getInputStream();

			byte[] bdata = FileCopyUtils.copyToByteArray(inputStream);

			JsonNode rootNode = mapper.readTree(bdata);

			if (!rootNode.isEmpty() && rootNode.get(userID) != null && rootNode.path(userID).get(campaignID) != null) {
				currentFormID = rootNode.path(userID).get(campaignID).asText();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return currentFormID;
	}

	/**
	 * Save current form id in file for user & campaign
	 * 
	 * @param userID
	 * @param campaignID
	 * @param currentFormID
	 */
	private void saveCurrentFormID(String userID, String campaignID, String currentFormID) {
		Resource resource = new ClassPathResource(getJsonFilePath());
		try {
			ObjectMapper mapper = new ObjectMapper();

			File file = resource.getFile();
			InputStream inputStream = resource.getInputStream();

			byte[] bdata = FileCopyUtils.copyToByteArray(inputStream);

			JsonNode rootNode = mapper.readTree(bdata);
			if (rootNode.isEmpty()) {
				rootNode = mapper.createObjectNode();
			}

			if (!rootNode.isEmpty() && rootNode.get(userID) != null) {
				((ObjectNode) rootNode.path(userID)).put(campaignID, currentFormID);
			} else {
				JsonNode campaignNode = mapper.createObjectNode();
				((ObjectNode) campaignNode).put(campaignID, currentFormID);

				((ObjectNode) rootNode).put(userID, campaignNode);
			}

			System.out.println("Saved File String:" + rootNode.toString());

			FileWriter fileWriter = new FileWriter(file);
			fileWriter.write(rootNode.toString());
			fileWriter.flush();
			fileWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private String getJsonFilePath() {
		return "userCurrentForm.json";
	}

//    private 

	private String getConsentFormID() {
		return "mandatory-consent-v1";
	}

	private Mono<SenderReceiverInfo> resolveUser(SenderReceiverInfo from, String appName) {
		try {
			String deviceString = from.getDeviceType().toString() + ":" + from.getUserID();
			String encodedBase64Key = encodeKey(secret);
			String deviceID = AESWrapper.encrypt(deviceString, encodedBase64Key);
			ClientResponse<UserResponse, Errors> response = campaignService.fusionAuthClient
					.retrieveUserByUsername(deviceID);
			if (response.wasSuccessful()) {
				from.setDeviceID(response.successResponse.user.id.toString());
//                checkConsent(from.getCampaignID(), response.successResponse.user);
				return Mono.just(from);
			} else {
				return botService.updateUser(deviceString, appName)
						.flatMap(new Function<Pair<Boolean, String>, Mono<SenderReceiverInfo>>() {
							@Override
							public Mono<SenderReceiverInfo> apply(Pair<Boolean, String> result) {
								if (result.getLeft()) {
									from.setDeviceID(result.getRight());
									return Mono.just(from);
								} else {
									return Mono.just(null);
								}
							}
						}).doOnError(new Consumer<Throwable>() {
							@Override
							public void accept(Throwable throwable) {
								log.error("Error in updateUser" + throwable.getMessage());
							}
						});
			}
		} catch (Exception e) {
			e.printStackTrace();
			log.error("Error in resolveUser" + e.getMessage());
			return Mono.just(null);
		}
	}

	private Boolean checkUserCampaignConsent(String campaignID, User user) {
		Boolean consent = false;
		try {
			Object consentData = user.data.get("consent");
			ArrayList consentArray = (ArrayList) consentData;
			if (consentArray != null && consentArray.contains(campaignID)) {
				consent = true;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return consent;
	}

	private void addUserCampaignConsent(String campaignID, User user) {
		try {
			Object consentData = user.data.get("consent");
			ArrayList consentArray = (ArrayList) consentData;

			if (consentArray == null || (consentArray != null && !consentArray.contains(campaignID))) {
				consentArray.add(campaignID);

				user.data.put("consent", consentArray);

				updateFAUser(user);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void updateFAUser(User user) {
		log.error("User: " + user);
		UserRequest r = new UserRequest(user);

		ClientResponse<UserResponse, Errors> response = campaignService.fusionAuthClient.updateUser(user.id, r);
		if (response.wasSuccessful()) {
			log.error("user update success");
		} else {
			log.error("error in user update" + response.errorResponse);
		}
	}

	private void logTimeTaken(long startTime, int checkpointID) {
		long endTime = System.nanoTime();
		long duration = (endTime - startTime) / 1000000;
		log.info(String.format("CP-%d: %d ms", checkpointID, duration));
	}

	private Mono<String> getLastMessageID(XMessage msg) {
		if (msg.getMessageType().toString().equalsIgnoreCase("text")) {
			return getLatestXMessage(msg.getFrom().getUserID(), yesterday, "SENT")
					.map(new Function<XMessageDAO, String>() {
						@Override
						public String apply(XMessageDAO msg1) {
							if (msg1.getId() == null) {
								System.out.println("cError");
								return "";
							}
							return String.valueOf(msg1.getId());
						}
					});

		} else if (msg.getMessageType().toString().equalsIgnoreCase("button")) {
			return getLatestXMessage(msg.getFrom().getUserID(), yesterday, "SENT")
					.map(new Function<XMessageDAO, String>() {
						@Override
						public String apply(XMessageDAO lastMessage) {
							return String.valueOf(lastMessage.getId());
						}
					});
		}
		return Mono.empty();
	}

	private Mono<XMessageDAO> getLatestXMessage(String userID, LocalDateTime yesterday, String messageState) {
		return xMessageRepository.findAllByUserIdAndTimestampAfter(userID, yesterday).collectList()
				.map(new Function<List<XMessageDAO>, XMessageDAO>() {
					@Override
					public XMessageDAO apply(List<XMessageDAO> xMessageDAOS) {
						if (xMessageDAOS.size() > 0) {
							List<XMessageDAO> filteredList = new ArrayList<>();
							for (XMessageDAO xMessageDAO : xMessageDAOS) {
								if (xMessageDAO.getMessageState().equals(XMessage.MessageState.SENT.name())
										|| xMessageDAO.getMessageState().equals(XMessage.MessageState.REPLIED.name()))
									filteredList.add(xMessageDAO);
							}
							if (filteredList.size() > 0) {
								filteredList.sort(new Comparator<XMessageDAO>() {
									@Override
									public int compare(XMessageDAO o1, XMessageDAO o2) {
										return o1.getTimestamp().compareTo(o2.getTimestamp());
									}
								});
							}
							return xMessageDAOS.get(0);
						}
						return new XMessageDAO();
					}
				}).doOnError(genericError("getLatestXMessage", null));
	}

	private Mono<String> fetchAdapterID(String appName) {
		return botService.getCurrentAdapter(appName);
	}

	private Mono<String> getAppName(String text, SenderReceiverInfo from) {
		LocalDateTime yesterday = LocalDateTime.now().minusDays(1L);
		log.info("Inside getAppName " + text + "::" + from.getUserID());
		if (text.equals("")) {
			try {
				return getLatestXMessage(from.getUserID(), yesterday, XMessage.MessageState.SENT.name())
						.map(new Function<XMessageDAO, String>() {
							@Override
							public String apply(XMessageDAO xMessageLast) {
								return xMessageLast.getApp();
							}
						});
			} catch (Exception e2) {
				return getLatestXMessage(from.getUserID(), yesterday, XMessage.MessageState.SENT.name())
						.map(new Function<XMessageDAO, String>() {
							@Override
							public String apply(XMessageDAO xMessageLast) {
								return xMessageLast.getApp();
							}
						});
			}
		} else {
			try {
				log.info("Inside getAppName " + text + "::" + from.getUserID());
				return botService.getCampaignFromStartingMessage(text)
						.flatMap(new Function<String, Mono<? extends String>>() {
							@Override
							public Mono<String> apply(String appName1) {
								log.info("Inside getCampaignFromStartingMessage => " + appName1);
								if (appName1 == null || appName1.equals("")) {
									try {
										return getLatestXMessage(from.getUserID(), yesterday,
												XMessage.MessageState.SENT.name())
														.map(new Function<XMessageDAO, String>() {
															@Override
															public String apply(XMessageDAO xMessageLast) {
																return (xMessageLast.getApp() == null
																		|| xMessageLast.getApp().isEmpty())
																				? "finalAppName"
																				: xMessageLast.getApp();
															}
														});
									} catch (Exception e2) {
										return getLatestXMessage(from.getUserID(), yesterday,
												XMessage.MessageState.SENT.name())
														.map(new Function<XMessageDAO, String>() {
															@Override
															public String apply(XMessageDAO xMessageLast) {
																return (xMessageLast.getApp() == null
																		|| xMessageLast.getApp().isEmpty())
																				? "finalAppName"
																				: xMessageLast.getApp();
															}
														});
									}
								}
								return (appName1 == null || appName1.isEmpty()) ? Mono.just("finalAppName")
										: Mono.just(appName1);
							}
						}).doOnError(genericError("getCampaignFromStartingMessage", null));
			} catch (Exception e) {
				log.info("Inside getAppName - exception => " + e.getMessage());
				try {
					return getLatestXMessage(from.getUserID(), yesterday, XMessage.MessageState.SENT.name())
							.map(new Function<XMessageDAO, String>() {
								@Override
								public String apply(XMessageDAO xMessageLast) {
									return xMessageLast.getApp();
								}
							});
				} catch (Exception e2) {
					return getLatestXMessage(from.getUserID(), yesterday, XMessage.MessageState.SENT.name())
							.map(new Function<XMessageDAO, String>() {
								@Override
								public String apply(XMessageDAO xMessageLast) {
									return xMessageLast.getApp();
								}
							});
				}
			}
		}
	}
}
