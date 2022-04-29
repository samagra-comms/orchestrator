package com.uci.orchestrator.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.uci.orchestrator.ApplicationConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;


@Slf4j
@SpringBootTest(classes = ApplicationConfiguration.class)
@ExtendWith(MockitoExtension.class)
class ReactiveConsumerTest {

    @Autowired
    ReactiveConsumer reactiveConsumer;

    @Test
    void onMessage() throws JsonProcessingException {
        reactiveConsumer.onMessage();
    }
}