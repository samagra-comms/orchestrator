package com.uci.orchestrator.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.inversoft.error.Errors;
import com.inversoft.rest.ClientResponse;
import com.uci.dao.models.XMessageDAO;
import com.uci.dao.repository.XMessageRepository;
import com.uci.utils.BotService;
import com.uci.utils.CampaignService;
import com.uci.utils.encryption.AESWrapper;
import com.uci.utils.kafka.ReactiveProducer;
import com.uci.utils.kafka.SimpleProducer;
import com.uci.utils.telemetry.LogTelemetryMessage;
import com.uci.utils.telemetry.TelemetryLogger;
import com.uci.utils.telemetry.util.TelemetryEventNames;

import io.fusionauth.domain.api.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.DeviceType;
import messagerosa.core.model.SenderReceiverInfo;
import messagerosa.core.model.XMessage;
import messagerosa.xml.XMessageParser;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.common.protocol.types.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kie.api.runtime.KieSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import javax.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.uci.utils.encryption.AESWrapper.encodeKey;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReactiveConsumer {

    private final Flux<ReceiverRecord<String, String>> reactiveKafkaReceiver;

//    @Autowired
//    public KieSession kSession;

    @Autowired
    public XMessageRepository xMessageRepository;

    @Autowired
    public SimpleProducer kafkaProducer;

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

    public AESWrapper encryptor;

    private final String DEFAULT_APP_NAME = "Global Bot";
    LocalDateTime yesterday = LocalDateTime.now().minusDays(1L);

    private static final Logger telemetrylogger = LogManager.getLogger(TelemetryLogger.class);
    
    @Value("${producer.id}")
	private String producerID;
    
    @EventListener(ApplicationStartedEvent.class)
    public void onMessage() {
        reactiveKafkaReceiver
        		.doOnError(genericError("Error in receiving message from kafka", null))
                .doOnNext(new Consumer<ReceiverRecord<String, String>>() {
                    @Override
                    public void accept(ReceiverRecord<String, String> stringMessage) {
                        try {
                            final long startTime = System.nanoTime();
                            XMessage msg = XMessageParser.parse(new ByteArrayInputStream(stringMessage.value().getBytes()));
                            SenderReceiverInfo from = msg.getFrom();
                            getAppName(msg.getPayload().getText(), msg.getFrom())
                            		.doOnError(genericError("Error in getting app name", msg))
                            		.doOnNext(new Consumer<String>() {
                                        @Override
                                        public void accept(String appName) {
                                            logTimeTaken(startTime, 2);
                                            fetchAdapterID(appName)
                                            		.doOnError(genericError("Error in fetching adpater id", msg))
                                            		.doOnNext(new Consumer<String>() {
                                                        @Override
                                                        public void accept(String adapterID) {
                                                            logTimeTaken(startTime, 3);
                                                            from.setCampaignID(appName);
                                                            from.setDeviceType(DeviceType.PHONE);
                                                            resolveUser(from, appName)
                                                            		.doOnError(genericError("Error in resolving user", msg))
                                                            		.doOnNext(new Consumer<SenderReceiverInfo>() {
                                                                        @Override
                                                                        public void accept(SenderReceiverInfo from) {
                                                                            msg.setFrom(from);
                                                                            msg.setApp(appName);
                                                                            getLastMessageID(msg)
                                                                            		.doOnError(genericError("Error in getting last message id", msg))
                                                                            		.doOnNext(lastMessageID -> {
                                                                                        logTimeTaken(startTime, 4);
                                                                                        msg.setLastMessageID(lastMessageID);
                                                                                        msg.setAdapterId(adapterID);
                                                                                        /* Logs for telemetry events */
                                                                                        logTelemteryEvents(msg);
                                                                                        if (msg.getMessageState().equals(XMessage.MessageState.REPLIED) || msg.getMessageState().equals(XMessage.MessageState.OPTED_IN)) {
                                                                                            try {
                                                                                                kafkaProducer.send(odkTransformerTopic, msg.toXML());
                                                                                                // reactiveProducer.sendMessages(odkTransformerTopic, msg.toXML());
                                                                                            } catch (JAXBException e) {
                                                                                                e.printStackTrace();
                                                                                                genericError("Error in sending message to kafka topic", msg);
                                                                                            }
                                                                                            logTimeTaken(startTime, 15);
                                                                                        }
                                                                                    })
                                                                                    .subscribe();
                                                                        }
                                                                    }).subscribe();

                                                        }
                                                    })
                                                    .subscribe();
                                        }
                                    })
                                    .subscribe();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                })
                .doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable e) {
                        System.out.println(e.getMessage());
                        log.error("KafkaFlux exception", e);
                    }
                })
                .subscribe();
    }
    
    private void logTelemteryEvents(XMessage msg) {
    	/* Start Conversation Log */
        if(msg.getMessageState().equals(XMessage.MessageState.SENT)) {
        	telemetrylogger.info(new LogTelemetryMessage(String.format("Message sent"),
					TelemetryEventNames.SENT, "", msg.getChannel(),
					msg.getProvider(), producerID, msg.getFrom().getUserID()));
        } else if(msg.getMessageState().equals(XMessage.MessageState.DELIVERED)) {
        	telemetrylogger.info(new LogTelemetryMessage(String.format("Message Delivered"),
					TelemetryEventNames.DELIVERED, "", msg.getChannel(),
					msg.getProvider(), producerID, msg.getFrom().getUserID()));
        } else if(msg.getMessageState().equals(XMessage.MessageState.READ)) {
        	telemetrylogger.info(new LogTelemetryMessage(String.format("Message Read"),
					TelemetryEventNames.READ, "", msg.getChannel(),
					msg.getProvider(), producerID, msg.getFrom().getUserID()));
        }
    }
    
    /* Error to be logged */
    private Consumer<Throwable> genericError(String s, XMessage xmsg) {
		return c -> {
			log.error(s + "::" + c.getMessage());
			/* Log Telemetry event - Exception */
			if(xmsg != null) {
				telemetrylogger.info(new LogTelemetryMessage(s,
						TelemetryEventNames.AUDITEXCEPTIONS, "", xmsg.getChannel(),
						xmsg.getProvider(), producerID, xmsg.getFrom().getUserID()));
			} else {
				telemetrylogger.info(new LogTelemetryMessage(s,
						TelemetryEventNames.AUDITEXCEPTIONS));
			}
		};
	}

    private Mono<SenderReceiverInfo> resolveUser(SenderReceiverInfo from, String appName) {
        try {
            String deviceString = from.getDeviceType().toString() + ":" + from.getUserID();
            String encodedBase64Key = encodeKey(secret);
            String deviceID = AESWrapper.encrypt(deviceString, encodedBase64Key);
            ClientResponse<UserResponse, Errors> response = campaignService.fusionAuthClient.retrieveUserByUsername(deviceID);
            if (response.wasSuccessful()) {
                from.setUserID(deviceID);
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
                        });
            }
        } catch (Exception e) {
            e.printStackTrace();
            return Mono.just(null);
        }
    }

    private void logTimeTaken(long startTime, int checkpointID) {
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        log.info(String.format("CP-%d: %d ms", checkpointID, duration));
    }

    private Mono<String> getLastMessageID(XMessage msg) {
        if (msg.getMessageType().toString().equalsIgnoreCase("text")) {
            return getLatestXMessage(msg.getFrom().getUserID(), yesterday, "SENT").map(new Function<XMessageDAO, String>() {
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
            return getLatestXMessage(msg.getFrom().getUserID(), yesterday, "SENT").map(new Function<XMessageDAO, String>() {
                @Override
                public String apply(XMessageDAO lastMessage) {
                    return String.valueOf(lastMessage.getId());
                }
            });
//
//            map(new Function<XMessageDAO, String>() {
//                @Override
//                public String apply(XMessageDAO lastMessage) {
//                    return String.valueOf(lastMessage.getId());
//                }
//            });
        }
        return Mono.empty();
    }

    private Mono<XMessageDAO> getLatestXMessage(String userID, LocalDateTime yesterday, String messageState) {
        return xMessageRepository.findAllByUserIdAndTimestampAfter(userID, yesterday).collectList().map(new Function<List<XMessageDAO>, XMessageDAO>() {
            @Override
            public XMessageDAO apply(List<XMessageDAO> xMessageDAOS) {
                if (xMessageDAOS.size() > 0) {
                    List<XMessageDAO> filteredList = new ArrayList<>();
                    for (XMessageDAO xMessageDAO : xMessageDAOS) {
                        if (xMessageDAO.getMessageState().equals(XMessage.MessageState.SENT.name()) ||
                                xMessageDAO.getMessageState().equals(XMessage.MessageState.REPLIED.name()) )
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
        });
    }

    private Mono<String> fetchAdapterID(String appName) {
        return botService.getCurrentAdapter(appName);
    }

    private Mono<String> getAppName(String text, SenderReceiverInfo from) {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1L);
        if (text.equals("")) {
            try {
                return getLatestXMessage(from.getUserID(), yesterday, XMessage.MessageState.SENT.name()).map(new Function<XMessageDAO, String>() {
                    @Override
                    public String apply(XMessageDAO xMessageLast) {
                        return xMessageLast.getApp();
                    }
                });
            } catch (Exception e2) {
                return getLatestXMessage(from.getUserID(), yesterday, XMessage.MessageState.SENT.name()).map(new Function<XMessageDAO, String>() {
                    @Override
                    public String apply(XMessageDAO xMessageLast) {
                        return xMessageLast.getApp();
                    }
                });
            }
        } else {
            try {
                return botService.getCampaignFromStartingMessage(text)
                        .flatMap(new Function<String, Mono<? extends String>>() {
                            @Override
                            public Mono<String> apply(String appName1) {
                                if (appName1 == null || appName1.equals("")) {
                                    try {
                                        return getLatestXMessage(from.getUserID(), yesterday, XMessage.MessageState.SENT.name()).map(new Function<XMessageDAO, String>() {
                                            @Override
                                            public String apply(XMessageDAO xMessageLast) {
                                                return (xMessageLast.getApp() == null || xMessageLast.getApp().isEmpty()) ? "finalAppName" : xMessageLast.getApp();
                                            }
                                        });
                                    } catch (Exception e2) {
                                        return getLatestXMessage(from.getUserID(), yesterday, XMessage.MessageState.SENT.name()).map(new Function<XMessageDAO, String>() {
                                            @Override
                                            public String apply(XMessageDAO xMessageLast) {
                                                return (xMessageLast.getApp() == null || xMessageLast.getApp().isEmpty()) ? "finalAppName" : xMessageLast.getApp();
                                            }
                                        });
                                    }
                                }
                                return (appName1 == null || appName1.isEmpty()) ? Mono.just("finalAppName") : Mono.just(appName1);
                            }
                        });
            } catch (Exception e) {
                try {
                    return getLatestXMessage(from.getUserID(), yesterday, XMessage.MessageState.SENT.name()).map(new Function<XMessageDAO, String>() {
                        @Override
                        public String apply(XMessageDAO xMessageLast) {
                            return xMessageLast.getApp();
                        }
                    });
                } catch (Exception e2) {
                    return getLatestXMessage(from.getUserID(), yesterday, XMessage.MessageState.SENT.name()).map(new Function<XMessageDAO, String>() {
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
