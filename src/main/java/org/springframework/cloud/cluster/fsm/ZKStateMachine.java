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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.BackgroundCallback;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.WatchedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * State machine with ZooKeeper as back end.
 *
 * @param <S> enum of states
 */
public class ZKStateMachine<S extends Enum<S>> implements StateMachine<S>, MessageListener {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private final RabbitTemplate rabbitTemplate;
	private final CuratorFramework client;
	private final String name;
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

	public ZKStateMachine(RabbitTemplate rabbitTemplate, CuratorFramework client, String name, Class<S> stateEnum, S initialState) {
		this.rabbitTemplate = rabbitTemplate;
		this.client = client;
		this.name = name;
		this.path = '/' + name;
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
						rabbitTemplate.send("key-hack", MessageBuilder.withBody(state.toString().getBytes()).build());
//						rabbitTemplate.send(MessageBuilder.withBody(state.toString().getBytes()).build());
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

	@Override
	public void onMessage(Message message) {
		logger.info("Message received: {}", message);
	}
}
