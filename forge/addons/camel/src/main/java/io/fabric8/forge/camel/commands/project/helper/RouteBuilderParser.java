/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.forge.camel.commands.project.helper;

import java.util.List;

import io.fabric8.forge.camel.commands.project.model.CamelEndpointDetails;
import org.jboss.forge.roaster.model.Annotation;
import org.jboss.forge.roaster.model.source.FieldSource;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.jboss.forge.roaster.model.source.MethodSource;

import static io.fabric8.forge.camel.commands.project.helper.CamelCatalogHelper.endpointComponentName;

public class RouteBuilderParser {

    public static void parseRouteBuilder(JavaClassSource clazz, String baseDir, String fullyQualifiedFileName,
                                         List<CamelEndpointDetails> endpoints) {
        // must be a route builder class (or from spring-boot)
        // TODO: we should look up the type hierachy if possible
        String superType = clazz.getSuperType();
        if (superType != null) {
            boolean valid = "org.apache.camel.builder.RouteBuilder".equals(superType)
                    || "org.apache.camel.spring.boot.FatJarRouter".equals(superType);
            if (!valid) {
                return;
            }
        }

        // look for fields which are not used in the route
        for (FieldSource<JavaClassSource> field : clazz.getFields()) {

            // is the field annotated with a Camel endpoint
            String uri = null;
            for (Annotation ann : field.getAnnotations()) {
                if ("org.apache.camel.EndpointInject".equals(ann.getQualifiedName())) {
                    uri = ann.getStringValue();
                } else if ("org.apache.camel.cdi.Uri".equals(ann.getQualifiedName())) {
                    uri = ann.getStringValue();
                }
            }

            // we only want to add fields which are not used in the route
            if (uri != null && findEndpointByUri(endpoints, uri) == null) {

                // we only want the relative dir name from the
                String fileName = fullyQualifiedFileName;
                if (fileName.startsWith(baseDir)) {
                    fileName = fileName.substring(baseDir.length() + 1);
                }
                String id = field.getName();

                CamelEndpointDetails detail = new CamelEndpointDetails();
                detail.setFileName(fileName);
                detail.setEndpointInstance(id);
                detail.setEndpointUri(uri);
                detail.setEndpointComponentName(endpointComponentName(uri));
                // we do not know if this field is used as consumer or producer only, but we try
                // to find out by scanning the route in the configure method below
                endpoints.add(detail);
            }
        }

        // look if any of these fields are used in the route only as consumer or producer, as then we can
        // determine this to ensure when we edit the endpoint we should only the options accordingly
        MethodSource<JavaClassSource> method = CamelJavaParserHelper.findConfigureMethod(clazz);
        if (method != null) {
            // consumers only
            List<String> uris = CamelJavaParserHelper.parseCamelConsumerUris(method, false, true);
            for (String uri : uris) {
                CamelEndpointDetails detail = findEndpointByUri(endpoints, uri);
                if (detail != null) {
                    // its a consumer only
                    detail.setConsumerOnly(true);
                }
            }
            // producer only
            uris = CamelJavaParserHelper.parseCamelProducerUris(method, false, true);
            for (String uri : uris) {
                CamelEndpointDetails detail = findEndpointByUri(endpoints, uri);
                if (detail != null) {
                    if (detail.isConsumerOnly()) {
                        // its both a consumer and producer
                        detail.setConsumerOnly(false);
                        detail.setProducerOnly(false);
                    } else {
                        // its a producer only
                        detail.setProducerOnly(true);
                    }
                }
            }

            // look for endpoints in the configure method that are string based
            // consumers only
            uris = CamelJavaParserHelper.parseCamelConsumerUris(method, true, false);
            for (String uri : uris) {
                String fileName = fullyQualifiedFileName;
                if (fileName.startsWith(baseDir)) {
                    fileName = fileName.substring(baseDir.length() + 1);
                }

                CamelEndpointDetails detail = new CamelEndpointDetails();
                detail.setFileName(fileName);
                detail.setEndpointInstance(null);
                detail.setEndpointUri(uri);
                detail.setEndpointComponentName(endpointComponentName(uri));
                detail.setConsumerOnly(true);
                detail.setProducerOnly(false);
                endpoints.add(detail);
            }
            uris = CamelJavaParserHelper.parseCamelProducerUris(method, true, false);
            for (String uri : uris) {
                // the same uri may already have been used as consumer as well
                CamelEndpointDetails detail = findEndpointByUri(endpoints, uri);
                if (detail == null) {
                    // its a producer only uri
                    String fileName = fullyQualifiedFileName;
                    if (fileName.startsWith(baseDir)) {
                        fileName = fileName.substring(baseDir.length() + 1);
                    }

                    detail = new CamelEndpointDetails();
                    detail.setFileName(fileName);
                    detail.setEndpointInstance(null);
                    detail.setEndpointUri(uri);
                    detail.setEndpointComponentName(endpointComponentName(uri));
                    detail.setConsumerOnly(false);
                    detail.setProducerOnly(true);

                    endpoints.add(detail);
                } else {
                    // we already have this uri as a consumer, then mark it as both consumer+producer
                    detail.setConsumerOnly(false);
                    detail.setProducerOnly(false);
                }
            }
        }
    }

    private static CamelEndpointDetails findEndpointByUri(List<CamelEndpointDetails> endpoints, String uri) {
        for (CamelEndpointDetails detail : endpoints) {
            if (uri.equals(detail.getEndpointUri())) {
                return detail;
            }
        }
        return null;
    }
}