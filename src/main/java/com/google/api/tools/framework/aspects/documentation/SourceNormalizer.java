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

package com.google.api.tools.framework.aspects.documentation;

import com.google.api.tools.framework.aspects.documentation.source.CodeBlock;
import com.google.api.tools.framework.aspects.documentation.source.FileInclusion;
import com.google.api.tools.framework.aspects.documentation.source.Instruction;
import com.google.api.tools.framework.aspects.documentation.source.SectionHeader;
import com.google.api.tools.framework.aspects.documentation.source.SourceParser;
import com.google.api.tools.framework.aspects.documentation.source.SourceRoot;
import com.google.api.tools.framework.aspects.documentation.source.SourceVisitor;
import com.google.api.tools.framework.aspects.documentation.source.Text;
import com.google.api.tools.framework.model.DiagReporter;
import com.google.api.tools.framework.model.DiagReporter.LocationContext;
import com.google.api.tools.framework.model.DiagReporter.ResolvedLocation;
import com.google.api.tools.framework.model.Element;
import com.google.api.tools.framework.model.SimpleLocation;
import com.google.api.tools.framework.util.VisitsAfter;
import com.google.api.tools.framework.util.VisitsBefore;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Set;

/**
 * Normalizes documentation source by substituting file inclusion instructions with external
 * content.
 */
class SourceNormalizer implements DocumentationProcessor {

  private static final Joiner ARROW_JOINER = Joiner.on(" -> ");

  private final DiagReporter diagResolver;
  private final String docPath;

  public SourceNormalizer(DiagReporter diagReporter, String docPath) {
    Preconditions.checkNotNull(diagReporter, "diagCollector should not be null.");
    this.diagResolver = diagReporter;
    this.docPath = docPath;
  }

  /**
   * Normalizes documentation source by substituting file inclusion instructions with external
   * content. The heading levels of external content will be adjusted based on parent's heading
   * level so that they will be nested headings of parent. For example:
   *
   * <pre>
   *   docset1.md file:
   *   # Docset 1
   *    (== include docset2.md ==)
   *
   *   docset2.md file:
   *   # Docset2
   *
   *   After the normalization, the result would be:
   *   # Docset1
   *   ## Docset2
   * </pre>
   *
   * @return normalized source, or source if errors detected
   */
  @Override
  public String process(String source, LocationContext sourceLocation, Element element) {
    if (Strings.isNullOrEmpty(source)) {
      return source;
    }
    Normalizer normalizer = new Normalizer();
    String result = normalizer.normalize(source, sourceLocation, element);
    return result;
  }

  /** Helper class to do actual normalization. */
  private class Normalizer extends SourceVisitor {

    /** Tracks the file inclusion chain to detect cyclic inclusion. */
    private final Set<String> fileInclusionPath = Sets.newLinkedHashSet();

    /** Stack of base section levels used for adjusting section levels for included content. */
    private final Deque<Integer> baseSectionLevels = new LinkedList<>(ImmutableList.of(0));

    private final StringBuilder builder = new StringBuilder();
    private LocationContext location = ResolvedLocation.create(SimpleLocation.UNKNOWN);
    private Element element;

    private String normalize(String source, LocationContext location, Element element) {
      Preconditions.checkNotNull(source, "source should not be null.");
      Preconditions.checkNotNull(location, "location should not be null.");
      this.element = element;
      int errorCount = diagResolver.getDiagCollector().getErrorCount();
      SourceParser parser = new SourceParser(source, location, diagResolver, docPath);
      SourceRoot root = parser.parse();
      LocationContext savedLocation = this.location;
      this.location = location;
      visit(root);
      this.location = savedLocation;
      return diagResolver.getDiagCollector().getErrorCount() > errorCount
          ? source
          : builder.toString();
    }

    /** Visits {@link Text} element to append its content directly to the normalized result. */
    @VisitsBefore
    void normalize(Text text) {
      builder.append(text.getContent());
    }

    /** Visits {@link CodeBlock} element to append its content directly to the normalized result. */
    @VisitsBefore
    void normalize(CodeBlock codeBlock) {
      builder.append(codeBlock.getContent());
    }

    /** Visits {@link Instruction} element, evaluating it and appending content (usually empty). */
    @VisitsBefore
    void normalize(Instruction instruction) {
      instruction.evalute(element);
      builder.append(instruction.getContent());
    }

    /**
     * Visits {@link FileInclusion} element to recursively resolve the file reference and append the
     * external content to the normalized result.
     */
    @VisitsBefore
    boolean normalize(FileInclusion inclusion) {
      String filePath = inclusion.getRelativeFilePath();

      // Stop visiting if cyclic file inclusion is detected.
      if (fileInclusionPath.contains(filePath)) {
        String path = ARROW_JOINER.join(fileInclusionPath);
        diagResolver.reportError(
            ResolvedLocation.create(SimpleLocation.TOPLEVEL),
            "Cyclic file inclusion detected for '%s' via %s",
            filePath,
            path);
        return false;
      }
      String content = inclusion.getContent();
      if (Strings.isNullOrEmpty(content)) {
        return false;
      }

      // Save state before normalizing included content.
      fileInclusionPath.add(filePath);
      baseSectionLevels.addLast(baseSectionLevels.peekLast() + inclusion.getSectionLevel());
      normalize(
          content, ResolvedLocation.create(new SimpleLocation(inclusion.getFileName())), element);
      builder.append('\n');
      return true;
    }

    /** Cleans up state after visiting the {@link FileInclusion} elements. */
    @VisitsAfter
    void afterNormalize(FileInclusion inclusion) {
      fileInclusionPath.remove(inclusion.getRelativeFilePath());
      baseSectionLevels.removeLast();
    }

    /**
     * Visits the {@link SectionHeader} element to adjust the heading level according to the current
     * base heading level.
     */
    @VisitsBefore
    void normalize(SectionHeader header) {
      int sectionLevel = baseSectionLevels.peekLast() + header.getLevel();
      builder.append(
          String.format("%s %s\n\n", Strings.repeat("#", sectionLevel), header.getText()));
    }
  }
}
