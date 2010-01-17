/*
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

package jlibs.xml.sax.binding.impl.processor;

import jlibs.core.annotation.processing.AnnotationError;
import jlibs.core.annotation.processing.Printer;
import jlibs.core.lang.BeanUtil;
import jlibs.core.lang.StringUtil;
import jlibs.core.lang.model.ModelUtil;
import jlibs.xml.sax.binding.SAXContext;
import org.xml.sax.Attributes;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Santhosh Kumar T
 */
abstract class BindingAnnotation{
    protected String methodDecl;
    protected Class annotation;

    BindingAnnotation(Class annotation, String methodDecl){
        this.annotation = annotation;
        this.methodDecl = methodDecl;
    }

    public void consume(Binding binding, ExecutableElement method, AnnotationMirror mirror){
        validate(method, mirror);
    }
    
    protected boolean matches(AnnotationMirror mirror){
        return ((TypeElement)mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation.getCanonicalName());
    }

    @SuppressWarnings({"UnusedDeclaration"})
    protected void validate(ExecutableElement method, AnnotationMirror mirror){
        validateModifiers(method);
    }

    public String lvalue(ExecutableElement method){
        return "";
    }

    public abstract String params(ExecutableElement method);

    protected void validateModifiers(ExecutableElement method){
        Collection<Modifier> modifiers = method.getModifiers();
        if(!modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.FINAL))
            throw new AnnotationError(method, "method with annotation "+annotation+" must be final");
    }

    protected boolean matches(ExecutableElement method, int paramIndex, Class expected){
        VariableElement param = method.getParameters().get(paramIndex);
        if(param.asType().getKind()== TypeKind.DECLARED){
            Name paramType = ((TypeElement)((DeclaredType)param.asType()).asElement()).getQualifiedName();
            if(paramType.contentEquals(expected.getName()))
                return true;
        }
        return false;
    }

    protected String context(ExecutableElement method, int paramIndex, String defaultArg){
        VariableElement param = method.getParameters().get(paramIndex);
        switch(param.asType().getKind()){
            case DECLARED:
                Name paramType = ((TypeElement)((DeclaredType)param.asType()).asElement()).getQualifiedName();
                if(paramType.contentEquals(SAXContext.class.getName()))
                    return defaultArg;
                else
                    return "("+paramType+")"+defaultArg+".object";
            case INT:
                return "(java.lang.Integer)"+defaultArg+".object";
            case BOOLEAN:
            case FLOAT:
            case DOUBLE:
            case LONG:
            case BYTE:
                return "(java.lang."+ BeanUtil.firstLetterToUpperCase(param.asType().getKind().toString().toLowerCase())+")"+defaultArg+".object";
            default:
                throw new AnnotationError(method, "method annotated with "+annotation.getCanonicalName()+" can't take "+param.asType().getKind()+" as argument");
        }
    }

    public void printMethod(Printer pw, Binding binding){
        List<ExecutableElement> methods = new ArrayList<ExecutableElement>();
        if(getMethods(binding, methods)){
            pw.println("@Override");
            pw.println(methodDecl);
            pw.indent++;

            pw.println("switch(state){");
            pw.indent++;
            int id = 0;
            for(ExecutableElement method : methods){
                if(method!=null){
                    pw.print("case "+id+":");

                    List<QName> path = binding.idMap.get(id);
                    if(path.size()>0)
                        pw.println(" // "+StringUtil.join(path.iterator(), "/"));
                    else
                        pw.println();

                    pw.indent++;
                    printCase(pw, method);
                    pw.println("break;");
                    pw.indent--;
                }
                id++;
            }
            pw.indent--;
            pw.println("}");

            pw.indent--;
            pw.println("}");
            pw.emptyLine(true);
        }
    }

    abstract boolean getMethods(Binding binding, List<ExecutableElement> methods);

    private void printCase(Printer pw, ExecutableElement method){
        pw.print(lvalue(method));
        if(method.getModifiers().contains(Modifier.STATIC))
            pw.println(pw.clazz.getSimpleName()+"."+method.getSimpleName()+"("+ params(method)+");");
        else
            pw.println("handler."+method.getSimpleName()+"("+ params(method)+");");
    }
    
}

class ElementAnnotation extends BindingAnnotation{
    ElementAnnotation(){
        super(jlibs.xml.sax.binding.Binding.Element.class, null);
    }

    @Override
    public void consume(Binding binding, ExecutableElement method, AnnotationMirror mirror){
        super.consume(binding, method, mirror);
        TypeElement bindingClazz = (TypeElement)((DeclaredType)ModelUtil.getAnnotationValue(method, mirror, "clazz")).asElement();
        if(ModelUtil.getAnnotationMirror(bindingClazz, jlibs.xml.sax.binding.Binding.class)==null)
            throw new AnnotationError(method, mirror, bindingClazz.getQualifiedName()+" should have annotation "+jlibs.xml.sax.binding.Binding.class.getCanonicalName());
        String element = ModelUtil.getAnnotationValue(method, mirror, "element");
        binding.getBinding(method, mirror, element).element = bindingClazz;
    }

    @Override
    public String params(ExecutableElement method){
        return null;
    }

    @Override
    boolean getMethods(Binding binding, List<ExecutableElement> methods){
        throw new UnsupportedOperationException();
    }
}

class BindingStartAnnotation extends BindingAnnotation{
    BindingStartAnnotation(){
        super(
            jlibs.xml.sax.binding.Binding.Start.class,
            "public void startElement(int state, SAXContext current, Attributes attributes) throws SAXException{"
        );
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public void consume(Binding binding, ExecutableElement method, AnnotationMirror mirror){
        super.consume(binding, method, mirror);
        for(AnnotationValue xpath: (Collection<AnnotationValue>)ModelUtil.getAnnotationValue(method, mirror, "value"))
            binding.getBinding(method, mirror, (String)xpath.getValue()).startMethod = method;
    }

    public String lvalue(ExecutableElement method){
        if(method.getReturnType().getKind()== TypeKind.VOID)
            return "";
        else
            return "current.object = ";
    }

    private String param(ExecutableElement method, int paramIndex){
        if(matches(method, paramIndex, Attributes.class))
            return "attributes";
        else
            return context(method, paramIndex, "current");
    }

    @Override
    public String params(ExecutableElement method){
        switch(method.getParameters().size()){
            case 0:
                return "";
            case 1:
                return param(method, 0);
            case 2:
                return param(method, 0)+", "+param(method, 1);
            default:
                throw new AnnotationError(method, "method annotated with "+annotation.getCanonicalName()+" must not take more than two arguments");
        }
    }

    @Override
    boolean getMethods(Binding binding, List<ExecutableElement> methods){
        boolean nonEmpty = binding.startMethod!=null;
        methods.add(binding.startMethod);
        for(BindingRelation bindingRelation: binding.registry.values())
            nonEmpty |= getMethods(bindingRelation.binding, methods);
        return nonEmpty;
    }
}

class BindingTextAnnotation extends BindingAnnotation{
    BindingTextAnnotation(){
        super(
            jlibs.xml.sax.binding.Binding.Text.class,
            "public void text(int state, SAXContext current, String text) throws SAXException{"
        );
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public void consume(Binding binding, ExecutableElement method, AnnotationMirror mirror){
        super.consume(binding, method, mirror);
        for(AnnotationValue xpath: (Collection<AnnotationValue>)ModelUtil.getAnnotationValue(method, mirror, "value"))
            binding.getBinding(method, mirror, (String)xpath.getValue()).textMethod = method;
    }

    public String lvalue(ExecutableElement method){
        if(method.getReturnType().getKind()== TypeKind.VOID)
            return "";
        else
            return "current.object = ";
    }

    @Override
    public String params(ExecutableElement method){
        if(method.getParameters().size()>0){
            if(!matches(method, method.getParameters().size()-1, String.class))
                throw new AnnotationError(method, "method annotated with "+annotation.getCanonicalName()+" must take String as last argument");
        }
        switch(method.getParameters().size()){
            case 1:
                return "text";
            case 2:
                return context(method, 0, "current")+", text";
            default:
                throw new AnnotationError(method, "method annotated with "+annotation.getCanonicalName()+" must take either one or two argument(s)");
        }
    }

    @Override
    boolean getMethods(Binding binding, List<ExecutableElement> methods){
        boolean nonEmpty = binding.textMethod!=null;
        methods.add(binding.textMethod);
        for(BindingRelation bindingRelation: binding.registry.values())
            nonEmpty |= getMethods(bindingRelation.binding, methods);
        return nonEmpty; 
    }
}

class BindingFinishAnnotation extends BindingAnnotation{
    BindingFinishAnnotation(){
        super(
            jlibs.xml.sax.binding.Binding.Finish.class,
            "public void endElement(int state, SAXContext current) throws SAXException{"
        );
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public void consume(Binding binding, ExecutableElement method, AnnotationMirror mirror){
        super.consume(binding, method, mirror);
        for(AnnotationValue xpath: (Collection<AnnotationValue>)ModelUtil.getAnnotationValue(method, mirror, "value"))
            binding.getBinding(method, mirror, (String)xpath.getValue()).finishMethod = method;
    }

    public String lvalue(ExecutableElement method){
        if(method.getReturnType().getKind()== TypeKind.VOID)
            return "";
        else
            return "current.object = ";
    }

    @Override
    public String params(ExecutableElement method){
        switch(method.getParameters().size()){
            case 0:
                return "";
            case 1:
                return context(method, 0, "current");
            default:
                throw new AnnotationError(method, "method annotated with "+annotation.getCanonicalName()+" must not take more than one argument");
        }
    }

    @Override
    boolean getMethods(Binding binding, List<ExecutableElement> methods){
        boolean nonEmpty = binding.finishMethod!=null;
        methods.add(binding.finishMethod);
        for(BindingRelation bindingRelation: binding.registry.values())
            nonEmpty |= getMethods(bindingRelation.binding, methods);
        return nonEmpty;
    }
}

class RelationAnnotation extends BindingAnnotation{
    boolean start;
    RelationAnnotation(boolean start){
        super(
            start ? jlibs.xml.sax.binding.Relation.Start.class : jlibs.xml.sax.binding.Relation.Finish.class,
            "public void "+(start ? "start" : "end")+"Relation(int state, SAXContext parent, SAXContext current) throws SAXException{"
        );
        this.start = start;
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public void consume(Binding binding, ExecutableElement method, AnnotationMirror mirror){
        super.consume(binding, method, mirror);
        for(AnnotationValue child: (Collection<AnnotationValue>)ModelUtil.getAnnotationValue(method, mirror, "value")){
            String xpath = (String)child.getValue();
            Binding parentBinding;
            int slash = xpath.lastIndexOf('/');
            if(slash==-1){
                parentBinding = binding;
            }else{
                parentBinding = binding.getBinding(method, mirror, xpath.substring(0, slash));
                xpath = xpath.substring(slash+1);
            }
            Relation childRelation = parentBinding.getRelation(method, mirror, xpath);

            if(start)
                childRelation.startedMethod = method;
            else
                childRelation.finishedMethod = method;
        }
    }

    @Override
    public String params(ExecutableElement method){
        switch(method.getParameters().size()){
            case 2:
                return context(method, 0, "parent")+", "+ context(method, 1, "current");
            default:
                throw new AnnotationError(method, "method annotated with "+annotation.getCanonicalName()+" must take exactly two arguments");
        }
    }
    @Override
    boolean getMethods(Binding binding, List<ExecutableElement> methods){
        methods.add(null);
        return _getMethods(binding, methods);
    }

    private boolean _getMethods(Binding binding, List<ExecutableElement> methods){
        boolean nonEmpty = false;
        for(BindingRelation bindingRelation: binding.registry.values()){
            ExecutableElement method = start ? bindingRelation.relation.startedMethod : bindingRelation.relation.finishedMethod;
            nonEmpty |= method!=null;
            methods.add(method);
            nonEmpty |= _getMethods(bindingRelation.binding, methods);
        }
        return nonEmpty;
    }
}