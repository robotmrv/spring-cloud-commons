/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.loadbalancer.client;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import reactor.core.publisher.Mono;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.reactive.Request;
import org.springframework.cloud.client.loadbalancer.reactive.Response;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Default implementation of {@link ReactorLoadBalancerClient}.
 *
 * @author Olga Maciaszek-Sharma
 */
public class DefaultReactorLoadBalancerClient implements ReactorLoadBalancerClient {

	private static final String PERCENTAGE_SIGN = "%";

	private static final String DEFAULT_SCHEME = "http";

	private static final String DEFAULT_SECURE_SCHEME = "https";

	private static final Map<String, String> INSECURE_SCHEME_MAPPINGS;

	static {
		INSECURE_SCHEME_MAPPINGS = new HashMap<>();
		INSECURE_SCHEME_MAPPINGS.put(DEFAULT_SCHEME, DEFAULT_SECURE_SCHEME);
		INSECURE_SCHEME_MAPPINGS.put("ws", "wss");
	}

	private final LoadBalancerClientFactory clientFactory;

	public DefaultReactorLoadBalancerClient(LoadBalancerClientFactory clientFactory) {
		this.clientFactory = clientFactory;
	}

	// see original
	// https://github.com/spring-cloud/spring-cloud-gateway/blob/master/spring-cloud-gateway-core/
	// src/main/java/org/springframework/cloud/gateway/support/ServerWebExchangeUtils.java
	private static boolean containsEncodedParts(URI uri) {
		boolean encoded = (uri.getRawQuery() != null && uri.getRawQuery()
				.contains(PERCENTAGE_SIGN))
				|| (uri.getRawPath() != null && uri.getRawPath()
				.contains(PERCENTAGE_SIGN))
				|| (uri.getRawFragment() != null && uri.getRawFragment()
				.contains(PERCENTAGE_SIGN));
		// Verify if it is really fully encoded. Treat partial encoded as unencoded.
		if (encoded) {
			try {
				UriComponentsBuilder.fromUri(uri).build(true);
				return true;
			}
			catch (IllegalArgumentException ignore) {
			}
			return false;
		}
		return false;
	}

	private static int computePort(int port, String scheme) {
		if (port >= 0) {
			return port;
		}
		if (Objects.equals(scheme, DEFAULT_SECURE_SCHEME)) {
			return 443;
		}
		return 80;
	}

	@Override
	public Mono<Response<ServiceInstance>> choose(String serviceId, Request request) {
		ReactorLoadBalancer<ServiceInstance> loadBalancer = getLoadBalancer(serviceId);
		if (loadBalancer == null) {
			return Mono.just(new EmptyResponse());
		}
		return loadBalancer.choose(request);
	}

	@Override
	public Mono<Response<ServiceInstance>> choose(String serviceId) {
		ReactorLoadBalancer<ServiceInstance> loadBalancer = getLoadBalancer(serviceId);
		if (loadBalancer == null) {
			return Mono.just(new EmptyResponse());
		}
		return loadBalancer.choose();
	}

	@Override
	public Mono<URI> reconstructURI(ServiceInstance serviceInstance, URI original) {
		if (serviceInstance == null) {
			return Mono.defer(() -> Mono
					.error(new IllegalArgumentException("Service Instance cannot be null.")));
		}
		return Mono.just(doReconstructURI(serviceInstance, original));
	}

	private URI doReconstructURI(ServiceInstance serviceInstance, URI original) {
		String host = serviceInstance.getHost();
		String scheme = Optional.ofNullable(serviceInstance.getScheme())
				.orElse(computeScheme(original, serviceInstance));
		int port = computePort(serviceInstance.getPort(), scheme);

		if (Objects.equals(host, original.getHost()) && port == original.getPort()
				&& Objects.equals(scheme, original.getScheme())) {
			return original;
		}

		boolean encoded = containsEncodedParts(original);
		return UriComponentsBuilder.fromUri(original).scheme(scheme).host(host).port(port)
				.build(encoded).toUri();
	}

	private String computeScheme(URI original, ServiceInstance serviceInstance) {
		String originalOrDefault = Optional.ofNullable(original.getScheme())
				.orElse(DEFAULT_SCHEME);
		if (serviceInstance.isSecure() && INSECURE_SCHEME_MAPPINGS
				.containsKey(originalOrDefault)) {
			return INSECURE_SCHEME_MAPPINGS.get(originalOrDefault);
		}
		return originalOrDefault;
	}

	private ReactorServiceInstanceLoadBalancer getLoadBalancer(String serviceId) {
		return clientFactory.getLoadBalancer(serviceId);
	}

}
