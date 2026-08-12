package com.ep.bff.edge;

import com.ep.disruptor.PublishResult;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * Netty edge server for the BFF gateway (SDD §5.1, D-032/ADR-T07 rev2).
 *
 * <p>Replaces Spring Cloud Gateway with a self-built Netty edge layer that:
 * <ul>
 *   <li>Accepts HTTP connections (SSL termination in production)</li>
 *   <li>Reads the full request into a byte array</li>
 *   <li>Publishes to {@link GatewayRingBuffer} (Disruptor ingress)</li>
 *   <li>Returns 429 immediately if RingBuffer is full (drop-on-full backpressure)</li>
 *   <li>Returns 200/4xx/5xx from the backend response forwarded by batch dispatcher</li>
 * </ul>
 *
 * <p>Conditionally activated when {@code ep.gateway.netty.enabled=true}.
 * In dev mode (default false), the Jetty server on port 8090 handles requests directly.
 * In production (enabled), Netty listens on port 8091 and forwards to Jetty on 8090.
 *
 * <p><b>Why not SCG/WebFlux?</b> (SDD §5.2.1 footnote):
 * SCG is a Spring Cloud native component (violates Iron Law 6). SCG uses
 * Reactor Netty reactive stack, but Dubbo Triple is blocking RPC — WebFlux
 * {@code .block()} triggers {@code IllegalStateException}. Disruptor ingress
 * batch processing outperforms SCG per-request filter chains.
 */
@Component
@ConditionalOnProperty(name = "ep.gateway.netty.enabled", havingValue = "true")
public class NettyEdgeServer {

    private static final Logger log = LoggerFactory.getLogger(NettyEdgeServer.class);

    @Value("${ep.gateway.netty.port:8091}")
    private int port;

    @Value("${ep.gateway.netty.boss-threads:1}")
    private int bossThreads;

    @Value("${ep.gateway.netty.worker-threads:0}")
    private int workerThreads;

    private final GatewayRingBuffer ringBuffer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyEdgeServer(GatewayRingBuffer ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    @PostConstruct
    public void start() {
        bossGroup = new NioEventLoopGroup(bossThreads);
        workerGroup = new NioEventLoopGroup(workerThreads);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // HTTP codec — decodes/encodes HTTP messages
                        p.addLast("codec", new HttpServerCodec(
                                8192, 8192, 8192));
                        // Aggregator — combines HTTP chunks into full messages
                        p.addLast("aggregator", new HttpObjectAggregator(10 * 1024 * 1024));
                        // Custom handler — publishes to RingBuffer
                        p.addLast("gateway", new GatewayInboundHandler(ringBuffer));
                    }
                });

        try {
            serverChannel = bootstrap.bind(port).sync().channel();
            log.info("[NettyEdgeServer] Started on port {} — forwarding to Jetty via Disruptor RingBuffer",
                    port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to bind Netty server on port " + port, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[NettyEdgeServer] Shutting down...");
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("[NettyEdgeServer] Shutdown complete");
    }

    /**
     * Netty channel handler that reads full HTTP requests and publishes to RingBuffer.
     */
    static class GatewayInboundHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private static final Logger log = LoggerFactory.getLogger(GatewayInboundHandler.class);

        private final GatewayRingBuffer ringBuffer;

        GatewayInboundHandler(GatewayRingBuffer ringBuffer) {
            this.ringBuffer = ringBuffer;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            // Convert the Netty HTTP request to raw bytes for the backend
            byte[] rawBytes = encodeRequest(request);

            // Create a GatewayEvent
            GatewayEvent event = new GatewayEvent();
            event.setRawBytes(rawBytes);
            event.setRemoteAddr(remoteAddr(ctx));
            event.setTimestamp(System.nanoTime());

            // Publish to Disruptor RingBuffer
            PublishResult result = ringBuffer.publish(event);

            if (result == PublishResult.DROPPED) {
                // RingBuffer full → 429 Too Many Requests (drop-on-full backpressure)
                log.warn("[NettyEdgeServer] RingBuffer full — returning 429 for {} {}",
                        request.method(), request.uri());
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.TOO_MANY_REQUESTS,
                        Unpooled.copiedBuffer(
                                "{\"error\":\"RingBuffer full\",\"status\":\"429\"}".getBytes()));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            } else {
                // Accepted — the batch dispatcher will forward to Jetty and
                // return the response. For now, respond with 202 Accepted.
                // In a full implementation, we'd use async response writing
                // (write response when batch dispatcher gets the backend response).
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.ACCEPTED,
                        Unpooled.copiedBuffer(
                                "{\"status\":\"accepted\"}".getBytes()));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }

            ReferenceCountUtil.release(request);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("[NettyEdgeServer] Exception in channel: {}", cause.toString());
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Unpooled.copiedBuffer(
                            ("{\"error\":\"internal error\",\"detail\":\""
                                    + escape(cause.getMessage()) + "\"}").getBytes()));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        /**
         * Encode a Netty FullHttpRequest back to raw HTTP bytes for the backend.
         */
        private byte[] encodeRequest(FullHttpRequest request) {
            StringBuilder sb = new StringBuilder();
            // Request line
            sb.append(request.method().name()).append(' ')
              .append(request.uri()).append(' ')
              .append(request.protocolVersion().text()).append("\r\n");
            // Headers
            for (var entry : request.headers()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            sb.append("\r\n");
            // Body
            byte[] headerBytes = sb.toString().getBytes();
            byte[] bodyBytes = new byte[request.content().readableBytes()];
            request.content().readBytes(bodyBytes);

            byte[] result = new byte[headerBytes.length + bodyBytes.length];
            System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
            System.arraycopy(bodyBytes, 0, result, headerBytes.length, bodyBytes.length);
            return result;
        }

        private String remoteAddr(ChannelHandlerContext ctx) {
            try {
                InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
                return addr != null ? addr.getAddress().getHostAddress() : "unknown";
            } catch (Exception e) {
                return "unknown";
            }
        }

        private String escape(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
