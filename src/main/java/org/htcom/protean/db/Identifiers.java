/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.db;

/** DDL identifier sanitization — DDL cannot be bound, so a whitelist blocks injection. */
final class Identifiers {

    private Identifiers() {
    }

    /**
     * Converts a moduleId into a safe DB/user identifier.
     * Whitelist {@code [a-z0-9_]}, must start with a letter, shortened with a hash when it exceeds maxLen.
     */
    static String safeName(String moduleId, int maxLen) {
        String s = moduleId.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) {
            s = "m_" + s;
        }
        if (s.length() > maxLen) {
            String hash = Integer.toHexString(moduleId.hashCode());
            s = s.substring(0, Math.max(1, maxLen - hash.length() - 1)) + "_" + hash;
        }
        return s;
    }
}
