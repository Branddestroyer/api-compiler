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

package com.google.api.tools.framework.importers.swagger;

import static com.google.common.truth.Truth.assertThat;

import com.google.api.Service;
import com.google.api.tools.framework.model.Diag;
import com.google.api.tools.framework.model.Diag.Kind;
import com.google.api.tools.framework.model.testing.BaselineTestCase;
import com.google.api.tools.framework.model.testing.DiagUtils;
import com.google.api.tools.framework.model.testing.ServiceConfigTestingUtil;
import com.google.api.tools.framework.model.testing.TestDataLocator;
import com.google.api.tools.framework.model.testing.TextFormatForTest;
import com.google.api.tools.framework.tools.FileWrapper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)

public class SwaggerImportTest extends BaselineTestCase {
  private static final String DEFAULT_TYPE_NAMESPACE = "namespace.types";
  private static final String DEFAULT_SERVICE_NAME = "";
  private static final String[] ALLOWED_EXTENSIONS =
      new String[] {"json", "yaml", "yml", "invalid"};

  private static final ImmutableSet<String> EMPTY_VISIBILITY_LABELS = ImmutableSet.<String>of();
  private static final ImmutableList<FileWrapper> NO_ADDITIONAL_CONFIGS =
      ImmutableList.<FileWrapper>of();

  private static final TestDataLocator testDataLocator =
      TestDataLocator.create(SwaggerImportTest.class);

  private static final String ENDPOINTS_YAML = "google/serviceconfig/endpoints/endpoints.yaml";
  private static final ImmutableList<FileWrapper> ADDITIONAL_CONFIGS;

  static {
    ImmutableList.Builder<FileWrapper> builder = ImmutableList.<FileWrapper>builder();

    try {
      builder.add(
          FileWrapper.create(
              ENDPOINTS_YAML,
              Resources.toString(Resources.getResource(ENDPOINTS_YAML), StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
    ADDITIONAL_CONFIGS = builder.build();
  }

  private URL getFileResourceUrl(String fileName) {
    return testDataLocator.findTestData(fileName);
  }

  private void testWithDefaults(String... files) {
    test(
        DEFAULT_SERVICE_NAME,
        DEFAULT_TYPE_NAMESPACE,
        ImmutableSet.<String>of(),
        ImmutableList.<String>copyOf(files),
        ImmutableList.<FileWrapper>of());
  }

  private void test(
      String serviceName,
      String typeNamespace,
      Set<String> visibility,
      ImmutableList<String> files,
      ImmutableList<FileWrapper> additional) {
    try {
      ImmutableList.Builder<FileWrapper> fileContents = new ImmutableList.Builder<>();
      for (String file : files) {
        String fileName = findFile(file);
        URL fileUrl = getFileResourceUrl(fileName);
        String swaggerSpec = testDataLocator.readTestData(fileUrl);
        fileContents.add(FileWrapper.create(fileName, swaggerSpec));
      }
      OpenApiToService swaggerToService =
          new OpenApiToService(fileContents.build(), serviceName, typeNamespace, additional);
      Service service = swaggerToService.createServiceConfig();

      int errorCount = 0;
      for (Diag diag : swaggerToService.getDiagCollector().getDiags()) {
        testOutput().println(DiagUtils.getDiagToPrint(diag, false));
        if (diag.getKind() == Kind.ERROR) {
          errorCount++;
        }
      }
      assertThat(swaggerToService.getDiagCollector().getErrorCount()).isEqualTo(errorCount);

      if (service == null) {
        testOutput().println("Service config creation failed");
        return;
      }

      // Remove the predefined types from the Service Object to make the baseline clean.
      Service.Builder printableServiceBuilder = service.toBuilder();
      printableServiceBuilder.clearTypes();
      for (int i = 0; i < service.getTypesCount(); ++i) {
        if (!service.getTypes(i).getName().startsWith("google.protobuf.")) {
          printableServiceBuilder.addTypes(service.getTypes(i).toBuilder());
        }
      }
      printableServiceBuilder.clearEnums();
      for (int i = 0; i < service.getEnumsCount(); ++i) {
        if (!service.getEnums(i).getName().startsWith("google.protobuf.")) {
          printableServiceBuilder.addEnums(service.getEnums(i).toBuilder());
        }
      }

      // Remove the config version too.
      printableServiceBuilder.clearConfigVersion();
      // This is a temporary hack, and https://b/34954570 is created to remove the hack.
      if (!printableServiceBuilder.hasExperimental()) {
        printableServiceBuilder.getExperimentalBuilder().clearAuthorization();
      }
      printableServiceBuilder =
          ServiceConfigTestingUtil.clearIrrelevantData(printableServiceBuilder);
      testOutput().println(TextFormatForTest.INSTANCE.printToString(printableServiceBuilder));

    } catch (Exception e) {
      e.printStackTrace();
      // Uncomment below to see stack traces in testOutput (useful for debugging)
      // e.printStackTrace(testOutput());
      testOutput().println(e.getMessage());
      return;
    }
  }

  private String findFile(String filenameWithoutExtension) {
    for (String allowedExtension : ALLOWED_EXTENSIONS) {
      String filename = filenameWithoutExtension + "." + allowedExtension;
      URL fileUrl = getFileResourceUrl(filename);
      if (fileUrl != null) {
        return filename;
      }
    }
    throw new IllegalArgumentException(
        String.format(
            "No testfile for filename '%s' with a valid extension. Valid extensions are {%s}.",
            filenameWithoutExtension, Joiner.on(",").join(ALLOWED_EXTENSIONS)));
  }

  @Test
  public void primitive_types() throws Exception {
    testWithDefaults("primitive_types");
  }

  @Test
  public void library_example() throws Exception {
    testWithDefaults("library_example");
  }

  // TODO (guptasu): Currently tools framework does now allow certain types to be used as
  // body/response of an API.
  // Once I fix this issue, output of this test will be a valid service config as compared to error
  // that it is producing now.
  @Test
  public void additional_properties() throws Exception {
    testWithDefaults("additional_properties");
  }

  @Test
  public void reference_model() throws Exception {
    testWithDefaults("reference_model");
  }

  @Test
  public void array_model() throws Exception {
    testWithDefaults("array_model");
  }

  @Test
  public void response_no() throws Exception {
    testWithDefaults("response_no");
  }

  // TODO (guptasu): Currently tools framework does now allow certain types to be used as
  // body/response of an API.
  // On I fix this issue, output of this test will be a valid service config as compared to error
  // that it is producing now.
  @Test
  public void response_multiple() throws Exception {
    testWithDefaults("response_multiple");
  }

  @Test
  public void formdata_as_param() throws Exception {
    testWithDefaults("formdata_as_param");
  }

  @Test
  public void reference_properties() throws Exception {
    testWithDefaults("reference_properties");
  }

  @Test
  public void type_from_body() throws Exception {
    testWithDefaults("type_from_body");
  }

  @Test
  public void object_type_property() throws Exception {
    testWithDefaults("object_type_property");
  }

  @Test
  public void schema_error() throws Exception {
    testWithDefaults("schema_error");
  }

  @Test
  public void shared_parameters() throws Exception {
    testWithDefaults("shared_parameters");
  }

  @Test
  public void distributed_swagger() throws Exception {
    testWithDefaults(
        "distributed_swagger", "distributed_shared_swagger_defs", "distributed_shared_json");
  }

  @Test
  public void distributed_swagger_missing_file() throws Exception {
    testWithDefaults("distributed_swagger_missing_file");
  }

  @Test
  public void invalid_swagger() throws Exception {
    testWithDefaults("invalid_swagger");
  }

  @Test
  public void auth() throws Exception {
    testWithDefaults("auth");
  }

  @Test
  public void auth_multiple_oauth_logicalAND_error() throws Exception {
    testWithDefaults("auth_multiple_oauth_logicalAND_error");
  }

  @Test
  public void oauth_in_security() throws Exception {
    testWithDefaults("oauth_in_security");
  }

  @Test
  public void auth_default() throws Exception {
    testWithDefaults("auth_default");
  }

  @Test
  public void auth_with_error() throws Exception {
    testWithDefaults("auth_with_error");
  }

  @Test
  public void petstore() throws Exception {
    testWithDefaults("petstore");
  }

  @Test
  public void missing_host() throws Exception {
    testWithDefaults("missing_host");
  }

  @Test
  public void missing_definitions() throws Exception {
    testWithDefaults("missing_definitions");
  }

  @Test
  public void operation_name_slash() throws Exception {
    testWithDefaults("operation_name_slash");
  }

  @Test
  public void options_operation() throws Exception {
    testWithDefaults("options_operation");
  }

  @Test
  public void head_operation() throws Exception {
    testWithDefaults("head_operation");
  }

  @Test
  public void yml_extension() throws Exception {
    testWithDefaults("yml_extension");
  }

  @Test
  public void invalid_vendor_extension_type() throws Exception {
    testWithDefaults("invalid_vendor_extension_type");
  }

  @Test
  public void invalid_extension_json_array() throws Exception {
    testWithDefaults("invalid_extension_json_array");
  }

  @Test
  public void invalid_google_allow_extension_value() throws Exception {
    testWithDefaults("invalid_google_allow_extension_value");
  }

  @Test
  public void x_google_allow_extension_all() throws Exception {
    testWithDefaults("x_google_allow_extension_all");
  }

  @Test
  public void x_google_allow_extension_with_auth() throws Exception {
    testWithDefaults("x_google_allow_extension_with_auth");
  }

  @Test
  public void x_google_allow_extension_with_auth_in_operation() throws Exception {
    testWithDefaults("x_google_allow_extension_with_auth_in_operation");
  }

  @Test
  public void x_google_allow_extension_all_with_existing_catchall_methods() throws Exception {
    testWithDefaults("x_google_allow_extension_all_with_existing_catchall_methods");
  }

  @Test
  public void x_google_experimental_authorization() throws Exception {
    testWithDefaults("x_google_experimental_authorization");
  }

  @Test
  public void x_google_experimental_authorization_invalid() throws Exception {
    testWithDefaults("x_google_experimental_authorization_invalid");
  }

  @Test
  public void x_google_endpoints() throws Exception {
    testWithDefaults("x-google-endpoints");
  }

  @Test
  public void x_google_endpoints_with_ip() throws Exception {
    testWithDefaults("x-google-endpoints-with-ip");
  }

  @Test
  public void x_google_endpoints_with_cname() throws Exception {
    testWithDefaults("x-google-endpoints-with-cname");
  }

  @Test
  public void x_google_endpoints_with_mismatched_name() throws Exception {
    testWithDefaults("x-google-endpoints-with-mismatched-name");
  }

  @Test
  public void x_google_endpoints_with_multiple_entries() throws Exception {
    testWithDefaults("x-google-endpoints-with-multiple-entries");
  }

  @Test
  public void error_multiple_extension_for_same_property() throws Exception {
    testWithDefaults("error_multiple_extension_for_same_property");
  }

  @Test
  public void x_google_audiences() throws Exception {
    testWithDefaults("x_google_audiences");
  }

  @Test
  public void x_google_invalid_unknown_field() throws Exception {
    testWithDefaults("x-google-invalid-unknown-field");
  }

  @Test
  public void api_deprecated_false() throws Exception {
    testWithDefaults("api_deprecated_false");
  }

  @Test
  public void multi_apis_single_name() throws Exception {
    testWithDefaults(
        "multi_apis_single_name",
        "multi_apis_single_name2",
        "multi_apis_single_name3",
        "multi_apis_single_name4");
  }

  @Test
  public void multi_apis_single_name_version_conflict() throws Exception {
    testWithDefaults(
        "multi_apis_single_name_version_conflict",
        "multi_apis_single_name_version_conflict2",
        "multi_apis_single_name_version_conflict3");
  }

  @Test
  public void petstore_expanded() throws Exception {
    testWithDefaults("petstore_expanded");
  }

  @Test
  public void invalid_opertion_id() throws Exception {
    test(
        "",
        "",
        EMPTY_VISIBILITY_LABELS,
        ImmutableList.of("invalid_opertion_id"),
        NO_ADDITIONAL_CONFIGS);
  }

  @Test
  public void corrupt_json() throws Exception {
    test("", "", EMPTY_VISIBILITY_LABELS, ImmutableList.of("corrupt_json"), NO_ADDITIONAL_CONFIGS);
  }

  @Test
  public void invalid_extension() throws Exception {
    test(
        "",
        "",
        EMPTY_VISIBILITY_LABELS,
        ImmutableList.of("invalid_extension"),
        NO_ADDITIONAL_CONFIGS);
  }

  @Test
  public void compiler_warning() throws Exception {
    test(
        "",
        "",
        EMPTY_VISIBILITY_LABELS,
        ImmutableList.of("compiler_warning"),
        ImmutableList.of(
            FileWrapper.create(
                "compiler_warning.yaml",
                testDataLocator.readTestData(
                    getFileResourceUrl("compiler_warning.yaml")))));
  }

  @Test
  public void multiple_swagger_hosts() throws Exception {
    test(
        "",
        "",
        EMPTY_VISIBILITY_LABELS,
        ImmutableList.of("petstore", "uber"),
        NO_ADDITIONAL_CONFIGS);
  }

  @Test
  public void multiple_swagger() throws Exception {
    test(
        "",
        "",
        EMPTY_VISIBILITY_LABELS,
        ImmutableList.of("petstore", "petstore_v2"),
        NO_ADDITIONAL_CONFIGS);
  }

  @Test
  public void top_level_security_ext() throws Exception {
    test(
        "",
        "",
        EMPTY_VISIBILITY_LABELS,
        ImmutableList.of("top_level_security_ext"),
        NO_ADDITIONAL_CONFIGS);
  }

  @Test
  public void bookstore() throws Exception {
    test(
        "bookstore.appspot.com",
        "",
        EMPTY_VISIBILITY_LABELS,
        ImmutableList.of("bookstore"),
        ADDITIONAL_CONFIGS);
  }

  @Test
  public void bookstore_visibility() throws Exception {
    test(
        "bookstore.appspot.com",
        "",
        ImmutableSet.<String>of("EARLY_ACCESS_PROGRAM"),
        ImmutableList.of("bookstore"),
        ADDITIONAL_CONFIGS);
  }

  @Test
  public void array_type_missing_items() throws Exception {
    testWithDefaults("array_type_missing_items");
  }

  @Test
  public void type_reference_array_loop() throws Exception {
    testWithDefaults("type_reference_array_loop");
  }

  @Test
  public void type_reference_ref_loop() throws Exception {
    testWithDefaults("type_reference_ref_loop");
  }

  @Test
  public void type_reference_object_self_reference() throws Exception {
    testWithDefaults("type_reference_object_self_reference");
  }

  @Test
  public void invalid_type_definition_reference_prefix() throws Exception {
    testWithDefaults("invalid_type_definition_reference_prefix");
  }

  @Test
  public void invalid_type_reference_prefix() throws Exception {
    testWithDefaults("invalid_type_reference_prefix");
  }
}
