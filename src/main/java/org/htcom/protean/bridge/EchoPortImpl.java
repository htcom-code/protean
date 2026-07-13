/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.bridge;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Profile("!worker")
public class EchoPortImpl implements EchoPort {

    @Override
    public int addInt(int a, int b) {
        return a + b;
    }

    @Override
    public Point move(Point p, int dx, int dy) {
        return new Point(p.x() + dx, p.y() + dy);
    }

    @Override
    public List<Point> shift(List<Point> points, int dx, int dy) {
        return points.stream().map(p -> new Point(p.x() + dx, p.y() + dy)).toList();
    }

    @Override
    public int risky(int x) {
        if (x < 0) {
            throw new IllegalArgumentException("negative not allowed: " + x);
        }
        return x * 2;
    }

    @Override
    public int riskyChained(int x) {
        if (x < 0) {
            throw new IllegalStateException("outer failure",
                    new IllegalArgumentException("inner cause: " + x));
        }
        return x;
    }

    @Override
    public Map<String, Integer> tally(List<String> words) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String w : words) {
            out.put(w, w.length());
        }
        return out;
    }

    @Override
    public Map<String, List<Point>> groupByParity(List<Point> points) {
        Map<String, List<Point>> out = new LinkedHashMap<>();
        for (Point p : points) {
            out.computeIfAbsent(p.x() % 2 == 0 ? "even" : "odd", k -> new java.util.ArrayList<>()).add(p);
        }
        return out;
    }

    @Override
    public String echoOrNull(String s) {
        return s == null ? null : "echo:" + s;
    }

    @Override
    public int size(String s) {
        return s.length();
    }

    @Override
    public int size(List<Point> points) {
        return points.size();
    }
}
