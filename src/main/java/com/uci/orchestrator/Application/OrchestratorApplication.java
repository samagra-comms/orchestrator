package com.uci.orchestrator.Application;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.cassandra.CassandraDataAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.cassandra.repository.config.EnableReactiveCassandraRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableKafka
@EnableAsync
@ComponentScan(basePackages = {"com.uci.orchestrator", "com.uci.dao", "messagerosa", "com.uci.utils"})
//@EnableReactiveCassandraRepositories("com.uci.dao")
@EntityScan(basePackages = {"com.uci.dao.models", "com.uci.orchestrator"})
@PropertySource("application-messagerosa.properties")
@PropertySource("application.properties")
@SpringBootApplication(exclude = {CassandraDataAutoConfiguration.class})
@EnableTransactionManagement
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }

    @Bean
    public NewTopic transactionsTopic() {
        return TopicBuilder.name("com.odk.transformer")
                .partitions(2)
                .replicas(1)
                .build();
    }

}
