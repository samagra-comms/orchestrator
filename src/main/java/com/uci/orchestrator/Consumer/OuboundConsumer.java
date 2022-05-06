package com.uci.orchestrator.Consumer;

import com.uci.utils.kafka.SimpleProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.XMessage;
import messagerosa.xml.XMessageParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import javax.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class OuboundConsumer {

    @Value("${outbound}")
    private String outboundTopic;

    @Autowired
    public SimpleProducer kafkaProducer;

    @KafkaListener(id = "${processOutbound}", topics = "${processOutbound}", properties = {"spring.json.value.default.type=java.lang.String"})
    public void onMessage(@Payload String stringMessage) {
        try {
            XMessage msg = XMessageParser.parse(new ByteArrayInputStream(stringMessage.getBytes()));
            kafkaProducer.send(outboundTopic, msg.toXML());
        } catch(JAXBException e) {
            e.printStackTrace();
        }
    }
}
