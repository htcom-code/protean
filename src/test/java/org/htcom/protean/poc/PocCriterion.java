/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.poc;

/**
 * The six criteria a change must clear before it may be applied. Each was derived from a defect that reached main and
 * was found later — the list is a record of what this project actually gets wrong, not a generic checklist.
 *
 * <p>Performance and memory are deliberately excluded.
 */
public enum PocCriterion {

    /** The module serves. Nothing else matters if this fails. */
    ROUTE("Route"),

    /** A live shared-lib jar and a live library generation reach their dependents; a failing gate leaves that one dependent behind (sticky), not the others. */
    PROPAGATION("Propagation"),

    /** What the admin surface reports matches what is actually running — the defect class where a route served one generation while status claimed another. */
    REPORTING("Reporting"),

    /** No credential appears where an onlooker can read it: process arguments, container metadata, or the config surface. */
    SECRETS("Secrets"),

    /** Modules come back without being redeployed, and a scope is reused rather than provisioned again. */
    RECONCILE("Reconcile"),

    /** The documented scenarios still reproduce with their payloads and commands unchanged — the regression this project keeps breaking. */
    LEGACY_SCENARIO("Legacy scenario");

    private final String label;

    PocCriterion(String label) {
        this.label = label;
    }

    /** Column header for the verdict table. */
    public String label() {
        return label;
    }
}
