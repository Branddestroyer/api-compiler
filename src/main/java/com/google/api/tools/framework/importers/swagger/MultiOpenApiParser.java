/*
 * Copyright (C) 2016 Google, Inc.
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

package com.google.api.tools.framework.importers.swagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import com.github.fge.jsonschema.report.ProcessingReport;
import com.github.fge.jsonschema.report.ProcessingMessage;
import com.google.api.Service;
import com.google.api.tools.framework.importers.swagger.aspects.utils.ExtensionNames;
import com.google.api.tools.framework.tools.FileWrapper;
import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import io.swagger.models.Swagger;
import io.swagger.parser.SwaggerParser;
import io.swagger.util.Json;
import io.swagger.util.Yaml;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Converts Multiple swagger files from in memory {@link FileWrapper}s to {@link OpenApiFile}
 * objects.
 */
public class MultiOpenApiParser {

  private static final String SCHEMA_RESOURCE_PATH = "swagger/schema2_0/schema.json";
  private static final Map<String, ObjectMapper> mapperForExtension =
      ImmutableMap.of("yaml", Yaml.mapper(), "yml", Yaml.mapper(), "json", Json.mapper());
  private static final String SWAGGER_VERSION_PROPERTY = "swagger";
  private static final String CURRENT_SWAGGER_VERSION = "2.0";

  /** Build resources for a single Swagger file. */
  @AutoValue
  public abstract static class OpenApiFile {

    public abstract Service.Builder serviceBuilder();

    public abstract Swagger swagger();

    public abstract String filename();

    public abstract String apiName();

    public abstract OpenApiConversionResources conversionResources();

    public static OpenApiFile create(
        Service.Builder serviceBuilder, Swagger swagger, String filename, String typeNamespace)
        throws OpenApiConversionException {
      String hostname = Strings.nullToEmpty(swagger.getHost());
      String version = Strings.nullToEmpty(swagger.getInfo().getVersion());
      String googleApiName = "";
      if (swagger.getVendorExtensions() != null) {
        googleApiName = Strings.nullToEmpty(
            (String) swagger.getVendorExtensions().get(ExtensionNames.API_NAME));
      }
      String apiName = ApiNameGenerator.generate(hostname, googleApiName, version);
      return new AutoValue_MultiOpenApiParser_OpenApiFile(
          serviceBuilder,
          swagger,
          filename,
          apiName,
          OpenApiConversionResources.create(swagger, filename, apiName, typeNamespace));
    }
  }

  public static List<OpenApiFile> convert(List<FileWrapper> openApiFiles, String typeNamespace)
      throws OpenApiConversionException {
    Map<String, FileWrapper> savedFilePaths = OpenApiFileWriter.saveFilesOnDisk(openApiFiles);
    Map<String, File> openApiFilesMap = validateInputFiles(savedFilePaths);
    Service.Builder serviceBuilder = Service.newBuilder();
    ImmutableList.Builder<OpenApiFile> openApiObjects = ImmutableList.builder();
    for (Entry<String, File> openApiFile : openApiFilesMap.entrySet()) {
      openApiObjects.add(
          buildOpenApiFile(
              serviceBuilder, openApiFile.getKey(), openApiFile.getValue(), typeNamespace));
    }

    return openApiObjects.build();
  }

  private static OpenApiFile buildOpenApiFile(
      Service.Builder serviceToBuild, String userDefinedFilename, File file, String typeNamespace)
      throws OpenApiConversionException {
    Swagger swagger = tryGetOpenApi(file, userDefinedFilename);
    return OpenApiFile.create(serviceToBuild, swagger, userDefinedFilename, typeNamespace);
  }

  private static Swagger tryGetOpenApi(File file, String userDefinedFilename)
      throws OpenApiConversionException {
    try {
      Swagger swagger = new SwaggerParser().read(file.getAbsolutePath());
      if (swagger == null) {
        throw new OpenApiConversionException(
            String.format(
                "OpenAPI spec in file {%s} is ill formed and cannot be parsed",
                userDefinedFilename));
      } else {
        return swagger;
      }
    } catch (RuntimeException ex) {
      throw new OpenApiConversionException(
          String.format(
              "OpenAPI spec in file {%s} is ill formed and cannot be parsed: %s",
              userDefinedFilename, ex.getMessage()));
    }
  }

  /**
   * Ensures that all files are valid json/yaml and does schema validation on swagger spec. Returns
   *
   * <p>the valid swagger file.
   *
   * @throws OpenApiConversionException
   */
  private static Map<String, File> validateInputFiles(Map<String, FileWrapper> savedFilePaths)
      throws OpenApiConversionException {
    Map<String, File> topLevelOpenApiFiles = getTopLevelOpenApiFiles(savedFilePaths);
    if (topLevelOpenApiFiles.isEmpty()) {
      throw new OpenApiConversionException(
          String.format(
              "Cannot find a valid OpenAPI %s spec in the input files", CURRENT_SWAGGER_VERSION));
    }
    return topLevelOpenApiFiles;
  }

  private static Map<String, File> getTopLevelOpenApiFiles(Map<String, FileWrapper> savedFiles)
      throws OpenApiConversionException {
    ImmutableMap.Builder<String, File> topLevelFiles = ImmutableMap.builder();
    for (Entry<String, FileWrapper> savedFile : savedFiles.entrySet()) {

      try {
        String inputFileContent = savedFile.getValue().getFileContents().toStringUtf8();
        File inputFile = new File(savedFile.getValue().getFilename());
        ObjectMapper objMapper = createObjectMapperForExtension(inputFile);
        JsonNode data = objMapper.readTree(inputFileContent);

        if (isTopLevelOpenApiFile(data)) {
          validateSwaggerSpec(data);
          topLevelFiles.put(savedFile.getKey(), inputFile);
        }
      } catch (IOException ex) {
        throw new OpenApiConversionException("Unable to parse the content. " + ex.getMessage(), ex);
      }
    }
    return topLevelFiles.build();
  }

  private static boolean isTopLevelOpenApiFile(JsonNode data) {
    return data.get(SWAGGER_VERSION_PROPERTY) != null
        && data.get(SWAGGER_VERSION_PROPERTY).toString().contains(CURRENT_SWAGGER_VERSION);
  }

  private static ObjectMapper createObjectMapperForExtension(File file)
      throws OpenApiConversionException {
    String fileExtension = Files.getFileExtension(file.getAbsolutePath());
    if (mapperForExtension.containsKey(fileExtension)) {
      return mapperForExtension.get(fileExtension);
    }
    throw new OpenApiConversionException(
        String.format(
            "OpenAPI file '%s' has invalid extension '%s'. Only files with {%s} file "
                + "extensions are allowed.",
            file.getName(), fileExtension, Joiner.on(", ").join(mapperForExtension.keySet())));
  }

  /**
   * Validates the input Swagger JsonNode against Swagger Specification schema.
   *
   * @throws OpenApiConversionException
   */
  private static void validateSwaggerSpec(JsonNode swaggerJsonNode)
      throws OpenApiConversionException {
    ProcessingReport report = null;
    try {
      URL url = Resources.getResource(SCHEMA_RESOURCE_PATH);
      String swaggerSchema = Resources.toString(url, StandardCharsets.UTF_8);
      JsonNode schemaNode = Yaml.mapper().readTree(swaggerSchema);
      JsonSchema schema = JsonSchemaFactory.byDefault().getJsonSchema(schemaNode);
      report = schema.validate(swaggerJsonNode);
    } catch (Exception ex) {
      throw new OpenApiConversionException("Unable to parse the content. " + ex.getMessage(), ex);
    }
    if (!report.isSuccess()) {
      String message = "";
      Iterator itr = report.iterator();
      if (itr.hasNext()) {
        message += ((ProcessingMessage) itr.next()).toString();
      }
      while(itr.hasNext())
      {
        message += "," + ((ProcessingMessage) itr.next()).toString();
      }
      throw new OpenApiConversionException(
          String.format("Invalid OpenAPI file. Please fix the schema errors:\n%s", message));
    }
  }
}
