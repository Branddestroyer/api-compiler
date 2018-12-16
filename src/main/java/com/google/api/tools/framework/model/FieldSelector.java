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

package com.google.api.tools.framework.model;

import com.google.common.base.CaseFormat;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;

import java.util.Iterator;
import java.util.Objects;

/**
 * Represents a field selector, a list of fields with some special operations on it.
 */
public class FieldSelector {

  private static final Splitter FIELD_PATH_SPLITTER = Splitter.on('.');
  private static final Joiner FIELD_PATH_JOINER = Joiner.on('.');

  /**
   * Construct an empty field selector.
   */
  public static FieldSelector of() {
    return new FieldSelector(ImmutableList.<Field>of());
  }

  /**
   * Construct a field selector for the given field.
   */
  public static FieldSelector of(Field field) {
    return new FieldSelector(ImmutableList.of(field));
  }

  /**
   * Construct a field selector for the given fields.
   */
  public static FieldSelector of(ImmutableList<Field> fields) {
    return new FieldSelector(fields);
  }

  /**
   * Construct a field selector by resolving a field path (as in 'a.b.c') against a message. Returns
   * null if resolution fails.
   */
  public static FieldSelector resolve(MessageType message, String fieldPath) {
    Iterator<String> path = FIELD_PATH_SPLITTER.split(fieldPath).iterator();
    ImmutableList.Builder<Field> fieldsBuilder = ImmutableList.builder();

    MessageType currMessage = message;
    while (path.hasNext()) {
      String fieldName = path.next();
      Field field = currMessage.lookupField(fieldName);
      if (field == null) {
        return null;
      }
      fieldsBuilder.add(field);
      if (path.hasNext()) {
        if (!field.getType().isMessage()) {
          return null;
        }
        currMessage = field.getType().getMessageType();
      }
    }

    return new FieldSelector(fieldsBuilder.build());
  }

  public static boolean hasSinglePathElement(String path) {
    return !path.isEmpty() && !path.contains(".");
  }

  private final ImmutableList<Field> fields;
  private volatile String paramName;

  private FieldSelector(ImmutableList<Field> fields) {
    this.fields = fields;
  }

  /**
   * Returns the fields of this selector.
   */
  public ImmutableList<Field> getFields() {
    return fields;
  }

  /**
   * Returns the last field of the selector.
   */
  public Field getLastField() {
    return fields.get(fields.size() - 1);
  }

  /**
   * Returns the type of this field selector, i.e. the type of the last field in the field list.
   */
  public TypeRef getType() {
    return getLastField().getType();
  }

  /**
   * Extend the selector by the field.
   */
  public FieldSelector add(Field field) {
    return new FieldSelector(ImmutableList.<Field>builder().addAll(fields).add(field).build());
  }

  /**
   * Check whether this selector is a prefix of another selector.
   */
  public boolean isPrefixOf(FieldSelector sel) {
    for (int i = 0; i < fields.size(); i++) {
      if (i >= sel.fields.size() || fields.get(i) != sel.fields.get(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the HTTP parameter name of this selector. This is the sequence of proto field names
   * lower-cameled and joined by '.'.
   */
  public String getParamName() {
    if (paramName != null) {
      return paramName;
    }
    return paramName = Joiner.on('.').join(FluentIterable.from(fields)
        .transform(new Function<Field, String>() {
          @Override public String apply(Field field) {
            return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, field.getSimpleName());
          }
        }));
  }

  @Override public String toString() {
    return FIELD_PATH_JOINER.join(FluentIterable.from(fields).transform(
        new Function<Field, String>() {
          @Override public String apply(Field field) {
            return field.getSimpleName();
          }
        }));
  }

  @Override
  public int hashCode() {
    // Note that paramName doesn't count because it is derived.
    return Objects.hash(fields);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    FieldSelector other = (FieldSelector) obj;
    return Objects.equals(fields, other.fields);
  }
}
