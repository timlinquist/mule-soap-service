/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.service.soap.generator;

import static org.mule.service.soap.SoapTestUtils.assertSimilarXml;
import static org.mule.service.soap.SoapTestXmlValues.DOWNLOAD_ATTACHMENT;
import static org.mule.service.soap.util.XmlTransformationUtils.stringToDocument;

import org.mule.metadata.xml.api.XmlTypeLoader;
import org.mule.service.soap.generator.attachment.AttachmentResponseEnricher;
import org.mule.wsdl.parser.model.operation.OperationModel;

import java.util.Map;

import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.ExchangeImpl;
import org.junit.Test;
import org.w3c.dom.Document;
import io.qameta.allure.Description;

abstract class ResponseEnricherTestCase extends AbstractEnricherTestCase {

  @Test
  @Description("Enrich a response that contains attachments")
  public void enrich() throws Exception {
    ExchangeImpl exchange = new ExchangeImpl();
    Document doc = stringToDocument(getResponse());
    Map<String, OperationModel> ops = model.getService("TestService").getPort("TestPort").getOperationsMap();
    AttachmentResponseEnricher enricher = getEnricher(model.getLoader(), ops);
    String result = enricher.enrich(doc, DOWNLOAD_ATTACHMENT, exchange);
    assertSimilarXml(testValues.getDownloadAttachmentResponse(), result);
    assertAttachment(exchange);
  }

  protected abstract AttachmentResponseEnricher getEnricher(XmlTypeLoader loader, Map<String, OperationModel> ops);

  protected abstract String getResponse();

  protected abstract void assertAttachment(Exchange exchange);
}
