/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.web;

import org.htcom.protean.module.ModulePatch;

import java.util.List;

/**
 * Body of {@code PATCH /platform/modules/{id}}. Send only changed files to overlay onto the current
 * descriptor.
 *
 * @param version     new version (required — it is an update)
 * @param files       files to add/replace (source/test/resource)
 * @param removeFiles keys to remove (source/test FQCN or resource path)
 */
public record ModulePatchRequest(String version, List<ModulePatch.FileSpec> files, List<String> removeFiles) {
}
