package io.github.qyvlik.jsonrpclite.core.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import io.github.qyvlik.jsonrpclite.core.jsonrpc.entity.request.RequestObject;
import io.github.qyvlik.jsonrpclite.core.jsonrpc.entity.response.ResponseObject;
import io.github.qyvlik.jsonrpclite.core.jsonsub.pub.ChannelMessage;
import io.github.qyvlik.jsonrpclite.core.jsonsub.sub.SubRequestObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketConnectionManager;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class RpcClient {
    private StandardWebSocketClient webSocketClient;
    private WebSocketSession webSocketSession;
    private WebSocketConnectionManager webSocketConnectionManager;
    private String wsUrl;
    // todo expired the object in map
    private Map<Long, RpcResponseFuture> rpcCallback = Maps.newConcurrentMap();
    private Map<String, ChannelMessageHandler> channelCallback = Maps.newConcurrentMap();

    private AtomicLong rpcRequestCounter = new AtomicLong(0);

    public RpcClient(String wsUrl) {
        this.wsUrl = wsUrl;
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(10 * 1024 * 1024);
        webSocketClient = new StandardWebSocketClient(container);
        webSocketConnectionManager = new WebSocketConnectionManager(webSocketClient,
                new RpcClientTextHandler(), this.wsUrl);
    }

    public void startup() {
        webSocketConnectionManager.start();
    }

    public boolean isOpen() {
        return webSocketSession != null && webSocketSession.isOpen();
    }

    public void listenSub(String channel, Boolean subscribe, List params, ChannelMessageHandler handler) throws IOException {
        if (!isOpen()) {
            throw new RuntimeException("callRpcAsyncInternal failure webSocketSession is not open");
        }

        if (subscribe != null && subscribe) {
            channelCallback.put(channel, handler);
        } else {
            channelCallback.remove(channel);
        }

        SubRequestObject subRequestObject = new SubRequestObject();
        subRequestObject.setChannel(channel);
        subRequestObject.setSubscribe(subscribe);
        subRequestObject.setParams(params);

        webSocketSession.sendMessage(new TextMessage(JSON.toJSONString(subRequestObject)));
    }

    public Future<ResponseObject> callRpcAsync(String method, List params) throws Exception {
        if (!isOpen()) {
            throw new RuntimeException("callRpcAsyncInternal failure webSocketSession is not open");
        }

        Long id = rpcRequestCounter.getAndIncrement();
        RequestObject requestObject = new RequestObject();
        requestObject.setId(id);
        requestObject.setMethod(method);
        requestObject.setParams(params);
        return callRpcAsyncInternal(requestObject, true);
    }

    private Future<ResponseObject> callRpcAsyncInternal(RequestObject requestObject,
                                                        boolean returnFuture)
            throws IOException {
        if (requestObject == null || requestObject.getId() == null) {
            throw new RuntimeException("callRpcAsyncInternal failure requestObject is null or requestObject's id is null");
        }

        RpcResponseFuture rpcResponseFuture = null;
        if (returnFuture) {
            rpcResponseFuture = new RpcResponseFuture();
            rpcCallback.put(requestObject.getId(), rpcResponseFuture);
        }

        webSocketSession.sendMessage(new TextMessage(JSON.toJSONString(requestObject)));

        return rpcResponseFuture;
    }

    private class RpcClientTextHandler extends AbstractWebSocketHandler {
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            webSocketSession = session;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message)
                throws Exception {
            JSONObject resObj = JSON.parseObject(message.getPayload());

            if (StringUtils.isNotBlank(resObj.getString("channel"))) {
                ChannelMessageHandler channelMessageHandler
                        = channelCallback.get(resObj.getString("channel"));
                if (channelMessageHandler != null) {
                    ChannelMessage channelMessage = resObj.toJavaObject(ChannelMessage.class);
                    channelMessageHandler.handle(channelMessage);
                }
            } else {
                ResponseObject responseObject = resObj.toJavaObject(ResponseObject.class);
                RpcResponseFuture future = rpcCallback.remove(responseObject.getId());
                if (future != null) {
                    future.setResponseObject(responseObject);
                }
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
            webSocketSession = null;
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            // todo
        }
    }

}
