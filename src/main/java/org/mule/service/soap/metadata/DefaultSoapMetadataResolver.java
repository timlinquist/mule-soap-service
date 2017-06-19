/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.service.soap.metadata;

import org.mule.metadata.api.TypeLoader;
import org.mule.runtime.api.metadata.MetadataResolvingException;
import org.mule.runtime.soap.api.client.metadata.SoapMetadataResolver;
import org.mule.runtime.soap.api.client.metadata.SoapOperationMetadata;
import org.mule.service.soap.introspection.WsdlDefinition;

import java.util.Set;

/**
 * Default immutable {@link SoapMetadataResolver} implementation.
 *
 * @since 1.0
 */
public class DefaultSoapMetadataResolver implements SoapMetadataResolver {

  private static final TypeIntrospecterDelegate inputDelegate = new InputTypeIntrospecterDelegate();
  private static final TypeIntrospecterDelegate outputDelegate = new OutputTypeIntrospecterDelegate();

  private final HeadersMetadataResolver headersResolver;
  private final BodyMetadataResolver bodyResolver;
  private final AttachmentsMetadataResolver attachmentsResolver;
  private final ServiceOperationsResolver keysResolver;

  public DefaultSoapMetadataResolver(WsdlDefinition introspecter, TypeLoader loader) {
    bodyResolver = new BodyMetadataResolver(introspecter, loader);
    headersResolver = new HeadersMetadataResolver(introspecter, loader);
    attachmentsResolver = new AttachmentsMetadataResolver(introspecter, loader);
    keysResolver = new ServiceOperationsResolver(introspecter);
  }

  @Override
  public SoapOperationMetadata getInputMetadata(String operation) throws MetadataResolvingException {
    return new ImmutableSoapOperationMetadata(bodyResolver.getMetadata(operation, inputDelegate),
                                              headersResolver.getMetadata(operation, inputDelegate),
                                              attachmentsResolver.getMetadata(operation, inputDelegate));
  }

  @Override
  public SoapOperationMetadata getOutputMetadata(String operation) throws MetadataResolvingException {
    return new ImmutableSoapOperationMetadata(bodyResolver.getMetadata(operation, outputDelegate),
                                              headersResolver.getMetadata(operation, outputDelegate),
                                              attachmentsResolver.getMetadata(operation, outputDelegate));
  }

  @Override
  public Set<String> getAvailableOperations() {
    return keysResolver.getAvailableOperations();
  }
}
