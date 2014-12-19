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

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.framework.api.transaction.CuratorTransaction;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.util.Assert;

/**
 * State machine with ZooKeeper as back end.
 *
 * @param <S> enum of states
 */
public class ZKStateMachine<S extends Enum<S>> implements StateMachine<S> {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private final String id = UUID.randomUUID().toString();
	private final CuratorFramework client;
	private final String name;
	private final String statePath;
	private final String stateLogPath;
	private final Class<S> stateEnum;
	private final S initialState;
	private final Map<S, Transitions<S>> transitions = new ConcurrentHashMap<S, Transitions<S>>();
	private final List<StateListener<S>> listeners = new CopyOnWriteArrayList<StateListener<S>>();
	private final AtomicReference<StateWrapper> currentStateRef = new AtomicReference<StateWrapper>();
	private final CuratorWatcher watcher = new StateWatcher();

	public ZKStateMachine(CuratorFramework client, String name, Class<S> stateEnum, S initialState) {
		this.client = client;
		this.name = name;
		this.statePath = '/' + name + "/current";
		this.stateLogPath = '/' + name + "/log";
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

	@Override
	public void start() {
		try {
			if (client.checkExists().forPath(statePath) == null) {
				String initialStateString = createStateString(new StateWrapper(initialState, 0));
				client.inTransaction()
						.create().forPath("/" + name)
						.and()
						.create().forPath(statePath, initialStateString.getBytes())
						.and()
						.create().forPath(stateLogPath)
						.and()
						.create().forPath(stateLogPath + '/' + initialStateString)
						.and()
						.commit();
			}
		}
		catch (KeeperException.NodeExistsException e) {
			// ignore
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}

		currentStateRef.set(readState());
	}

	@Override
	public void stop() {
		// todo: should this remove the zk node?
	}

	@Override
	public boolean isRunning() {
		throw new UnsupportedOperationException();
	}

	public S getCurrentState() {
		return currentStateRef.get().state;
	}

	private StateWrapper readState()  {
		return readState(null);
	}

	private StateWrapper readState(Stat stat)  {
		try {
			if (stat == null) {
				stat = new Stat();
			}
			String stateString = new String(client.getData().storingStatIn(stat).usingWatcher(watcher).forPath(statePath));
			String[] s = stateString.split(":");
			int stateVersion = Integer.parseInt(s[0]);
			S state = Enum.valueOf(stateEnum, s[1]);
			return new StateWrapper(state, stateVersion);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private String createStateString(StateWrapper stateWrapper) {
		return String.format(Locale.ENGLISH, "%010d", stateWrapper.version)
				+ ':' + stateWrapper.state.toString() + ':' + id;
	}

	@Override
	public void transitionTo(final S state) {
		StateWrapper cached = currentStateRef.get();
		Stat currentStat = new Stat();
		StateWrapper current = readState(currentStat);
		if (cached.version == current.version) {
			if (transitions.get(cached.state).to.contains(state)) {
				CuratorTransaction tx = client.inTransaction();
				String stateString = createStateString(new StateWrapper(state, current.version + 1));

				try {
					tx.create().forPath(stateLogPath + '/' + stateString)
							.and()
							.setData().withVersion(currentStat.getVersion()).forPath(statePath, stateString.getBytes())
							.and()
							.commit();
				}
				catch (Exception e) {
					// todo: handle CAS failures
					throw new RuntimeException(e);
				}
			}
			else {
				// todo: invalid transition
			}
		}
		else {
			// todo: cache is out of date
		}

	}

	private class StateWatcher implements CuratorWatcher {
		@Override
		public void process(WatchedEvent event) throws Exception {
			switch (event.getType()) {
				case NodeDataChanged:
					StateWrapper currentState = currentStateRef.get();
					StateWrapper newState = readState();
					if (currentState.version + 1 == newState.version &&
							currentStateRef.compareAndSet(currentState, newState)) {
						for (StateListener<S> listener : listeners) {
							listener.onEnter(newState.state);
						}
					}
					else {
						// this means that more than one state transition
						// happened; we need to read the log and play back
						// all of the transitions that were missed
						List<String> logEntries = client.getChildren().forPath(stateLogPath);
						Collections.sort(logEntries);
						boolean currentInLog = false;
						for (String entry : logEntries) {
							String[] fields = entry.split(":");
							int version = Integer.parseInt(fields[0]);
							if (version == currentState.version) {
								currentInLog = true;
							}
							else if (version > currentState.version) {
								if (currentInLog) {
									S state = Enum.valueOf(stateEnum, fields[1]);
									currentStateRef.set(new StateWrapper(state, version));
									for (StateListener<S> listener : listeners) {
										listener.onEnter(newState.state);
									}
								}
								else {
									// TODO: this means that the log is missing
									// transitions that occurred after the
									// last known transition...
								}
							}
						}
					}
					break;
				default:
					client.checkExists().usingWatcher(this).forPath(statePath);
					break;
			}
		}
	}

	private class StateWrapper {
		private final S state;
		private final int version;

		public StateWrapper(S state, int version) {
			Assert.notNull(state);
			this.state = state;
			this.version = version;
		}
	}

}
