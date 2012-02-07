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

package jlibs.wadl;

import jlibs.core.io.FileUtil;
import jlibs.core.io.IOUtil;
import jlibs.core.lang.Ansi;
import jlibs.core.lang.JavaProcessBuilder;
import jlibs.core.net.URLUtil;
import jlibs.core.util.RandomUtil;
import jlibs.wadl.model.*;
import jlibs.wadl.runtime.Path;
import jlibs.xml.sax.AnsiHandler;
import jlibs.xml.sax.XMLDocument;
import jlibs.xml.xsd.XSInstance;
import jlibs.xml.xsd.XSParser;
import org.apache.xerces.xs.XSModel;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;

import static jlibs.core.lang.Ansi.Attribute;
import static jlibs.core.lang.Ansi.Color;

/**
 * @author Santhosh Kumar T
 */
public class Command{
    private WADLTerminal terminal;

    public Command(WADLTerminal terminal){
        this.terminal = terminal;
    }

    public boolean run(String command) throws Exception{
        List<String> args = getArguments(command);

        String arg1 = args.get(0);
        if(arg1.equals("import"))
            importWADL(args.get(1));
        else if(arg1.equals("cd"))
            return cd(args.size()==1 ? null : args.get(1));
        else if(arg1.equals("set")){
            Properties vars = new Properties();
            for(String arg: args){
                int equals = arg.indexOf('=');
                if(equals!=-1){
                    String var = arg.substring(0, equals);
                    String value = arg.substring(equals+1);
                    vars.setProperty(var, value);
                }
            }
            Path path = terminal.getCurrentPath();
            while(path!=null){
                String var = path.variable();
                if(var!=null){
                    String value = vars.getProperty(var);
                    if(value!=null)
                        path.value = value;
                }
                path = path.parent;
            }
        }else if(arg1.equals("target"))
            terminal.getCurrentPath().getRoot().value = args.size()==1 ? null : args.get(1);
        else if(arg1.equals("server")){
            server(args.get(1));
        }else
            return send(args);
        return true;
    }

    private List<String> getArguments(String command){
        List<String> args = new ArrayList<String>();
        StringTokenizer stok = new StringTokenizer(command, " ");
        while(stok.hasMoreTokens())
            args.add(stok.nextToken());
        return args;
    }

    private boolean cd(String pathString){
        Path path = terminal.getCurrentPath();
        if(pathString==null)
            path = path.getRoot();
        else
            path = path.get(pathString);
        terminal.setCurrentPath(path);
        return true;
    }

    private void importWADL(String systemID) throws Exception{
        Application application = new WADLReader().read(systemID);

        XSModel schema = null;
        if(application.getGrammars()!=null){
            List<String> includes = new ArrayList<String>();
            for(Include include: application.getGrammars().getInclude()){
                if(include.getHref()!=null)
                    includes.add(URLUtil.resolve(systemID, include.getHref()).toString());
            }
            if(!includes.isEmpty())
                schema = new XSParser().parse(includes.toArray(new String[includes.size()]));
        }

        Path root = null;
        for(Resources resources: application.getResources()){
            URI base = URI.create(resources.getBase());
            String url = base.getScheme()+"://"+base.getHost();
            if(base.getPort()!=-1)
                url += ":"+base.getPort();
            root = null;
            for(Path path: terminal.getRoots()){
                if(path.name.equals(url)){
                    root = path;
                    break;
                }
            }
            if(root==null){
                root = new Path(null, url);
                terminal.getRoots().add(root);
                if(base.getPath()!=null && !base.getPath().isEmpty())
                    root = root.add(base.getPath());
            }
            root.schema = schema;
            for(Resource resource: resources.getResource())
                importResource(resource, root);
        }
        terminal.setCurrentPath(root);
    }

    private void importResource(Resource resource, Path path){
        path = path.add(resource.getPath());
        if(path.resource==null)
            path.resource = resource;
        else
            path.resource.getMethodOrResource().addAll(resource.getMethodOrResource());

        for(Object obj: resource.getMethodOrResource()){
            if(obj instanceof Resource)
                importResource((Resource)obj, path);
        }
    }

    private void server(String server){
        for(Path root: terminal.getRoots()){
            if(root.name.equalsIgnoreCase(server)){
                terminal.setCurrentPath(root);
                return;
            }
        }
    }

    private HttpURLConnection prepareSend(List<String> args) throws Exception{
        Path path = terminal.getCurrentPath();
        if(args.size()>1)
            path = path.get(args.get(1));
        if(path==null || path.resource==null){
            System.err.println("resource not found");
            return null;
        }

        Method method = null;
        for(Object obj: path.resource.getMethodOrResource()){
            if(obj instanceof Method){
                Method m = (Method)obj;
                if(m.getName().equalsIgnoreCase(args.get(0))){
                    method = m;
                    break;
                }
            }
        }
        if(method==null){
            System.err.println("unsupported method: "+args.get(0));
            return null;
        }

        Request request = method.getRequest();
        File payload = null;
        if(request!=null){
            if(!request.getRepresentation().isEmpty()){
                Representation rep = request.getRepresentation().get(RandomUtil.random(0, request.getRepresentation().size()-1));
                if(rep.getElement()!=null){
                    XSInstance xsInstance = new XSInstance();
                    payload = new File("temp.xml");
                    XMLDocument xml = new XMLDocument(new StreamResult(payload), true, 4, null);
                    xsInstance.generate(path.getSchema(), rep.getElement(), xml);
                }
            }
        }

        if(payload!=null){
            JavaProcessBuilder processBuilder = new JavaProcessBuilder();
            StringTokenizer stok = new StringTokenizer(System.getProperty("java.class.path"), FileUtil.PATH_SEPARATOR);
            while(stok.hasMoreTokens())
                processBuilder.classpath(stok.nextToken());
            processBuilder.mainClass(Editor.class.getName());
            processBuilder.arg(payload.getAbsolutePath());
            processBuilder.arg("text/xml");
            processBuilder.launch(DUMMY_OUTPUT, DUMMY_OUTPUT).waitFor();
            if(!payload.exists())
                return null;
        }

        return path.execute(method, new HashMap<String, List<String>>(), payload);
    }

    private static final Ansi SUCCESS = new Ansi(Attribute.BRIGHT, Color.GREEN, Color.BLACK);
    private static final Ansi FAILURE = new Ansi(Attribute.BRIGHT, Color.RED, Color.BLACK);

    private boolean send(List<String> args) throws Exception{
        HttpURLConnection con = prepareSend(args);
        if(con==null)
            return false;

        Ansi result = con.getResponseCode()/100==2 ? SUCCESS : FAILURE;
        result.outln(con.getResponseCode()+" "+con.getResponseMessage());
        System.out.println();

        boolean success = true;
        InputStream in = con.getErrorStream();
        if(in==null)
            in = con.getInputStream();
        else
            success = false;

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        IOUtil.pump(in, bout, true, true);
        if (bout.size() == 0)
            return success;
        if(isXML(con.getContentType())){
            PrintStream sysErr = System.err;
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
            try {
                TransformerFactory factory = TransformerFactory.newInstance();
                Transformer transformer = factory.newTransformer();
                transformer.transform(new StreamSource(new ByteArrayInputStream(bout.toByteArray())), new SAXResult(new AnsiHandler()));
                transformer.reset();
                return success;
            } catch (Exception ex) {
                // ignore
            } finally {
                System.setErr(sysErr);
            }
        }
        System.out.println(bout);
        System.out.println();
        return success;
    }

    public static boolean isXML(String contentType) {
        if(contentType==null)
            return false;
        int semicolon = contentType.indexOf(';');
        if(semicolon!=-1)
            contentType = contentType.substring(0, semicolon);
        if("text/xml".equalsIgnoreCase(contentType))
            return true;
        else if(contentType.startsWith("application/"))
            return contentType.endsWith("application/xml") || contentType.endsWith("+xml");
        else
            return false;
    }

    private static final OutputStream DUMMY_OUTPUT = new OutputStream(){
        @Override
        public void write(int b) throws IOException{}
        @Override
        public void write(byte[] b) throws IOException{}
        @Override
        public void write(byte[] b, int off, int len) throws IOException{}
    };
}