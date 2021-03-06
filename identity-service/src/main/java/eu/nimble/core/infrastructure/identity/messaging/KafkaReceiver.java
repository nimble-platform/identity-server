package eu.nimble.core.infrastructure.identity.messaging;

import eu.nimble.core.infrastructure.identity.config.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Created by Johannes Innerbichler on 27.09.18.
 */
@Component
public class KafkaReceiver {
    @KafkaListener(topics = "${nimble.kafka.topics.companyUpdates}", containerFactory = "companyUpdatesKafkaListenerContainerFactory")
        public void receiveCompanyUpdates(ConsumerRecord<String, KafkaConfig.AuthorizedCompanyUpdate> consumerRecord) {
        System.out.println("Receiver: " + consumerRecord.value().getCompanyId());
    }
}
