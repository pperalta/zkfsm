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

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;


/**
 * Definition of supported state transitions.
 *
 * @param <S> enum of states
 */
public class Transitions<S> {
	final S state;
	final Set<S> to = new CopyOnWriteArraySet<S>();

	public Transitions(S state) {
		this.state = state;
	}

	public Transitions<S> addTo(S state) {
		this.to.add(state);
		return this;
	}

	public S getState() {
		return state;
	}

	public Set<S> getTo() {
		return to;
	}
}
