/*
 * Copyright (c) 2026 htjulia <htjulia1@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.htcom.protean.mcp.debug;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import org.htcom.protean.compiler.RuntimeCompiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A single debug session = an attached JDI {@link VirtualMachine} + event pump + suspend state.
 *
 * <p>Breakpoints are set with {@code SUSPEND_EVENT_THREAD}, so on a hit <b>only that thread</b> is suspended and the
 * pump keeps running (no impact on the host or other threads). Use {@link #awaitStop} to wait for a stop, read state
 * while stopped via {@link #frames}/{@link #variables}, and resume with {@link #resume} (stepping is separate).
 */
public class DebugSession {

    /** Stop location. */
    public record Stop(String className, String method, int line, long threadId) {}

    /** Stack frame (read-only view). */
    public record Frame(int index, String className, String method, int line) {}

    /** Step kind. */
    public enum StepDepth { OVER, INTO, OUT }

    private final String id;
    /** Session owner (requester identity). null if none (unauthenticated / stdio). Basis for future per-user list_sessions scoping. */
    private final String owner;
    private final VirtualMachine vm;
    private final EventRequestManager erm;
    /** Last observed stop location (for list_sessions exposure). null if not yet stopped. */
    private volatile Stop lastStop;

    private final LinkedBlockingQueue<Stop> stops = new LinkedBlockingQueue<>();
    private final Map<String, List<Integer>> pending = new ConcurrentHashMap<>(); // breakpoints pending on not-yet-loaded classes
    private final Thread pump;
    private volatile boolean running = true;
    private volatile ThreadReference pausedThread;
    /** Cleanup hooks to run on session termination (e.g. kill the dedicated worker launched by debug-launch + restore routes). */
    private final List<Runnable> disposeHooks = new CopyOnWriteArrayList<>();
    /** Timestamp of last activity (tool access). Used for idle auto-reclamation (leak guard). Monotonic. */
    private volatile long lastActivityNanos = System.nanoTime();

    DebugSession(String id, VirtualMachine vm) {
        this(id, vm, null);
    }

    DebugSession(String id, VirtualMachine vm, String owner) {
        this.id = id;
        this.owner = owner;
        this.vm = vm;
        this.erm = vm.eventRequestManager();
        this.pump = new Thread(this::pump, "protean-debug-" + id);
        this.pump.setDaemon(true);
        this.pump.start();
    }

    public String id() {
        return id;
    }

    /** Session owner (requester identity, nullable). For future per-user lookup scoping. */
    public String owner() {
        return owner;
    }

    public String vmName() {
        return vm.name();
    }

    /** Last observed stop location (nullable). */
    public Stop lastStop() {
        return lastStop;
    }

    /** Whether the session is currently stopped at a breakpoint/step. */
    public boolean paused() {
        return pausedThread != null;
    }

    /** Registers a cleanup hook to run on termination (executed in reverse registration order in dispose). */
    public void onDispose(Runnable hook) {
        disposeHooks.add(hook);
    }

    /** Refreshes the activity timestamp (resets the idle timer). Called on access via {@link DebugCore#session(String)}. */
    public void touch() {
        lastActivityNanos = System.nanoTime();
    }

    /** Last activity timestamp (nanoTime). Used for idle auto-reclamation. */
    public long lastActivityNanos() {
        return lastActivityNanos;
    }

    public List<String> threadNames() {
        return vm.allThreads().stream().map(ThreadReference::name).toList();
    }

    // --- Breakpoints ---

    /** Sets a breakpoint at {@code className:line}. If the class is not yet loaded, installs it after ClassPrepare load. */
    public void setBreakpoint(String className, int line) {
        List<ReferenceType> loaded = vm.classesByName(className);
        if (!loaded.isEmpty()) {
            install(loaded.get(0), line);
            return;
        }
        pending.computeIfAbsent(className, k -> new CopyOnWriteArrayList<>()).add(line);
        ClassPrepareRequest cpr = erm.createClassPrepareRequest();
        cpr.addClassFilter(className);
        cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        cpr.enable();
    }

    private void install(ReferenceType type, int line) {
        List<Location> locs;
        try {
            locs = type.locationsOfLine(line);
        } catch (AbsentInformationException e) {
            throw new IllegalStateException("no line information (compile with debug info -g): " + type.name(), e);
        }
        if (locs.isEmpty()) {
            throw new IllegalStateException("line has no executable location: " + type.name() + ":" + line);
        }
        BreakpointRequest bp = erm.createBreakpointRequest(locs.get(0));
        bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        bp.enable();
    }

    // --- Await stop / resume ---

    /** Waits up to {@code timeoutMs} for the next stop. Returns null if none. */
    public Stop awaitStop(long timeoutMs) {
        try {
            Stop s = stops.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (s != null) {
                lastStop = s;   // for exposing the last stop location in list_sessions etc.
            }
            return s;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Resumes the stopped thread (continue). */
    public void resume() {
        ThreadReference t = pausedThread;
        pausedThread = null;
        if (t != null) {
            t.resume(); // SUSPEND_EVENT_THREAD → resumes only that thread
        }
    }

    /** Executes one step (over/into/out) and stops at the next line. Receive the resulting stop via {@link #awaitStop}. */
    public void step(StepDepth depth) {
        ThreadReference t = requirePaused();
        int jdiDepth = switch (depth) {
            case OVER -> StepRequest.STEP_OVER;
            case INTO -> StepRequest.STEP_INTO;
            case OUT -> StepRequest.STEP_OUT;
        };
        StepRequest req = erm.createStepRequest(t, StepRequest.STEP_LINE, jdiDepth);
        req.addCountFilter(1);
        req.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        req.enable();
        pausedThread = null;
        t.resume(); // the StepEvent is caught again as a stop by the pump
    }

    // --- Reads: frames / variables ---

    public List<Frame> frames() {
        ThreadReference t = requirePaused();
        try {
            List<Frame> out = new ArrayList<>();
            int i = 0;
            for (StackFrame f : t.frames()) {
                Location loc = f.location();
                out.add(new Frame(i++, loc.declaringType().name(), loc.method().name(), loc.lineNumber()));
            }
            return out;
        } catch (IncompatibleThreadStateException e) {
            throw new IllegalStateException("thread is not in a stopped state", e);
        }
    }

    /** Visible local variables of the frame (name→value string). Variable names require {@code -g:vars} compilation. */
    public Map<String, String> variables(int frameIndex) {
        ThreadReference t = requirePaused();
        try {
            StackFrame frame = t.frame(frameIndex);
            Map<String, String> out = new LinkedHashMap<>();
            for (LocalVariable v : frame.visibleVariables()) {
                Value val = frame.getValue(v);
                out.put(v.name(), String.valueOf(val));
            }
            return out;
        } catch (IncompatibleThreadStateException e) {
            throw new IllegalStateException("thread is not in a stopped state", e);
        } catch (AbsentInformationException e) {
            throw new IllegalStateException("no local variable information (compile with -g:vars)", e);
        }
    }

    /** Expression evaluation result (value string + runtime type name). */
    public record Eval(String value, String type) {}

    /**
     * Evaluates an expression in the context of the stopped frame (paths + getters, {@link ExpressionEvaluator}).
     *
     * <p>Because {@code invokeMethod} invalidates the frame, <b>locals and this are captured as Value snapshots before
     * evaluation</b>, and <b>breakpoints are temporarily disabled</b> during evaluation (so that getters do not cause
     * re-entrant stops or deadlocks). Method calls are performed by the evaluator with {@code INVOKE_SINGLE_THREADED}.
     */
    public Eval evaluate(int frameIndex, String expression) {
        return evaluate(frameIndex, expression, null);
    }

    /**
     * @param compiler {@link RuntimeCompiler} for lambda synthesis (if null, lambdas are unsupported; everything else works).
     */
    public Eval evaluate(int frameIndex, String expression, RuntimeCompiler compiler) {
        ThreadReference t = requirePaused();
        try {
            StackFrame frame = t.frame(frameIndex);
            Map<String, Value> locals = new LinkedHashMap<>();
            Map<String, LocalVariable> localDefs = new LinkedHashMap<>();
            try {
                for (LocalVariable v : frame.visibleVariables()) {
                    locals.put(v.name(), frame.getValue(v));   // snapshot before invoke (in case the frame is invalidated)
                    localDefs.put(v.name(), v);                 // for live-frame setValue on assignment (lvalue)
                }
            } catch (AbsentInformationException ignored) {
                // no -g:vars → attempt evaluation with only this-fields/literals, without locals
            }
            ObjectReference thisObj = frame.thisObject();
            // when injecting the synthesized lambda class, use the classloader of the stop-point's declaring type for target-type visibility (null=bootstrap).
            ObjectReference loader = frame.location().declaringType().classLoader();
            ExpressionEvaluator ev = new ExpressionEvaluator(
                    vm, t, frameIndex, locals, localDefs, thisObj, compiler, loader);

            // disable breakpoints during evaluation (prevent re-entrancy) → restore in finally.
            List<BreakpointRequest> paused = new ArrayList<>();
            for (BreakpointRequest b : erm.breakpointRequests()) {
                if (b.isEnabled()) {
                    b.disable();
                    paused.add(b);
                }
            }
            try {
                Value v = ev.evaluate(expression);
                return new Eval(ExpressionEvaluator.render(v), ExpressionEvaluator.typeName(v));
            } finally {
                paused.forEach(EventRequest::enable);
            }
        } catch (IncompatibleThreadStateException e) {
            throw new IllegalStateException("thread is not in a stopped state", e);
        }
    }

    /**
     * fix-and-continue — replaces the bytecode of a loaded class in place (JDI redefineClasses).
     * <b>Only method bodies</b> are allowed (standard JVMTI limitation: adding/removing methods/fields or changing
     * signatures is not allowed → UnsupportedOperationException). If a structural change is needed, redeploy via module
     * hot-swap.
     */
    public void redefine(String className, byte[] bytecode) {
        if (!vm.canRedefineClasses()) {
            throw new IllegalStateException("target VM does not support redefineClasses");
        }
        List<ReferenceType> types = vm.classesByName(className);
        if (types.isEmpty()) {
            throw new IllegalStateException("class not loaded (not a redefine target): " + className);
        }
        vm.redefineClasses(Map.of(types.get(0), bytecode));
    }

    ThreadReference pausedThread() {
        return pausedThread;
    }

    VirtualMachine vm() {
        return vm;
    }

    private ThreadReference requirePaused() {
        ThreadReference t = pausedThread;
        if (t == null) {
            throw new IllegalStateException("no stopped thread (no breakpoint hit yet)");
        }
        return t;
    }

    // --- Event pump ---

    private void pump() {
        try {
            while (running) {
                EventSet set = vm.eventQueue().remove();
                boolean resume = true;
                for (Event e : set) {
                    if (e instanceof BreakpointEvent be) {
                        pausedThread = be.thread();
                        Location loc = be.location();
                        stops.offer(new Stop(loc.declaringType().name(), loc.method().name(),
                                loc.lineNumber(), be.thread().uniqueID()));
                        resume = false; // keep the thread suspended
                    } else if (e instanceof StepEvent se) {
                        pausedThread = se.thread();
                        erm.deleteEventRequest(se.request()); // step requests are one-shot
                        Location loc = se.location();
                        stops.offer(new Stop(loc.declaringType().name(), loc.method().name(),
                                loc.lineNumber(), se.thread().uniqueID()));
                        resume = false;
                    } else if (e instanceof ClassPrepareEvent cpe) {
                        resolvePending(cpe.referenceType());
                    } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                        running = false;
                        resume = false;
                    }
                }
                if (resume) {
                    set.resume();
                }
            }
        } catch (VMDisconnectedException | InterruptedException ignored) {
            running = false;
        }
    }

    private void resolvePending(ReferenceType type) {
        List<Integer> lines = pending.remove(type.name());
        if (lines != null) {
            for (int line : lines) {
                install(type, line);
            }
        }
    }

    /** Terminates the session — stops the pump and detaches from the target VM. */
    public void dispose() {
        running = false;
        pump.interrupt();
        try {
            vm.dispose();
        } catch (RuntimeException ignored) {
            // ignore if already dead or disconnected
        }
        // run cleanup hooks after VM detach (worker kill / route restore). Isolate hook exceptions so one does not block others.
        for (Runnable hook : disposeHooks) {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // ignore cleanup failures (proceed even with partial cleanup)
            }
        }
    }
}
