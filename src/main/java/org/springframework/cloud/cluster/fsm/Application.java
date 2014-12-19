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

import java.util.Arrays;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * @author Patrick Peralta
 */
@Configuration
@ComponentScan
@EnableAutoConfiguration
public class Application {
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	public static final int ZK_SERVER_PORT = 3121;

	@Bean
	public TestingServer zkServer() throws Exception {
		return new TestingServer(ZK_SERVER_PORT);
	}

	/**
	 * @return Curator/ZooKeeper connection; currently hardcoded to
	 * {@value #ZK_SERVER_PORT}.
	 */
	@Bean(initMethod = "start", destroyMethod = "close")
	@DependsOn("zkServer")
	public CuratorFramework curatorClient() {
		return CuratorFrameworkFactory.builder()
				.defaultData(new byte[0])
				.retryPolicy(new ExponentialBackoffRetry(1000, 3))
				.connectString("localhost:" + ZK_SERVER_PORT)
				.build();
	}

	@Bean
	public StateMachine<Demo.LightSwitch> stateMachine() {
		return new ZKStateMachine<Demo.LightSwitch>(
				curatorClient(), "light-switch", Demo.LightSwitch.class, Demo.LightSwitch.OFF,
				Arrays.<Transitions<Demo.LightSwitch>>asList(
						new Transitions<Demo.LightSwitch>(Demo.LightSwitch.OFF).addTo(Demo.LightSwitch.ON),
						new Transitions<Demo.LightSwitch>(Demo.LightSwitch.ON).addTo(Demo.LightSwitch.OFF)));
	}

	@Bean
	public Demo demo() {
		return new Demo(stateMachine());
	}

	public static void main(String args[]) {
		SpringApplication.run(Application.class, args);
	}

}
