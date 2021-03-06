/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.rendering.xml.internal.serializer;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import javax.inject.Singleton;

import org.apache.commons.lang3.ObjectUtils;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.util.ReflectionUtils;
import org.xwiki.properties.ConverterManager;
import org.xwiki.rendering.listener.descriptor.ListenerDescriptor;
import org.xwiki.rendering.listener.descriptor.ListenerElement;
import org.xwiki.rendering.xml.internal.XMLConfiguration;
import org.xwiki.rendering.xml.internal.XMLUtils;
import org.xwiki.rendering.xml.internal.parameter.ParameterManager;

/**
 * Proxy called as an event lister to produce SAX events.
 * 
 * @version $Id$
 * @since 5.0M1
 */
@Component
@Singleton
public class DefaultXMLSerializer implements InvocationHandler
{
    private static final Pattern VALID_ELEMENTNAME = Pattern.compile("[A-Za-z][A-Za-z0-9:_.-]*");

    private ContentHandler contentHandler;

    private ParameterManager parameterManager;

    private ListenerDescriptor descriptor;

    private ConverterManager converter;

    private XMLConfiguration configuration;

    public DefaultXMLSerializer(ContentHandler contentHandler, ParameterManager parameterManager,
        ListenerDescriptor descriptor, ConverterManager converter, XMLConfiguration configuration)
    {
        this.contentHandler = contentHandler;
        this.parameterManager = parameterManager;
        this.descriptor = descriptor;
        this.converter = converter;
        this.configuration = configuration != null ? configuration : new XMLConfiguration();
    }

    private boolean isValidBlockElementName(String blockName)
    {
        return VALID_ELEMENTNAME.matcher(blockName).matches()
            && !this.configuration.getElementParameter().equals(blockName);
    }

    private String getBlockName(String eventName, String prefix)
    {
        String blockName = eventName.substring(prefix.length());
        blockName = Character.toLowerCase(blockName.charAt(0)) + blockName.substring(1);

        return blockName;
    }

    private void addInlineParameters(AttributesImpl attributes, List<Object> parameters, ListenerElement element)
    {
        for (int i = 0; i < parameters.size(); ++i) {
            Object parameter = parameters.get(i);

            if (parameter != null) {
                Type type = element.getParameters().get(i);
                Class< ? > typeClass = ReflectionUtils.getTypeClass(type);

                if (XMLUtils.isSimpleType(typeClass)) {
                    attributes.addAttribute(null, null, this.configuration.getElementParameter() + i, null,
                        this.converter.<String> convert(String.class, parameter));

                    parameters.set(i, null);
                } else if (ObjectUtils.equals(XMLUtils.defaultValue(typeClass), parameter)) {
                    attributes.addAttribute(null, null, this.configuration.getElementParameter() + i, null, "");

                    parameters.set(i, null);
                }
            }
        }
    }

    private AttributesImpl createStartAttributes(String blockName, List<Object> parameters)
    {
        AttributesImpl attributes = new AttributesImpl();

        if (!isValidBlockElementName(blockName)) {
            attributes.addAttribute(null, null, this.configuration.getAttributeBlockName(), null, blockName);
        }

        if (parameters != null) {
            ListenerElement element = this.descriptor.getElements().get(blockName.toLowerCase());

            addInlineParameters(attributes, parameters, element);
        }

        return attributes;
    }

    private void removeDefaultParameters(List<Object> parameters, ListenerElement descriptor)
    {
        if (parameters != null) {
            for (int i = 0; i < parameters.size(); ++i) {
                Object value = parameters.get(i);

                if (value != null && !shouldPrintParameter(value, i, descriptor)) {
                    parameters.set(i, null);
                }
            }
        }
    }

    private void beginEvent(String eventName, Object[] parameters)
    {
        String blockName = getBlockName(eventName, "begin");

        ListenerElement element = this.descriptor.getElements().get(blockName.toLowerCase());

        List<Object> elementParameters = parameters != null ? Arrays.asList(parameters) : null;

        // Remove useless parameters
        removeDefaultParameters(elementParameters, element);

        // Put as attributes parameters which are simple enough to not require full XML serialization
        AttributesImpl attributes = createStartAttributes(blockName, elementParameters);

        // Get proper element name
        String elementName;
        if (isValidBlockElementName(blockName)) {
            elementName = blockName;
        } else {
            elementName = this.configuration.getElementBlock();
        }

        // Print start element
        startElement(elementName, attributes);

        // Print complex parameters
        printParameters(elementParameters, element);
    }

    private void endEvent(String eventName)
    {
        String blockName = getBlockName(eventName, "end");

        if (isValidBlockElementName(blockName)) {
            endElement(blockName);
        } else {
            endElement(this.configuration.getElementBlock());
        }
    }

    private void onEvent(String eventName, Object[] parameters)
    {
        String blockName = getBlockName(eventName, "on");

        ListenerElement element = this.descriptor.getElements().get(blockName.toLowerCase());

        List<Object> elementParameters = parameters != null ? Arrays.asList(parameters) : null;

        // Remove useless parameters
        removeDefaultParameters(elementParameters, element);

        // Put as attributes parameters which are simple enough to not require full XML serialization
        AttributesImpl attributes =
            (elementParameters != null && elementParameters.size() > 1) ? createStartAttributes(blockName,
                Arrays.asList(parameters)) : new AttributesImpl();

        // Get proper element name
        String elementName;
        if (isValidBlockElementName(blockName)) {
            elementName = blockName;
        } else {
            elementName = this.configuration.getElementBlock();
        }

        // Print start element
        startElement(elementName, attributes);

        // Print complex parameters
        if (parameters != null && parameters.length == 1 && XMLUtils.isSimpleType(element.getParameters().get(0))) {
            String value = parameters[0].toString();
            try {
                this.contentHandler.characters(value.toCharArray(), 0, value.length());
            } catch (SAXException e) {
                throw new RuntimeException("Failed to send sax event", e);
            }
        } else {
            printParameters(elementParameters, element);
        }

        // Print end element
        endElement(elementName);
    }

    private boolean shouldPrintParameter(Object value, int index, ListenerElement descriptor)
    {
        boolean print = true;

        Type type = descriptor.getParameters().get(index);

        if (type instanceof Class) {
            Class< ? > typeClass = (Class< ? >) type;
            try {
                if (typeClass.isPrimitive()) {
                    print = !XMLUtils.defaultValue(typeClass).equals(value);
                }
            } catch (Exception e) {
                // Should never happen
            }
        }

        return print;
    }

    private void printParameters(List<Object> parameters, ListenerElement descriptor)
    {
        if (parameters != null && !parameters.isEmpty()) {
            for (int i = 0; i < parameters.size(); ++i) {
                Object value = parameters.get(i);

                if (value != null && shouldPrintParameter(value, i, descriptor)) {
                    startElement(this.configuration.getElementParameter() + i);

                    this.parameterManager.serialize(descriptor.getParameters().get(i), value, this.contentHandler);

                    endElement(this.configuration.getElementParameter() + i);
                }
            }
        }
    }

    private void startElement(String elemntName)
    {
        startElement(elemntName, (String[][]) null);
    }

    private void startElement(String elemntName, String[][] parameters)
    {
        startElement(elemntName, createAttributes(parameters));
    }

    private void startElement(String elemntName, Attributes attributes)
    {
        try {
            this.contentHandler.startElement("", elemntName, elemntName, attributes);
        } catch (SAXException e) {
            throw new RuntimeException("Failed to send sax event", e);
        }
    }

    private void endElement(String elemntName)
    {
        try {
            this.contentHandler.endElement("", elemntName, elemntName);
        } catch (SAXException e) {
            throw new RuntimeException("Failed to send sax event", e);
        }
    }

    /**
     * Convert provided table into {@link Attributes} to use in xml writer.
     */
    private Attributes createAttributes(String[][] parameters)
    {
        AttributesImpl attributes = new AttributesImpl();

        if (parameters != null && parameters.length > 0) {
            for (String[] entry : parameters) {
                attributes.addAttribute(null, null, entry[0], null, entry[1]);
            }
        }

        return attributes;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
    {
        Object result = null;

        if (method.getName().equals("setContentHandler")) {
            this.contentHandler = (ContentHandler) args[0];
        } else if (method.getName().equals("getContentHandler")) {
            result = this.contentHandler;
        } else if (method.getName().startsWith("begin")) {
            beginEvent(method.getName(), args);
        } else if (method.getName().startsWith("end")) {
            endEvent(method.getName());
        } else if (method.getName().startsWith("on")) {
            onEvent(method.getName(), args);
        } else {
            throw new NoSuchMethodException(method.toGenericString());
        }

        return result;
    }
}
