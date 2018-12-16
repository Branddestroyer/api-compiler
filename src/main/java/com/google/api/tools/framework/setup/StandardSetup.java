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

package com.google.api.tools.framework.setup;

import com.google.api.tools.framework.aspects.authentication.AuthConfigAspect;
import com.google.api.tools.framework.aspects.authentication.validators.AuthenticationValidator;
import com.google.api.tools.framework.aspects.control.ControlConfigAspect;
import com.google.api.tools.framework.aspects.documentation.DocumentationConfigAspect;
import com.google.api.tools.framework.aspects.endpoint.EndpointConfigAspect;
import com.google.api.tools.framework.aspects.http.HttpConfigAspect;
import com.google.api.tools.framework.aspects.http.linters.HttpParameterReservedKeywordRule;
import com.google.api.tools.framework.aspects.http.validators.HttpConfigAspectValidator;
import com.google.api.tools.framework.aspects.naming.NamingConfigAspect;
import com.google.api.tools.framework.aspects.naming.linters.ServiceNameRule;
import com.google.api.tools.framework.aspects.servicecontrol.ServiceControlConfigAspect;
import com.google.api.tools.framework.aspects.superquota.SuperQuotaConfigAspect;
import com.google.api.tools.framework.aspects.superquota.validators.QuotaLimitNameValidator;
import com.google.api.tools.framework.aspects.superquota.validators.QuotaLimitValuesValidator;
import com.google.api.tools.framework.aspects.superquota.validators.QuotaMetricRuleValidator;
import com.google.api.tools.framework.aspects.superquota.validators.QuotaMetricsExistValidator;
import com.google.api.tools.framework.aspects.superquota.validators.QuotaUnitValidator;
import com.google.api.tools.framework.aspects.systemparameter.SystemParameterConfigAspect;
import com.google.api.tools.framework.aspects.usage.UsageConfigAspect;
import com.google.api.tools.framework.aspects.versioning.VersionConfigAspect;
import com.google.api.tools.framework.aspects.versioning.linters.ConfigVersionRule;
import com.google.api.tools.framework.model.Model;
import com.google.api.tools.framework.processors.linter.Linter;
import com.google.api.tools.framework.processors.merger.Merger;
import com.google.api.tools.framework.processors.normalizer.Normalizer;
import com.google.api.tools.framework.processors.resolver.Resolver;

/** A class providing registration of standard processors and config aspects. */
public class StandardSetup {
  private StandardSetup() {}

  /** Registers all standard processors. */
  public static void registerStandardProcessors(Model model) {
    model.registerProcessor(new Resolver());
    model.registerProcessor(new Merger());
    model.registerProcessor(new Normalizer());
    model.registerProcessor(new Linter());
  }

  /**
   * Registers all standard config aspects. This must be done after the service config has been
   * attached to the model.
   */
  public static void registerStandardConfigAspects(Model model) {

    model.registerConfigAspect(DocumentationConfigAspect.create(model));

    VersionConfigAspect versionAspect = VersionConfigAspect.create(model);
    versionAspect.registerLintRule(new ConfigVersionRule(versionAspect));
    model.registerConfigAspect(versionAspect);

    model.registerConfigAspect(EndpointConfigAspect.create(model));

    HttpConfigAspect http = HttpConfigAspect.create(model);
    http.registerLintRule(new HttpParameterReservedKeywordRule(http));
    model.registerConfigAspect(http);

    NamingConfigAspect namingAspect = NamingConfigAspect.create(model);
    namingAspect.registerLintRule(new ServiceNameRule(namingAspect));
    model.registerConfigAspect(namingAspect);

    model.registerConfigAspect(SystemParameterConfigAspect.create(model));

    model.registerConfigAspect(UsageConfigAspect.create(model));
    model.registerConfigAspect(ControlConfigAspect.create(model));

    model.registerConfigAspect(AuthConfigAspect.create(model));
    model.registerConfigAspect(ServiceControlConfigAspect.create(model));
    model.registerConfigAspect(SuperQuotaConfigAspect.create(model));

    registerValidators(model);

  }

  private static void registerValidators(Model model) {
    model.registerValidator(new HttpConfigAspectValidator(model.getDiagReporter()));

      model.registerValidator(new QuotaUnitValidator(model.getDiagReporter()));
      model.registerValidator(new QuotaLimitValuesValidator(model.getDiagReporter()));
    model.registerValidator(new QuotaLimitNameValidator(model.getDiagReporter()));
    model.registerValidator(new QuotaMetricRuleValidator(model.getDiagReporter()));
    model.registerValidator(new QuotaMetricsExistValidator(model.getDiagReporter()));

    model.registerValidator(new AuthenticationValidator(model.getDiagReporter()));
  }

}
