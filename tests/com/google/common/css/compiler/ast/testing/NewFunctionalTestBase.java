/*
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.css.compiler.ast.testing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.common.css.SourceCode;
import com.google.common.css.compiler.ast.BasicErrorManager;
import com.google.common.css.compiler.ast.CssTree;
import com.google.common.css.compiler.ast.ErrorManager;
import com.google.common.css.compiler.ast.GssError;
import com.google.common.css.compiler.ast.GssParser;
import com.google.common.css.compiler.ast.GssParserException;

import java.util.Map;

/**
 * Base class for testing the passes which use an {@link ErrorManager}.
 *
 */
public class NewFunctionalTestBase extends FunctionalTestCommonBase {

  protected static final String TEST_FILENAME = "test";

  protected ErrorManager errorManager;

  @Override
  public void parseAndBuildTree(String sourceCode) {
    parseAndBuildTree(ImmutableMap.of(TEST_FILENAME, sourceCode));
  }

  /**
   * Parses GSS style sheets and calls fail if an exception is thrown.
   *
   * @param fileNameToGss a map connecting names to GSS style sheets
   */
  public void parseAndBuildTree(ImmutableMap<String, String> fileNameToGss) {
    try {
      parseAndRun(fileNameToGss);
    } catch (GssParserException e) {
      fail(e.getMessage());
    }
  }

  /**
   * Parses a GSS style sheet and returns the tree. In addition, it checks
   * whether the actual error messages exactly match the expected ones.
   *
   * @param gss the GSS style sheet
   * @param expectedMessages the expected error messages in the right order
   * @return the CSS tree created by the parser
   * @throws GssParserException
   */
  protected CssTree parseAndRun(String gss, String ... expectedMessages)
      throws GssParserException {
    return parseAndRun(
        ImmutableMap.of(TEST_FILENAME, gss), true /* exactMatch */,
        expectedMessages);
  }

  /**
   * Parses a GSS style sheet and returns the tree. In addition, it checks
   * whether the actual error messages match the expected ones.
   *
   * @param gss the GSS style sheet
   * @param exactMatch Determines if the actual error messages have to exactly
   *     match the expected ones or only have to contain them.
   * @param expectedMessages the expected error messages or parts of the
   *     messages in the right order
   * @return the CSS tree created by the parser
   * @throws GssParserException
   */
  protected CssTree parseAndRun(String gss, boolean exactMatch,
      String... expectedMessages) throws GssParserException {
    return parseAndRun(
        ImmutableMap.of(TEST_FILENAME, gss), exactMatch, expectedMessages);
}

  /**
   * Parses GSS style sheets and returns one tree containing everything.
   * In addition, it checks whether the actual error messages exactly match
   * the expected ones.
   *
   * @param fileNameToGss a map connecting names to GSS style sheets
   * @param expectedMessages the expected error messages in the right order
   * @return the CSS tree created by the parser
   * @throws GssParserException
   */
  protected CssTree parseAndRun(
      ImmutableMap<String, String> fileNameToGss,
      String... expectedMessages) throws GssParserException {
    return parseAndRun(fileNameToGss, true /* exactMatch */, expectedMessages);
  }

  /**
   * Parses GSS style sheets and returns one tree containing everything.
   * In addition, it checks whether the actual error messages exactly match
   * the expected ones.
   *
   * @param fileNameToGss a map connecting names to GSS style sheets
   * @param exactMatch Determines if the actual error messages have to exactly
   *     match the expected ones or only have to contain them.
   * @param expectedMessages the expected error messages or parts of the
   *     messages in the right order
   * @return the CSS tree created by the parser
   * @throws GssParserException
   */
  protected CssTree parseAndRun(
      ImmutableMap<String, String> fileNameToGss, boolean exactMatch,
      String... expectedMessages) throws GssParserException {
    tree = parse(fileNameToGss);
    errorManager = new TestErrorManager(exactMatch, expectedMessages);
    runPass();
    errorManager.generateReport();
    assertTrue("Encountered all errors.",
               ((TestErrorManager)errorManager).hasEncounteredAllErrors());
    return tree;
  }

  /**
   * Parses GSS style sheets and returns one tree containing everything.
   *
   * @param fileNameToGss a map connecting names to GSS style sheets
   * @return the CSS tree created by the parser
   * @throws GssParserException
   */
  protected CssTree parse(ImmutableMap<String, String> fileNameToGss)
      throws GssParserException {
    Builder<SourceCode> builder = ImmutableList.builder();
    for (Map.Entry<String, String> entry : fileNameToGss.entrySet()) {
      builder.add(new SourceCode(entry.getKey(), entry.getValue()));
    }
    return new GssParser(builder.build()).parse();
  }

  public ErrorManager getErrorManager() {
    return errorManager;
  }

  public CssTree getTree() {
    return tree;
  }

  /**
   * Used to check whether the actual error messages match the expected ones.
   */
  public static class TestErrorManager extends BasicErrorManager {
    private final String[] expectedMessages;
    private int currentIndex = 0;
    private boolean exactMatch;

    public TestErrorManager(String[] expectedMessages) {
      this(true, expectedMessages);
    }

    /**
     * Used to check whether the actual error messages match the expected ones.
     *
     * @param exactMatch Determines if the actual error messages have to exactly
     *     match the expected ones or only have to contain them.
     * @param expectedMessages the expected error messages or parts of the
     *     messages in the right order
     */
    public TestErrorManager(boolean exactMatch, String[] expectedMessages) {
      this.exactMatch = exactMatch;
      this.expectedMessages = expectedMessages;
    }

    @Override
    public void generateReport() {
      for (GssError error : errors) {
        print(error.getMessage());
      }
    }

    @Override
    public void print(String message) {
      assertTrue("Unexpected extra error: " + message,
          currentIndex < expectedMessages.length);
      if (exactMatch) {
        assertEquals(expectedMessages[currentIndex], message);
      } else {
        assertTrue(message.contains(expectedMessages[currentIndex]));
      }
      currentIndex++;
    }

    public boolean hasEncounteredAllErrors() {
      return currentIndex == expectedMessages.length;
    }
  }
}