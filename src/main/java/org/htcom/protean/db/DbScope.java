/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

/**
 * Connection info for the isolated DB scope provisioned for a module. Passed straight to the worker so the module
 * sees only its own DB.
 *
 * @param url      the scoped JDBC URL (MySQL = dedicated DATABASE, Postgres = currentSchema)
 * @param username the module's dedicated user/role
 * @param password the generated password
 */
public record DbScope(String url, String username, String password) {}
