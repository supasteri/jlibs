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

package jlibs.wamp4j.spi;

import java.io.OutputStream;

/**
 * @author Santhosh Kumar Tekuri
 */
public interface WebSocket{
    public String subProtocol();
    public void setListener(Listener listener);

    public OutputStream createOutputStream();
    public void release(OutputStream out);

    public boolean isWritable();
    public void send(MessageType type, OutputStream out);
    public void flush();

    public boolean isOpen();
    public void close();
    public void kill();
}
