/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.service.soap.client;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang.builder.ToStringBuilder.reflectionToString;
import static org.mule.runtime.core.api.util.IOUtils.toDataHandler;
import static org.mule.runtime.soap.api.SoapVersion.SOAP12;
import static org.mule.service.soap.util.XmlTransformationUtils.stringToDomElement;

import org.mule.metadata.xml.XmlTypeLoader;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.extension.api.soap.SoapAttachment;
import org.mule.runtime.extension.api.soap.message.MessageDispatcher;
import org.mule.runtime.soap.api.SoapVersion;
import org.mule.runtime.soap.api.client.SoapClient;
import org.mule.runtime.soap.api.client.metadata.SoapMetadataResolver;
import org.mule.runtime.soap.api.exception.BadRequestException;
import org.mule.runtime.soap.api.exception.DispatchingException;
import org.mule.runtime.soap.api.exception.SoapFaultException;
import org.mule.runtime.soap.api.exception.SoapServiceException;
import org.mule.runtime.soap.api.message.SoapRequest;
import org.mule.runtime.soap.api.message.SoapResponse;
import org.mule.service.soap.generator.SoapRequestGenerator;
import org.mule.service.soap.generator.SoapResponseGenerator;
import org.mule.service.soap.generator.attachment.AttachmentRequestEnricher;
import org.mule.service.soap.generator.attachment.AttachmentResponseEnricher;
import org.mule.service.soap.generator.attachment.MtomRequestEnricher;
import org.mule.service.soap.generator.attachment.MtomResponseEnricher;
import org.mule.service.soap.generator.attachment.SoapAttachmentRequestEnricher;
import org.mule.service.soap.generator.attachment.SoapAttachmentResponseEnricher;
import org.mule.service.soap.introspection.WsdlIntrospecter;
import org.mule.service.soap.metadata.DefaultSoapMetadataResolver;
import org.mule.service.soap.util.XmlTransformationException;
import org.mule.service.soap.util.XmlTransformationUtils;

import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.io.IOUtils;
import org.apache.cxf.attachment.AttachmentImpl;
import org.apache.cxf.binding.soap.SoapFault;
import org.apache.cxf.binding.soap.SoapHeader;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.message.Attachment;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.ExchangeImpl;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * a {@link SoapClient} implementation based on CXF.
 *
 * @since 1.0
 */
public class SoapCxfClient implements SoapClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(SoapCxfClient.class);

  public static final String WSC_DISPATCHER = "mule.wsc.dispatcher";
  public static final String MULE_ATTACHMENTS_KEY = "mule.wsc.attachments";
  public static final String MULE_WSC_ADDRESS = "mule.wsc.address";
  public static final String MULE_HEADERS_KEY = "mule.wsc.headers";
  public static final String MULE_TRANSPORT_HEADERS_KEY = "mule.wsc.transport.headers";
  public static final String MULE_SOAP_ACTION = "mule.wsc.soap.action";
  public static final String MULE_WSC_ENCODING = "mule.wsc.encoding";

  private final SoapRequestGenerator requestGenerator;
  private final SoapResponseGenerator responseGenerator;

  private final Client client;
  private final WsdlIntrospecter introspecter;
  private final XmlTypeLoader loader;
  private final String address;
  private final MessageDispatcher dispatcher;
  private final SoapVersion version;
  private final String encoding;
  private final boolean isMtom;

  SoapCxfClient(Client client,
                WsdlIntrospecter introspecter,
                XmlTypeLoader typeLoader,
                String address,
                MessageDispatcher dispatcher,
                SoapVersion version,
                String encoding,
                boolean isMtom) {
    this.client = client;
    this.introspecter = introspecter;
    this.loader = typeLoader;
    this.address = address;
    this.dispatcher = dispatcher;
    this.version = version;
    this.isMtom = isMtom;
    this.encoding = encoding;
    // TODO: MULE-10889 -> instead of creating this enrichers, interceptors that works with the live stream would be ideal
    this.requestGenerator = new SoapRequestGenerator(getRequestEnricher(isMtom), introspecter, loader);
    this.responseGenerator = new SoapResponseGenerator(getResponseEnricher(isMtom));
  }

  @Override
  public void stop() throws MuleException {
    dispatcher.dispose();
    client.destroy();
  }

  @Override
  public void start() throws MuleException {
    dispatcher.initialise();
  }

  /**
   * Consumes an operation from a SOAP Web Service.
   */
  @Override
  public SoapResponse consume(SoapRequest request) throws SoapFaultException {
    Map<String, SoapAttachment> attachments = request.getAttachments();
    String operation = request.getOperation();
    XMLStreamReader envelope;
    try {
      String xml = request.getContent() != null ? IOUtils.toString(request.getContent()) : null;
      envelope = requestGenerator.generate(operation, xml, attachments);
    } catch (IOException e) {
      throw new BadRequestException("an error occurred while parsing the provided request");
    }
    Exchange exchange = new ExchangeImpl();
    Object[] response =
        invoke(operation, envelope, request.getSoapHeaders(), request.getTransportHeaders(), attachments, exchange);
    return responseGenerator.generate(operation, response, exchange);
  }

  @Override
  public SoapMetadataResolver getMetadataResolver() {
    return new DefaultSoapMetadataResolver(introspecter, loader);
  }


  /**
   * Invokes a Web Service Operation with the specified parameters.
   *
   * @param operation        the operation that is going to be invoked.
   * @param payload          the request body to be bounded in the envelope.
   * @param headers          the request headers to be bounded in the envelope.
   * @param transportHeaders the headers to be bounded with the underlying transport request.
   * @param attachments      the set of attachments that aims to be sent with the request.
   * @param exchange         the exchange instance that will carry all the parameters when intercepting the message.
   */
  Object[] invoke(String operation,
                  Object payload,
                  Map<String, String> headers,
                  Map<String, String> transportHeaders,
                  Map<String, SoapAttachment> attachments,
                  Exchange exchange) {
    try {
      BindingOperationInfo bop = getInvocationOperation();
      Map<String, Attachment> soapAttachments = transformToCxfAttachments(attachments);
      List<SoapHeader> soapHeaders = transformToCxfHeaders(headers);
      Map<String, Object> ctx = getInvocationContext(operation, soapHeaders, transportHeaders, soapAttachments);
      return client.invoke(bop, new Object[] {payload}, ctx, exchange);
    } catch (SoapFault sf) {
      throw new SoapFaultException(sf.getFaultCode(), sf.getSubCode(), parseExceptionDetail(sf.getDetail()).orElse(null),
                                   sf.getReason(),
                                   sf.getNode(), sf.getRole(),
                                   sf);
    } catch (Fault f) {
      if (f.getMessage().contains("COULD_NOT_READ_XML")) {
        throw new BadRequestException(
                                      format("Error consuming the operation [%s], the request body is not a valid XML",
                                             operation));
      }
      throw new SoapFaultException(f.getFaultCode(), parseExceptionDetail(f.getDetail()).orElse(null), f);
    } catch (DispatchingException e) {
      throw e;
    } catch (Exception e) {
      throw new SoapServiceException(format("An unexpected error occur while consuming the [%s] web service operation",
                                            operation),
                                     e);
    }
  }

  private BindingOperationInfo getInvocationOperation() throws Exception {
    // Normally its not this hard to invoke the CXF Client, but we're
    // sending along some exchange properties, so we need to use a more advanced
    // method
    Endpoint ep = client.getEndpoint();
    // The operation is always named invoke because hits our ProxyService implementation.
    QName q = new QName(ep.getService().getName().getNamespaceURI(), "invoke");
    BindingOperationInfo bop = ep.getBinding().getBindingInfo().getOperation(q);
    if (bop.isUnwrappedCapable()) {
      bop = bop.getUnwrappedOperation();
    }
    return bop;
  }

  private Map<String, Object> getInvocationContext(String operation,
                                                   List<SoapHeader> headers,
                                                   Map<String, String> transportHeaders,
                                                   Map<String, Attachment> attachments) {
    Map<String, Object> props = new HashMap<>();

    if (isMtom) {
      props.put(MULE_ATTACHMENTS_KEY, attachments);
    } else {
      // is NOT mtom the attachments must not be touched by cxf, we create a custom request embedding the attachment in the xml
      props.put(MULE_ATTACHMENTS_KEY, emptyMap());
    }

    props.put(MULE_WSC_ADDRESS, address);
    props.put(MULE_WSC_ENCODING, encoding);
    props.put(MULE_HEADERS_KEY, headers);

    if (version == SOAP12) {
      props.put(MULE_SOAP_ACTION, operation);
    }

    props.put(MULE_TRANSPORT_HEADERS_KEY, transportHeaders != null ? transportHeaders : emptyMap());

    props.put(WSC_DISPATCHER, dispatcher);

    Map<String, Object> ctx = new HashMap<>();
    ctx.put(Client.REQUEST_CONTEXT, props);
    return ctx;
  }

  private List<SoapHeader> transformToCxfHeaders(Map<String, String> headers) {
    if (headers == null) {
      return emptyList();
    }
    return headers.entrySet().stream()
        .map(header -> {
          try {
            return new SoapHeader(new QName(null, header.getKey()), stringToDomElement(header.getValue()));
          } catch (Exception e) {
            throw new BadRequestException("Cannot parse input header [" + header.getKey() + "]", e);
          }
        })
        .collect(toList());
  }

  private Map<String, Attachment> transformToCxfAttachments(Map<String, SoapAttachment> attachments) {
    ImmutableMap.Builder<String, Attachment> builder = ImmutableMap.builder();
    attachments.forEach((name, value) -> {
      try {
        builder.put(name, new AttachmentImpl(name, toDataHandler(name, value.getContent(), value.getContentType())));
      } catch (IOException e) {
        throw new BadRequestException(format("Error while preparing attachment [%s] for upload", name), e);
      }
    });
    return builder.build();
  }

  private AttachmentRequestEnricher getRequestEnricher(boolean isMtom) {
    return isMtom ? new MtomRequestEnricher(introspecter, loader) : new SoapAttachmentRequestEnricher(introspecter, loader);
  }

  private AttachmentResponseEnricher getResponseEnricher(boolean isMtom) {
    return isMtom ? new MtomResponseEnricher(introspecter, loader) : new SoapAttachmentResponseEnricher(introspecter, loader);
  }

  private Optional<String> parseExceptionDetail(Element detail) {
    try {
      return ofNullable(XmlTransformationUtils.nodeToString(detail));
    } catch (XmlTransformationException e) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Error while parsing Soap Exception detail: " + detail.toString(), e);
      }
      return empty();
    }
  }

  @Override
  public String toString() {
    return reflectionToString(this);
  }
}
