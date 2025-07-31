/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.aws.s3;

import java.io.Serializable;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.base.Splitter;
import org.apache.iceberg.util.PropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolver for S3 path overrides that allows transparent remapping of S3 paths at runtime. This is
 * useful for disaster recovery scenarios where data is replicated to different regions/buckets and
 * paths in metadata files need to be dynamically remapped.
 */
public class S3PathOverrideResolver implements Serializable {
  private static final Logger LOG = LoggerFactory.getLogger(S3PathOverrideResolver.class);

  private final boolean enabled;
  private final Map<String, PathMapping> mappings;

  /** Represents a source-to-target path mapping. */
  public static class PathMapping implements Serializable {
    private final String sourcePrefix;
    private final String targetPrefix;

    PathMapping(String sourcePrefix, String targetPrefix) {
      Preconditions.checkNotNull(sourcePrefix, "Source prefix cannot be null");
      Preconditions.checkNotNull(targetPrefix, "Target prefix cannot be null");
      this.sourcePrefix = normalizePrefix(sourcePrefix);
      this.targetPrefix = normalizePrefix(targetPrefix);
    }

    private static String normalizePrefix(String prefix) {
      return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    boolean matches(String path) {
      return path != null && path.startsWith(sourcePrefix);
    }

    String remap(String path) {
      return targetPrefix + path.substring(sourcePrefix.length());
    }

    public String sourcePrefix() {
      return sourcePrefix;
    }

    public String targetPrefix() {
      return targetPrefix;
    }
  }

  /**
   * Creates a new S3PathOverrideResolver from the given properties.
   *
   * @param properties configuration properties
   */
  public S3PathOverrideResolver(Map<String, String> properties) {
    this.enabled =
        PropertyUtil.propertyAsBoolean(
            properties,
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED,
            S3FileIOProperties.S3_PATH_OVERRIDE_ENABLED_DEFAULT);
    this.mappings = parseMappings(properties);

    if (enabled && !mappings.isEmpty()) {
      LOG.info("S3 path override enabled with {} mapping(s)", mappings.size());
      mappings.forEach(
          (source, mapping) ->
              LOG.info("  {} -> {}", mapping.sourcePrefix(), mapping.targetPrefix()));
    }
  }

  private Map<String, PathMapping> parseMappings(Map<String, String> properties) {
    // Sort by prefix length (descending) to ensure longer prefixes match first
    Map<String, PathMapping> result = new TreeMap<>(
        Comparator.comparing(String::length).reversed().thenComparing(Comparator.naturalOrder()));

    if (!enabled) {
      return result;
    }

    Map<String, String> mappingProps =
        PropertyUtil.propertiesWithPrefix(properties, S3FileIOProperties.S3_PATH_OVERRIDE_PREFIX);

    // Group mappings by ID
    Map<String, Map<String, String>> mappingById = new TreeMap<>();
    for (Map.Entry<String, String> entry : mappingProps.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();

      // Parse mapping ID and property type (source/target)
      List<String> parts = Splitter.on('.').splitToList(key);
      if (parts.size() >= 2) {
        String mappingId = parts.get(0);
        String propType = parts.get(1);

        mappingById.computeIfAbsent(mappingId, k -> new TreeMap<>()).put(propType, value);
      }
    }

    // Create PathMapping objects
    for (Map.Entry<String, Map<String, String>> entry : mappingById.entrySet()) {
      String mappingId = entry.getKey();
      Map<String, String> props = entry.getValue();

      String source = props.get("source");
      String target = props.get("target");

      if (source != null && target != null) {
        PathMapping mapping = new PathMapping(source, target);
        result.put(mapping.sourcePrefix(), mapping);
      } else {
        LOG.warn(
            "Ignoring incomplete path override mapping '{}': source={}, target={}",
            mappingId,
            source,
            target);
      }
    }

    return result;
  }

  /**
   * Resolves the given path according to configured mappings.
   *
   * @param originalPath the original S3 path
   * @return the remapped path, or the original path if no mapping applies
   */
  public String resolvePath(String originalPath) {
    if (!enabled || originalPath == null || mappings.isEmpty()) {
      return originalPath;
    }

    // Find the first matching mapping (ordered by source prefix for deterministic behavior)
    for (PathMapping mapping : mappings.values()) {
      if (mapping.matches(originalPath)) {
        String remappedPath = mapping.remap(originalPath);
        LOG.debug("Remapped path from {} to {}", originalPath, remappedPath);
        return remappedPath;
      }
    }

    return originalPath;
  }

  /**
   * Returns whether path override is enabled.
   *
   * @return true if path override is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns the number of configured mappings.
   *
   * @return number of mappings
   */
  public int mappingCount() {
    return mappings.size();
  }
}
