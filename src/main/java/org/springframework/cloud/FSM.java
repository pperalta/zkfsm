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

package org.springframework.cloud;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.BackgroundCallback;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.WatchedEvent;

/**
 * Prototype of an asynchronous finite state machine using
 * ZooKeeper for state persistence.
 *
 * @author Patrick Peralta
 */
public class FSM {
	public static final int ZK_SERVER_PORT = 3121;

	/**
	 * State machine definition.
	 *
	 * @param <S> enum of states
	 */
	interface StateMachine<S extends Enum<S>> {

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


	/**
	 * Listener interface for state transitions.
	 *
	 * @param <S> enum of states
	 */
	interface StateListener<S> {

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

	/**
	 * Definition of supported state transitions.
	 *
	 * @param <S> enum of states
	 */
	static class Transitions<S> {
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

	/**
	 * State machine with ZooKeeper as back end.
	 *
	 * @param <S> enum of states
	 */
	static class ZKStateMachine<S extends Enum<S>> implements StateMachine<S> {
		private final CuratorFramework client;
		private final String path;
		private final Class<S> stateEnum;
		private final S initialState;
		private final Map<S, Transitions<S>> transitions = new ConcurrentHashMap<S, Transitions<S>>();
		private final List<StateListener<S>> listeners = new CopyOnWriteArrayList<StateListener<S>>();
		private final BackgroundCallback listenerTrigger = new BackgroundCallback() {
			@Override
			public void processResult(CuratorFramework client, CuratorEvent event) throws Exception {
				switch (event.getType()) {
					case GET_DATA:
						assert event.getData() != null;
						S state = Enum.valueOf(stateEnum, new String(event.getData()));
						for (StateListener<S> listener : listeners) {
							listener.onEnter(state);
						}
						break;
					default:
				}
			}
		};
		private final CuratorWatcher watcher = new CuratorWatcher() {
			@Override
			public void process(WatchedEvent event) throws Exception {
				switch (event.getType()) {
					case NodeCreated:
					case NodeDataChanged:
						client.getData().usingWatcher(this).inBackground(listenerTrigger).forPath(path);
						break;
					default:
						client.checkExists().usingWatcher(this).forPath(path);
						break;
				}
			}
		};

		public ZKStateMachine(CuratorFramework client, String path, Class<S> stateEnum, S initialState) {
			this.client = client;
			this.path = path;
			this.stateEnum = stateEnum;
			this.initialState = initialState;
		}

		public void addTransitions(Transitions<S> newTransitions) {
			this.transitions.put(newTransitions.getState(), newTransitions);
		}

		public void addListener(StateListener<S> listener) {
			listeners.add(listener);
		}

		public void removeListener(StateListener<S> listener) {
			listeners.remove(listener);
		}

		public S getCurrentState() {
			try {
				return Enum.valueOf(stateEnum, new String(client.getData().usingWatcher(watcher).forPath(path)));
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void transitionTo(final S state) {
			try {
				client.getData().usingWatcher(watcher).inBackground(new BackgroundCallback() {
					@Override
					public void processResult(CuratorFramework client, CuratorEvent event) throws Exception {
						// a successful transition will:
						//   1. read the current state and current version
						//   2. if the current state allows for a transition,
						//      issue a CAS operation
						//   3. if the operation succeeds, an event will be
						//      raised and any registered StateListeners will
						//      be notified

						int version = event.getStat().getVersion();
						S currentState = Enum.valueOf(stateEnum, new String(event.getData()));

						// TODO: if we can't transition, what should we do?
						if (transitions.get(currentState).to.contains(state)) {
							client.setData().withVersion(version).forPath(path, state.toString().getBytes());
						}
					}
				}).forPath(path);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void start() {
			try {
				if (client.checkExists().usingWatcher(watcher).forPath(path) == null) {
					client.create().inBackground().forPath(path, this.initialState.toString().getBytes());
				}
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public void stop() {
			// todo: should this remove the zk node?
		}

		@Override
		public boolean isRunning() {
			throw new UnsupportedOperationException();
		}
	}

	// ----------------------------------------------------------------------
	// Example usage below
	// ----------------------------------------------------------------------

	public enum LightSwitch {OFF, ON}

	public static void main(String[] args) throws Exception {
		TestingServer server = new TestingServer(ZK_SERVER_PORT);
		CuratorFramework client = CuratorFrameworkFactory.builder()
				.defaultData(new byte[0])
				.retryPolicy(new ExponentialBackoffRetry(1000, 3))
				.connectString("localhost:" + ZK_SERVER_PORT)
				.build();

		client.start();

		// create the state machine, add supported transitions
		StateMachine<LightSwitch> stateMachine = new ZKStateMachine<LightSwitch>(client,
				"/lightswitch", LightSwitch.class, LightSwitch.OFF);

		stateMachine.addTransitions(new Transitions<LightSwitch>(LightSwitch.OFF).addTo(LightSwitch.ON));
		stateMachine.addTransitions(new Transitions<LightSwitch>(LightSwitch.ON).addTo(LightSwitch.OFF));
		stateMachine.addListener(new StateListener<LightSwitch>() {
			@Override
			public void onEnter(LightSwitch state) {
				System.out.printf("\t Event -> light switch is now %s%n", state.toString());
			}
		});

		// start the state machine and perform transitions
		stateMachine.start();
		System.out.printf("\t -> initial state: %s%n", stateMachine.getCurrentState());
		stateMachine.transitionTo(LightSwitch.ON);
		// since transitions are async, it is likely that OFF will be logged below
		System.out.printf("\t -> state after transition: %s%n", stateMachine.getCurrentState());
		Thread.sleep(1000);
		System.out.printf("\t -> state after sleep: %s%n", stateMachine.getCurrentState());
		Thread.sleep(1000);

		client.close();
		server.close();
	}

}
