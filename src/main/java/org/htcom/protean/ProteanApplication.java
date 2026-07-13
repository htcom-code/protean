/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Entry point for standalone/dev runs, tests, and worker boot.
 *
 * <p>Library consumers receive their beans not from this class but from
 * {@code ProteanAutoConfiguration} (auto-configuration). Hence no {@code @ComponentScan} is placed
 * here — scanning is owned solely by the auto-config, which prevents double registration. Instead,
 * {@link EnableAutoConfiguration} loads that auto-config, so this entry point's boot path follows
 * the exact same auto-configuration path as consumers do.
 *
 * <p>{@link SpringBootConfiguration} is applied directly to keep a <b>single
 * @SpringBootConfiguration</b>, so that configuration auto-detection by a {@code @SpringBootTest}
 * without {@code classes=} keeps finding this class.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class ProteanApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProteanApplication.class, args);
    }
}
