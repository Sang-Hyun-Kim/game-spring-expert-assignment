package com.gameexpert.chat.relay;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.gameexpert.chat.service.LocalChatSender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
public class ChatRelay implements MessageListener {
    public static final String CHANNEL = "webcraft:chat";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final LocalChatSender localChatSender;

    public void publish(Long worldId, Object message) {
        ObjectNode chatJson = objectMapper.createObjectNode();

        chatJson.put("worldId", worldId);
        chatJson.set("message", objectMapper.valueToTree(message));

        redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(chatJson));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        JsonNode rootNode = objectMapper.readTree(message.getBody());
        Long worldId = rootNode.path("worldId").asLong();
        localChatSender.send(worldId, rootNode.path("message"));
    }
}
