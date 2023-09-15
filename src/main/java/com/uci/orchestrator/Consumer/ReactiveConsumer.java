package com.uci.orchestrator.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.uci.orchestrator.Service.CommonService;
import com.uci.orchestrator.Service.TransformerProducer;
import com.uci.utils.BotService;
import com.uci.utils.bot.util.BotUtil;
import com.uci.utils.cache.service.RedisCacheService;
import com.uci.utils.kafka.SimpleProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.DeviceType;
import messagerosa.core.model.SenderReceiverInfo;
import messagerosa.core.model.XMessage;
import messagerosa.xml.XMessageParser;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.ReceiverRecord;

import javax.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReactiveConsumer {

    private final Flux<ReceiverRecord<String, String>> reactiveKafkaReceiver;

    @Autowired
    public SimpleProducer kafkaProducer;
    @Autowired
    public TransformerProducer transactionalKafkaProducer;

    @Value("${odk-transformer}")
    public String odkTransformerTopic;

    @Value("${broadcast-transformer}")
    public String broadcastTransformerTopic;

    @Value("${generic-transformer}")
    public String genericTransformerTopic;

    @Autowired
    public BotService botService;

    @Autowired
    private RedisCacheService redisCacheService;

    @Value("${broadcastNotificationChunkSize}")
    private String broadcastNotificationChunkSize;

    private long notificationProcessedCount;

    private long consumeCount;
    private long pushCount;
    private long insertSetCount, existSetCount, existingFederatedUsers;
    @Value("${notification-kafka-cache}")
    private String notificationKafkaCache;

    @Autowired
    private CommonService commonService;

    @KafkaListener(id = "${inboundProcessed}", topics = "${inboundProcessed}", properties = {"spring.json.value.default.type=java.lang.String"})
    @Transactional
    public void onMessage(@Payload String stringMessage) {
        try {
            final long startTime = System.nanoTime();
            commonService.logTimeTaken(startTime, 0, null);
            XMessage msg = XMessageParser.parse(new ByteArrayInputStream(stringMessage.getBytes()));
            if (msg != null && msg.getProvider().equalsIgnoreCase("firebase")) {
                consumeCount++;
                log.info("Consume topic by Orchestrator count : " + consumeCount);

                /**
                 *  Kafka Duplication check
                 *  If messageId found in cache we don't send notifications
                 *  We need to resolve this issue from kafka side this is temporary solution
                 */
                if (msg.getMessageId() != null && msg.getMessageId().getChannelMessageId() != null && notificationKafkaCache != null && !notificationKafkaCache.isEmpty()) {
                    String messageId = msg.getMessageId().getChannelMessageId();

                    if (!redisCacheService.isKeyExists(notificationKafkaCache)) {
                        Set<String> messageIdSet = new HashSet<>();
                        messageIdSet.add(messageId);
                        redisCacheService.setCache(notificationKafkaCache, messageIdSet);
                        insertSetCount++;
                        log.info("ReactiveConsumer:First MessageId Insert in Redis count : " + insertSetCount + " MessageId : " + messageId);
                    } else {
                        Set<String> messageIdSet = (Set<String>) redisCacheService.getCache(notificationKafkaCache);
                        if (messageIdSet.contains(messageId)) {
                            existSetCount++;
                            log.info("ReactiveConsumer:Already Consumed : " + existSetCount + " MessageId : " + messageId);
                            return;
                        } else {
                            messageIdSet.add(messageId);
                            redisCacheService.setCache(notificationKafkaCache, messageIdSet);
                            insertSetCount++;
                            log.info("ReactiveConsumer:MessageId Insert in Redis count : " + insertSetCount + " MessageId : " + messageId);
                        }
                    }
                } else {
                    log.error("ReactiveConsumer:MessageId Not Found : " + msg.getMessageId() + " or Notification Cache Name Empty : " + notificationKafkaCache);
                }

                // This code for kafka duplication problem
//                if (msg.getMessageId() != null && msg.getMessageId().getChannelMessageId() != null) {
//                    String messageId = msg.getMessageId().getChannelMessageId();
//                    if (messageIdSet.contains(messageId)) {
//                        existSetCount++;
//                        log.info("ReactiveConsumer:Already Counsumed : " + existSetCount + " MessageId : " + messageId);
//                        return;
//                    } else {
//                        insertSetCount++;
//                        log.info("ReactiveConsumer:Insert in set count : " + insertSetCount + " MessageId : " + messageId);
//                        messageIdSet.add(messageId);
//                    }
//                }
//                log.info("ReactiveConsumer: Total MessageId Set : " + messageIdSet.size());
            }

            SenderReceiverInfo from = msg.getFrom();
            commonService.logTimeTaken(startTime, 1, null);
            botService.getBotNodeFromName(msg.getApp()).doOnNext(new Consumer<JsonNode>() {
                @Override
                public void accept(JsonNode botNode) {
                    if (botNode != null && !botNode.isEmpty()) {
                        from.setCampaignID(msg.getApp());
                        if (from.getDeviceType() == null) {
                            from.setDeviceType(DeviceType.PHONE);
                        }
                        if (msg.getAdapterId() == null || msg.getAdapterId().isEmpty()) {
                            msg.setAdapterId(BotUtil.getBotNodeAdapterId(botNode));
                        }
                        /* Set XMessage Transformers */
                        XMessage message = commonService.setXMessageTransformers(msg, botNode);

                        String appId = botNode.get("id").asText();
                        JsonNode firstTransformer = botNode.findValues("transformers").get(0);

                        if (message != null && message.getProviderURI().equals("firebase") && message.getChannelURI().equals("web")) {
                            if (firstTransformer.findValue("type") != null && firstTransformer.findValue("type").asText().equals(BotUtil.transformerTypeBroadcast)) {
                                try {
                                    log.info("ReactiveConsumer:broadcastNotificationChunkSize : " + broadcastNotificationChunkSize);
                                    /* Switch From & To */
                                    commonService.switchFromTo(msg);
                                    Integer chunkSize = null;
                                    try {
                                        chunkSize = Integer.parseInt(broadcastNotificationChunkSize);
                                    } catch (NumberFormatException ex) {
                                        chunkSize = null;
                                    }
                                    if (chunkSize != null) {
                                        if (msg.getTransformers() != null && msg.getTransformers().size() > 0 && msg.getTransformers().get(0) != null
                                                && msg.getTransformers().get(0).getMetaData() != null && msg.getTransformers().get(0).getMetaData().get("federatedUsers") != null) {
                                            JSONArray federatedUsers = new JSONObject(msg.getTransformers().get(0).getMetaData().get("federatedUsers")).getJSONArray("list");
                                            int totalFederatedUsers = federatedUsers.length();
                                            if (totalFederatedUsers <= chunkSize) {
                                                log.info("ReactiveConsumer:Pushed Federated Users to Kafka Topic: " + totalFederatedUsers);
                                                kafkaProducer.send(broadcastTransformerTopic, msg.toXML());
                                            } else {
                                                List<JSONArray> jsonArrayList = commonService.chunkArrayList(federatedUsers, chunkSize);
                                                int count = 1;
                                                for (JSONArray jsonArray : jsonArrayList) {
                                                    log.info("Total Federated Users : " + federatedUsers.length() + " Chunk size : " + jsonArray.length() + " Sent to kafka : " + count);
                                                    msg.getTransformers().get(0).getMetaData().put("federatedUsers", new JSONObject().put("list", jsonArray).toString());
                                                    log.info("ReactiveConsumer:Pushed Federated Users to Kafka Topic: " + jsonArray.length());
                                                    kafkaProducer.send(broadcastTransformerTopic, msg.toXML());
                                                    count++;
                                                }
                                            }
                                        } else {
                                            log.error("ReactiveConsumer:federatedUsers not found in xMessage: " + msg);
                                        }
                                    } else {
                                        if (msg.getTransformers() != null && msg.getTransformers().size() > 0 && msg.getTransformers().get(0) != null
                                                && msg.getTransformers().get(0).getMetaData() != null && msg.getTransformers().get(0).getMetaData().get("federatedUsers") != null) {
                                            JSONArray federatedUsers = new JSONObject(msg.getTransformers().get(0).getMetaData().get("federatedUsers")).getJSONArray("list");
                                            log.info("ReactiveConsumer:Pushed Federated Users to Kafka Topic: " + federatedUsers.length());
                                            kafkaProducer.send(broadcastTransformerTopic, msg.toXML());
                                        } else {
                                            log.error("ReactiveConsumer:federatedUsers not found in xMessage: " + msg);
                                        }
                                    }
                                    pushCount++;
                                    notificationProcessedCount++;
                                    commonService.logTimeTaken(startTime, 0, "Notification processed by orchestrator: " + notificationProcessedCount + " :: Push count : "
                                            + pushCount + " :: orchestrator-notification-process-end-time: %d ms");
                                } catch (Exception ex) {
                                    log.error("ReactiveConsumer:Notification Triggering Process:Error in pushing xMessage to kafka: " + ex.getMessage());
                                }
                            } else {
                                log.error("ReactiveConsumer:onMessage:: Invalid Type Found : " + firstTransformer.findValue("type"));
                            }
                        } else {
                            log.info("ReactiveConsumer:ResolveUser Calling: " + message);
                            commonService.resolveUser(message, appId)
                                    .doOnNext(new Consumer<XMessage>() {
                                        @Override
                                        public void accept(XMessage msg) {
                                            SenderReceiverInfo from = msg.getFrom();
                                            // msg.setFrom(from);
                                            try {
//                                                getLastMessageID(msg)
//                                                        .doOnNext(lastMessageID -> {
                                                commonService.logTimeTaken(startTime, 4, null);
//                                                msg.setLastMessageID(lastMessageID);

                                                /* Switch From & To */
                                                commonService.switchFromTo(msg);

                                                if (msg.getMessageState().equals(XMessage.MessageState.REPLIED) || msg.getMessageState().equals(XMessage.MessageState.OPTED_IN)) {
                                                    try {
                                                        log.info("final msg.toXML(): " + msg.toXML().toString());
                                                        if (firstTransformer.findValue("type") != null && firstTransformer.findValue("type").asText().equals("generic")) {
                                                            kafkaProducer.send(genericTransformerTopic, msg.toXML());
                                                        } else {
                                                            transactionalKafkaProducer.sendTransaction(odkTransformerTopic, msg.toXML());
//                                                            kafkaProducer.send(odkTransformerTopic, msg.toXML());
                                                        }
                                                        // reactiveProducer.sendMessages(odkTransformerTopic, msg.toXML());
                                                    } catch (JAXBException e) {
                                                        e.printStackTrace();
                                                    }
                                                    commonService.logTimeTaken(startTime, 0, "process-end: %d ms");
                                                } else {
                                                    log.error("ReactiveConsumer:onMessage:: MessageSate Invalid Found : " + msg.getMessageState());
                                                }
//                                                        })
//                                                        .doOnError(new Consumer<Throwable>() {
//                                                            @Override
//                                                            public void accept(Throwable throwable) {
//                                                                log.error("Error in getLastMessageID" + throwable.getMessage());
//                                                            }
//                                                        })
//                                                        .subscribe();
                                            } catch (Exception ex) {
                                                log.error("ReactiveConsumer:ODK and Generic Bot Processing:Exception: " + ex.getMessage());
                                            }

                                        }
                                    })
                                    .doOnError(new Consumer<Throwable>() {
                                        @Override
                                        public void accept(Throwable throwable) {
                                            log.error("Error in resolveUser" + throwable.getMessage());
                                        }
                                    }).subscribe();
                        }
                    } else {
                        log.error("Bot node not found by name: " + msg.getApp());
                    }

                }

            }).subscribe();
        } catch (Exception e) {
            log.error("An Error ReactiveConsumer : " + e.getMessage());
        }
    }
}
