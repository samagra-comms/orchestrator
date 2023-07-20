package com.uci.orchestrator.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.uci.orchestrator.Service.CommonService;
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

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignConsumer {
    @Autowired
    public SimpleProducer kafkaProducer;
    @Autowired
    private BotService botService;
    private long consumeCount;
    private long pushCount;
    @Value("${notification-kafka-cache}")
    private String notificationKafkaCache;
    @Autowired
    private RedisCacheService redisCacheService;
    private long insertSetCount, existSetCount, existingFederatedUsers;
    @Autowired
    private CommonService commonService;
    @Value("${broadcastNotificationChunkSize}")
    private String broadcastNotificationChunkSize;
    @Value("${broadcast-transformer}")
    public String broadcastTransformerTopic;
    private long notificationProcessedCount;

    @KafkaListener(id = "${notificationInboundProcessed}", topics = "${notificationInboundProcessed}", properties = {"spring.json.value.default.type=java.lang.String"})
    public void consumeMessage(@Payload String stringMessage) {
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
                        log.info("CampaignConsumer:First MessageId Insert in Redis count : " + insertSetCount + " MessageId : " + messageId);
                    } else {
                        Set<String> messageIdSet = (Set<String>) redisCacheService.getCache(notificationKafkaCache);
                        if (messageIdSet.contains(messageId)) {
                            existSetCount++;
                            log.info("CampaignConsumer:Already Consumed : " + existSetCount + " MessageId : " + messageId);
                            return;
                        } else {
                            messageIdSet.add(messageId);
                            redisCacheService.setCache(notificationKafkaCache, messageIdSet);
                            insertSetCount++;
                            log.info("CampaignConsumer:MessageId Insert in Redis count : " + insertSetCount + " MessageId : " + messageId);
                        }
                    }
                } else {
                    log.error("CampaignConsumer:MessageId Not Found : " + msg.getMessageId() + " or Notification Cache Name Empty : " + notificationKafkaCache);
                }

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

                        JsonNode firstTransformer = botNode.findValues("transformers").get(0);

                        if (message != null && message.getProviderURI().equals("firebase") && message.getChannelURI().equals("web")) {
                            if (firstTransformer.findValue("type") != null && firstTransformer.findValue("type").asText().equals(BotUtil.transformerTypeBroadcast)) {
                                try {
                                    log.info("CampaignConsumer:broadcastNotificationChunkSize : " + broadcastNotificationChunkSize);
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
                                                log.info("CampaignConsumer:Pushed Federated Users to Kafka Topic: " + totalFederatedUsers);
                                                kafkaProducer.send(broadcastTransformerTopic, msg.toXML());
                                            } else {
                                                List<JSONArray> jsonArrayList = commonService.chunkArrayList(federatedUsers, chunkSize);
                                                int count = 1;
                                                for (JSONArray jsonArray : jsonArrayList) {
                                                    log.info("Total Federated Users : " + federatedUsers.length() + " Chunk size : " + jsonArray.length() + " Sent to kafka : " + count);
                                                    msg.getTransformers().get(0).getMetaData().put("federatedUsers", new JSONObject().put("list", jsonArray).toString());
                                                    log.info("CampaignConsumer:Pushed Federated Users to Kafka Topic: " + jsonArray.length());
                                                    kafkaProducer.send(broadcastTransformerTopic, msg.toXML());
                                                    count++;
                                                }
                                            }
                                        } else {
                                            log.error("CampaignConsumer:federatedUsers not found in xMessage: " + msg);
                                        }
                                    } else {
                                        if (msg.getTransformers() != null && msg.getTransformers().size() > 0 && msg.getTransformers().get(0) != null
                                                && msg.getTransformers().get(0).getMetaData() != null && msg.getTransformers().get(0).getMetaData().get("federatedUsers") != null) {
                                            JSONArray federatedUsers = new JSONObject(msg.getTransformers().get(0).getMetaData().get("federatedUsers")).getJSONArray("list");
                                            log.info("CampaignConsumer:Pushed Federated Users to Kafka Topic: " + federatedUsers.length());
                                            kafkaProducer.send(broadcastTransformerTopic, msg.toXML());
                                        } else {
                                            log.error("CampaignConsumer:federatedUsers not found in xMessage: " + msg);
                                        }
                                    }
                                    pushCount++;
                                    notificationProcessedCount++;
                                    commonService.logTimeTaken(startTime, 0, "Notification processed by orchestrator: " + notificationProcessedCount + " :: Push count : "
                                            + pushCount + " :: orchestrator-notification-process-end-time: %d ms");
                                } catch (Exception ex) {
                                    log.error("CampaignConsumer:Notification Triggering Process:Error in pushing xMessage to kafka: " + ex.getMessage());
                                }
                            } else {
                                log.error("CampaignConsumer:onMessage:: Invalid Type Found : " + firstTransformer.findValue("type"));
                            }
                        } else {
                            log.error("CampaignConsumer:onMessage:: Invalid Provider Type: " + message.getProviderURI());
                        }
                    } else {
                        log.error("CampaignConsumer:onMessage::Bot node not found by name: " + msg.getApp());
                    }
                }
            }).subscribe();
        } catch (Exception e) {
            log.error("CampaignConsumer:onMessage::An Error: " + e.getMessage());
        }
    }


//    @KafkaListener(id = "${campaign}", topics = "${campaign}", properties = {"spring.json.value.default.type=java.lang.String"})
//    public void consumeMessage(String campaignID) throws Exception {
//        log.info("CampaignID {}", campaignID);
//        processMessage(campaignID)
//                .doOnError(s -> log.info(s.getMessage()))
//                .subscribe(new Consumer<XMessage>() {
//                    @Override
//                    public void accept(XMessage xMessage) {
//                        log.info("Pushing to : " + TransformerRegistry.getName(xMessage.getTransformers().get(0).getId()));
//                        try {
//                            kafkaProducer.send("com.odk.broadcast", xMessage.toXML());
//                        } catch (JAXBException e) {
//                            e.printStackTrace();
//                        }
//                    }
//                });
//
//    }
//    /**
//     * Retrieve a campaign's info from its identifier (Campaign ID)
//     *
//     * @param campaignID - String {Campaign Identifier}
//     * @return XMessage
//     */
//    public Mono<XMessage> processMessage(String campaignID) throws Exception {
//        // Get campaign ID and get campaign details {data: transformers [broadcast(SMS), <formID>(Whatsapp)]}
//        return botService
//                .getBotNodeFromId(campaignID)
//                .doOnError(s -> log.info(s.getMessage()))
//                .map(new Function<JsonNode, XMessage>() {
//                    @Override
//                    public XMessage apply(JsonNode campaignDetails) {
//                        ObjectMapper mapper = new ObjectMapper();
//                        JsonNode adapter = campaignDetails.findValues("logic").get(0).get(0).get("adapter");
//
//                        // Create a new campaign xMessage
//                        XMessagePayload payload = XMessagePayload.builder().text("").build();
//
//                        String userSegmentName = ((ArrayNode) campaignDetails.get("userSegments")).get(0).get("name").asText();
//                        SenderReceiverInfo to = SenderReceiverInfo.builder()
//                                .userID(userSegmentName)
//                                .build();
//
//                        Transformer broadcast = Transformer.builder()
//                                .id("1")
//                                .build();
//                        ArrayList<Transformer> transformers = new ArrayList<>();
//                        transformers.add(broadcast);
//
//                        Map<String, String> metadata = new HashMap<>();
//                        SenderReceiverInfo from = SenderReceiverInfo.builder()
//                                .userID("admin")
//                                .meta(metadata)
//                                .build();
//
//                        XMessage.MessageType messageType = XMessage.MessageType.BROADCAST_TEXT;
//
//                        return XMessage.builder()
//                                .app(campaignDetails.get("name").asText())
//                                .channelURI(adapter.get("channel").asText())
//                                .providerURI(adapter.get("provider").asText())
//                                .payload(payload)
//                                .conversationStage(new ConversationStage(0, ConversationStage.State.STARTING))
//                                .timestamp(System.currentTimeMillis())
//                                .transformers(transformers)
//                                .to(to)
//                                .messageType(messageType)
//                                .from(from)
//                                .build();
//                    }
//                }).doOnError(e -> {
//                    log.error("Error in Campaign Consume::" + e.getMessage());
//                });
//
//    }
}