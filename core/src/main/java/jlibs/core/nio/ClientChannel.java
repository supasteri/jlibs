/**
 * JLibs: Common Utilities for Java
 * Copyright (C) 2009  Santhosh Kumar T <santhosh.tekuri@gmail.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 */

package jlibs.core.nio;

import jlibs.core.net.SSLUtil;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

/**
 * @author Santhosh Kumar T
 */
public class ClientChannel extends NIOChannel implements ByteChannel{
    protected final NIOSelector nioSelector;
    protected final SelectionKey key;
    protected ClientChannel(NIOSelector nioSelector, SocketChannel socketChannel) throws IOException{
        super(++nioSelector.lastClientID, socketChannel);
        key = realChannel().register(nioSelector.selector, 0, this);
        this.nioSelector = nioSelector;
        if(isConnected())
            nioSelector.connectedClients++;
        else
            nioSelector.connectionPendingClients++;
    }

    protected ClientChannel(NIOSelector nioSelector, SocketChannel socketChannel, SocketAddress acceptedFrom) throws IOException{
        this(nioSelector, socketChannel);
        this.acceptedFrom = acceptedFrom;
    }

    protected ClientChannel(){
        nioSelector = null;
        key = null;
        next = prev = this;
    }

    @Override
    public SocketChannel realChannel(){
        return (SocketChannel)channel;
    }

    private SocketAddress acceptedFrom;
    public SocketAddress acceptedFrom(){
        return acceptedFrom;
    }

    public boolean connect(SocketAddress remote) throws IOException{
        boolean wasConnected = isConnected();
        boolean connected = realChannel().connect(remote);
        if(!wasConnected && connected){
            nioSelector.connectionPendingClients--;
            nioSelector.connectedClients++;
        }
        return connected;
    }

    public boolean finishConnect() throws IOException{
        boolean wasConnected = isConnected();
        boolean connected = realChannel().finishConnect();
        if(!wasConnected && connected){
            nioSelector.connectionPendingClients--;
            nioSelector.connectedClients++;
        }
        return connected;
    }

    public boolean isConnected(){
        return realChannel().isConnected();
    }

    public static final int OP_READ = SelectionKey.OP_READ;
    public static final int OP_WRITE = SelectionKey.OP_WRITE;
    public static final int OP_CONNECT = SelectionKey.OP_CONNECT;

    private Transport transport = new PlainTransport(this);

    private HandshakeCompletedListener handshakeCompletedListener;
    public void setHandshakeCompletedListener(HandshakeCompletedListener handshakeCompletedListener) {
        this.handshakeCompletedListener = handshakeCompletedListener;
    }

    public void enableSSL(SSLEngine engine) throws IOException{
        if(isConnected()){
            HandshakeCompletedListener listener = handshakeCompletedListener;
            listener = null;
            transport = new SSLTransport(transport, engine, listener);
        }else
            throw new ConnectionPendingException();
    }

    public void enableSSL(SSLParameters sslParameters) throws IOException{
        if(isConnected()){
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");

                TrustManager tm[];
                KeyStore trustStore = SSLUtil.newTrustStore();
                if(trustStore==null){
                    tm = new TrustManager[]{
                       new X509TrustManager() {
                           public X509Certificate[] getAcceptedIssuers(){
                               return new X509Certificate[0];
                           }
                           public void checkClientTrusted(X509Certificate[] certs, String authType ){}
                           public void checkServerTrusted(X509Certificate[] certs, String authType ){}
                       }
                    };
                }else{
                    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(SSLUtil.newTrustStore());
                    tm = tmf.getTrustManagers();
                }

                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(SSLUtil.newKeyStore(), SSLUtil.getKeyStorePassword());

                sslContext.init(kmf.getKeyManagers(), tm , null);
                SSLEngine engine = sslContext.createSSLEngine();
                engine.setUseClientMode(acceptedFrom()==null);
                if(sslParameters!=null)
                    engine.setSSLParameters(sslParameters);
                enableSSL(engine);
            }catch(IOException ex){
                throw ex;
            }catch(Exception ex){
                throw new IOException(ex);
            }
        }else
            throw new ConnectionPendingException();
    }

    public void enableSSL() throws IOException{
        enableSSL((SSLParameters)null);
    }

    public boolean sslEnabled(){
        return transport instanceof SSLTransport;
    }

    public int interests(){
        return transport.interests();
    }

    public void addInterest(int operation) throws IOException{
        transport.addInterest(operation);
    }

    public void removeInterest(int operation) throws IOException{
        transport.removeInterest(operation);
    }

    public int ready(){
        return transport.ready();
    }

    public boolean isConnectable(){
        return (ready() & OP_CONNECT)!=0;
    }

    public boolean isReadable(){
        return (ready() & OP_READ)!=0;
    }

    public boolean isWritable(){
        return (ready() & OP_WRITE)!=0;
    }

    protected long interestTime;
    public boolean isTimeout(){
        return nioSelector.socketTimeout>0 && interestTime<nioSelector.timeoutIterator.time;
    }

    @Override
    protected boolean process(){
        return transport.process();
    }

    private boolean eof;
    public boolean isEOF(){
        return eof;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException{
        int read = transport.read(dst);
        if(read==-1)
            eof = true;
        return read;
    }

    @Override
    public int write(ByteBuffer src) throws IOException{
        return transport.write(src);
    }

    public void shutdownOutput() throws IOException{
        transport.shutdownOutput();
    }

    public boolean isOutputShutdown(){
        return transport.isOutputShutdown();
    }

    @Override
    public boolean isOpen(){
        return transport.isOpen();
    }

    @Override
    public void close() throws IOException{
        transport.close();
    }

    protected ClientChannel prev, next;

    @Override
    public String toString(){
        return (sslEnabled() ? "SSLClientChannel@" : "ClientChannel@")+id;
    }
}
