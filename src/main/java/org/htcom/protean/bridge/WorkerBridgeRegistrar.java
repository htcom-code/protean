/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.htcom.protean.autoconfigure.ProteanProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.lang.reflect.Proxy;

/**
 * Worker-side RPC bridge registrar (worker profile + rpc-bridge=true).
 * For any shared interface FQCN, registers a dynamic proxy as a singleton in the worker root context.
 * A module child context (parent=root) then injects it by type and, when called, forwards to the main bridge.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "protean.worker.rpc-bridge", havingValue = "true")
public class WorkerBridgeRegistrar {

    private final ApplicationContext context;
    private final ObjectMapper mapper;
    private final String bridgeUrl;
    /** Shared secret to authenticate main bridge calls (null when auth is disabled). */
    private final String bridgeSecret;
    /** Auth scheme (token|hmac) used to shape the outgoing request. */
    private final String bridgeAuthMode;

    public WorkerBridgeRegistrar(ApplicationContext context, ObjectMapper mapper,
                                 ProteanProperties props) {
        this.context = context;
        this.mapper = mapper;
        this.bridgeUrl = props.getBridge().getUrl();
        this.bridgeSecret = props.getBridge().getSecret();
        this.bridgeAuthMode = props.getBridge().getAuthMode();
    }

    /** Registers a proxy bean for the interface FQCN (if not already present). Idempotent. */
    public synchronized void register(String ifaceFqcn) {
        Class<?> iface;
        try {
            iface = Class.forName(ifaceFqcn);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("failed to load bridge interface: " + ifaceFqcn, e);
        }
        if (context.getBeanNamesForType(iface).length > 0) {
            return;  // already registered
        }
        Object proxy = Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[]{iface},
                new BridgeInvocationHandler(bridgeUrl, iface, mapper, null, bridgeSecret, bridgeAuthMode));
        ((ConfigurableApplicationContext) context).getBeanFactory().registerSingleton(ifaceFqcn, proxy);
    }
}
