/**
 * Copyright 2015 Santhosh Kumar Tekuri
 *
 * The JLibs authors license this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package jlibs.wamp4j.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.AttributeKey;
import jlibs.wamp4j.Util;
import jlibs.wamp4j.spi.AcceptListener;
import jlibs.wamp4j.spi.WebSocketServer;

import java.net.URI;
import java.util.concurrent.ThreadFactory;

/**
 * @author Santhosh Kumar Tekuri
 */
public class NettyWebSocketServer implements WebSocketServer{
    private static final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(1, new ThreadFactory(){
        @Override
        public Thread newThread(Runnable r){
            return new Thread(r, "NettyWebSocketServer");
        }
    });

    private static final AttributeKey<AcceptListener> ACCEPT_LISTENER = AttributeKey.newInstance(AcceptListener.class.getName());
    private Channel channel;

    @Override
    public void bind(final URI uri, final String subProtocols[], final AcceptListener listener){
        int port = uri.getPort();
        if(port==-1)
            port = 80;
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(eventLoopGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>(){
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception{
                        ch.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(65536),
                                new Handshaker(uri, listener, subProtocols)
                        );
                    }
                });
        bootstrap.bind(uri.getHost(), port).addListener(new ChannelFutureListener(){
            @Override
            public void operationComplete(ChannelFuture future) throws Exception{
                if(future.isSuccess()){
                    channel = future.channel();
                    channel.attr(ACCEPT_LISTENER).set(listener);
                    listener.onBind(NettyWebSocketServer.this);
                }else
                    listener.onError(future.cause());
            }
        });
    }

    @Override
    public boolean isEventLoop(){
        return eventLoopGroup.next().inEventLoop();
    }

    @Override
    public void submit(Runnable r){
        eventLoopGroup.submit(r);
    }

    @Override
    public void close(){
        channel.close().addListener(new ChannelFutureListener(){
            @Override
            public void operationComplete(ChannelFuture future) throws Exception{
                AcceptListener acceptListener = channel.attr(ACCEPT_LISTENER).get();
                if(!future.isSuccess())
                    acceptListener.onError(future.cause());
                acceptListener.onClose(NettyWebSocketServer.this);
            }
        });
    }

    private static class Handshaker extends SimpleChannelInboundHandler<FullHttpRequest>{
        private final URI uri;
        private final AcceptListener acceptListener;
        private final String subProtocols[];

        public Handshaker(URI uri, AcceptListener acceptListener, String subProtocols[]){
            this.uri = uri;
            this.acceptListener = acceptListener;
            this.subProtocols = subProtocols;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception{
            WebSocketServerHandshaker handshaker = new WebSocketServerHandshakerFactory(getWebSocketLocation(request),
                    Util.toString(subProtocols), false).newHandshaker(request);
            if(handshaker==null){
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                return;
            }

            ChannelFuture future = handshaker.handshake(ctx.channel(), request);
            for(String subProtocol : subProtocols){
                if(subProtocol.equals(handshaker.selectedSubprotocol())){
                    NettyWebSocket webSocket = new NettyWebSocket(handshaker, subProtocol);
                    ctx.pipeline().addLast("ws-aggregator", new WebSocketFrameAggregator(16 * 1024 * 1024));
                    ctx.pipeline().addLast("websocket", webSocket);
                    ctx.pipeline().remove(this);
                    webSocket.channelActive(ctx);
                    acceptListener.onAccept(webSocket);
                    return;
                }
            }
            future.addListener(ChannelFutureListener.CLOSE);
        }

        private String getWebSocketLocation(FullHttpRequest req) {
            String location =  req.headers().get("Host") + uri.getPath();
            String protocol = uri.getScheme();
            return ("https".equals(protocol)?"wss":"ws")+"://"+location;
        }
    }
}
