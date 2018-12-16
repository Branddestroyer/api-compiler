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

import com.google.common.collect.ImmutableList;
import com.google.inject.Key;

/**
 * Interface for a processor which performs an analysis or transformation task on the model.
 */
public interface Processor {

  /**
   * The list of stages this processor needs for its operations. Stages are attached as attributes
   * on the {@link Model}. A stage may or may not be associated with actual data of the type of the
   * parameter of the key.
   */
  ImmutableList<Key<?>> requires();

  /**
   * The stage this processor establishes if operation successfully finishes. The processor is
   * responsible to attach the stage key to the model.
   */
  Key<?> establishes();

  /**
   * Runs this processor. It is guaranteed that the {@link Processor#requires()} stages are
   * established before this method is called via {@link Model#establishStage(Key)}. The method
   * should return true if processing successfully finished and the given {@link #establishes()} key
   * has been attached to the model. Any errors, warnings, and hints during processing should be
   * attached to the model via {@link Model#getDiagCollector}.
   */
  boolean run(Model model);
}
