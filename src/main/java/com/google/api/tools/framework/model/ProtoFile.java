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

import com.google.api.tools.framework.model.ExtensionPool.Extension;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Maps;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Syntax;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Represents a protocol buffer file.
 */
public class ProtoFile extends ProtoContainerElement {

  private static final Joiner DOT_JOINER = Joiner.on('.');
  // Locations for documentation in proto files.
  private static final ImmutableList<String> FILE_DOC_LOCATIONS = ImmutableList.of(
      "12", // syntax statement

      // TODO(user) Decide if we want to add this back in. In its current form, it doesn't work
      // because the comments are attached to specific "option" statements, so the path is something
      // that starts with "8" (e.g. "8.999.0").  We would need to do a prefix match (or some such)
      // on the path to pick them all up, and that would end up potentially adding new comments to
      // the returned comment block at the file-level, which COULD conceivably break something. And
      // this file-level behavior doesn't seem to be documented anywhere, so we could just drop
      // this support on "option" statements.
      // "8",  // option statement

      "2"   // package statement
      );

  /**
   * Creates a new protocol file backed up by the given descriptor.
   */
  public static ProtoFile create(Model model, FileDescriptorProto proto, boolean isSource,
      ExtensionPool extensionPool) {
    return new ProtoFile(model, proto, isSource, extensionPool);
  }

  // The location path is empty for ProtoFile element.
  private static final String PATH = "";
  private final Model model;
  private final FileDescriptorProto proto;
  private final boolean isSource;
  private final ImmutableList<Interface> interfaces;
  private final ImmutableListMultimap<String, DescriptorProtos.SourceCodeInfo.Location> locationMap;
  private final Map<ProtoElement, Location> protoToLocation = Maps.newHashMap();
  private final Map<Extension, Field> extensions = Maps.newHashMap();
  private final Syntax syntax;

  private ProtoFile(Model model, FileDescriptorProto proto, boolean isSource,
      ExtensionPool extensionPool) {
    super(null, proto.getName(), PATH);
    this.model = model;
    this.isSource = isSource;
    this.proto = proto;
    buildChildren(proto.getMessageTypeList(),
        proto.getEnumTypeList(),
        PATH,
        FileDescriptorProto.MESSAGE_TYPE_FIELD_NUMBER,
        FileDescriptorProto.ENUM_TYPE_FIELD_NUMBER,
        extensionPool);

    // Build services.
    ImmutableList.Builder<Interface> interfacesBuilder = ImmutableList.builder();
    List<ServiceDescriptorProto> serviceProtos = proto.getServiceList();
    for (int i = 0; i < serviceProtos.size(); i++) {
      String childPath = buildPath(null, FileDescriptorProto.SERVICE_FIELD_NUMBER, i);
      interfacesBuilder.add(Interface.create(this, serviceProtos.get(i), childPath));
    }

    interfaces = interfacesBuilder.build();

    // Build location map
    ImmutableListMultimap.Builder<String, DescriptorProtos.SourceCodeInfo.Location> builder =
        ImmutableListMultimap.builder();
    for (DescriptorProtos.SourceCodeInfo.Location location :
        proto.getSourceCodeInfo().getLocationList()) {
      builder.put(DOT_JOINER.join(location.getPathList()), location);
    }

    // Add all extension locations
    for (Entry<Extension, Field> entry : extensions.entrySet()) {
      builder.put(entry.getKey().getPath(), entry.getKey().getLocation());
      protoToLocation.put(entry.getValue(), entry.getKey().getFileLocation());
    }
    locationMap = builder.build();

    // Initialize ProtoFile location.
    syntax = getProtoSyntax(proto);
    protoToLocation.put(this, new ProtoLocation(this));
  }

  @Override public String toString() {
    return "file " + getSimpleName();
  }

  /**
   * Returns true if this object represents something that is configured as deprecated.
   */
  @Override public boolean isDeprecated() {
    return proto.getOptions().getDeprecated();
  }

  /**
   * Returns true if this file is a proper source, in contrast to a dependency.
   */
  public boolean isSource() {
    return isSource;
  }

  @Override
  public Model getModel() {
    return model;
  }

  @Override
  public String getFullName() {
    return proto.getPackage();
  }

  @Override
  public ProtoFile getFile() {
    return this;
  }

  @Override
  public Location getLocation() {
    return protoToLocation.get(this);
  }

  /**
   * Returns the file descriptor proto.
   */
  public FileDescriptorProto getProto() {
    return proto;
  }

  @Override public Map<FieldDescriptor, Object> getOptionFields() {
    return proto.getOptions().getAllFields();
  }

  /**
   * Returns the dependencies.
   */
  public ImmutableList<ProtoFile> getDependencies() {
    ImmutableList.Builder<ProtoFile> builder = ImmutableList.builder();
    for (ProtoFile file : model.getFiles()) {
      if (proto.getDependencyList().contains(file.getSimpleName())) {
        builder.add(file);
      }
    }
    return builder.build();
  }

  /**
   * Returns the interfaces in this file.
   */
  public ImmutableList<Interface> getInterfaces() {
    return interfaces;
  }

  /**
   * Returns the interfaces reachable with active scoper.
   */
  public Iterable<Interface> getReachableInterfaces() {
    return getModel().reachable(interfaces);
  }

  /**
   * Package private helper to get the location backed up by this proto file for the given element.
   */
  Location getLocation(ProtoElement element) {
    if (protoToLocation.containsKey(element)) {
      return protoToLocation.get(element);
    }

    Location location =
        ProtoLocation.convertFrom(getSourceCodeLocation(element.getPath()), element);
    protoToLocation.put(element, location);
    return location;
  }

  /**
   * Helper to get the documentation backed up by this proto file for the given
   * element.
   */
  public String getDocumentation(ProtoElement element) {
    if (element instanceof ProtoFile) {
      // For files themselves, comments from multiple locations are composed.
      StringBuilder result = new StringBuilder();
      for (String path : FILE_DOC_LOCATIONS) {
        String comment = getDocumentation(path);
        if (Strings.isNullOrEmpty(comment)) {
          continue;
        }
        if (result.length() > 0) {
          result.append('\n');
        }
        result.append(comment);
      }
      return result.toString();
    } else {
      return getDocumentation(element.getPath());
    }
  }

  @Override
  public Syntax getSyntax() {
    return syntax;
  }

  private String getDocumentation(String path) {
    String comment = "";
    DescriptorProtos.SourceCodeInfo.Location location = getSourceCodeLocation(path);
    if (location != null) {
      if (!Strings.isNullOrEmpty(location.getLeadingComments())) {
        comment = location.getLeadingComments();
      }
      if (!Strings.isNullOrEmpty(location.getTrailingComments())){
        comment += location.getTrailingComments();
      }
    }
    return comment;
  }

  private DescriptorProtos.SourceCodeInfo.Location getSourceCodeLocation(String path) {
    if (locationMap.containsKey(path)) {
      // We get the first location.
      return locationMap.get(path).get(0);
    } else {
      return null;
    }
  }

  private static Syntax getProtoSyntax(FileDescriptorProto proto) {
    if (!proto.hasSyntax()) {
      // TODO(user): This can be removed once protoc outputs proto2 when proto2 is being used.
      //     According to liujisi@ it would break a lot of tests, so it is currently not done.
      return Syntax.SYNTAX_PROTO2;
    }
    switch (proto.getSyntax()) {
      case "proto2":
        return Syntax.SYNTAX_PROTO2;
      case "proto3":
        return Syntax.SYNTAX_PROTO3;
      default:
        throw new IllegalArgumentException(
            "Illegal proto syntax for file " + proto.getName() + ": " + proto.getSyntax());
    }
  }

  void addExtension(Extension extension, Field field) {
    extensions.put(extension, field);
  }
}
