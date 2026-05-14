/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 LLM 服务实现类
 * <p>
 * 该服务负责智能路由和调度大模型请求，主要功能包括：
 * 1. 根据请求特性选择最佳的大模型提供商
 * 2. 支持多模型候选的自动降级和故障转移
 * 3. 维护模型健康状态，优化路由策略
 * 4. 支持同步和流式两种调用方式
 */
@Slf4j
@Service
@Primary
public class RoutingLLMService implements LLMService {

    private static final int FIRST_PACKET_TIMEOUT_SECONDS = 60;
    private static final String STREAM_INTERRUPTED_MESSAGE = "流式请求被中断";
    private static final String STREAM_NO_PROVIDER_MESSAGE = "无可用大模型提供者";
    private static final String STREAM_START_FAILED_MESSAGE = "流式请求启动失败";
    private static final String STREAM_TIMEOUT_MESSAGE = "流式首包超时";
    private static final String STREAM_NO_CONTENT_MESSAGE = "流式请求未返回内容";
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final ModelRoutingExecutor executor;
    private final Map<String, ChatClient> clientsByProvider;

    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        logInput("chat", request, null);
        long startTime = System.currentTimeMillis();
        String result = executor.executeWithFallback(
                ModelCapability.CHAT,
                selector.selectChatCandidates(request.getThinking()),
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> {
                    String response = client.chat(request, target);
                    logOutputSync(target, response, System.currentTimeMillis() - startTime);
                    return response;
                }
        );
        return result;
    }

    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        List<ModelTarget> targets = selector.selectChatCandidates(request.getThinking());
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(STREAM_NO_PROVIDER_MESSAGE);
        }

        logInput("streamChat", request, targets.get(0));

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;
        long startTime = System.currentTimeMillis();

        for (ModelTarget target : targets) {
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }

            FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
            LoggingStreamCallback loggingCallback = new LoggingStreamCallback(callback, target, startTime);
            ProbeBufferingCallback wrapper = new ProbeBufferingCallback(loggingCallback, awaiter);

            StreamCancellationHandle handle;
            try {
                handle = client.streamChat(request, wrapper, target);
            } catch (Exception e) {
                healthStore.markFailure(target.id());
                lastError = e;
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                continue;
            }
            if (handle == null) {
                healthStore.markFailure(target.id());
                lastError = new RemoteException(STREAM_START_FAILED_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }

            FirstPacketAwaiter.Result result = awaitFirstPacket(awaiter, handle, callback);

            // 判断结果
            if (result.isSuccess()) {
                wrapper.commit();
                healthStore.markSuccess(target.id());
                return handle;
            }

            // 失败处理
            healthStore.markFailure(target.id());
            handle.cancel();

            lastError = buildLastErrorAndLog(result, target, label);
        }

        // 所有模型都失败了，通知客户端错误
        throw notifyAllFailed(callback, lastError);
    }

    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    private FirstPacketAwaiter.Result awaitFirstPacket(FirstPacketAwaiter awaiter,
                                                       StreamCancellationHandle handle,
                                                       StreamCallback callback) {
        try {
            return awaiter.await(FIRST_PACKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancel();
            RemoteException interruptedException = new RemoteException(STREAM_INTERRUPTED_MESSAGE, e, BaseErrorCode.REMOTE_ERROR);
            callback.onError(interruptedException);
            throw interruptedException;
        }
    }

    private Throwable buildLastErrorAndLog(FirstPacketAwaiter.Result result, ModelTarget target, String label) {
        switch (result.getType()) {
            case ERROR -> {
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败，切换下一个模型",
                        label, target.id(), target.candidate().getProvider(), error);
                return error;
            }
            case TIMEOUT -> {
                RemoteException timeout = new RemoteException(STREAM_TIMEOUT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求超时，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return timeout;
            }
            case NO_CONTENT -> {
                RemoteException noContent = new RemoteException(STREAM_NO_CONTENT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求无内容完成，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return noContent;
            }
            default -> {
                RemoteException unknown = new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败（未知类型），切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return unknown;
            }
        }
    }

    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }

    /**
     * 流式首包探测回调：
     * - 探测阶段先缓存事件，避免失败模型的内容污染下游输出
     * - 首包成功后 commit，按原始顺序回放缓存并转实时转发
     */
    private static final class ProbeBufferingCallback implements StreamCallback {

        private final StreamCallback downstream;
        private final FirstPacketAwaiter awaiter;
        private final Object lock = new Object();
        private final List<BufferedEvent> bufferedEvents = new ArrayList<>();
        private volatile boolean committed;

        private ProbeBufferingCallback(StreamCallback downstream, FirstPacketAwaiter awaiter) {
            this.downstream = downstream;
            this.awaiter = awaiter;
            this.committed = false;
        }

        @Override
        public void onContent(String content) {
            awaiter.markContent();
            bufferOrDispatch(BufferedEvent.content(content));
        }

        @Override
        public void onThinking(String content) {
            awaiter.markContent();
            bufferOrDispatch(BufferedEvent.thinking(content));
        }

        @Override
        public void onComplete() {
            awaiter.markComplete();
            bufferOrDispatch(BufferedEvent.complete());
        }

        @Override
        public void onError(Throwable t) {
            awaiter.markError(t);
            bufferOrDispatch(BufferedEvent.error(t));
        }

        /**
         * 首包探测成功后提交：
         * 1. 原子切换为 committed
         * 2. 按事件顺序回放缓存，保证时序一致
         */
        private void commit() {
            List<BufferedEvent> snapshot;
            synchronized (lock) {
                if (committed) {
                    return;
                }
                committed = true;
                if (bufferedEvents.isEmpty()) {
                    return;
                }
                snapshot = new ArrayList<>(bufferedEvents);
                bufferedEvents.clear();
            }
            for (BufferedEvent event : snapshot) {
                dispatch(event);
            }
        }

        private void bufferOrDispatch(BufferedEvent event) {
            boolean dispatchNow;
            synchronized (lock) {
                dispatchNow = committed;
                if (!dispatchNow) {
                    bufferedEvents.add(event);
                }
            }
            if (dispatchNow) {
                dispatch(event);
            }
        }

        private void dispatch(BufferedEvent event) {
            switch (event.type()) {
                case CONTENT -> downstream.onContent(event.content());
                case THINKING -> downstream.onThinking(event.content());
                case COMPLETE -> downstream.onComplete();
                case ERROR -> downstream.onError(event.error() != null
                        ? event.error()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR));
            }
        }

        private record BufferedEvent(EventType type, String content, Throwable error) {

            private static BufferedEvent content(String content) {
                return new BufferedEvent(EventType.CONTENT, content, null);
            }

            private static BufferedEvent thinking(String content) {
                return new BufferedEvent(EventType.THINKING, content, null);
            }

            private static BufferedEvent complete() {
                return new BufferedEvent(EventType.COMPLETE, null, null);
            }

            private static BufferedEvent error(Throwable error) {
                return new BufferedEvent(EventType.ERROR, null, error);
            }
        }

        private enum EventType {
            CONTENT,
            THINKING,
            COMPLETE,
            ERROR
        }
    }

    // ==================== 日志辅助方法 ====================

    /**
     * 记录模型调用输入
     */
    private void logInput(String method, ChatRequest request, ModelTarget target) {
        List<ChatMessage> messages = request.getMessages();
        int messageCount = messages != null ? messages.size() : 0;
        String targetInfo = target != null
                ? String.format("provider=%s, model=%s", target.candidate().getProvider(), target.candidate().getModel())
                : "pending routing";

        log.info("[LLM-{}] 调用开始 | {} | 消息数: {}, temperature: {}, topP: {}, thinking: {}",
                method, targetInfo, messageCount,
                request.getTemperature(), request.getTopP(), request.getThinking());

        if (log.isDebugEnabled() && messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                String content = msg.getContent();
                String truncated = content != null && content.length() > 500
                        ? content.substring(0, 500) + "...(truncated, total " + content.length() + " chars)"
                        : content;
                log.debug("[LLM-{}] 输入消息[{}] role={}, content={}", method, i, msg.getRole(), truncated);
            }
        }
    }

    /**
     * 记录同步调用输出
     */
    private void logOutputSync(ModelTarget target, String response, long elapsedMs) {
        int length = response != null ? response.length() : 0;
        log.info("[LLM-chat] 调用完成 | provider={}, model={} | 输出长度: {} chars, 耗时: {}ms",
                target.candidate().getProvider(), target.candidate().getModel(), length, elapsedMs);

        if (log.isDebugEnabled()) {
            String truncated = response != null && response.length() > 1000
                    ? response.substring(0, 1000) + "...(truncated, total " + response.length() + " chars)"
                    : response;
            log.debug("[LLM-chat] 输出内容: {}", truncated);
        }
    }

    /**
     * 流式调用日志包装回调
     * <p>在流式输出完成时记录输出摘要和耗时</p>
     */
    private class LoggingStreamCallback implements StreamCallback {

        private final StreamCallback delegate;
        private final ModelTarget target;
        private final long startTime;
        private final StringBuilder contentBuffer = new StringBuilder();
        private final StringBuilder thinkingBuffer = new StringBuilder();

        private LoggingStreamCallback(StreamCallback delegate, ModelTarget target, long startTime) {
            this.delegate = delegate;
            this.target = target;
            this.startTime = startTime;
        }

        @Override
        public void onContent(String content) {
            if (content != null) {
                contentBuffer.append(content);
            }
            delegate.onContent(content);
        }

        @Override
        public void onThinking(String content) {
            if (content != null) {
                thinkingBuffer.append(content);
            }
            delegate.onThinking(content);
        }

        @Override
        public void onComplete() {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.info("[LLM-streamChat] 调用完成 | provider={}, model={} | 输出长度: {} chars, 思考长度: {} chars, 耗时: {}ms",
                    target.candidate().getProvider(), target.candidate().getModel(),
                    contentBuffer.length(), thinkingBuffer.length(), elapsedMs);

            if (log.isDebugEnabled()) {
                String content = contentBuffer.toString();
                String truncated = content.length() > 1000
                        ? content.substring(0, 1000) + "...(truncated, total " + content.length() + " chars)"
                        : content;
                log.debug("[LLM-streamChat] 输出内容: {}", truncated);
                if (thinkingBuffer.length() > 0) {
                    String thinking = thinkingBuffer.toString();
                    String thinkTruncated = thinking.length() > 500
                            ? thinking.substring(0, 500) + "...(truncated, total " + thinking.length() + " chars)"
                            : thinking;
                    log.debug("[LLM-streamChat] 思考内容: {}", thinkTruncated);
                }
            }
            delegate.onComplete();
        }

        @Override
        public void onError(Throwable t) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.warn("[LLM-streamChat] 调用失败 | provider={}, model={} | 已输出: {} chars, 耗时: {}ms, 错误: {}",
                    target.candidate().getProvider(), target.candidate().getModel(),
                    contentBuffer.length(), elapsedMs, t.getMessage());
            delegate.onError(t);
        }
    }
}
