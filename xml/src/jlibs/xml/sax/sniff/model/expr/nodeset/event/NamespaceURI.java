/**
 * JLibs: Common Utilities for Java
 * Copyright (C) 2009  Santhosh Kumar T
 * <p/>
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <p/>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 */

package jlibs.xml.sax.sniff.model.expr.nodeset.event;

import jlibs.xml.sax.sniff.events.Attribute;
import jlibs.xml.sax.sniff.events.Element;
import jlibs.xml.sax.sniff.events.Event;
import jlibs.xml.sax.sniff.model.Node;
import jlibs.xml.sax.sniff.model.UserResults;
import jlibs.xml.sax.sniff.model.expr.Expression;

/**
 * @author Santhosh Kumar T
 */
public class NamespaceURI extends EventData{
    public NamespaceURI(Node contextNode, UserResults member, Expression predicate){
        super(contextNode, member, predicate);
    }

    @Override
    protected String getData(Event event){
        switch(event.type()){
            case Event.ELEMENT:
                return ((Element)event).uri;
            case Event.ATTRIBUTE:
                return ((Attribute)event).uri;
            case Event.PI:
                return "";
        }
        return null;
    }
}