/*
 * Copyright 2014 the original author or authors.
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

package org.springframework.cloud.cluster.fsm;

/**
 * Listener interface for state transitions.
 *
 * @param <S> enum of states
 */
public interface StateListener<S> {

	/**
	 * Indicates a state transition to the given state.
	 *
	 * @param state new state
	 */
	void onEnter(S state);

	// TODO: this would be nice to have but I don't
	// know if this is possible with ZK...
	// void onExit(S state);
}
