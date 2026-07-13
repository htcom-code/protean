/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.compiler;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.function.ToIntFunction;

/**
 * JDBC leak-prevention helper for a closing shared-lib generation (Tomcat-style). {@link ModuleSharedLibs} defines a
 * copy of this class into a <b>child of the generation's ClassLoader</b> and invokes it there, so when it calls
 * {@link DriverManager}, the caller classloader is the generation's — and {@link DriverManager}'s caller-classloader
 * filter therefore lets it see and deregister the drivers that generation loaded. Invoked through the JDK-native
 * {@link ToIntFunction} interface so both the defining side and the caller share one (bootstrap-loaded) type.
 *
 * <p>Deregisters every registered driver whose class was loaded by the passed generation ClassLoader and returns the
 * count. Kept dependency-free and self-contained (it is loaded in isolation, without the rest of the app on its CL).
 */
public final class SharedLibJdbcCleanup implements ToIntFunction<ClassLoader> {

    @Override
    public int applyAsInt(ClassLoader generationCl) {
        int count = 0;
        for (Driver driver : Collections.list(DriverManager.getDrivers())) {
            if (driver.getClass().getClassLoader() == generationCl) {
                try {
                    DriverManager.deregisterDriver(driver);
                    count++;
                } catch (SQLException e) {
                    throw new IllegalStateException("failed to deregister JDBC driver "
                            + driver.getClass().getName(), e);
                }
            }
        }
        return count;
    }
}
