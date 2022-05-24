package com.uci.orchestrator.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inversoft.error.Errors;
import com.inversoft.rest.ClientResponse;
import com.uci.dao.models.XMessageDAO;
import com.uci.dao.repository.XMessageRepository;
import com.uci.utils.BotService;
import com.uci.utils.CampaignService;
import com.uci.utils.cache.service.RedisCacheService;
import com.uci.utils.encryption.AESWrapper;
import com.uci.utils.kafka.ReactiveProducer;
import com.uci.utils.kafka.SimpleProducer;
import com.uci.utils.service.UserService;

import io.fusionauth.domain.api.UserConsentResponse;
import io.fusionauth.domain.api.UserRequest;
import io.fusionauth.domain.api.UserResponse;
import io.fusionauth.domain.User;
import io.fusionauth.domain.UserRegistration;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.DeviceType;
import messagerosa.core.model.SenderReceiverInfo;
import messagerosa.core.model.Transformer;
import messagerosa.core.model.XMessage;
import messagerosa.core.model.XMessagePayload;
import messagerosa.xml.XMessageParser;
import org.apache.commons.lang3.tuple.Pair;
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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.lang.Nullable;
import org.springframework.messaging.handler.annotation.Payload;
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
    
    @Value("${broadcast-transformer}")
    public String broadcastTransformerTopic;

    @Autowired
    public BotService botService;

    @Autowired
    public CampaignService campaignService;
    
    @Autowired
    private UserService userService;

    @Value("${encryptionKeyString}")
    private String secret;

    @Autowired
    private RedisCacheService redisCacheService;
    
    public AESWrapper encryptor;

    private final String DEFAULT_APP_NAME = "Global Bot";
    LocalDateTime yesterday = LocalDateTime.now().minusDays(1L);

    
    @KafkaListener(id = "${inboundProcessed}", topics = "${inboundProcessed}", properties = {"spring.json.value.default.type=java.lang.String"})
    public void onMessage(@Payload String stringMessage) {
        try {
            final long startTime = System.nanoTime();
            logTimeTaken(startTime, 0);
            XMessage msg = XMessageParser.parse(new ByteArrayInputStream(stringMessage.getBytes()));
            SenderReceiverInfo from = msg.getFrom();
            logTimeTaken(startTime, 1);
            fetchAdapterID(msg.getApp())
                    .doOnNext(new Consumer<String>() {
                        @Override
                        public void accept(String adapterID) {
                            logTimeTaken(startTime, 3);
                            from.setCampaignID(msg.getApp());
                            if(from.getDeviceType() == null) {
                                from.setDeviceType(DeviceType.PHONE);
                            }
                            campaignService.getCampaignFromNameTransformer(msg.getApp()).doOnNext(new Consumer<JsonNode>() {
                                @Override
                                public void accept(JsonNode campaign) {
                                    /* Set XMessage Transformers */
                                    XMessage message = setXMessageTransformers(msg, campaign);

                                    String appId = campaign.get("id").asText();
                                    JsonNode firstTransformer = campaign.findValues("transformers").get(0).get(0);

                                    resolveUserNew(message, appId)
                                            .doOnNext(new Consumer<XMessage>() {
                                                @Override
                                                public void accept(XMessage msg) {
                                                    SenderReceiverInfo from = msg.getFrom();
                                                    // msg.setFrom(from);
                                                    getLastMessageID(msg)
                                                            .doOnNext(lastMessageID -> {
                                                                logTimeTaken(startTime, 4);
                                                                msg.setLastMessageID(lastMessageID);
                                                                msg.setAdapterId(adapterID);

                                                                /* Switch From & To */
                                                                switchFromTo(msg);

                                                                if (msg.getMessageState().equals(XMessage.MessageState.REPLIED) || msg.getMessageState().equals(XMessage.MessageState.OPTED_IN)) {
                                                                    try {
                                                                        log.info("final msg.toXML(): "+msg.toXML().toString());
                                                                        if(firstTransformer.get("type") != null && firstTransformer.get("type").asText().equals("broadcast")) {
                                                                            kafkaProducer.send(broadcastTransformerTopic, msg.toXML());
                                                                        } else {
                                                                            kafkaProducer.send(odkTransformerTopic, msg.toXML());
                                                                        }
                                                                        // reactiveProducer.sendMessages(odkTransformerTopic, msg.toXML());
                                                                    } catch (JAXBException e) {
                                                                        e.printStackTrace();
                                                                    }
                                                                    logTimeTaken(startTime, 15);
                                                                }
                                                            })
                                                            .doOnError(new Consumer<Throwable>() {
                                                                @Override
                                                                public void accept(Throwable throwable) {
                                                                    log.error("Error in getLastMessageID" + throwable.getMessage());
                                                                }
                                                            })
                                                            .subscribe();
                                                }
                                            })
                                            .doOnError(new Consumer<Throwable>() {
                                                @Override
                                                public void accept(Throwable throwable) {
                                                    log.error("Error in resolveUser" + throwable.getMessage());
                                                }
                                            }).subscribe();
                                }

                            }).subscribe();
                        }
                    })
                    .doOnError(new Consumer<Throwable>() {
                        @Override
                        public void accept(Throwable throwable) {
                            log.error("Error in fetchAdapterID" + throwable.getMessage());
                        }
                    })
                    .subscribe();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Set Transformer in XMessage with transformer required data in meta
     * @param xMessage
     * @param campaign
     * @return XMessage
     */
    private XMessage setXMessageTransformers(XMessage xMessage, JsonNode campaign) {
        ArrayList<Transformer> transformers = new ArrayList<Transformer>();

        ArrayList transformerList = (ArrayList) campaign.findValues("transformers");
        transformerList.forEach(transformerTmp -> {
            JsonNode transformerNode = (JsonNode) transformerTmp;
            int i=0;
            while(transformerNode.get(i) != null) {
                JsonNode transformer = transformerNode.get(i);
                log.info("transformer:"+transformer);

                HashMap<String, String> metaData = new HashMap<String, String>();
                metaData.put("id", transformer.get("id").asText());
                metaData.put("type", transformer.get("type") != null && !transformer.get("type").asText().isEmpty()
                        ? transformer.get("type").asText()
                        : "");
                metaData.put("formID", transformer.findValue("formID") != null && !transformer.findValue("formID").asText().isEmpty()
                        ? transformer.findValue("formID").asText()
                        : "");
                metaData.put("startingMessage", campaign.findValue("startingMessage").asText());
                metaData.put("botId", campaign.findValue("id").asText());
                metaData.put("botOwnerOrgID", campaign.findValue("ownerOrgID").asText());
                if(transformer.get("type") != null && transformer.get("type").asText().equals("broadcast")) {
                    metaData.put("federatedUsers", getFederatedUsersMeta(campaign, transformer));
                }

                if(transformer.findValue("hiddenFields") != null && !transformer.findValue("hiddenFields").isEmpty()) {
                    metaData.put("hiddenFields", campaign.findValue("hiddenFields").toString());
                }

                if(transformer.get("meta").get("templateId") != null && !transformer.get("meta").get("templateId").asText().isEmpty()){
                    metaData.put("templateId", transformer.get("meta").get("templateId").asText());
                }

                Transformer transf = new Transformer();
                transf.setId(transformer.get("id").asText());
                transf.setMetaData(metaData);

                transformers.add(transf);
                i++;
            }
        });
        xMessage.setTransformers(transformers);
        return xMessage;
    }

    /**
     * Get Federated Users Data for Broadcast transformer
     * @param campaign
     * @param transformer
     * @return Federated users as json string
     */
    private String getFederatedUsersMeta(JsonNode campaign, JsonNode transformer) {
    	String campaignID = campaign.get("id").asText();
    	
    	/* Get federated users from federation services */
        JSONArray users = userService.getUsersFromFederatedServers(campaignID);
        
        if(users != null) {
        	/* Create request body data for user template message */
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
        	node.put("body", transformer.get("meta").get("body").asText());
        	node.put("type", transformer.get("meta").get("type").asText());
        	node.put("user", transformer.get("meta").get("user").asText());
        	
        	ArrayNode sampleData = mapper.createArrayNode();
        	for (int i = 0; i < users.length(); i++) {
            	ObjectNode userData = mapper.createObjectNode();
                if(transformer.get("meta") != null && transformer.get("meta").get("params") != null
                        && !transformer.get("meta").get("params").toString().isEmpty()){
                    JSONArray paramArr = new JSONArray(transformer.get("meta").get("params").toString());
                    for(int k=0; k<paramArr.length(); k++){
                        if(!((JSONObject) users.get(i)).isNull(paramArr.getString(k))){
                            userData.put(paramArr.getString(k), ((JSONObject) users.get(i)).getString(paramArr.getString(k)));
                        }
                    }
                }
            	userData.put("__index", i);
            	sampleData.add(userData);
        	}
        	node.put("sampleData", sampleData);
        	
        	/* Fetch user messages by template from template service */
        	ArrayList<JSONObject> usersMessage = userService.getUsersMessageByTemplate(node);
            
        	log.info("usersMessage: "+usersMessage);
        	
        	/* Set User messages against the user phone */
        	ObjectNode federatedUsersMeta = mapper.createObjectNode();
        	ArrayNode userMetaData = mapper.createArrayNode();
            usersMessage.forEach(userMsg -> {
        		int j = Integer.parseInt(userMsg.get("__index").toString());
        		String userPhone = ((JSONObject) users.get(j)).getString("phoneNo");
//        		userPhone = "7597185708";
               
        		ObjectNode map = mapper.createObjectNode();
        		map.put("phone", userPhone);
        		map.put("message", userMsg.get("body").toString());
                userMetaData.add(map);
        		
        		log.info("index: "+j+", body: "+userMsg.get("body").toString()+", phone:"+userPhone);
        	});
            
            federatedUsersMeta.put("list", userMetaData);

            return federatedUsersMeta.toString();
        }
        return "";
    }
    
    private Mono<XMessage> resolveUserNew(XMessage xmsg, String appId) {
        try {
            SenderReceiverInfo from = xmsg.getFrom();
            String appName = xmsg.getApp();
            Boolean found = false;
            
            UUID appID = UUID.fromString(appId);
            
            String deviceString = from.getDeviceType().toString() + ":" + from.getUserID();
            String encodedBase64Key = encodeKey(secret);
            String deviceID = AESWrapper.encrypt(deviceString, encodedBase64Key);
            log.info("deviceString: "+deviceString+", encyprted deviceString: "+deviceID);
            String userID = getFAUserIdForApp(deviceID, appID);
            
            if (userID != null && !userID.isEmpty()) {
            	log.info("Found FA user id");
//                log.info("FA response user uuid: "+response.successResponse.user.id.toString()
//                +", username: "+response.successResponse.user.username);
                from.setDeviceID(userID);
                from.setEncryptedDeviceID(deviceID);
                xmsg.setFrom(from);
                return Mono.just(xmsg);
            } else {
                return botService.updateUser(deviceString, appName)
                        .flatMap(new Function<Pair<Boolean, String>, Mono<XMessage>>() {
                            @Override
                            public Mono<XMessage> apply(Pair<Boolean, String> result) {
                            	log.info("FA update user");
                                if (result.getLeft()) {
                                    from.setDeviceID(result.getRight());
                                    from.setEncryptedDeviceID(deviceID);
                                    xmsg.setFrom(from);
                                    ClientResponse<UserResponse, Errors> response = campaignService.fusionAuthClient.retrieveUserByUsername(deviceID);
                                    if (response.wasSuccessful() && isUserRegistered(response, appID)) {
                                    	redisCacheService.setFAUserIDForAppCache(getFACacheName(deviceID, appID), response.successResponse.user.id.toString());
                                        return Mono.just(xmsg);
                                    } else {
                                        return Mono.just(xmsg);
                                    }
                                } else {
                                    xmsg.setFrom(null);
                                    return Mono.just(xmsg);
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
            xmsg.setFrom(null);
            return Mono.just(xmsg);
        }
    }
    
    /**
     * Get Fusion Auth User's UUID for App
     * @param deviceID
     * @param appID
     * @return
     */
    private String getFAUserIdForApp(String deviceID, UUID appID) {
    	String userID = null;
    
    	Object result = redisCacheService.getFAUserIDForAppCache(getFACacheName(deviceID, appID));
    	userID = result != null ? result.toString() : null;
    	
    	if(userID == null || userID.isEmpty()) {
    		ClientResponse<UserResponse, Errors> response = campaignService.fusionAuthClient.retrieveUserByUsername(deviceID);
            
            if (response.wasSuccessful() && isUserRegistered(response, appID)) {
            	userID = response.successResponse.user.id.toString();
            	redisCacheService.setFAUserIDForAppCache(getFACacheName(deviceID, appID), userID);
            }
    	}
        return userID;
    }
    
    /**
     * Check if FA user is registered for appid
     * @param response
     * @param appID
     * @return
     */
    private Boolean isUserRegistered(ClientResponse<UserResponse, Errors> response, UUID appID) {
    	List<UserRegistration> registrations = response.successResponse.user.getRegistrations();
    	for(int i=0; i<registrations.size(); i++) {
    		if(registrations.get(i).applicationId.equals(appID)) {
    			return true;
    		}
    	}
    	return false;
    }
    
    private String getFACacheName(String deviceID, UUID appID) {
    	return deviceID+"-"+appID.toString();
    }
    
    private Mono<XMessage> xmsgCampaignForm(XMessage xmsg, User user) {
        return campaignService.getCampaignFromNameTransformer(xmsg.getCampaign())
            .map(new Function<JsonNode, XMessage>() {
                @Override
                public XMessage apply(JsonNode campaign) {
                    String campaignID = campaign.findValue("id").asText();
                    Map<String, String> formIDs = getCampaignFormIds(campaign);
                    String currentFormID = getCurrentFormId(xmsg, campaignID, formIDs, user);
                    
                    HashMap<String, String> metaData = new HashMap<String, String>();
                    metaData.put("campaignId", campaignID);
                    metaData.put("currentFormID", currentFormID);
                    
                    saveCurrentFormID(xmsg.getFrom().getUserID(), campaignID, 
                            currentFormID);
                    
                    Transformer transf = new Transformer();
                    transf.setId("test");
                    transf.setMetaData(metaData);
                    
                    ArrayList<Transformer> transformers = new ArrayList<Transformer>();
                    transformers.add(transf);
                    
                    xmsg.setTransformers(transformers);
                    
                    try {
                        System.out.println("XML:"+xmsg.toXML());
                    } catch (JAXBException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                    
                    return xmsg;
                }
            });
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
            
            System.out.println("formIDs:"+formIDs2);
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
        
        /* if current form id is empty, then set the first form id as current form id 
         * else if current form id is equal to consent form id
            *   
         */
        System.out.println("currentFormID:"+currentFormID);
        if(currentFormID == null || currentFormID.isEmpty()) {
            if(!formIDs.isEmpty() && formIDs.size() > 0) {
                currentFormID = formIDs.values().toArray()[0].toString();
            }
        } 
        
        /* if current form is consent form */
        if(currentFormID.equals(getConsentFormID())) {
            /* if consent already exists, set next form as current one,
             * else check the response for further details */
            if(consent) {
                currentFormID = formIDs.values().toArray()[1].toString();
            } else {
                String response = xmsg.getPayload().getText();
                if(response.equals("1")) {
                    //update fusion auth client for consent & set the next form id as current form id
                    addUserCampaignConsent(campaignID, user);
                    currentFormID = formIDs.values().toArray()[1].toString();
                } else if(response.equals("2")) {
                    //drop conversation
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
            
            if(!rootNode.isEmpty() && rootNode.get(userID) != null 
                    && rootNode.path(userID).get(campaignID) != null) {
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
            if(rootNode.isEmpty()) {
                rootNode = mapper.createObjectNode();
            }
            
            if(!rootNode.isEmpty() && rootNode.get(userID) != null) {
                ((ObjectNode) rootNode.path(userID)).put(campaignID, currentFormID);
            } else {
                JsonNode campaignNode = mapper.createObjectNode();
                ((ObjectNode) campaignNode).put(campaignID, currentFormID);
                
                ((ObjectNode) rootNode).put(userID, campaignNode);
            }
            
            System.out.println("Saved File String:"+rootNode.toString());
            
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

    /**
     * NOT IN USE
     * Save and get user data, authenticated by FA, 
     * @param campaignID
     * @param user
     * @return
     */
//    private Mono<SenderReceiverInfo> resolveUser(SenderReceiverInfo from, String appName) {
//        try {
//            String deviceString = from.getDeviceType().toString() + ":" + from.getUserID();
//            String encodedBase64Key = encodeKey(secret);
//            String deviceID = AESWrapper.encrypt(deviceString, encodedBase64Key);
//            ClientResponse<UserResponse, Errors> response = campaignService.fusionAuthClient.retrieveUserByUsername(deviceID);
//            if (response.wasSuccessful()) {
//                from.setDeviceID(response.successResponse.user.id.toString());
////                checkConsent(from.getCampaignID(), response.successResponse.user);
//                return Mono.just(from);
//            } else {
//                return botService.updateUser(deviceString, appName)
//                        .flatMap(new Function<Pair<Boolean, String>, Mono<SenderReceiverInfo>>() {
//                            @Override
//                            public Mono<SenderReceiverInfo> apply(Pair<Boolean, String> result) {
//                                if (result.getLeft()) {
//                                    from.setDeviceID(result.getRight());
//                                    return Mono.just(from);
//                                } else {
//                                    return Mono.just(null);
//                                }
//                            }
//                        }).doOnError(new Consumer<Throwable>() {
//                            @Override
//                            public void accept(Throwable throwable) {
//                                log.error("Error in updateUser" + throwable.getMessage());
//                            }
//                        });
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//            log.error("Error in resolveUser" + e.getMessage());
//            return Mono.just(null);
//        }
//    }
    
    private Boolean checkUserCampaignConsent(String campaignID, User user) {
        Boolean consent = false;
        try {
            Object consentData = user.data.get("consent");
            ArrayList consentArray = (ArrayList) consentData;
            if(consentArray != null && consentArray.contains(campaignID)) {
                consent = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return consent;
    }
    
    private void addUserCampaignConsent(String campaignID, User user) 
    {
        try {
            Object consentData = user.data.get("consent");
            ArrayList consentArray = (ArrayList) consentData;
            
            if(consentArray == null || (
                consentArray != null && !consentArray.contains(campaignID))
            ){
                consentArray.add(campaignID);
                
                user.data.put("consent", consentArray); 
                
                updateFAUser(user);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void updateFAUser(User user) {
        System.out.println(user);
        UserRequest r = new UserRequest(user);
        
        ClientResponse<UserResponse, Errors> response = campaignService.fusionAuthClient.updateUser(user.id, r);
        if(response.wasSuccessful()) {
            System.out.println("user update success");
        } else {
            System.out.println("error in user update"+response.errorResponse);
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
        return xMessageRepository
                .findAllByUserIdAndTimestampAfter(userID, yesterday).collectList()
                .map(new Function<List<XMessageDAO>, XMessageDAO>() {
                    @Override
                    public XMessageDAO apply(List<XMessageDAO> xMessageDAOS) {
                        if (xMessageDAOS.size() > 0) {
                            List<XMessageDAO> filteredList = new ArrayList<>();
                            for (XMessageDAO xMessageDAO : xMessageDAOS) {
                                if (xMessageDAO.getMessageState().equals(XMessage.MessageState.SENT.name()) ||
                                        xMessageDAO.getMessageState().equals(XMessage.MessageState.REPLIED.name()))
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
                }).doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        log.error("Error in getLatestXMessage" + throwable.getMessage());
                    }
                });
    }

    private Mono<String> fetchAdapterID(String appName) {
        return botService.getCurrentAdapter(appName);
    }

    private Mono<String> getAppName(String text, SenderReceiverInfo from) {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1L);
        log.info("Inside getAppName " + text + "::" + from.getUserID());
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
                log.info("Inside getAppName " + text + "::" + from.getUserID());
                return botService.getCampaignFromStartingMessage(text)
                        .flatMap(new Function<String, Mono<? extends String>>() {
                            @Override
                            public Mono<String> apply(String appName1) {
                                log.info("Inside getCampaignFromStartingMessage => " + appName1);
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
                        }).doOnError(new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) {
                                log.error("Error in getCampaignFromStartingMessage" + throwable.getMessage());
                            }
                        });
            } catch (Exception e) {
                log.info("Inside getAppName - exception => " + e.getMessage());
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
    
    /**
     * Switch from & To in XMessage
     * @param xMessage
     */
    private void switchFromTo(XMessage xMessage) {
        SenderReceiverInfo from = xMessage.getFrom();
        SenderReceiverInfo to = xMessage.getTo();
        xMessage.setFrom(to);
        xMessage.setTo(from);
    }
}
