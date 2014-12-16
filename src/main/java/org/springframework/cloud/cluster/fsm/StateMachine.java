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
 * State machine definition.
 *
 * @param <S> enum of states
 */
public interface StateMachine<S extends Enum<S>> {

	void start();

	void stop();

	boolean isRunning();

	/**
	 * Request a state transition. This state transition
	 * will succeed if the current state allows for
	 * a transition to the requested state.
	 *
	 * @param state the requested state
	 */
	void transitionTo(S state);

	/**
	 * Add a state transition definition.
	 *
	 * @param transitions new state transitions
	 */
	void addTransitions(Transitions<S> transitions);

	/**
	 * Add a state transition listener. This listener
	 * will be triggered when a state transition occurs,
	 * whether initiated locally or remotely.
	 *
	 * @param listener listener to add
	 */
	void addListener(StateListener<S> listener);

	/**
	 * Remove the state transition listener.
	 *
	 * @param listener listener to remove
	 */
	void removeListener(StateListener<S> listener);

	/**
	 * Return the current state.
	 *
	 * @return current state
	 */
	S getCurrentState();
}
