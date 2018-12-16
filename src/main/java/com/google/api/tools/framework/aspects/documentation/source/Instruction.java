/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.api.tools.framework.aspects.documentation.source;

import com.google.api.tools.framework.aspects.documentation.DocumentationConfigAspect;
import com.google.api.tools.framework.aspects.documentation.model.DeprecationDescriptionAttribute;
import com.google.api.tools.framework.aspects.documentation.model.InliningAttribute;
import com.google.api.tools.framework.aspects.documentation.model.PageAttribute;
import com.google.api.tools.framework.aspects.documentation.model.RequiredFieldAttribute;
import com.google.api.tools.framework.aspects.documentation.model.ResourceAttribute;
import com.google.api.tools.framework.model.Diag;
import com.google.api.tools.framework.model.DiagReporter.ResolvedLocation;
import com.google.api.tools.framework.model.Element;
import com.google.api.tools.framework.model.EnumType;
import com.google.api.tools.framework.model.Field;
import com.google.api.tools.framework.model.MessageType;
import com.google.api.tools.framework.model.ProtoContainerElement;
import com.google.inject.Key;

/** Represents Docgen instructions other than file inclusion: (== code arg ==) */
public class Instruction extends ContentElement {

  private static final String PAGE_INSTRUCTION = "page";
  private static final String SUPPRESS_WARNING_INSTRUCTION = "suppress_warning";
  private static final String RESOURCE_INSTRUCTION = "resource_for";
  private static final String DEPRECATION_DESCRIPTION = "deprecation_description";
  private static final String INLINE_INSTRUCTION = "inline_message";
  private static final String REQUIRED_FIELD_INSTRUCTION = "required_field";

  private final String code;
  private final String arg;

  public Instruction(String code, String arg, int startIndex, int endIndex) {
    super(startIndex, endIndex);
    this.code = code.trim();
    this.arg = arg.trim();
  }

  /** Returns the instruction code */
  public String getCode() {
    return code;
  }

  /** Returns the instruction argument. */
  public String getArg() {
    return arg;
  }

  /** Return the content (empty for instruction). */
  @Override
  public String getContent() {
    return "";
  }

  /** Evaluate the instruction in context of given element. */
  public void evalute(Element element) {
    switch (code) {
      case PAGE_INSTRUCTION:
        element.putAttribute(PageAttribute.KEY, PageAttribute.create(arg));
        break;
      case SUPPRESS_WARNING_INSTRUCTION:
        element
            .getModel()
            .getDiagReporter()
            .getDiagSuppressor()
            .addSuppressionDirective(element, arg, element.getModel().getConfigAspects());
        break;
      case RESOURCE_INSTRUCTION:
        if (!(element instanceof MessageType)) {
          element
              .getModel()
              .getDiagReporter()
              .reportError(
                  ResolvedLocation.create(element.getLocation()),
                  "resource instruction must be associated with a message declaration, but "
                      + "'%s' is not a message.",
                  element.getFullName());
        } else {
          element.addAttribute(ResourceAttribute.KEY, ResourceAttribute.create(arg));
        }
        break;
      case INLINE_INSTRUCTION:
        if (!(element instanceof MessageType || element instanceof EnumType)) {
          element
              .getModel()
              .getDiagReporter()
              .reportError(
                  ResolvedLocation.create(element.getLocation()),
                  INLINE_INSTRUCTION
                      + " instruction must be associated with a "
                      + "message/enum declaration, but '%s' is not a message/enum.",
                  element.getFullName());
        } else if (element instanceof MessageType && subCyclic((MessageType) element)) {
          if (!element.getModel().getExperiments()
              .isExperimentEnabled(DocumentationConfigAspect.INLINE_ALL_MESSAGES)) {
            element
                .getModel()
                .getDiagReporter()
                .reportError(
                    ResolvedLocation.create(element.getLocation()),
                    INLINE_INSTRUCTION
                        + " instruction must be associated with a *non-recursive* "
                        + "message declaration, but '%s' is recursive or contains a recursive "
                        + "subfield.",
                    element.getFullName());

          } else {
            // Warn and skip if inline-all-messages experiment enabled
            element
                .getModel()
                .getDiagReporter()
                .reportWarning(
                    ResolvedLocation.create(element.getLocation()),
                    "message '%s' is recursive and will not be inlined.",
                    element.getFullName());
          }
        } else {
          if (element instanceof ProtoContainerElement) {
            recursivePutAttribute(
                (ProtoContainerElement) element, InliningAttribute.KEY, new InliningAttribute());
          } else {
            element.putAttribute(InliningAttribute.KEY, new InliningAttribute());
          }
        }
        break;
      case DEPRECATION_DESCRIPTION:
        element.putAttribute(
            DeprecationDescriptionAttribute.KEY, DeprecationDescriptionAttribute.create(arg));
        break;
      case REQUIRED_FIELD_INSTRUCTION:
        if (!(element instanceof Field)) {
          element
              .getModel()
              .getDiagReporter()
              .report(
                  Diag.error(
                      element.getLocation(),
                      "required_field instruction can only be applied to a field."));
        } else {
          addMethodToRequiredField((Field) element);
        }
        break;
      default:
        element
            .getModel()
            .getDiagReporter()
            .report(
                Diag.error(element.getLocation(), "documentation instruction '%s' unknown.", code));
    }
  }

  private void addMethodToRequiredField(Field field) {
    String restMethodName = getArg();
    MessageType messageType = (MessageType) field.getParent();
    if (messageType.hasAttribute(RequiredFieldAttribute.KEY)) {
      messageType.getAttribute(RequiredFieldAttribute.KEY).addField(field, restMethodName);
    } else {
      RequiredFieldAttribute requiredFieldAttribute = new RequiredFieldAttribute();
      requiredFieldAttribute.addField(field, restMethodName);
      messageType.putAttribute(RequiredFieldAttribute.KEY, requiredFieldAttribute);
    }
    return;
  }

  private <T> void recursivePutAttribute(ProtoContainerElement element, Key<T> key, T attribute) {
    element.putAttribute(key, attribute);
    for (ProtoContainerElement message : element.getMessages()) {
      recursivePutAttribute(message, key, attribute);
    }
    for (EnumType enumType : element.getEnums()) {
      enumType.putAttribute(key, attribute);
    }
  }

  private boolean subCyclic(MessageType message) {
    if (message.isCyclic()) {
      return true;
    }
    for (Field subMessageField : message.getMessageFields()) {
      if (subCyclic(subMessageField.getType().getMessageType())) {
        return true;
      }
    }
    return false;
  }
}
