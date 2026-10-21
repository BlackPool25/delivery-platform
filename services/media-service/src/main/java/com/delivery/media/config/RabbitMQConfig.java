package com.delivery.media.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${media.rabbitmq.exchange:video.transcode.exchange}")
    private String exchangeName;

    @Value("${media.rabbitmq.queue:video.transcode.jobs}")
    private String queueName;

    @Value("${media.rabbitmq.routing-key:video.transcode.job}")
    private String routingKey;

    @Bean
    public TopicExchange videoTranscodeExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Queue videoTranscodeQueue() {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", exchangeName + ".dlx")
                .withArgument("x-dead-letter-routing-key", routingKey + ".dlq")
                .build();
    }

    @Bean
    public Binding videoTranscodeBinding(Queue videoTranscodeQueue, TopicExchange videoTranscodeExchange) {
        return BindingBuilder.bind(videoTranscodeQueue).to(videoTranscodeExchange).with(routingKey);
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
