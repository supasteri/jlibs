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

package jlibs.wamp4j.router;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jlibs.wamp4j.Debugger;
import jlibs.wamp4j.WAMPSerialization;
import jlibs.wamp4j.spi.AcceptListener;
import jlibs.wamp4j.spi.WebSocket;
import jlibs.wamp4j.spi.WebSocketServer;

import java.net.URI;

import static jlibs.wamp4j.Debugger.ROUTER;
import static jlibs.wamp4j.Util.serialization;
import static jlibs.wamp4j.Util.subProtocols;

/**
 * todo: ssl
 * @author Santhosh Kumar Tekuri
 */
public class WAMPRouter{
    protected final ArrayNode array = JsonNodeFactory.instance.arrayNode();

    private final WebSocketServer server;
    private final URI uri;
    private final WAMPSerialization serializations[];
    protected final Realms realms = new Realms();

    public WAMPRouter(WebSocketServer server, URI uri, WAMPSerialization... serializations){
        this.server = server;
        this.uri = uri;
        this.serializations = serializations;
    }

    public WAMPRouter(WebSocketServer server, URI uri){
        this(server, uri, WAMPSerialization.values());
    }

    protected RouterListener listener;
    public void bind(final RouterListener listener){
        if(ROUTER)
            Debugger.println(this, "-- bind %s", uri);
        this.listener = listener;
        server.bind(uri, subProtocols(serializations), new AcceptListener(){
            @Override
            public void onBind(WebSocketServer server){
                if(ROUTER)
                    Debugger.println(WAMPRouter.this, "-- bound %s", uri);
                listener.onBind(WAMPRouter.this);
            }

            @Override
            public void onAccept(WebSocket webSocket){
                if(ROUTER)
                    Debugger.println(WAMPRouter.this, "-- accept");
                WAMPSerialization serialization = serialization(webSocket, serializations);
                webSocket.setListener(new Session(WAMPRouter.this, webSocket, serialization));
            }

            @Override
            public void onError(Throwable error){
                listener.onError(WAMPRouter.this, error);
            }

            @Override
            public void onClose(WebSocketServer server){
                listener.onClose(WAMPRouter.this);
            }
        });
    }

    public void close(){
        if(server.isEventLoop()){
            if(ROUTER)
                Debugger.println(this, "-- close");
            realms.close();
            if(ROUTER)
                Debugger.println(this, "-- disconnect");
            server.close();
        }else{
            server.submit(new Runnable(){
                @Override
                public void run(){
                    close();
                }
            });
        }
    }

    @Override
    public String toString(){
        return getClass().getSimpleName();
    }

    /*-------------------------------------------------[ Flush Chain ]---------------------------------------------------*/

    protected Session flushHead;
    protected Session flushTail;

    protected void addToFlushList(Session session){
        assert !session.flushNeeded;
        session.flushNeeded = true;
        if(flushHead==null)
            flushHead = session;
        else
            flushTail.flushNext = session;
        flushTail = session;
    }

    protected Session removeFromFlushList(){
        if(flushHead==null)
            return null;
        Session removed = flushHead;
        assert removed.flushNeeded;
        flushHead = removed.flushNext;
        if(flushHead==null)
            flushTail = null;
        removed.flushNext = null;
        removed.flushNeeded = false;
        return removed;
    }

    protected void removeFromFlushList(Session session){
        assert session.flushNeeded;
        session.flushNeeded = false;
        if(flushHead==session){
            flushHead = session.flushNext;
            if(flushHead==null)
                flushTail = null;
        }else{
            Session prev = flushHead;
            while(prev.flushNext!=session)
                prev = prev.flushNext;
            prev.flushNext = session.flushNext;
            if(flushTail==session)
                flushTail = prev;
        }
    }
}
