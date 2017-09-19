/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.service.soap.metadata;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.mule.test.allure.AllureConstants.WscFeature.WSC_EXTENSION;

import org.mule.metadata.api.model.BinaryType;
import org.mule.metadata.api.model.NullType;
import org.mule.metadata.api.model.ObjectFieldType;
import org.mule.metadata.api.model.ObjectType;
import org.mule.runtime.api.metadata.MetadataResolvingException;
import org.mule.runtime.soap.api.client.metadata.SoapOperationMetadata;

import java.util.Collection;

import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.Assert;
import org.junit.Test;

@Feature(WSC_EXTENSION)
@Story("Metadata")
public class AttachmentMetadataTestCase extends AbstractMetadataTestCase {

  @Test
  @Description("Checks the Input Metadata of an operation with required input attachments")
  public void operationWithInputAttachmentMetadata() throws MetadataResolvingException {
    SoapOperationMetadata result = resolver.getInputMetadata("uploadAttachment");
    ObjectType attachments = toObjectType(result.getAttachmentsType());
    Collection<ObjectFieldType> attachmentFields = attachments.getFields();
    assertThat(attachmentFields, hasSize(1));
    assertThat(attachmentFields.iterator().next().getKey().getName().getLocalPart(), is("attachment"));
  }

  @Test
  @Description("Checks the Input Metadata of an operation without attachments")
  public void operationWithoutInputAttachmentsMetadata() throws MetadataResolvingException {
    SoapOperationMetadata result = resolver.getInputMetadata("echo");
    assertThat(result.getAttachmentsType(), is(instanceOf(NullType.class)));
  }

  @Test
  @Description("Checks the Output Metadata of an operation that contains output attachments")
  public void operationWithOutputAttachmentsMetadata() throws MetadataResolvingException {
    SoapOperationMetadata result = resolver.getOutputMetadata("downloadAttachment");
    ObjectType objectType = toObjectType(result.getAttachmentsType());
    assertThat(objectType.getFields(), hasSize(1));
    ObjectFieldType attachment = objectType.getFields().iterator().next();
    assertThat(attachment.getKey().getName().getLocalPart(), is("attachment"));
    assertThat(attachment.getValue(), is(instanceOf(BinaryType.class)));
    assertThat(result.getBodyType(), is(instanceOf(NullType.class)));
  }

  @Test
  @Description("Checks the metadata for an operation that is ONE WAY")
  public void oneWayOperationMetadata() throws MetadataResolvingException {
    SoapOperationMetadata input = resolver.getInputMetadata("oneWay");
    SoapOperationMetadata output = resolver.getOutputMetadata("oneWay");
    Assert.assertThat(input.getAttachmentsType(), is(instanceOf(NullType.class)));
    Assert.assertThat(output.getAttachmentsType(), is(instanceOf(NullType.class)));
  }
}
