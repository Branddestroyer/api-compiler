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

package com.google.api.tools.framework.aspects.servicecontrol;

import com.google.api.Billing;
import com.google.api.Billing.BillingDestination;
import com.google.api.LabelDescriptor;
import com.google.api.LogDescriptor;
import com.google.api.Logging;
import com.google.api.Logging.LoggingDestination;
import com.google.api.MetricDescriptor;
import com.google.api.MetricDescriptor.MetricKind;
import com.google.api.MetricDescriptor.ValueType;
import com.google.api.MonitoredResourceDescriptor;
import com.google.api.Monitoring;
import com.google.api.Monitoring.MonitoringDestination;
import com.google.api.Service;
import com.google.api.tools.framework.aspects.ConfigAspectBase;
import com.google.api.tools.framework.aspects.LintRule;
import com.google.api.tools.framework.model.DiagReporter.LocationContext;
import com.google.api.tools.framework.model.DiagReporter.MessageLocationContext;
import com.google.api.tools.framework.model.DiagReporter.ResolvedLocation;
import com.google.api.tools.framework.model.Location;
import com.google.api.tools.framework.model.SimpleLocation;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Validator that ensures that service control config is syntactically correct (i.e. metric name is
 * not empty) and semantically correct (i.e. metric references defined label key). Application
 * validation (i.e. log with given name actually exists) is out of scope.
 */
class ServiceControlConfigValidator {
  private static ServiceControlConfigBounds bounds =
      new ServiceControlConfigBounds.Builder().build();

  private static final String UNUSED_METRICS_RULE = "unused-metrics";
  private static final String UNUSED_LOGS_RULE = "unused-logs";

  public static ImmutableList<String> getLintRuleNames() {
    return ImmutableList.of(UNUSED_METRICS_RULE, UNUSED_LOGS_RULE);
  }

  // The log name prefix for Cloud Audit Logs.
  private static final String CLOUD_AUDIT_LOG_PREFIX = "cloudaudit.googleapis.com";

  // The allowed log name prefixes for Cloud Audit logs.
  private final List<String> allowedCloudAuditLogNamePrefixes =
      ImmutableList.of(
          "cloudaudit.googleapis.com/activity", "cloudaudit.googleapis.com/data_access");

  // The allowed billing status values.
  private final Set<String> allowedBillingStatuses = ImmutableSet.of("current", "delinquent");

  // Holds config aspect to report validation errors and warnings to.
  private final ConfigAspectBase configAspect;

  // Holds names of the defined monitored resources.
  private final Set<String> monitoredResources = new HashSet<>();

  // Holds names of the defined metrics. Boolean value indicates whether the log was used or not.
  private final Map<String, Boolean> metrics = new TreeMap<>();

  // Holds names of the defined logs. Boolean value indicates whether the log was used or not.
  private final Map<String, Boolean> logs = new TreeMap<>();

  // Maps metric name to its descriptor.
  private final Map<String, MetricDescriptor> metricDescriptors = new TreeMap<>();

  private ServiceControlConfigValidator(ConfigAspectBase configAspect) {
    this.configAspect = configAspect;
  }

  /** Configures service control config validator with its bounds. */
  @VisibleForTesting
  public static void configure(ServiceControlConfigBounds bounds) {
    ServiceControlConfigValidator.bounds = bounds;
  }

  /** Validates service service control config for syntactic and semantic correctness. */
  public static void validate(ConfigAspectBase configAspect, Service serviceConfig) {
    ServiceControlConfigValidator validator = new ServiceControlConfigValidator(configAspect);
    validator.validateMonitoredResources(serviceConfig.getMonitoredResourcesList());
    validator.validateMetrics(serviceConfig.getMetricsList());
    validator.validateLogs(serviceConfig.getLogsList());
    validator.validateBilling(serviceConfig.getBilling());
    validator.validateMonitoring(serviceConfig.getMonitoring());
    validator.validateLogging(serviceConfig.getLogging());
    validator.validateMetricsAndLogsUsed();
  }

  private void validateMonitoredResources(
      List<MonitoredResourceDescriptor> monitoredResourcesList) {
    // - Monitored resources list cannot be longer than predefined limit.
    validateMaxListSize(
        ResolvedLocation.create(SimpleLocation.TOPLEVEL),
        "monitored resources list",
        monitoredResourcesList,
        bounds.getMaxMonitoredResources());
    // - Monitored resources names must be unique across merged lists. Note that monitored
    // resource name is case sensitive.
    for (MonitoredResourceDescriptor monitoredResource : monitoredResourcesList) {
      if (monitoredResources.contains(monitoredResource.getType())) {
        error(
            MessageLocationContext.create(
                monitoredResource, MonitoredResourceDescriptor.TYPE_FIELD_NUMBER),
            "The '%s' monitored resource is already defined. "
                + "The monitored resource type must be unique.",
            monitoredResource.getType());
      }
      monitoredResources.add(monitoredResource.getType());
      validateMonitoredResource(monitoredResource);
    }
  }

  private void validateMonitoredResource(MonitoredResourceDescriptor monitoredResource) {
    // - Monitored resource type cannot be empty.
    // - Monitored resource type cannot be longer than predefined limit.
    validateNonNullStringLength(
        MessageLocationContext.create(
            monitoredResource, MonitoredResourceDescriptor.TYPE_FIELD_NUMBER),
        "monitored resource type",
        monitoredResource.getType());
    // - Monitored resource display name cannot be longer than predefined limit.
    validateStringLength(
        MessageLocationContext.create(
            monitoredResource, MonitoredResourceDescriptor.DISPLAY_NAME_FIELD_NUMBER),
        "monitored resource display name",
        monitoredResource.getDisplayName());
    // - Monitored resource description cannot be longer than predefined limit.
    validateStringLength(
        MessageLocationContext.create(
            monitoredResource, MonitoredResourceDescriptor.DESCRIPTION_FIELD_NUMBER),
        "monitored resource description",
        monitoredResource.getDescription());

    // - Label key value cannot be empty while the label keys list can be empty.
    validateLabels(
        MessageLocationContext.create(
            monitoredResource, MonitoredResourceDescriptor.DISPLAY_NAME_FIELD_NUMBER),
        String.format(
            "'%s' monitored resource '%s'",
            monitoredResource.getType(), monitoredResource.getDisplayName()),
        monitoredResource.getLabelsList());
  }

  private void validateMetrics(List<MetricDescriptor> metricsList) {
    // - Metrics list cannot be longer than predefined limit.
    validateMaxListSize(
        ResolvedLocation.create(SimpleLocation.TOPLEVEL),
        "metrics list",
        metricsList,
        bounds.getMaxMetrics());
    // - Metric name must be unique across merged lists.
    for (MetricDescriptor metric : metricsList) {
      if (metrics.containsKey(metric.getName())) {
        error(
            MessageLocationContext.create(metric, MetricDescriptor.NAME_FIELD_NUMBER),
            "The '%s' metric is already defined. The metric name must be unique.",
            metric.getName());
      }
      metrics.put(metric.getName(), false);
      validateMetric(metric);
    }
  }

  private void validateMetric(MetricDescriptor metric) {
    // - Metric name cannot be empty.
    // - Metric name cannot be longer than predefined limit.
    validateNonNullStringLength(
        MessageLocationContext.create(metric, MetricDescriptor.NAME_FIELD_NUMBER),
        "metric name",
        metric.getName());
    // - Metric display name cannot be longer than predefined limit.
    validateStringLength(
        MessageLocationContext.create(metric, MetricDescriptor.DISPLAY_NAME_FIELD_NUMBER),
        "metric display name",
        metric.getDisplayName());
    // - Metric description cannot be longer than predefined limit.
    validateStringLength(
        MessageLocationContext.create(metric, MetricDescriptor.DESCRIPTION_FIELD_NUMBER),
        "metric description",
        metric.getDescription());

    // - Label key value cannot be empty while the label keys list can be empty.
    validateLabels(
        MessageLocationContext.create(metric, MetricDescriptor.NAME_FIELD_NUMBER),
        String.format("'%s' metric", metric.getName()),
        metric.getLabelsList());

    // - Metric kind must be set to one of the predefined values except for
    // METRIC_KIND_UNSPECIFIED.
    if (metric.getMetricKind() == MetricKind.METRIC_KIND_UNSPECIFIED) {
      error(
          MessageLocationContext.create(metric, MetricDescriptor.NAME_FIELD_NUMBER),
          "The metric kind of the '%s' metric is not specified. "
              + "Allowed values are GAUGE, DELTA and CUMULATIVE.",
          metric.getName());
    }
    // - Metric value type must be set to one of the predefined values except for
    // VALUE_TYPE_UNSPECIFIED.
    if (metric.getValueType() == ValueType.VALUE_TYPE_UNSPECIFIED) {
      error(
          MessageLocationContext.create(metric, MetricDescriptor.NAME_FIELD_NUMBER),
          "The metric value type of the '%s' metric is not specified. "
              + "Allowed values are BOOL, INT64, DOUBLE, STRING, DISTRIBUTION and MONEY.",
          metric.getName());
    }

    // - Metrics of type BOOL and STRING must be of GAUGE kind.
    if ((metric.getValueType() == ValueType.BOOL || metric.getValueType() == ValueType.STRING)
        && metric.getMetricKind() != MetricKind.GAUGE) {
      error(
          MessageLocationContext.create(metric, MetricDescriptor.NAME_FIELD_NUMBER),
          "The '%s' metric is of %s type and of %s kind. "
              + "Metrics of value type BOOL and STRING must be of GUAGE kind.",
          metric.getName(),
          metric.getValueType().getValueDescriptor().getName(),
          metric.getMetricKind().getValueDescriptor().getName());
    }

    metricDescriptors.put(metric.getName(), metric);
  }

  private void validateLogs(List<LogDescriptor> logsList) {
    // - Logs list cannot be longer than predefined limit.
    validateMaxListSize(
        ResolvedLocation.create(SimpleLocation.TOPLEVEL),
        "logs list",
        logsList,
        bounds.getMaxLogs());
    // - Log names must be unique across merged lists.
    for (LogDescriptor log : logsList) {
      if (logs.containsKey(log.getName())) {
        error(
            MessageLocationContext.create(log, LogDescriptor.NAME_FIELD_NUMBER),
            "The '%s' log is already defined. The log name must be unique.",
            log.getName());
      }
      logs.put(log.getName(), false);
      validateLog(log);
    }
  }

  private void validateLog(LogDescriptor log) {
    // - Log name cannot be empty.
    // - Log name cannot be longer than predefined limit.
    validateNonNullStringLength(
        MessageLocationContext.create(log, LogDescriptor.NAME_FIELD_NUMBER),
        "log name",
        log.getName());
    // - Log display name cannot be longer than predefined limit.
    validateStringLength(
        MessageLocationContext.create(log, LogDescriptor.DISPLAY_NAME_FIELD_NUMBER),
        "log display name",
        log.getDisplayName());
    // - Log description cannot be longer than predefined limit.
    validateStringLength(
        MessageLocationContext.create(log, LogDescriptor.DESCRIPTION_FIELD_NUMBER),
        "log description",
        log.getDescription());

    // - Label key value cannot be empty while the label keys list can be empty.
    validateLabels(
        MessageLocationContext.create(log, LogDescriptor.NAME_FIELD_NUMBER),
        String.format("'%s' log", log.getName()),
        log.getLabelsList());

    // Cloud Audit log names must have the allowed prefixes.
    if (log.getName().startsWith(CLOUD_AUDIT_LOG_PREFIX)) {
      validateCloudAuditLogName(
          MessageLocationContext.create(log, LogDescriptor.NAME_FIELD_NUMBER), log.getName());
    }
  }

  private void validateMonitoring(Monitoring monitoring) {
    Set<String> usedResources = new HashSet<>();
    for (MonitoringDestination destination : monitoring.getConsumerDestinationsList()) {
      validateMonitoringDestination("consumer", destination, usedResources);
    }

    // The monitored resource type can only appear in one destination within destination
    // group (producer or consumer). Clear used resources set before reusing it.
    usedResources.clear();
    for (MonitoringDestination destination : monitoring.getProducerDestinationsList()) {
      validateMonitoringDestination("producer", destination, usedResources);
    }
  }

  private void validateMonitoringDestination(
      String type, MonitoringDestination destination, Set<String> usedResources) {
    String destinationName = getDestinationName(type, destination);

    // - Monitored resource name must refer to the name defined in the monitored resources.
    if (!monitoredResources.contains(destination.getMonitoredResource())) {
      error(
          MessageLocationContext.create(
              destination, MonitoringDestination.MONITORED_RESOURCE_FIELD_NUMBER),
          "The %s refers to the monitored resource that cannot be resolved.",
          destinationName);
    }

    // - Multiple destinations cannot use the same monitored resource type.
    if (!usedResources.add(destination.getMonitoredResource())) {
      error(
          SimpleLocation.TOPLEVEL,
          "Multiple %s monitoring destinations use the same monitored resource type '%s'.",
          type,
          destination.getMonitoredResource());
    }

    // - Metrics list must not be empty.
    if (destination.getMetricsCount() == 0) {
      error(
          MessageLocationContext.create(
              destination, MonitoringDestination.MONITORED_RESOURCE_FIELD_NUMBER),
          "The %s must contain at least one metric name.",
          destinationName);
    }

    // - Metrics names list cannot be longer than predefined limit.
    validateMaxListSize(
        MessageLocationContext.create(
            destination, MonitoringDestination.MONITORED_RESOURCE_FIELD_NUMBER),
        destinationName + " metric names list",
        destination.getMetricsList(),
        bounds.getMaxMetrics());

    // - Metric names must refer to a name defined in the metrics section.
    Set<String> seenMetrics = new HashSet<>();
    for (int i = 0; i < destination.getMetricsCount(); ++i) {
      String metricName = destination.getMetrics(i);
      LocationContext location =
          MessageLocationContext.createForRepeatedByFieldName(
              destination, MonitoringDestination.METRICS_FIELD_NUMBER, i);

      if (!seenMetrics.add(metricName)) {
        error(location, "The %s uses '%s' metric more than once.", destinationName, metricName);
      }
      if (!metrics.containsKey(metricName)) {
        error(
            location,
            "The %s refers to '%s' metric that cannot be resolved.",
            destinationName,
            metricName);
      } else {
        // Mark metric name as used.
        metrics.put(metricName, true);
      }
    }

  }

  private void validateLogging(Logging logging) {
    Set<String> usedResources = new HashSet<>();
    for (LoggingDestination destination : logging.getConsumerDestinationsList()) {
      validateLoggingDestination("consumer", destination, usedResources);
    }

    // The monitored resource type can only appear in one destination within destination
    // group (producer or consumer). Clear used resources set before reusing it.
    usedResources.clear();
    for (LoggingDestination destination : logging.getProducerDestinationsList()) {
      validateLoggingDestination("producer", destination, usedResources);
    }
  }

  private void validateLoggingDestination(
      String type, LoggingDestination destination, Set<String> usedResources) {
    String destinationName = getDestinationName(type, destination);

    // - Monitored resource name must refer to the name defined in the monitored resources.
    if (!monitoredResources.contains(destination.getMonitoredResource())) {
      error(
          MessageLocationContext.create(
              destination, LoggingDestination.MONITORED_RESOURCE_FIELD_NUMBER),
          "The %s refers to the monitored resource that cannot be resolved.",
          destinationName);
    }

    // - Multiple destinations cannot use the same monitored resource type.
    if (!usedResources.add(destination.getMonitoredResource())) {
      error(
          SimpleLocation.TOPLEVEL,
          "Multiple %s logging destinations use the same monitored resource type '%s'.",
          type,
          destination.getMonitoredResource());
    }

    // - Logs list must not be empty.
    if (destination.getLogsCount() == 0) {
      error(
          MessageLocationContext.create(
              destination, LoggingDestination.MONITORED_RESOURCE_FIELD_NUMBER),
          "The %s must contain at least one log name.",
          destinationName);
    }

    // - Logs names list cannot be longer than predefined limit.
    validateMaxListSize(
        MessageLocationContext.create(
            destination, LoggingDestination.MONITORED_RESOURCE_FIELD_NUMBER),
        destinationName + " log names list",
        destination.getLogsList(),
        bounds.getMaxLogs());

    // - Log names must refer to a name defined in the metrics section.
    Set<String> seenMetrics = new HashSet<>();
    for (int i = 0; i < destination.getLogsCount(); ++i) {
      String logName = destination.getLogs(i);
      LocationContext location =
          MessageLocationContext.createForRepeatedByFieldName(
              destination, LoggingDestination.LOGS_FIELD_NUMBER, i);
      if (!seenMetrics.add(logName)) {
        error(location, "The %s uses '%s' metric more than once.", destinationName, logName);
      }
      if (!logs.containsKey(logName)) {
        error(
            location,
            "The %s refers to '%s' log that cannot be resolved.",
            destinationName,
            logName);
      } else {
        // Mark log name as used.
        logs.put(logName, true);
      }
    }

  }

  private void validateBilling(Billing billing) {
    validateBillingMonitoredResourceIsNotDoubleReference(billing);

    for (BillingDestination destination : billing.getConsumerDestinationsList()) {
      validateBillingDestination(destination);
    }

  }

  private void validateBillingMonitoredResourceIsDefined(BillingDestination destination) {
    String destinationName = getDestinationName(destination);

    // - Monitored resource name must refer to the name defined in the monitored resources.
    if (!monitoredResources.contains(destination.getMonitoredResource())) {
      error(
          MessageLocationContext.create(
              destination, BillingDestination.MONITORED_RESOURCE_FIELD_NUMBER),
          "The %s refers to the monitored resource that cannot be resolved.",
          destinationName);
    }
  }

  private void validateBillingMonitoredResourceIsNotDoubleReference(Billing billing) {
    // - Multiple destinations cannot use the same monitored resource type.
    Set<String> usedResources = new HashSet<>();
    for (BillingDestination destination : billing.getConsumerDestinationsList()) {
      if (!usedResources.add(destination.getMonitoredResource())) {
        error(
            SimpleLocation.TOPLEVEL,
            "Multiple consumer billing destinations use the same monitored resource type '%s'.",
            destination.getMonitoredResource());
      }
    }
  }

  private void validateBillingMetrics(
      List<String> metricNames,
      Message component,
      String componentName,
      int componentFieldNumber,
      int metricFieldNumber) {
    // - Metrics names list cannot be longer than predefined limit.
    validateMaxListSize(
        MessageLocationContext.create(component, componentFieldNumber),
        componentName + " metric names list",
        metricNames,
        bounds.getMaxMetrics());

    // - Metric names must refer to a name defined in the metrics section.
    Set<String> seenMetrics = new HashSet<>();
    for (int i = 0; i < metricNames.size(); i++) {
      String metricName = metricNames.get(i);
      LocationContext location =
          MessageLocationContext.createForRepeatedByFieldName(component, metricFieldNumber, i);

      if (!seenMetrics.add(metricName)) {
        error(location, "The %s uses '%s' metric more than once.", componentName, metricName);
      }
      if (!metrics.containsKey(metricName)) {
        error(
            location,
            "The %s refers to '%s' metric that cannot be resolved.",
            componentName,
            metricName);
      } else {
        // Mark metric name as used.
        metrics.put(metricName, true);
      }
    }
  }

  private void validateBillingMonitoredResouceMetrics(BillingDestination destination) {
    String destinationName = getDestinationName(destination);

    // - Metrics list must not be empty.
    if (destination.getMetricsCount() == 0) {
      error(
          MessageLocationContext.create(
              destination, BillingDestination.MONITORED_RESOURCE_FIELD_NUMBER),
          "The %s must contain at least one metric name.",
          destinationName);
    }

    validateBillingMetrics(
        destination.getMetricsList(),
        destination,
        destinationName,
        BillingDestination.MONITORED_RESOURCE_FIELD_NUMBER,
        BillingDestination.METRICS_FIELD_NUMBER);
  }

  private void validateBillingDestination(BillingDestination destination) {
    validateBillingMonitoredResourceIsDefined(destination);
    validateBillingMonitoredResouceMetrics(destination);
  }

  private static String getDestinationName(BillingDestination destination) {
    return String.format(
        "The 'consumer' billing destination for '%s' resource", destination.getMonitoredResource());
  }

  private static String getDestinationName(String type, MonitoringDestination destination) {
    return String.format(
        "The '%s' monitoring destination for '%s' resource",
        type, destination.getMonitoredResource());
  }

  private static String getDestinationName(String type, LoggingDestination destination) {
    return String.format(
        "The '%s' logging destination for '%s' resource", type, destination.getMonitoredResource());
  }

  private void validateMetricsAndLogsUsed() {
    validateAllEntitiesUsed("metric", UNUSED_METRICS_RULE, metrics);
    validateAllEntitiesUsed("log", UNUSED_LOGS_RULE, logs);
  }

  private void validateAllEntitiesUsed(
      String entityType, String lintRule, Map<String, Boolean> entities) {
    // - All defined entities must be referenced by other subsections of the service
    // service control config, unused entities will be reported as warnings.
    List<String> unused = new ArrayList<>();
    for (Map.Entry<String, Boolean> entity : entities.entrySet()) {
      // Check if the entity was used or not.
      if (!entity.getValue()) {
        unused.add(String.format("'%s'", entity.getKey()));
      }
    }
    Collections.sort(unused);
    if (unused.size() > 0) {
      // Report unused resource entities.
      warning(
          lintRule,
          ResolvedLocation.create(SimpleLocation.TOPLEVEL),
          "The %s(s) %s are not used.",
          entityType,
          Joiner.on(", ").join(unused));
    }
  }

  private void validateLabels(
      LocationContext location, String fieldRef, List<LabelDescriptor> labelsList) {
    // - Labels list cannot be longer than predefined limit.
    validateMaxListSize(
        location,
        String.format("label list of the %s", fieldRef),
        labelsList,
        bounds.getMaxLabels());
    for (LabelDescriptor label : labelsList) {
      validateLabel(location, fieldRef, label);
    }
  }

  private void validateLabel(LocationContext location, String fieldRef, LabelDescriptor label) {
    // - Label key cannot be empty.
    // - Label key cannot be longer than predefined limit.
    validateNonNullStringLength(location, String.format("%s label key", fieldRef), label.getKey());
    // - Label description cannot be longer than predefined limit.
    validateStringLength(
        location, String.format("%s label description", fieldRef), label.getDescription());
  }

  private void validateMaxListSize(
      LocationContext loc, String fieldRef, Collection<?> list, int maxSize) {
    if (list.size() > maxSize) {
      error(loc, "The %s has more than the allowed size of %d elements.", fieldRef, maxSize);
    }
  }

  private void validateNonNullStringLength(
      LocationContext location, String fieldRef, String labelOrName) {
    if (Strings.isNullOrEmpty(labelOrName)) {
      error(location, "The %s must not be empty.", fieldRef);
    }
    validateStringLength(location, fieldRef, labelOrName);
  }

  private void validateStringLength(LocationContext location, String fieldRef, String labelOrName) {
    if (Strings.isNullOrEmpty(labelOrName)) {
      return;
    }
    if (labelOrName.length() > bounds.getMaxStringLength()) {
      error(
          location,
          "The %s '%s' is too long. It must not be longer than %d characters.",
          fieldRef,
          labelOrName,
          bounds.getMaxStringLength());
    }
  }

  private void validateCloudAuditLogName(LocationContext location, final String logName) {
    boolean isAllowed =
        Iterables.any(
            allowedCloudAuditLogNamePrefixes,
            new Predicate<String>() {
              @Override
              public boolean apply(String allowedPrefix) {
                return logName.startsWith(allowedPrefix);
              }
            });
    if (!isAllowed) {
      error(
          location,
          "%s is not a valid Cloud Audit log name. Valid Cloud Audit log name prefixes are: %s.",
          logName,
          Joiner.on(", ").join(allowedCloudAuditLogNamePrefixes));
    }
  }

  private void error(Location location, String message, Object... params) {
    configAspect.error(location, message, params);
  }

  private void error(LocationContext location, String message, Object... params) {
    configAspect.error(location, message, params);
  }

  private void warning(String rule, LocationContext location, String message, Object... params) {
    String fullMessage =
        LintRule.formatLintWarning(message, rule, configAspect.getAspectName(), params);
    configAspect.getDiagReporter().reportWarning(location, fullMessage);
  }
}
