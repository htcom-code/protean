/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.module;

import org.htcom.protean.compiler.ProteanResourcePatternResolver;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Module child context. Identical to a plain {@link AnnotationConfigApplicationContext} except it uses
 * {@link ProteanResourcePatternResolver} so that {@code classpath*:} pattern scans enumerate the in-memory
 * module resources and classes. Because the context is itself a {@code ResourcePatternResolver}, scans that
 * use the context as their resource loader ({@code @MapperScan}/{@code @EntityScan}/{@code @ComponentScan}/{@code mapper-locations})
 * all go through this.
 */
public class ModuleApplicationContext extends AnnotationConfigApplicationContext {

    @Override
    protected ResourcePatternResolver getResourcePatternResolver() {
        return new ProteanResourcePatternResolver(this);
    }
}
