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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.InitializingBean;

/**
 * @author Patrick Peralta
 */
public class Demo implements InitializingBean {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	public enum LightSwitch {OFF, ON}

	private final StateMachine<LightSwitch> stateMachine;

	public Demo(StateMachine<LightSwitch> stateMachine) {
		this.stateMachine = stateMachine;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					Thread.sleep(5000);  //todo: hack -> wait for rabbit to start up

					stateMachine.addListener(new StateListener<Demo.LightSwitch>() {
						@Override
						public void onEnter(Demo.LightSwitch state) {
							logger.info("\t Event -> light switch is now {}", state);
						}
					});

					// start the state machine and perform transitions
					stateMachine.start();
					logger.info("\t -> initial state: {}", stateMachine.getCurrentState());
					stateMachine.transitionTo(LightSwitch.ON);
					// since transitions are async, it is likely that OFF will be logged below
					logger.info("\t -> state after transition: {}", stateMachine.getCurrentState());

					Thread.sleep(1000);
					logger.info("\t -> state after sleep: {}", stateMachine.getCurrentState());
					Thread.sleep(1000);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException(e);
				}
			}
		}).start();
	}

}
