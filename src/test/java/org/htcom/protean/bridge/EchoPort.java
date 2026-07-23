/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import java.util.List;
import java.util.Map;

/** Demo interface for RPC bridge primitive/composite/generic types plus exception propagation. */
public interface EchoPort {

    int addInt(int a, int b);            // primitive arguments/return

    Point move(Point p, int dx, int dy); // composite DTO + primitive

    List<Point> shift(List<Point> points, int dx, int dy); // generic collection arguments/return

    int risky(int x);                    // exception propagation: x<0 throws IllegalArgumentException

    int riskyChained(int x);             // exception cause-chain propagation: x<0 throws IllegalStateException(cause=IllegalArgumentException)

    Map<String, Integer> tally(List<String> words);             // returns Map<String,Integer>

    Map<String, List<Point>> groupByParity(List<Point> points); // nested generic Map<String,List<Point>>

    String echoOrNull(String s);         // null argument/return round-trip

    int size(String s);                  // overload #1 (String)

    int size(List<Point> points);        // overload #2 (List) — verifies method resolution by declared type
}
