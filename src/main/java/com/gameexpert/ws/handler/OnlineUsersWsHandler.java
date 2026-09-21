package com.gameexpert.ws.handler;

import java.util.List;
import com.gameexpert.ws.WorldBroadcaster;
import com.gameexpert.ws.WorldSessionRegistry;
import com.gameexpert.ws.WsMessageContext;
import com.gameexpert.ws.dto.OnlineUsersResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import static com.gameexpert.ws.NicknameHandshakeInterceptor.ATTR_NICKNAME;

@Component
@RequiredArgsConstructor
public class OnlineUsersWsHandler implements WsMessageHandler {
    private final WorldSessionRegistry registry;
    private final WorldBroadcaster broadcaster;

    @Override
    public String type() {
        return "onlineUsers";
    }

    @Override
    public void handle(WsMessageContext context, JsonNode message) {
        List<String> users = registry.entries(context.worldId()).stream()
                .filter(entry -> entry.session().isOpen())
                .map(entry -> entry.session().getAttributes().get(ATTR_NICKNAME).toString())
                .sorted()
                .toList();
        broadcaster.sendTo(context.session(), new OnlineUsersResponse(users, users.size()));
    }
}
