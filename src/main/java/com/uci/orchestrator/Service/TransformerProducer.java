package com.uci.orchestrator.Service;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.concurrent.ListenableFutureCallback;

import javax.validation.constraints.NotNull;

@Service
@Slf4j
public class TransformerProducer {
    @Qualifier("transactionalKafkaTemplate")
    private final KafkaTemplate<String, String> transactionalKafkaTemplate;

    public TransformerProducer(KafkaTemplate<String, String> transactionalKafkaTemplate) {
        this.transactionalKafkaTemplate = transactionalKafkaTemplate;
    }

    @Transactional
    public void sendTransaction(String topic, String message) {
        transactionalKafkaTemplate
                .send(topic, message)
                .addCallback(new ListenableFutureCallback<SendResult<String, String>>() {
                    @Override
                    public void onFailure(@NotNull Throwable throwable) {
                        log.error("Kafka::Send:Exception: Unable to push topic {} message {} due to {}", topic, message, throwable.getMessage());
                    }

                    @Override
                    public void onSuccess(SendResult<String, String> stringStringSendResult) {
                        log.info("Kafka::Send: Pushed to topic {}", topic);
                    }
                });
    }
}
