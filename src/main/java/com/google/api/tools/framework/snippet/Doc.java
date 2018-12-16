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

package com.google.api.tools.framework.snippet;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nullable;

/**
 * A Java implementation of a Wadler-Lindig pretty printer.
 *
 * <p>The pretty printer takes documents formed using a small set of operators, and formats them as
 * strings suitable for display in a fixed number of columns.
 *
 * <p>A pretty-printer document (or {@code Doc}) is an immutable object that represents a piece of
 * formatted text. A programmer can use the combinators {@link #EMPTY} and {@link #text text()}
 * to construct documents, and can concatenate documents with {@link #append append()}. For example:
 * <pre>
 * append(text("Good"), text("Morning"), text("World"))
 * </pre>
 * pretty-prints as:
 * <pre>
 * GoodMorningWorld
 * </pre>
 *
 * <p>Line breaking is controlled using the {@link #BREAK} and {@link #group group()} combinators.
 * Within a {@link #group group()} combinator, all breaks are printed using a common breaking
 * policy, described using a {@link GroupKind}. For example:
 * <pre>
 * group(HORIZONTAL, append(text("Good"), BREAK, text("Morning"), BREAK, text("World"))
 * </pre>
 * pretty-prints as
 * <pre>
 * Good Morning World
 * </pre>
 * whereas
 * <pre>
 * group(VERTICAL, append(text("Good"), BREAK, text("Morning"), BREAK, text("World"))
 * </pre>
 * pretty-prints as:
 * <pre>
 * Good
 * Morning
 * World
 * </pre>
 *
 * <p>A key reason for the pretty-printer's expressive power is the automatic group kind
 * ({@link GroupKind#AUTO}), which allows us to specify pretty-printing alternatives. Breaks in an
 * automatic group are all printed as spaces, if the result would not overflow a single line, or as
 * newlines, otherwise; the pretty-printer makes the appropriate choice at layout time depending on
 * the space available.
 *
 * <p>The combinators {@link #nest nest()}, {@link #align align()}, and {@link #hang hang()}
 * control indentation. For instance, the {@link #nest nest()} combinator increases the indentation
 * on new-lines by a number of columns. For example:
 * <pre>
 * nest(2, group(VERTICAL, append(text("Good"), BREAK, text("Morning"), BREAK, text("World")))
 * </pre>
 * pretty-prints as:
 * <pre>
 * Good
 *   Morning
 *   World
 * </pre>
 *
 * <p>Many methods of this class come in two variants:
 * <ul>
 * <li>a static method, written {@code Doc.group(xyz)}, and
 * <li>an instance method, written {@code xyz.group()}.
 * </ul>
 * The two may be used interchangeably, although the prefix (static) version usually leads to more
 * readable code.
 *
 * <p>Wadler's original pretty printer is implemented in Haskell [1]. The original Wadler version
 * relies on lazy evaluation, which Java does not have, so this implementation is based on Lindig's
 * strict version in Objective Caml [2].
 *
 * <p>References:
 * <ul>
 * <li>[1] Philip Wadler. "A Prettier Printer". Journal of Functional Programming, 1999.
 * <li>[2] Christian Lindig. "Strictly Pretty".
 * </ul>
 *
 * <p>This library is thread-safe.
 *
 */
public abstract class Doc {
  /** Size of the default indentation step in characters. */
  public static final int DEFAULT_INDENT = 2;

  /** The default number of columns to format for if no width is specified. */
  public static final int DEFAULT_WIDTH = 80;

  // Document combinators.

  /**
   * Returns a document representing the literal text {@code text}. Assumes that there are no
   * newline characters in the representation. (If there are new-line characters the pretty-printer
   * will print them as-is, usually leading to poorly formatted output.) For example,
   * {@code text("hello")} prints as {@code "hello"}.
   */
  public static Doc text(String text) {
    return new Text(text);
  }

  /** The empty document, which prints as {@code ""} (the empty string). */
  public static final Doc EMPTY = text("");

  // Convenience constants for common punctuation characters.
  public static final Doc COMMA = text(",");
  public static final Doc LBRACKET = text("[");
  public static final Doc RBRACKET = text("]");
  public static final Doc LPAREN = text("(");
  public static final Doc RPAREN = text(")");
  public static final Doc LBRACE = text("{");
  public static final Doc RBRACE = text("}");
  public static final Doc LANGLE = text("<");
  public static final Doc RANGLE = text(">");
  public static final Doc SEMI = Doc.text(";");

  private static final Doc COMMA_AND_BREAK = Doc.text(",").add(breakWith(" "));
  private static final Doc SPACE = Doc.text(" ");

  // White space (excluding breaks) generated by the pretty printer. This
  // is currently just a simple space.
  private static final CharMatcher WHITESPACE = CharMatcher.anyOf(" ");

  /** Applies the {@code text()} combinator to a sequence of strings. */
  public static List<Doc> texts(Iterable<String> strings) {
    List<Doc> results = Lists.newArrayList();
    for (String s : strings) {
      results.add(text(s));
    }
    return results;
  }

  /** Applies the {@code text()} combinator to a sequence of strings. */
  public static List<Doc> texts(String... strings) {
    return texts(Arrays.asList(strings));
  }

  /**
   * Splits the string {@code input} up into words, and places each word into a literal
   * {@link #text text()} document, separated by {@link #BREAK} objects.
   */
  private static final Splitter TO_WORDS =
      Splitter.on(CharMatcher.breakingWhitespace()).omitEmptyStrings();
  public static Doc words(String input) {
    return join(texts(TO_WORDS.split(input)));
  }

  /**
   * Returns a break, which will be printed according to the policy of the closest enclosing group.
   *
   * <p>The {@code representation} argument describes how the break should be printed in horizontal
   * mode; the representation is ignored in vertical mode. Assumes that there are no newline
   * characters in the representation. For example:
   * <ul>
   * <li>{@code group(HORIZONTAL, breakWith("-"))} prints as {@code "-"}
   * <li>{@code group(VERTICAL, breakWith("-"))}   prints as {@code "\n"}
   * </ul>
   */
  public static Doc breakWith(String representation) {
    return new Break(representation);
  }

  /**
   * The default break, which is printed either as a space in horizontal mode, or as a newline in
   * vertical mode. For example:
   * <ul>
   * <li>{@code group(HORIZONTAL, BREAK)} prints as {@code " "}
   * <li>{@code group(VERTICAL, BREAK)}   prints as {@code "\n"}
   * </ul>
   */
  public static final Doc BREAK = breakWith(" ");

  /**
   * A break that is printed either as the empty string in horizontal mode, or as a newline in
   * vertical mode.
   * <ul>
   * <li>{@code group(HORIZONTAL, SOFT_BREAK)} prints as {@code ""}
   * <li>{@code group(VERTICAL, SOFT_BREAK)}   prints as {@code "\n"}
   * </ul>
   */
  public static final Doc SOFT_BREAK = breakWith("");

  /**
   * Appends document {@code that} to the document, with no breaks between documents. For example,
   * {@code text("a").add(text("b c"))} prints as {@code "ab c"}.
   */
  public Doc add(Doc that) {
    if (this == Doc.EMPTY) {
      return that;
    }
    if (that == Doc.EMPTY) {
      return this;
    }
    return new Concat(this, that);
  }

  /** Appends a list of documents, with no breaks between documents. */
  public static Doc append(Doc... docs) {
    return append(Arrays.asList(docs));
  }

  /** Appends a list of documents, with no breaks between documents. */
  public static Doc append(Iterable<Doc> docs) {
    Doc output = EMPTY;
    for (Doc doc : docs) {
      output = output.add(doc);
    }
    return output;
  }

  /**
   * Appends documents, placing the separator {@code separator} between adjacent documents.
   * For example,
   * <pre>
   * joinWith(append(COMMA, BREAK), text("a"), text("b"), text("c")))
   * </pre>
   * prints as {@code "a, b, c"} in horizontal mode, or as {@code "a,\nb,\nc"} in vertical mode.
   */
  public static Doc joinWith(Doc separator, Doc... docs) {
    return joinWith(separator, Arrays.asList(docs));
  }

  /** Appends documents, placing the separator {@code separator} between adjacent documents. */
  public static Doc joinWith(Doc separator, Iterable<Doc> docs) {
    Doc output = EMPTY;
    boolean first = true;
    for (Doc doc : docs) {
      if (!first) {
        output = output.add(separator);
      }
      output = output.add(doc);
      first = false;
    }
    return output;
  }

  /** Appends documents, placing a {@link #BREAK} between adjacent documents. */
  public static Doc join(Doc... docs) {
    return joinWith(BREAK, Arrays.asList(docs));
  }

  /** Appends documents, placing a {@link #BREAK} between adjacent documents. */
  public static Doc join(Iterable<Doc> docs) {
    return joinWith(BREAK, docs);
  }

  /** Describes how the breaks within a {@link #group group()} should be printed. */
  public enum GroupKind {
    /** Breaks are printed as spaces. */
    HORIZONTAL,

    /** Breaks are printed as newlines. */
    VERTICAL,

    /**
     * The breaks in the group are all printed as spaces, if the result would not overflow a single
     * line, or as newlines, otherwise.
     */
    FILL,

    /**
     * Each break is printed as a space, if that would not lead to overflow, or as a newline,
     * otherwise. A different decision is made for each break.
     */
    AUTO
  }

  /**
   * Encloses document {@code child} in a group. Breaks are formatted according to the policy of the
   * closest enclosing group.
   *
   * <p>For example, consider formatting the document
   * <pre>
   * group(p, join(break(), text("xx"), text("yy"), text("zz"), text("ww")))
   * </pre>
   * under different policies {@code p} for different column widths.
   *
   * Under policy {@link GroupKind#HORIZONTAL}, all breaks in a group are printed as spaces,
   * regardless of whether that leads to an overflow:
   * <ul>
   * <li>Width 3:  {@code "xx yy zz ww"}
   * <li>Width 80: {@code "xx yy zz ww"}
   * </ul>
   *
   * Under policy {@link GroupKind#VERTICAL}, all breaks are printed as newlines:
   * <ul>
   * <li>Width 3:  {@code "xx\nyy\nzz\nww"}
   * <li>Width 80: {@code "xx\nyy\nzz\nww"}
   * </ul>
   *
   * Under policy {@link GroupKind#AUTO}, all breaks in the group are printed as spaces if the
   * result would not overflow a line, or as newlines, otherwise:
   * <ul>
   * <li>Width 3:  {@code "xx\nyy\nzz\nww"}
   * <li>Width 80: {@code "xx yy zz ww"}
   * </ul>
   *
   * Under policy {@link GroupKind#FILL}, each break is printed as a space if that would not lead to
   * overflow, or as a newline, otherwise. Unlike {@link GroupKind#AUTO}, a separate decision is
   * made for each break.
   * <ul>
   * <li>Width 3:  {@code "xx\nyy\nzz\nww"}
   * <li>Width 5:  {@code "xx yy\nzz ww"}
   * <li>Width 80: {@code "xx yy zz ww"}
   * </ul>
   */
  public static Doc group(GroupKind kind, Doc child) {
    return new Group(child, kind);
  }

  /** Encloses document {@code child} in an {@link GroupKind#AUTO} group. */
  public static Doc group(Doc child) {
    return group(GroupKind.AUTO, child);
  }

  /** Encloses document {@code child} in an {@link GroupKind#VERTICAL} group. */
  public static Doc vgroup(Doc child) {
    return group(GroupKind.VERTICAL, child);
  }

  /** Encloses document {@code child} in an {@link GroupKind#HORIZONTAL} group. */
  public static Doc hgroup(Doc child) {
    return group(GroupKind.HORIZONTAL, child);
  }

  /** Encloses document {@code child} in an {@link GroupKind#FILL} group. */
  public static Doc fgroup(Doc child) {
    return group(GroupKind.FILL, child);
  }

  /** Encloses the document in an {@link GroupKind#AUTO} group. */
  public Doc group() {
    return group(this);
  }

  /** Encloses the document in an {@link GroupKind#VERTICAL} group. */
  public Doc vgroup() {
    return vgroup(this);
  }

  /** Encloses the document in an {@link GroupKind#HORIZONTAL} group. */
  public Doc hgroup() {
    return hgroup(this);
  }

  /** Encloses the document in an {@link GroupKind#FILL} group. */
  public Doc fgroup() {
    return fgroup(this);
  }

  /** Encloses the document in an group of a given {@code kind}. */
  public Doc group(GroupKind kind) {
    return group(kind, this);
  }

  /**
   * Increases the indentation level of document {@code child} by {@code indent} columns.
   *
   * <p>After each vertical break, a number of spaces equal to the current indentation level is
   * added. For example:
   * <pre>
   * nest(2, group(VERTICAL, join(BREAK, text("x"), text("y"), text("z"))))
   * </pre>
   * prints as:
   * <pre>
   * x
   *   y
   *   z
   * </pre>
   * <p>Note that indentation is only relevant for vertical breaks; for example
   * <pre>
   * nest(2, group(HORIZONTAL, join(BREAK, text("x"), text("y"), text("z"))))
   * </pre>
   * prints as:
   * <pre>
   * x y z
   * </pre>
   * Indentation is cumulative, for example:
   * <pre>
   * nest(2, nest(2, group(VERTICAL,
   *                       join(BREAK, text("x"), text("y"), text("z")))))
   * </pre>
   * prints as:
   * <pre>
   * x
   *     y
   *     z
   * </pre>
   */
  public static Doc nest(int indent, Doc child) {
    return new Nest(indent, child);
  }

  /** Increases the indentation level of the document by {@code indent} columns. */
  public Doc nest(int indent) {
    return nest(indent, this);
  }

  /** Increase the indentation level of document {@code child} by {@link #DEFAULT_INDENT} spaces. */
  public static Doc nest(Doc child) {
    return new Nest(DEFAULT_INDENT, child);
  }

  /** Increase the indentation level of the document by {@link #DEFAULT_INDENT} spaces. */
  public Doc nest() {
    return nest(this);
  }

  /**
   * Aligns breaks in document {@code child} at the current column. For example:
   * <pre>
   * append(text("alist = ["),
   *        align(join(append(text(","), BREAK),
   *                   text("x"), text("y"), text("z"))),
   *        text("]"))
   * </pre>
   * prints in horizontal mode as:
   * <pre>
   * alist = [x, y, z]
   * </pre>
   * and prints in vertical mode as:
   * <pre>
   * alist = [x,
   *          y,
   *          z]
   * </pre>
   */
  public static Doc align(Doc child) {
    return new Align(child);
  }

  /** Aligns breaks in the document at the current column. */
  public Doc align() {
    return align(this);
  }

  /**
   * Performs a hanging indentation of document {@code child} with an indent of {@code indent}
   * spaces. For example
   * <pre>
   * append(text("alist = ["),
   *        hang(join(append(text(","), BREAK),
   *                  text("x"), text("y"), text("z"))),
   *        text("]"))
   * </pre>
   * prints in vertical mode as:
   * <pre>
   * alist = [x,
   *            y,
   *            z]
   * </pre>
   * {@code hang(indent, child)} is a shorthand for {@code align(nest(indent, child))}.
   */
  public static Doc hang(int indent, Doc child) {
    return nest(indent, child).align();
  }

  /**
   * Performs a hanging indentation of document {@code child} with an indent of
   * {@link #DEFAULT_INDENT} spaces.
   */
  public static Doc hang(Doc child) {
    return hang(DEFAULT_INDENT, child);
  }

  /** Performs a hanging indentation of the document with an indent of {@code indent} spaces. */
  public Doc hang(int indent) {
    return hang(indent, this);
  }

  /**
   * Performs a hanging indentation of the document with an indent of {@link #DEFAULT_INDENT}
   * spaces.
   */
  public Doc hang() {
    return hang(this);
  }

  /**
   * Sets the indentation level of document {@code child} to {@code indent} columns.
   */
  public static Doc indentAt(int indent, Doc child) {
    return new IndentAt(indent, child);
  }

  /** Sets the indentation level of the document to {@code indent} columns. */
  public Doc indentAt(int indent) {
    return indentAt(indent, this);
  }

  /**
   * Wraps document {@code child} in brackets.
   * For example, {@code brackets(text("x"))} prints as {@code "[x]"}.
   */
  public static Doc brackets(Doc child) {
    return LBRACKET.add(child).add(RBRACKET);
  }

  /** Wraps the document in brackets. */
  public Doc brackets() {
    return brackets(this);
  }

  /**
   * Wraps document {@code child} in parentheses.
   * For example, {@code parens(text("x"))} prints as {@code "(x)"}.
   */
  public static Doc parens(Doc child) {
    return LPAREN.add(child).add(RPAREN);
  }

  /** Wraps the document in parentheses. */
  public Doc parens() {
    return parens(this);
  }

  /**
   * Wraps document {@code child} in braces.
   * For example, {@code braces(text("x"))} prints as {@code "{x}"}.
   */
  public static Doc braces(Doc child) {
    return LBRACE.add(child).add(RBRACE);
  }

  /** Wraps the document in braces. */
  public Doc braces() {
    return braces(this);
  }

  /**
   * Wraps document {@code child} in angle brackets.
   * For example, {@code angles(text("x"))} prints as {@code "<x>"}.
   */
  public static Doc angles(Doc child) {
    return LANGLE.add(child).add(RANGLE);
  }

  /** Wraps the document in angle brackets. */
  public Doc angles() {
    return angles(this);
  }

  /**
   * ANSI color codes.
   */
  public enum AnsiColor {
    RED("\033[31m"), MAGENTA("\033[35m"), YELLOW("\033[33m"), DEFAULT(""), RESET("\033[0m");

    private final String code;
    private AnsiColor(String code) {
      this.code = code;
    }
    public String code() {
      return code;
    }
  }

  /**
   * Colors document {@code child} using the specified ANSI color code.
   *
   * TODO(user): nested colors do not work.
   */
  public static Doc color(AnsiColor c, Doc child) {
    return (new Text(c.code(), 0))
        .add(child)
        .add(new Text(AnsiColor.RESET.code(), 0));
  }

  /** Colors the document using the specified ANSI color code. */
  public Doc color(AnsiColor c) {
    return color(c, this);
  }

  /** Appends a separator to all documents except the last one in a list. */
  public static List<Doc> punctuate(Doc separator, List<Doc> docs) {
    List<Doc> output = Lists.newArrayList();
    for (int i = 0; i < docs.size(); i++) {
      if (i < docs.size() - 1) {
        output.add(docs.get(i).add(separator));
      } else {
        output.add(docs.get(i));
      }
    }
    return output;
  }

  /**
   * Returns a builder for a block which breaks vertically as
   * <pre>
   *   header
   *     line
   *     line
   *     ...
   *   [footer]
   * </pre>
   */
  public static BlockBuilder blockBuilder(Doc header, int indent) {
    return new BlockBuilder(header, indent);
  }

  /**
   * Returns a builder for a block with indentation of 2.
   */
  public static BlockBuilder blockBuilder(Doc header) {
    return new BlockBuilder(header, 2);
  }

  /**
   * A builder for block-like constructs.
   */
  public static class BlockBuilder {
    private final Doc header;
    private final int indent;
    private final List<Doc> lines = Lists.newArrayList();

    private BlockBuilder(Doc header, int indent) {
      this.header = header;
      this.indent = indent;
    }

    public BlockBuilder add(Doc line) {
      lines.add(line);
      return this;
    }

    public BlockBuilder addAll(Iterable<Doc> lines) {
      for (Doc line : lines) {
        this.lines.add(line);
      }
      return this;
    }

    public Doc build(@Nullable Doc footer) {
      // Build group which breaks vertically as
      //   header
      //     line
      //     line
      //     ...
      Doc headerAndLinesGroup =
          header.add(Doc.BREAK).add(Doc.join(lines)).vgroup().nest(indent);
      // Add footer if given underneath the header/lines group
      //    header
      //      line
      //      ...
      //    footer
      if (footer != null) {
        return Doc.join(headerAndLinesGroup, footer).vgroup();
      }
      return headerAndLinesGroup;
    }

    public Doc build() {
      return build(null);
    }
  }

  /**
   * Build a function invocation as
   * <pre>
   *   function ( arg1, BREAK arg2, BREAK ... argn )
   * </pre>
   * i.e. a break is only allowed after the first argument and before the last argument.
   */
  public static Doc invocation(int indent, Doc function, Iterable<Doc> arguments) {
    return function.add(Doc.parens(Doc.joinWith(COMMA_AND_BREAK, arguments).group().nest(indent)));
  }

  /**
   * Build a function invocation with indentation of 4 for arguments.
   */
  public static Doc invocation(Doc function, Iterable<Doc> arguments) {
    return invocation(4, function, arguments);
  }

  /**
   * Build a binary operator invocation as
   * <pre>
   *   left BREAK operator SPACE right
   * </pre>
   */
  public static Doc binary(int indent, Doc left, Doc operator, Doc right) {
    return left.add(Doc.BREAK).add(operator).add(SPACE).add(right).group().nest(indent);
  }

  /**
   * Builds a binary with indentation of 4.
   */
  public static Doc binary(Doc left, Doc operator, Doc right) {
    return binary(4, left, operator, right);
  }

  /**
   * Determines whether the document is empty except of whitespace.
   */
  public abstract boolean isWhitespace();

  /**
   * A layout mode describes how to break a group, and is either HORIZONTAL, VERTICAL, or FILL.
   * Layout modes are only used as part of the layout state machine; AUTO is never used as a layout
   * state.
   */
  private enum LayoutMode { HORIZONTAL, VERTICAL, FILL }

  /**
   * An agendum represents an entry in the stack of documents that need to be fitted or formatted.
   */
  private static class Agendum {
    final int indentation;
    final LayoutMode mode;
    final Doc doc;

    Agendum(int indentation, LayoutMode mode, Doc doc) {
      this.indentation = indentation;
      this.mode = mode;
      this.doc = doc;
    }
  }

  /**
   * Pretty prints the document into a {@link StringBuilder}, formatted for {@code width} columns.
   */
  public void prettyPrint(StringBuilder builder, int width) {
    Deque<Agendum> agenda = new ArrayDeque<Agendum>();
    agenda.push(new Agendum(0, LayoutMode.HORIZONTAL, this));
    int consumed = 0;
    while (!agenda.isEmpty()) {
      Agendum agendum = agenda.pop();
      consumed = agendum.doc.format(builder, agenda, width, agendum.indentation, consumed,
                                    agendum.mode);
    }
  }

  /**
   * Pretty prints the document into a {@link StringBuilder}, formatted for {@code DEFAULT_WIDTH}
   * columns.
   */
  public void prettyPrint(StringBuilder builder) {
    prettyPrint(builder, DEFAULT_WIDTH);
  }

  /**
   * Pretty prints the document, formatted for {@code width} columns. Returns the result as a
   * string.
   */
  public String prettyPrint(int width) {
    StringBuilder builder = new StringBuilder();
    prettyPrint(builder, width);
    return builder.toString();
  }

  /**
   * Pretty prints the document, formatted for {@code DEFAULT_WIDTH} columns. Returns the result as
   * a string.
   */
  public String prettyPrint() {
    return prettyPrint(DEFAULT_WIDTH);
  }

  @Override
  public String toString() {
    return prettyPrint();
  }

  /* Everything below this line is internal to the pretty printer.
   *
   * Internally, there are six ways to form a pretty-printer document.
   * Concat: the concatenation of two documents
   * Text: a literal string
   * Nest: increase the nesting level of an inner document
   * Align: align breaks to the current column
   * Break: a break, which is printed either as a space or a line break
   * Group: a group is a unit in which all breaks should be printed according
   *        to a common breaking policy.
   */

  /** The state manipulated by the fits() method. */
  private static class FitsState {
    /** Did the fits() method reach a line break or overflow the line? */
    boolean done;

    /** How many columns remain on the current line? */
    int width;

    FitsState(int width) {
      this.width = width;
      this.done = false;
    }
  }

  /**
   * Determines if this document fits in {@code state.width} columns, up to the next newline.
   * Updates {@code state.width} to the new number of columns, and sets {@code state.done} to true
   * if a newline is encountered or the line overflows.
   */
  protected abstract void fits(LayoutMode mode, FitsState state);

  /**
  * Determines whether the documents in {@code agenda} fit in {@code width} columns, up to the next
  * newline.
  */
  private static boolean agendaFits(Deque<Agendum> agenda, int width) {
    FitsState state = new FitsState(width);
    Iterator<Agendum> iterator = agenda.iterator();
    while (iterator.hasNext() && !state.done && state.width >= 0) {
      Agendum agendum = iterator.next();
      agendum.doc.fits(agendum.mode, state);
    }
    return state.width >= 0;
  }

  /**
   * Pretty-print a document into a {@link StringBuilder}. Updates the agenda, and returns an
   * updated number of consumed characters.
   *
   * @param agenda  the document layout worklist.
   * @param width  number of columns in the target layout (e.g., 80)
   * @param indentation  current number of columns of indentation.
   * @param consumed  number of columns already used in the current line.
   * @param mode  the current layout mode.
   * @return an updated number of consumed characters.
   */
  protected abstract int format(StringBuilder builder, Deque<Agendum> agenda, int width,
                                int indentation, int consumed, LayoutMode mode);

  /** The concatenation of two documents. */
  private static class Concat extends Doc {
    /** The first document. */
    final Doc left;

    /** The second document. */
    final Doc right;

    Concat(Doc left, Doc right) {
      this.left = left;
      this.right = right;
    }

    @Override
    protected void fits(LayoutMode mode, FitsState state) {
      left.fits(mode, state);
      if (!state.done) {
        right.fits(mode, state);
      }
    }

    @Override
    protected int format(StringBuilder builder, Deque<Agendum> agenda, int width, int indentation,
                         int consumed, LayoutMode mode) {
      agenda.push(new Agendum(indentation, mode, right));
      agenda.push(new Agendum(indentation, mode, left));
      return consumed;
    }

    @Override
    public boolean isWhitespace() {
      // Concat tree grows on the left (implemented in Doc.add method), therefore to shorten the
      // recursion for the below AND operation, we should check the right node first.
      return right.isWhitespace() && left.isWhitespace();
    }
  }

  /** Literal text. */
  private static class Text extends Doc {
    /** The literal text to print. */
    final String contents;

    /**
     * The width to use in size computations. By default this is simply the width of the text;
     * however when printing invisible characters (such as ANSI color codes) this may be set to
     * something different.
     */
    final int length;

    Text(String contents) {
      this.contents = contents;
      this.length = contents.length();
    }

    Text(String contents, int length) {
      this.contents = contents;
      this.length = length;
    }

    @Override
    protected void fits(LayoutMode mode, FitsState state) {
      state.width -= length;
      state.done = state.width < 0;
    }

    @Override
    protected int format(StringBuilder builder, Deque<Agendum> agenda, int width, int indentation,
                         int consumed, LayoutMode mode) {
      builder.append(contents);
      return consumed + length;
    }

    @Override
    public boolean isWhitespace() {
      return WHITESPACE.matchesAllOf(contents);
    }
  }

  /** Increase indentation level. */
  private static class Nest extends Doc {
    /** The increase in indentation. */
    final int indent;

    /** The child document to indent. */
    final Doc child;

    Nest(int indent, Doc child) {
      this.indent = indent;
      this.child = child;
    }

    @Override
    protected void fits(LayoutMode mode, FitsState state) {
      child.fits(mode, state);
    }

    @Override
    protected int format(StringBuilder builder, Deque<Agendum> agenda, int width, int indentation,
                         int consumed, LayoutMode mode) {
      agenda.push(new Agendum(indentation + indent, mode, child));
      return consumed;
    }

    @Override
    public boolean isWhitespace() {
      return child.isWhitespace();
    }
  }

  /** Align breaks to the current column */
  private static class Align extends Doc {
    /** The child document to align. */
    final Doc child;

    Align(Doc child) {
      this.child = child;
    }

    @Override
    protected void fits(LayoutMode mode, FitsState state) {
      child.fits(mode, state);
    }

    @Override
    protected int format(StringBuilder builder, Deque<Agendum> agenda, int width, int indentation,
                         int consumed, LayoutMode mode) {
      agenda.push(new Agendum(consumed, mode, child));
      return consumed;
    }

    @Override
    public boolean isWhitespace() {
      return child.isWhitespace();
    }
  }

  /** A break. */
  private static class Break extends Doc {
    /** The representation of the break in horizontal mode. Ignored in vertical mode. */
    final String representation;

    Break(String representation) {
      this.representation = representation;
    }

    @Override
    protected void fits(LayoutMode mode, FitsState state) {
      switch (mode) {
        case HORIZONTAL:
          state.width -= representation.length();
          state.done = state.width < 0;
          break;

        default:
          state.done = true;
      }
    }

    @Override
    protected int format(StringBuilder builder, Deque<Agendum> agenda, int width, int indentation,
                         int consumed, LayoutMode mode) {
      boolean horizontal;
      switch (mode) {
        case HORIZONTAL:
          horizontal = true;
          break;

        case VERTICAL:
          horizontal = false;
          break;

        default:
          horizontal = agendaFits(agenda, width - consumed - representation.length());
      }
      if (horizontal) {
        builder.append(representation);
        return consumed + representation.length();
      } else {
        // Drop trailing WS
        int i = builder.length() - 1;
        while (i >= 0 && WHITESPACE.matches(builder.charAt(i))) {
          builder.deleteCharAt(i--);
        }
        builder.append(String.format("%n"));
        builder.append(Strings.repeat(" ", indentation));
        return indentation;
      }
    }

    @Override
    public boolean isWhitespace() {
      return representation.trim().length() == 0;
    }
  }

  /** A group, in which Breaks share a common breaking policy */
  private static class Group extends Doc {
    /**
     * The contents of this Group. This Group's breaking policy controls the layout of any Breaks in
     * the child document for which this Group is the closest enclosing Group.
     */
    final Doc child;

    /** The kind (breaking policy) for this group. */
    final GroupKind kind;

    Group(Doc child, GroupKind kind) {
      this.child = child;
      this.kind = kind;
    }

    @Override
    protected void fits(LayoutMode mode, FitsState state) {
      child.fits(LayoutMode.HORIZONTAL, state);
    }

    @Override
    protected int format(StringBuilder builder, Deque<Agendum> agenda, int width, int indentation,
                         int consumed, LayoutMode mode) {
      switch (kind) {
        case HORIZONTAL:
          agenda.push(new Agendum(indentation, LayoutMode.HORIZONTAL, child));
          break;

        case VERTICAL:
          agenda.push(new Agendum(indentation, LayoutMode.VERTICAL, child));
          break;

        case FILL:
          agenda.push(new Agendum(indentation, LayoutMode.FILL, child));
          break;

        case AUTO:
          // Try horizontal first; if that doesn't fit, then use vertical.
          agenda.push(new Agendum(indentation, LayoutMode.HORIZONTAL, child));
          if (!agendaFits(agenda, width - consumed)) {
            agenda.pop();
            agenda.push(new Agendum(indentation, LayoutMode.VERTICAL, child));
          }
          break;
      }
      return consumed;
    }

    @Override
    public boolean isWhitespace() {
      return child.isWhitespace();
    }
  }

  /** Set a constant indentation level. */
  private static class IndentAt extends Doc {
    /** The indentation level. */
    final int indent;

    /** The child document to indent. */
    final Doc child;

    IndentAt(int indent, Doc child) {
      this.indent = indent;
      this.child = child;
    }

    @Override
    protected void fits(LayoutMode mode, FitsState state) {
      child.fits(mode, state);
    }

    @Override
    protected int format(StringBuilder builder, Deque<Agendum> agenda, int width, int indentation,
                         int consumed, LayoutMode mode) {
      agenda.push(new Agendum(indent, mode, child));
      return consumed + indent;
    }

    @Override
    public boolean isWhitespace() {
      return child.isWhitespace();
    }
  }
}
